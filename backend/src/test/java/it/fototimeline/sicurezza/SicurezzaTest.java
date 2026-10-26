package it.fototimeline.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.oneOf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.telefono.SorgenteTelefono;

/** Il login acceso, come su server2, senza un Keycloak vero: gli indirizzi non vengono mai chiamati. */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.login.attivo=true",
        "server.address=0.0.0.0",
        "spring.security.oauth2.client.registration.keycloak.client-id=fototimeline",
        "spring.security.oauth2.client.registration.keycloak.client-secret=segreto",
        "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.keycloak.scope=openid",
        "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://kc.invalid/auth",
        "spring.security.oauth2.client.provider.keycloak.token-uri=https://kc.invalid/token",
        "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://kc.invalid/certs",
        "spring.security.oauth2.client.provider.keycloak.user-name-attribute=sub",
        // La pagina Utenti senza il segreto del client: dice cosa manca, non chiama nessuno.
        "fototimeline.utenti.keycloak=https://kc.invalid/realms/fototimeline",
        // Token finto: non deve uscire da /api/salute. Nessun test qui manda davvero.
        "fototimeline.telegram.token=123456:token-segreto-di-prova",
        "fototimeline.telegram.chat=42",
        // Niente ricordi mandati dallo scheduler con quel token.
        "fototimeline.ricordi-telegram.attivo=false",
        // "Scegli da Google Foto" configurato, con un secret finto che non deve mai uscire.
        "fototimeline.google.client-id=id-di-prova.apps.googleusercontent.com",
        "fototimeline.google.client-secret=GOCSPX-segreto-di-prova",
})
@AutoConfigureMockMvc
class SicurezzaTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    List<SecurityFilterChain> catene;

    @Autowired
    RequestMappingHandlerMapping indirizzi;

    @Autowired
    RegistroUtenti registro;

    /** I tre ruoli, e chi è del realm ma senza nessuno dei tre. */
    private static final OidcLoginRequestPostProcessor ADMIN = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "capo"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
    private static final OidcLoginRequestPostProcessor GESTORE = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "gestore"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));
    private static final OidcLoginRequestPostProcessor UTILIZZATORE = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "nonna").claim("name", "Nonna"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-utilizzatore"));
    private static final OidcLoginRequestPostProcessor SENZA_RUOLO = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "ospite"));

    // ------------------------------------------------------------ inventario degli indirizzi

    /**
     * Ogni indirizzo dei controller col suo permesso. Un indirizzo nuovo che
     * qui manca fa fallire {@link #ogniIndirizzoEInInventario()}; un permesso
     * diverso da quello di ConfigurazioneSicurezza fa fallire
     * {@link #ogniIndirizzoHaIlSuoPermesso}. Livelli: PUBBLICO (senza login),
     * ENTRATO (anche senza ruolo), LETTURA (utilizzatore, gestore,
     * amministratore), GESTIONE (gestore, amministratore), ADMIN.
     * Con {@code ?} le varianti con un parametro che cambia il permesso.
     */
    static final String INVENTARIO = """
            *      /error                                         PUBBLICO
            GET    /salute                                        PUBBLICO
            GET    /c/{token}                                     PUBBLICO
            GET    /api/condivise/{token}                         PUBBLICO
            GET    /api/condivise/{token}/foto/{id}/miniatura     PUBBLICO
            GET    /api/condivise/{token}/foto/{id}/vista         PUBBLICO
            GET    /api/condivise/{token}/foto/{id}/video         PUBBLICO
            GET    /api/condivise/{token}/foto/{id}/originale     PUBBLICO
            GET    /api/condivise/{token}/zip                     PUBBLICO
            GET    /api/io                                        ENTRATO

            GET    /api/foto                                      LETTURA
            GET    /api/timeline                                  LETTURA
            GET    /api/mappa                                     LETTURA
            GET    /api/ricordi                                   LETTURA
            GET    /api/foto/{id}                                 LETTURA
            GET    /api/tag                                       LETTURA
            GET    /api/album                                     LETTURA
            GET    /api/caricate-da                               LETTURA
            GET    /api/foto/{id}/miniatura                       LETTURA
            GET    /api/foto/{id}/vista                           LETTURA
            GET    /api/foto/{id}/file                            LETTURA
            GET    /api/foto/{id}/file?scarica=true               GESTIONE
            GET    /api/foto/{id}/file?originale=true             GESTIONE
            GET    /api/foto/{id}/file?scarica=1                  GESTIONE
            GET    /api/foto/{id}/file?scarica=false              LETTURA
            GET    /api/foto/cerca-contenuto                      LETTURA
            GET    /api/luoghi                                    LETTURA
            GET    /api/cloud                                     LETTURA
            GET    /api/contenuto                                 LETTURA
            GET    /api/eventi                                    LETTURA
            GET    /api/eventi/nomi                               LETTURA
            GET    /api/eventi/{id}                               LETTURA
            GET    /api/volti                                     LETTURA
            GET    /api/foto/{id}/volti                           LETTURA
            GET    /api/volti/{id}/ritaglio                       LETTURA
            GET    /api/persone                                   LETTURA
            GET    /api/persone/{id}                              LETTURA

            POST   /api/foto                                      GESTIONE
            PUT    /api/foto/{id}                                 GESTIONE
            DELETE /api/foto/{id}                                 GESTIONE
            POST   /api/foto/multiple                             GESTIONE
            POST   /api/foto/{id}/ruota                           GESTIONE
            POST   /api/foto/ruota                                GESTIONE
            GET    /api/luoghi/cerca                              GESTIONE
            PUT    /api/foto/{id}/luogo                           GESTIONE
            DELETE /api/foto/{id}/luogo                           GESTIONE
            POST   /api/eventi                                    GESTIONE
            POST   /api/eventi/{id}/dividi                        GESTIONE
            POST   /api/eventi/{id}/foto                          GESTIONE
            POST   /api/eventi/togli                              GESTIONE
            POST   /api/volti/{id}/assegna                        GESTIONE
            POST   /api/volti/{id}/nome                           GESTIONE
            POST   /api/volti/{id}/rifiuta                        GESTIONE
            POST   /api/condivisioni                              GESTIONE
            POST   /ricevi-condivisi                              GESTIONE

            GET    /api/utenti                                    ADMIN
            GET    /api/gestione-utenti                           ADMIN
            POST   /api/gestione-utenti                           ADMIN
            PUT    /api/gestione-utenti/{id}/ruolo                ADMIN
            PUT    /api/gestione-utenti/{id}/attivo               ADMIN
            POST   /api/gestione-utenti/{id}/password             ADMIN
            DELETE /api/gestione-utenti/{id}                      ADMIN
            POST   /api/importa                                   ADMIN
            GET    /api/cartelle                                  ADMIN
            POST   /api/archivio/indicizza                        ADMIN
            GET    /api/importazioni/corrente                     ADMIN
            POST   /api/importazioni/annulla                      ADMIN
            GET    /api/importazioni/scartati                     ADMIN
            POST   /api/importazioni/scartati/riprova             ADMIN
            GET    /api/archivio/video                            ADMIN
            POST   /api/archivio/video/converti                   ADMIN
            POST   /api/archivio/video/annulla                    ADMIN
            POST   /api/archivio/luoghi                           ADMIN
            POST   /api/archivio/eventi                           ADMIN
            POST   /api/contenuto/indicizza                       ADMIN
            POST   /api/contenuto/annulla                         ADMIN
            POST   /api/volti/riconosci                           ADMIN
            POST   /api/volti/annulla                             ADMIN
            POST   /api/volti/{id}/togli                          ADMIN
            PUT    /api/persone/{id}                              ADMIN
            POST   /api/persone/{id}/nascondi                     ADMIN
            POST   /api/persone/unisci                            ADMIN
            POST   /api/persone/{id}/copertina                    ADMIN
            GET    /api/persone/{id}/volti                        ADMIN
            GET    /api/persone/{id}/proposte                     ADMIN
            POST   /api/persone/{id}/proposte/conferma            ADMIN
            GET    /api/persone/{id}/simili                       ADMIN
            PUT    /api/eventi/{id}                               ADMIN
            DELETE /api/eventi/{id}                               ADMIN
            POST   /api/eventi/unisci                             ADMIN
            GET    /api/cestino                                   ADMIN
            POST   /api/cestino/ripristina                        ADMIN
            POST   /api/cestino/elimina                           ADMIN
            POST   /api/cestino/svuota                            ADMIN
            GET    /api/quasi-uguali                              ADMIN
            POST   /api/quasi-uguali/risolvi                      ADMIN
            POST   /api/quasi-uguali/ignora                       ADMIN
            GET    /api/quasi-uguali/calcola                      ADMIN
            POST   /api/quasi-uguali/calcola                      ADMIN
            POST   /api/quasi-uguali/calcola/annulla              ADMIN
            GET    /api/telefoni                                  ADMIN
            POST   /api/telefoni                                  ADMIN
            PUT    /api/telefoni/{id}                             ADMIN
            DELETE /api/telefoni/{id}                             ADMIN
            POST   /api/telefoni/{id}/sincronizza                 ADMIN
            POST   /api/telefoni/video-grandi/{id}/annulla        ADMIN
            POST   /api/telefoni/video-grandi/{id}/riprova        ADMIN
            GET    /api/google                                    ADMIN
            GET    /api/google/collega                            ADMIN
            GET    /api/google/callback                           ADMIN
            POST   /api/google/scollega                           ADMIN
            POST   /api/google/scelta                             ADMIN
            GET    /api/google/scelta                             ADMIN
            POST   /api/google/scelta/annulla                     ADMIN
            POST   /api/google/scelta/chiudi                      ADMIN
            GET    /api/google/takeout                            ADMIN
            PUT    /api/google/takeout                            ADMIN
            POST   /api/google/takeout/avvia                      ADMIN
            POST   /api/google/takeout/annulla                    ADMIN
            POST   /api/google/takeout/zip/{id}/riprova           ADMIN
            GET    /api/condivisioni                              ADMIN
            DELETE /api/condivisioni/{id}                         ADMIN
            GET    /api/backup                                    ADMIN
            POST   /api/backup                                    ADMIN
            POST   /api/cloud/monta                               ADMIN
            POST   /api/cloud/smonta                              ADMIN
            GET    /api/salute                                    ADMIN
            POST   /api/salute/prova                              ADMIN
            GET    /api/diagnostica/thread                        ADMIN
            GET    /api/ricordi-telegram                          ADMIN
            PUT    /api/ricordi-telegram                          ADMIN
            POST   /api/ricordi-telegram/prova                    ADMIN
            """;

    enum Livello { PUBBLICO, ENTRATO, LETTURA, GESTIONE, ADMIN }

    record Voce(String metodo, String percorso, Livello livello) {

        /** Il percorso senza la variante: quello della mappatura del controller. */
        String mappatura() {
            int q = percorso.indexOf('?');
            return q < 0 ? percorso : percorso.substring(0, q);
        }
    }

    static List<Voce> inventario() {
        return INVENTARIO.lines().map(String::trim).filter(r -> !r.isEmpty())
                .map(r -> r.split("\\s+"))
                .map(c -> new Voce(c[0], c[1], Livello.valueOf(c[2])))
                .toList();
    }

    /** Chi prova: anonimo, del realm senza ruolo, e i tre ruoli; e da quale livello in su passa. */
    enum Chi {
        ANONIMO(Livello.PUBBLICO),
        SENZA_RUOLO(Livello.ENTRATO),
        UTILIZZATORE(Livello.LETTURA, "ROLE_fototimeline-utilizzatore"),
        GESTORE(Livello.GESTIONE, "ROLE_fototimeline-gestore"),
        ADMIN(Livello.ADMIN, "ROLE_fototimeline-admin");

        final Livello finoA;
        final String[] ruoli;

        Chi(Livello finoA, String... ruoli) {
            this.finoA = finoA;
            this.ruoli = ruoli;
        }

        Authentication autenticazione() {
            return this == ANONIMO
                    ? new AnonymousAuthenticationToken("prova", "anonimo", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"))
                    : new TestingAuthenticationToken(name().toLowerCase(), "x",
                            AuthorityUtils.createAuthorityList(java.util.stream.Stream.concat(
                                    java.util.stream.Stream.of("OIDC_USER"), java.util.Arrays.stream(ruoli))
                                    .toArray(String[]::new)));
        }
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> vociPerChi() {
        return inventario().stream().flatMap(v -> java.util.Arrays.stream(Chi.values())
                .map(c -> org.junit.jupiter.params.provider.Arguments.of(v.metodo() + " " + v.percorso(), v.livello(), c)));
    }

    /**
     * Le regole di ConfigurazioneSicurezza, chieste direttamente al filtro
     * dell'autorizzazione: nessun controller viene chiamato (niente lavori che
     * partono né messaggi su Telegram).
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0} {2}")
    @org.junit.jupiter.params.provider.MethodSource("vociPerChi")
    void ogniIndirizzoHaIlSuoPermesso(String indirizzo, Livello livello, Chi chi) {
        String[] parti = indirizzo.split(" ");
        String metodo = parti[0].equals("*") ? "GET" : parti[0];
        String uri = parti[1].replaceAll("\\{[^}]+}", "x");
        var richiesta = new org.springframework.mock.web.MockHttpServletRequest(metodo, uri.replaceAll("\\?.*", ""));
        int q = uri.indexOf('?');
        if (q >= 0) {
            for (String coppia : uri.substring(q + 1).split("&")) {
                String[] kv = coppia.split("=", 2);
                richiesta.setParameter(kv[0], kv.length > 1 ? kv[1] : "");
            }
            richiesta.setQueryString(uri.substring(q + 1));
        }
        richiesta.setServletPath(richiesta.getRequestURI());
        var decisione = autorizzazione().authorize(chi::autenticazione, richiesta);
        boolean passa = decisione != null && decisione.isGranted();
        assertThat(passa).as("%s per %s (livello %s)", indirizzo, chi, livello)
                .isEqualTo(chi.finoA.ordinal() >= livello.ordinal());
    }

    @SuppressWarnings("unchecked")
    private org.springframework.security.authorization.AuthorizationManager<jakarta.servlet.http.HttpServletRequest> autorizzazione() {
        return catene.stream().flatMap(c -> c.getFilters().stream())
                .filter(org.springframework.security.web.access.intercept.AuthorizationFilter.class::isInstance)
                .map(f -> ((org.springframework.security.web.access.intercept.AuthorizationFilter) f).getAuthorizationManager())
                .map(m -> (org.springframework.security.authorization.AuthorizationManager<jakarta.servlet.http.HttpServletRequest>) m)
                .findFirst().orElseThrow();
    }

    /** Un indirizzo nuovo senza la sua riga nell'inventario fa fallire qui: decidi chi lo può chiamare. */
    @Test
    void ogniIndirizzoEInInventario() {
        java.util.Set<String> nelCodice = new java.util.TreeSet<>();
        indirizzi.getHandlerMethods().forEach((info, metodo) -> {
            var metodi = info.getMethodsCondition().getMethods();
            for (String percorso : info.getPatternValues()) {
                if (metodi.isEmpty()) {
                    nelCodice.add("* " + percorso);
                }
                metodi.forEach(m -> nelCodice.add(m.name() + " " + percorso));
            }
        });
        java.util.Set<String> inventariati = new java.util.TreeSet<>();
        inventario().forEach(v -> inventariati.add(v.metodo() + " " + v.mappatura()));
        assertThat(nelCodice).as("indirizzi dei controller che mancano in INVENTARIO")
                .isSubsetOf(inventariati);
        assertThat(inventariati).as("righe di INVENTARIO senza un controller").isSubsetOf(nelCodice);
    }


    @Test
    void senzaLoginLeApiRispondono401ELePagineMandanoAKeycloak() throws Exception {
        mvc.perform(get("/api/foto")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/keycloak"));
        mvc.perform(get("/salute")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(get("/api/salute")).andExpect(status().isUnauthorized());
        // I file della PWA non chiedono il login: 200 se il frontend è compilato in static/, 404 se no
        // (in CI), mai 401 o il redirect a Keycloak.
        for (String pubblico : new String[] {"/manifest.webmanifest", "/sw.js", "/icone/icona-192.png"}) {
            mvc.perform(get(pubblico)).andExpect(r -> assertThat(r.getResponse().getStatus()).as(pubblico).isIn(200, 404));
        }
    }

    @Test
    void lUtilizzatoreVedeLeFotoMaNonMontaIlCloud() throws Exception {
        mvc.perform(get("/api/foto").with(UTILIZZATORE)).andExpect(status().isOk());
        mvc.perform(get("/api/io").with(UTILIZZATORE))
                .andExpect(jsonPath("$.login").value(true))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.gestore").value(false))
                .andExpect(jsonPath("$.ruolo").value("UTILIZZATORE"));
        mvc.perform(post("/api/cloud/smonta").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/backup").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/indicizza").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/luoghi").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/luoghi").with(UTILIZZATORE)).andExpect(status().isOk());
        mvc.perform(post("/api/archivio/video/converti").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/video/annulla").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        // Il backup sta in Strumenti: nemmeno l'elenco.
        mvc.perform(get("/api/backup").with(UTILIZZATORE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/utenti").with(UTILIZZATORE)).andExpect(status().isForbidden());
        // Nemmeno la pagina "Salute" e la prova di Telegram.
        mvc.perform(get("/api/salute").with(UTILIZZATORE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/salute/prova").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        // Né i ricordi su Telegram; "Accadde oggi" nell'app invece sì.
        mvc.perform(get("/api/ricordi-telegram").with(UTILIZZATORE)).andExpect(status().isForbidden());
        mvc.perform(put("/api/ricordi-telegram").with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attivo\":false,\"ora\":\"08:00\",\"foto\":6,\"nessunRicordo\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ricordi-telegram/prova").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/ricordi").with(UTILIZZATORE)).andExpect(status().isOk());
        // Ricerca per contenuto: lo stato lo vede, "Indicizza contenuto" no.
        mvc.perform(get("/api/contenuto").with(UTILIZZATORE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.attiva").value(false));
        mvc.perform(post("/api/contenuto/indicizza").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/contenuto/annulla").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void senzaVisioneLaRicercaPerContenutoNonCe() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(get("/api/contenuto")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/foto/cerca-contenuto").param("q", "cane")).andExpect(status().isUnauthorized());
        // Qui non c'è fototimeline.visione.url: la funzione è spenta, l'app va come prima.
        mvc.perform(get("/api/foto/cerca-contenuto").param("q", "cane").with(UTILIZZATORE))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/contenuto/indicizza").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/contenuto/indicizza").with(admin).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/contenuto/annulla").with(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/foto").param("q", "cane").with(UTILIZZATORE)).andExpect(status().isOk());
    }

    @Test
    void personeEVolti() throws Exception {
        // Senza login niente, nemmeno i ritagli dei volti.
        for (String privato : new String[] {"/api/volti", "/api/persone", "/api/persone/x", "/api/foto/x/volti",
                "/api/volti/x/ritaglio"}) {
            mvc.perform(get(privato)).andExpect(status().isUnauthorized());
        }
        mvc.perform(get("/api/foto").param("persona", "x")).andExpect(status().isUnauthorized());
        // Chi guarda vede lo stato (qui senza visione: spento) e il filtro per persona.
        mvc.perform(get("/api/volti").with(UTILIZZATORE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.attiva").value(false));
        mvc.perform(get("/api/foto").param("persona", "x").with(UTILIZZATORE)).andExpect(status().isOk());
        mvc.perform(get("/api/persone").with(UTILIZZATORE)).andExpect(status().isConflict());
        // ...ma non dà nomi ai volti di una foto.
        mvc.perform(post("/api/volti/x/nome").with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Anna\"}")).andExpect(status().isForbidden());
        // Il gestore sì, dal visore (qui 409 perché la funzione è spenta, non 403), col CSRF come tutto il resto.
        mvc.perform(post("/api/volti/x/nome").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Anna\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/volti/x/nome").with(GESTORE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Anna\"}")).andExpect(status().isForbidden());
        // La pagina Persone (nomi, unioni, "non è lei") sta in Strumenti: solo l'amministratore.
        mvc.perform(put("/api/persone/x").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Anna\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/persone/unisci").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"in\":\"a\",\"da\":[\"b\"]}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/volti/x/togli").with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/persone/x").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Anna\"}")).andExpect(status().isConflict());
        mvc.perform(post("/api/persone/unisci").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"in\":\"a\",\"da\":[\"b\"]}")).andExpect(status().isConflict());
        mvc.perform(post("/api/volti/x/togli").with(ADMIN).with(csrf())).andExpect(status().isConflict());
        // "Riconosci volti" è da admin.
        mvc.perform(post("/api/volti/riconosci").with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/volti/annulla").with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/volti/riconosci").with(ADMIN).with(csrf())).andExpect(status().isConflict());
        mvc.perform(post("/api/volti/annulla").with(ADMIN).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void ilDumpDeiThreadLoVedeSoloLAmministratore() throws Exception {
        mvc.perform(get("/api/diagnostica/thread")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/diagnostica/thread").with(GESTORE)).andExpect(status().isForbidden());
        // Un thread virtuale fermo: kill -3 non lo elenca, il dump sì.
        var fermo = new java.util.concurrent.CountDownLatch(1);
        Thread virtuale = Thread.ofVirtual().name("virtuale-di-prova").start(() -> {
            try {
                fermo.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
            String dump = mvc.perform(get("/api/diagnostica/thread").with(admin)).andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                    .andReturn().getResponse().getContentAsString();
            assertThat(dump).contains("dump dei thread").contains("DiagnosticaController").contains("virtuale-di-prova");
        } finally {
            fermo.countDown();
            virtuale.join();
        }
    }

    @Test
    void lAmministratoreVedeLaSaluteMaNonIlToken() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        String corpo = mvc.perform(get("/api/salute").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").isNotEmpty())
                .andExpect(jsonPath("$.controllatoIl").isNotEmpty())
                .andExpect(jsonPath("$.avvisiTelegram").value(true))
                .andExpect(jsonPath("$.voci[?(@.chiave == 'archivio')].stato").value("OK"))
                .andExpect(jsonPath("$.voci[?(@.chiave == 'backup')]").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("token-segreto").doesNotContain("123456");
        // Senza token CSRF la prova non parte (e qui non deve partire davvero).
        mvc.perform(post("/api/salute/prova").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void lAmministratoreImpostaIRicordiSuTelegramMaNonVedeIlToken() throws Exception {
        mvc.perform(get("/api/ricordi-telegram")).andExpect(status().isUnauthorized());
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        String corpo = mvc.perform(get("/api/ricordi-telegram").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.telegramConfigurato").value(true))
                .andExpect(jsonPath("$.ora").value("08:00"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("token-segreto").doesNotContain("123456");
        corpo = mvc.perform(put("/api/ricordi-telegram").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"attivo\":false,\"ora\":\"7:30\",\"foto\":4,\"nessunRicordo\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ora").value("07:30"))
                .andExpect(jsonPath("$.foto").value(4))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("token-segreto");
        mvc.perform(put("/api/ricordi-telegram").with(admin).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attivo\":true,\"ora\":\"alle otto\",\"foto\":4,\"nessunRicordo\":false}"))
                .andExpect(status().isBadRequest());
        // Senza token CSRF la prova non parte (e qui non deve partire davvero).
        mvc.perform(post("/api/ricordi-telegram/prova").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void leQuasiUgualiStannoInStrumentiSoloPerLAmministratore() throws Exception {
        // Al gestore niente, nemmeno l'elenco: è una pagina di Strumenti.
        mvc.perform(get("/api/quasi-uguali").with(GESTORE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/risolvi").with(GESTORE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[\"a\"],\"togli\":[\"b\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/ignora").with(GESTORE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"a\",\"b\"]}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/calcola").with(GESTORE).with(csrf())).andExpect(status().isForbidden());

        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(get("/api/quasi-uguali").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(0));
        mvc.perform(get("/api/quasi-uguali/calcola").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.senzaImpronta").value(0));
        // Qui le foto non ci sono, quindi 0.
        mvc.perform(post("/api/quasi-uguali/risolvi").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[\"a\"],\"togli\":[\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminate").value(0));
        mvc.perform(post("/api/quasi-uguali/risolvi").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[],\"togli\":[\"b\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin)).andExpect(status().isForbidden());
        // Senza foto il calcolo può già essere finito quando arriva la risposta.
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin).with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stato").value(oneOf("IN_CORSO", "FINITO")));
    }

    @Test
    void ilCestinoLoApreSoloLAmministratoreEliminareBastaIlGestore() throws Exception {
        mvc.perform(get("/api/cestino")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/cestino/svuota").with(csrf())).andExpect(status().isUnauthorized());
        // Eliminare (nel cestino) è del gestore; ripristinare e svuotare stanno in Strumenti.
        mvc.perform(delete("/api/foto/a").with(GESTORE).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/foto/a").with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        for (var chi : new OidcLoginRequestPostProcessor[] {GESTORE, UTILIZZATORE}) {
            mvc.perform(get("/api/cestino").with(chi)).andExpect(status().isForbidden());
            mvc.perform(post("/api/cestino/ripristina").with(chi).with(csrf())
                    .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"a\"]}"))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/cestino/svuota").with(chi).with(csrf())).andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/cestino").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.giorni").value(30));
        mvc.perform(post("/api/cestino/ripristina").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"a\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fatte").value(0));
        mvc.perform(post("/api/cestino/elimina").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"a\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.fatte").value(0));
        mvc.perform(post("/api/cestino/svuota").with(ADMIN).with(csrf())).andExpect(status().isOk());
        // Senza token CSRF no.
        mvc.perform(post("/api/cestino/svuota").with(ADMIN)).andExpect(status().isForbidden());
    }

    private static final OidcLoginRequestPostProcessor ANNA = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "anna").claim("name", "Anna Rossi"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));
    private static final OidcLoginRequestPostProcessor MARCO = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "marco").claim("name", "Marco"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));

    private void dueTelefoni() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        mongo.insert(new SorgenteTelefono("t-marco", "Telefono di Marco", "marco", false, "pcloud:Automatic Upload",
                6, 7, 6, Instant.parse("2026-01-01T00:00:00Z"), null));
        mongo.insert(new SorgenteTelefono("t-anna", "Telefono di Anna", "anna", false, "pcloud-anna:Automatic Upload",
                6, 7, 6, Instant.parse("2026-01-02T00:00:00Z"), null));
    }

    @Test
    void iTelefoniStannoInStrumentiSoloPerLAmministratore() throws Exception {
        dueTelefoni();
        // Anche il suo: il gestore non apre Strumenti (proposta di eccezione nella PR, non fatta).
        mvc.perform(get("/api/telefoni").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(put("/api/telefoni/t-anna").with(ANNA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attiva\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/telefoni").with(ANNA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Mio\",\"proprietario\":\"anna\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/telefoni/t-anna").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/telefoni/t-anna/sincronizza").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/telefoni").with(UTILIZZATORE)).andExpect(status().isForbidden());
    }

    @Test
    void lAmministratoreGestisceTuttiITelefoni() throws Exception {
        dueTelefoni();
        // Qui non c'è rclone: si vedono, ma non si attivano e non partono.
        mvc.perform(get("/api/telefoni").with(MARCO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(false))
                .andExpect(jsonPath("$.motivo").isNotEmpty())
                .andExpect(jsonPath("$.telefoni[*].id").value(org.hamcrest.Matchers.contains("t-marco", "t-anna")))
                .andExpect(jsonPath("$.telefoni[0].inCorso").value(false))
                .andExpect(jsonPath("$.telefoni[0].intervalloOre").value(6));
        String papa = "{\"nome\":\"Telefono di papà\",\"proprietario\":\"papa\",\"sorgente\":\"pcloud-papa:Automatic Upload\"}";
        mvc.perform(post("/api/telefoni").with(MARCO)
                .contentType(MediaType.APPLICATION_JSON).content(papa))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/telefoni").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(papa))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.proprietario").value("papa"))
                .andExpect(jsonPath("$.intervalloOre").value(6));
        mvc.perform(post("/api/telefoni").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(papa))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("già")));
        mvc.perform(put("/api/telefoni/t-marco").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ore")));
        mvc.perform(put("/api/telefoni/t-marco").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attiva\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/telefoni/t-marco").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":12,\"giorniPrimaDiCancellare\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalloOre").value(12))
                .andExpect(jsonPath("$.giorniPrimaDiCancellare").value(0));
        mvc.perform(put("/api/telefoni/nessuno").with(MARCO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":12}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/telefoni/t-marco/sincronizza").with(MARCO).with(csrf())).andExpect(status().isConflict());
        mvc.perform(delete("/api/telefoni/t-anna").with(MARCO).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/telefoni/t-anna").with(MARCO).with(csrf())).andExpect(status().isNotFound());
        // Il vecchio indirizzo non c'è più.
        mvc.perform(get("/api/telefono").with(MARCO)).andExpect(status().isNotFound());
    }

    @Test
    void gliUtentiSiRegistranoELeFotoSannoChiLeHaCaricate() throws Exception {
        mongo.remove(new Query(), Utente.class);
        mvc.perform(get("/api/io").with(ANNA))
                .andExpect(jsonPath("$.username").value("anna"))
                .andExpect(jsonPath("$.nome").value("Anna Rossi"));
        mvc.perform(get("/api/io").with(MARCO)).andExpect(jsonPath("$.admin").value(true));

        var immagine = new java.io.ByteArrayOutputStream();
        var img = new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(3, 4, 0x123456);
        javax.imageio.ImageIO.write(img, "png", immagine);
        mvc.perform(multipart("/api/foto").file(new MockMultipartFile("file", "mare.png", "image/png", immagine.toByteArray()))
                .with(ANNA).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].esito").value("CARICATA"))
                .andExpect(jsonPath("$[0].foto.caricataDa").value("anna"));

        mvc.perform(get("/api/caricate-da").with(UTILIZZATORE))
                .andExpect(jsonPath("$[0].username").value("anna"))
                .andExpect(jsonPath("$[0].nome").value("Anna Rossi"))
                .andExpect(jsonPath("$[0].conteggio").value(1));
        mvc.perform(get("/api/foto").param("caricataDa", "anna").with(UTILIZZATORE)).andExpect(jsonPath("$.totale").value(1));
        mvc.perform(get("/api/foto").param("caricataDa", "marco").with(UTILIZZATORE)).andExpect(jsonPath("$.totale").value(0));
        mvc.perform(get("/api/timeline").param("caricataDa", "anna").with(UTILIZZATORE))
                .andExpect(jsonPath("$[0].conteggio").value(1));

        mvc.perform(get("/api/utenti").with(MARCO)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].username").value(org.hamcrest.Matchers.contains("anna", "marco")))
                .andExpect(jsonPath("$[0].nome").value("Anna Rossi"))
                .andExpect(jsonPath("$[0].admin").value(false))
                .andExpect(jsonPath("$[1].admin").value(true));
    }

    @Test
    void lAmministratoreMontaMaServeIlTokenCsrf() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(get("/api/io").with(admin)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(post("/api/cloud/smonta").with(admin)).andExpect(status().isForbidden());
        // Passa la sicurezza; qui non c'è rclone, quindi 409.
        mvc.perform(post("/api/cloud/smonta").with(admin).with(csrf())).andExpect(status().isConflict());
        // Qui non c'è il dataset di GeoNames: "Calcola luoghi" passa la sicurezza ma non parte.
        mvc.perform(post("/api/archivio/luoghi").with(admin).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("GeoNames")));
        // L'archivio qui è un disco, sempre disponibile: l'indicizzazione parte.
        mvc.perform(post("/api/archivio/indicizza").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/indicizza").with(admin).with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.origine").value("INDICIZZAZIONE"));
    }

    /**
     * "Condividi → FotoTimeline" senza service worker: la POST del telefono non ha il token CSRF.
     * Torna all'app (che dice di riprovare) e nessun file entra, con o senza login.
     */
    @Test
    void laCondivisioneSenzaServiceWorkerTornaAllAppSenzaCaricareNiente() throws Exception {
        var immagine = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(12, 7, java.awt.image.BufferedImage.TYPE_INT_RGB),
                "png", immagine);
        var file = new MockMultipartFile("file", "dal-telefono.png", "image/png", immagine.toByteArray());
        long prima = mongo.count(new Query(), it.fototimeline.dominio.Foto.class);

        // Senza login: il CSRF la respinge prima di tutto, si torna all'app (che poi chiede il login).
        mvc.perform(multipart("/ricevi-condivisi").file(file).param("titolo", "x"))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/?condivisi=senza-app"));
        // Anche con un token valido, senza login non si passa: Keycloak.
        mvc.perform(multipart("/ricevi-condivisi").file(file).with(csrf()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/keycloak"));
        // Con login: di nuovo all'app, i file non si leggono.
        mvc.perform(multipart("/ricevi-condivisi").file(file).with(ANNA))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/?condivisi=senza-app"));
        mvc.perform(multipart("/ricevi-condivisi").file(file).with(ANNA).with(csrf()))
                .andExpect(status().isSeeOther())
                .andExpect(redirectedUrl("/?condivisi=senza-app"));
        assertThat(mongo.count(new Query(), it.fototimeline.dominio.Foto.class)).isEqualTo(prima);

        // Il caricamento vero resta chiuso senza token, e il redirect vale solo per quell'indirizzo.
        mvc.perform(multipart("/api/foto").file(file).with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/foto").file(file)).andExpect(status().isForbidden());
        mvc.perform(post("/api/cloud/smonta").with(ANNA)).andExpect(status().isForbidden());
        assertThat(mongo.count(new Query(), it.fototimeline.dominio.Foto.class)).isEqualTo(prima);
        // Una GET non è la condivisione: per chi non è entrato, il login come ogni pagina.
        mvc.perform(get("/ricevi-condivisi")).andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/keycloak"));
    }

    @Test
    void googleFotoSoloPerLAmministratore() throws Exception {
        for (String indirizzo : new String[] {"/api/google", "/api/google/collega", "/api/google/callback",
                "/api/google/scelta", "/api/google/takeout"}) {
            mvc.perform(get(indirizzo)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/google/scelta").with(csrf())).andExpect(status().isUnauthorized());

        // Google Foto (Picker e Takeout) sta in Strumenti: il gestore no.
        mvc.perform(get("/api/google").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(get("/api/google/collega").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/scelta").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        // Due amministratori: il collegamento a Google è di ciascuno.
        var annaAdmin = oidcLogin()
                .idToken(t -> t.claim("preferred_username", "anna").claim("name", "Anna Rossi"))
                .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        String corpo = mvc.perform(get("/api/google").with(annaAdmin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.configurato").value(true))
                .andExpect(jsonPath("$.collegato").value(false))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("GOCSPX").doesNotContain("segreto");
        mvc.perform(get("/api/google/takeout").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/takeout/avvia").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/takeout/annulla").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(put("/api/google/takeout").with(ANNA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sorgente\":\"gdrive:Altro\"}")).andExpect(status().isForbidden());
        // CSRF su tutte le POST; con il token: non collegato → 409.
        mvc.perform(post("/api/google/scelta").with(annaAdmin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/scelta").with(annaAdmin).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Collega Google")));
        mvc.perform(post("/api/google/scollega").with(annaAdmin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/scollega").with(annaAdmin).with(csrf())).andExpect(status().isNoContent());

        // Il consenso: a Google con lo state in sessione, mai il secret nell'indirizzo.
        var sessione = new org.springframework.mock.web.MockHttpSession();
        String verso = mvc.perform(get("/api/google/collega").with(annaAdmin).session(sessione))
                .andExpect(status().isFound())
                .andReturn().getResponse().getRedirectedUrl();
        assertThat(verso).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=id-di-prova.apps.googleusercontent.com")
                .contains("photospicker.mediaitems.readonly")
                .contains("redirect_uri=http://localhost/api/google/callback")
                .doesNotContain("GOCSPX");
        String stato = org.springframework.web.util.UriComponentsBuilder.fromUriString(verso).build()
                .getQueryParams().getFirst("state");
        assertThat(stato).hasSizeGreaterThan(30);
        // State sbagliato: niente scambio del codice, si torna all'app con un errore. E lo state si consuma.
        mvc.perform(get("/api/google/callback").param("code", "c").param("state", "falso").with(annaAdmin).session(sessione))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/?google=errore"));
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", stato).with(annaAdmin)
                .session(sessione))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/?google=errore"));
        // State giusto ma consenso negato: si torna all'app senza chiamare Google.
        mvc.perform(get("/api/google/collega").with(annaAdmin).session(sessione)).andExpect(status().isFound());
        String nuovo = ((it.fototimeline.google.SceltaGoogleFoto.StatoOAuth) sessione
                .getAttribute("it.fototimeline.google.GoogleController.stato")).valore();
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", nuovo).with(MARCO)
                .session(sessione))
                .andExpect(redirectedUrl("/?google=errore"));
        mvc.perform(get("/api/google/collega").with(annaAdmin).session(sessione)).andExpect(status().isFound());
        nuovo = ((it.fototimeline.google.SceltaGoogleFoto.StatoOAuth) sessione
                .getAttribute("it.fototimeline.google.GoogleController.stato")).valore();
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", nuovo).with(annaAdmin)
                .session(sessione))
                .andExpect(redirectedUrl("/?google=negato"));

        // L'amministratore vede il pannello del Takeout; qui non c'è rclone, quindi non parte.
        corpo = mvc.perform(get("/api/google/takeout").with(MARCO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(false))
                .andExpect(jsonPath("$.sorgente").value("gdrive:Takeout"))
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("GOCSPX");
        mvc.perform(post("/api/google/takeout/avvia").with(MARCO)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/takeout/avvia").with(MARCO).with(csrf())).andExpect(status().isConflict());
        mvc.perform(put("/api/google/takeout").with(MARCO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sorgente\":\"senza-due-punti\"}")).andExpect(status().isBadRequest());
        mvc.perform(put("/api/google/takeout").with(MARCO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"sorgente\":\"gdrive:Takeout\",\"giorniPrimaDiCancellare\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.giorniPrimaDiCancellare").value(30));
    }

    /** Una foto caricata dal gestore: id. */
    private String caricata(String nome, int larghezza, int altezza, int colore) throws Exception {
        var immagine = new java.io.ByteArrayOutputStream();
        var img = new java.awt.image.BufferedImage(larghezza, altezza, java.awt.image.BufferedImage.TYPE_INT_RGB);
        img.setRGB(1, 1, colore);
        javax.imageio.ImageIO.write(img, "png", immagine);
        String risposta = mvc.perform(multipart("/api/foto")
                        .file(new MockMultipartFile("file", nome, "image/png", immagine.toByteArray()))
                        .with(GESTORE).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].esito").value("CARICATA"))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(risposta, "$[0].foto.id");
    }

    @Test
    void lUtilizzatoreGuardaMaNonScaricaNeCambiaNiente() throws Exception {
        String id = caricata("panorama.png", 3000, 20, 0x336699);
        // Guarda: la scheda, la miniatura e la vista, ridotta (come nei link condivisi) e in JPEG.
        mvc.perform(get("/api/foto/" + id).with(UTILIZZATORE)).andExpect(status().isOk());
        mvc.perform(get("/api/foto/" + id + "/miniatura").with(UTILIZZATORE)).andExpect(status().isOk());
        byte[] vista = mvc.perform(get("/api/foto/" + id + "/vista").with(UTILIZZATORE))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(vista)).getWidth()).isLessThanOrEqualTo(2048);
        // Il gestore ha la vista di sempre (qui il PNG originale) e l'originale da scaricare.
        mvc.perform(get("/api/foto/" + id + "/vista").with(GESTORE)).andExpect(content().contentType(MediaType.IMAGE_PNG));
        mvc.perform(get("/api/foto/" + id + "/file").param("scarica", "true").with(GESTORE)).andExpect(status().isOk())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("attachment"));

        // L'originale no, in nessuna forma.
        mvc.perform(get("/api/foto/" + id + "/file").with(UTILIZZATORE)).andExpect(status().isForbidden());
        mvc.perform(get("/api/foto/" + id + "/file").param("scarica", "true").with(UTILIZZATORE))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/foto/" + id + "/file").param("originale", "true").with(UTILIZZATORE))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/foto/" + id + "/file").param("scarica", "on").with(UTILIZZATORE))
                .andExpect(status().isForbidden());

        // Niente modifiche, eliminazioni, rotazioni, caricamenti, link.
        mvc.perform(put("/api/foto/" + id).with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titolo\":\"mio\"}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/foto/" + id).with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/foto/" + id + "/ruota").with(UTILIZZATORE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\":90}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/foto/multiple").with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"],\"operazione\":\"PREFERITA\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/foto/" + id + "/luogo").with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"chiave\":null}")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/foto").file(new MockMultipartFile("file", "x.png", "image/png", new byte[] {1}))
                .with(UTILIZZATORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/condivisioni").with(UTILIZZATORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"]}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/importazioni/corrente").with(UTILIZZATORE)).andExpect(status().isForbidden());

        // Un video si guarda (a pezzi, inline), ma non si scarica; la sua "vista" è la miniatura.
        mongo.updateFirst(org.springframework.data.mongodb.core.query.Query.query(
                        org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new org.springframework.data.mongodb.core.query.Update().set("video", true).set("contentType", "video/mp4"),
                it.fototimeline.dominio.Foto.class);
        mvc.perform(get("/api/foto/" + id + "/file").with(UTILIZZATORE).header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(r -> assertThat(r.getResponse().getHeader("Content-Disposition")).startsWith("inline"));
        mvc.perform(get("/api/foto/" + id + "/file").param("scarica", "true").with(UTILIZZATORE))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/foto/" + id + "/file").param("originale", "true").with(UTILIZZATORE))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/foto/" + id + "/vista").with(UTILIZZATORE)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
    }

    @Test
    void ilGestoreLavoraSulleFotoMaNonApreStrumentiNeGliUtenti() throws Exception {
        String id = caricata("festa.png", 30, 20, 0x996633);
        mvc.perform(get("/api/io").with(GESTORE))
                .andExpect(jsonPath("$.ruolo").value("GESTORE"))
                .andExpect(jsonPath("$.gestore").value(true))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.radiceImportazione").doesNotExist());
        mvc.perform(put("/api/foto/" + id).with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"titolo\":\"Festa\",\"tag\":[\"famiglia\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.titolo").value("Festa"));
        mvc.perform(post("/api/foto/" + id + "/ruota").with(GESTORE).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\":90}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rotazione").value(90));
        mvc.perform(post("/api/foto/multiple").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"],\"operazione\":\"PREFERITA\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.modificate").value(1));
        mvc.perform(post("/api/condivisioni").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + id + "\"]}")).andExpect(status().isCreated());
        mvc.perform(delete("/api/foto/" + id).with(GESTORE).with(csrf())).andExpect(status().isNoContent());

        // Strumenti e utenti: 403, anche solo per guardare.
        for (String vietato : new String[] {"/api/utenti", "/api/gestione-utenti", "/api/cestino", "/api/backup",
                "/api/salute", "/api/importazioni/corrente", "/api/archivio/video", "/api/cartelle", "/api/telefoni",
                "/api/google", "/api/quasi-uguali", "/api/condivisioni", "/api/diagnostica/thread",
                "/api/ricordi-telegram", "/api/persone/x/proposte"}) {
            mvc.perform(get(vietato).with(GESTORE)).andExpect(r -> assertThat(r.getResponse().getStatus()).as(vietato)
                    .isEqualTo(403));
        }
        mvc.perform(post("/api/importa").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"cartella\":\"/tmp\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/indicizza").with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/eventi").with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/gestione-utenti").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void lAmministratoreApreLaPaginaUtentiCheDiceCosaManca() throws Exception {
        // Qui non c'è il segreto del client di Keycloak: la pagina lo dice, e non si crea niente.
        mvc.perform(get("/api/gestione-utenti").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(false))
                .andExpect(jsonPath("$.motivo").value(org.hamcrest.Matchers.containsString("FOTOTIMELINE_UTENTI_CLIENT_SECRET")));
        mvc.perform(post("/api/gestione-utenti").with(ADMIN).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"anna\",\"ruolo\":\"GESTORE\",\"password\":\"lunga-abbastanza\"}"))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/io").with(ADMIN)).andExpect(jsonPath("$.ruolo").value("AMMINISTRATORE"))
                .andExpect(jsonPath("$.gestore").value(true));
    }

    @Test
    void senzaRuoloSiEntraMaNonSiVedeNiente() throws Exception {
        mvc.perform(get("/api/io").with(SENZA_RUOLO)).andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(true))
                .andExpect(jsonPath("$.username").value("ospite"))
                .andExpect(jsonPath("$.ruolo").doesNotExist())
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.gestore").value(false));
        for (String vietato : new String[] {"/api/foto", "/api/timeline", "/api/mappa", "/api/ricordi", "/api/tag",
                "/api/foto/x/miniatura", "/api/foto/x/vista", "/api/cloud", "/api/caricate-da"}) {
            mvc.perform(get(vietato).with(SENZA_RUOLO)).andExpect(r -> assertThat(r.getResponse().getStatus()).as(vietato)
                    .isEqualTo(403));
        }
        // La pagina dell'app sì (senza static/ in CI è 404): lì dice di chiedere l'accesso.
        mvc.perform(get("/").with(SENZA_RUOLO)).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(200, 404));
    }

    @Test
    void dopoUnCambioDallaPaginaUtentiLaSessioneDiPrimaNonVale() throws Exception {
        var prima = oidcLogin()
                .idToken(t -> t.claim("preferred_username", "rita").issuedAt(Instant.now().minusSeconds(3600)))
                .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));
        mvc.perform(get("/api/foto").with(prima)).andExpect(status().isOk());
        registro.chiudiSessioni("rita");
        // Le API rispondono 401 (Angular torna al login), le pagine portano a Keycloak.
        mvc.perform(get("/api/foto").with(prima)).andExpect(status().isUnauthorized());
        mvc.perform(get("/").with(prima)).andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/keycloak"));
        // Rientrata dopo: vale.
        var dopo = oidcLogin()
                .idToken(t -> t.claim("preferred_username", "rita").issuedAt(Instant.now().plusSeconds(2)))
                .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));
        mvc.perform(get("/api/foto").with(dopo)).andExpect(status().isOk());
        // Gli altri non c'entrano.
        mvc.perform(get("/api/foto").with(GESTORE)).andExpect(status().isOk());
    }

    @Test
    void ruoliDiRealmDallAccessToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"realm_access\":{\"roles\":[\"fototimeline-admin\",\"offline_access\"]}}".getBytes());
        assertThat(RuoliKeycloak.ruoliDiRealm("h." + payload + ".firma"))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_fototimeline-admin", "ROLE_offline_access");
        assertThat(RuoliKeycloak.ruoliDiRealm("non-un-jwt")).isEmpty();
    }

    @Test
    void senzaLoginSoloSuQuestoPc() {
        ConfigurazioneSicurezza.controllaSoloLocale("127.0.0.1");
        assertThatThrownBy(() -> ConfigurazioneSicurezza.controllaSoloLocale("0.0.0.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfigurazioneSicurezza.controllaSoloLocale(""))
                .isInstanceOf(IllegalStateException.class);
    }
}
