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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
    void unUtenteDelRealmVedeLeFotoMaNonMontaIlCloud() throws Exception {
        mvc.perform(get("/api/foto").with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(get("/api/io").with(oidcLogin()))
                .andExpect(jsonPath("$.login").value(true))
                .andExpect(jsonPath("$.admin").value(false));
        mvc.perform(post("/api/cloud/smonta").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/backup").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/indicizza").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/luoghi").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/luoghi").with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(post("/api/archivio/video/converti").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/video/annulla").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/backup").with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(get("/api/utenti").with(oidcLogin())).andExpect(status().isForbidden());
        // Nemmeno la pagina "Salute" e la prova di Telegram.
        mvc.perform(get("/api/salute").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(post("/api/salute/prova").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        // Né i ricordi su Telegram; "Accadde oggi" nell'app invece sì.
        mvc.perform(get("/api/ricordi-telegram").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(put("/api/ricordi-telegram").with(oidcLogin()).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"attivo\":false,\"ora\":\"08:00\",\"foto\":6,\"nessunRicordo\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/ricordi-telegram/prova").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/ricordi").with(oidcLogin())).andExpect(status().isOk());
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
    void leQuasiUgualiLeRisolveChiunqueMaLeImprontePartonoSoloDallAmministratore() throws Exception {
        mvc.perform(get("/api/quasi-uguali").with(oidcLogin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(0));
        mvc.perform(get("/api/quasi-uguali/calcola").with(oidcLogin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.senzaImpronta").value(0));
        // Come l'eliminazione: basta essere del realm (qui le foto non ci sono, quindi 0).
        mvc.perform(post("/api/quasi-uguali/risolvi").with(oidcLogin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[\"a\"],\"togli\":[\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminate").value(0));
        mvc.perform(post("/api/quasi-uguali/risolvi").with(oidcLogin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[],\"togli\":[\"b\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/quasi-uguali/calcola").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/calcola/annulla").with(oidcLogin()).with(csrf()))
                .andExpect(status().isForbidden());

        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin)).andExpect(status().isForbidden());
        // Senza foto il calcolo può già essere finito quando arriva la risposta.
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin).with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stato").value(oneOf("IN_CORSO", "FINITO")));
    }

    private static final OidcLoginRequestPostProcessor ANNA = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "anna").claim("name", "Anna Rossi"));
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
    void ogniFamiliareVedeSoloIlSuoTelefonoEPuoSoloSincronizzarlo() throws Exception {
        dueTelefoni();
        mvc.perform(get("/api/telefoni").with(ANNA)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(false))
                .andExpect(jsonPath("$.telefoni.length()").value(1))
                .andExpect(jsonPath("$.telefoni[0].id").value("t-anna"));
        mvc.perform(get("/api/telefoni").with(oidcLogin())).andExpect(jsonPath("$.telefoni.length()").value(0));
        mvc.perform(put("/api/telefoni/t-anna").with(ANNA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attiva\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/telefoni").with(ANNA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"Mio\",\"proprietario\":\"anna\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/telefoni/t-anna").with(ANNA).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/telefoni/t-marco/sincronizza").with(ANNA).with(csrf())).andExpect(status().isNotFound());
        // Il suo sì; qui non c'è rclone, quindi 409.
        mvc.perform(post("/api/telefoni/t-anna/sincronizza").with(ANNA).with(csrf())).andExpect(status().isConflict());
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

        mvc.perform(get("/api/caricate-da").with(oidcLogin()))
                .andExpect(jsonPath("$[0].username").value("anna"))
                .andExpect(jsonPath("$[0].nome").value("Anna Rossi"))
                .andExpect(jsonPath("$[0].conteggio").value(1));
        mvc.perform(get("/api/foto").param("caricataDa", "anna").with(oidcLogin())).andExpect(jsonPath("$.totale").value(1));
        mvc.perform(get("/api/foto").param("caricataDa", "marco").with(oidcLogin())).andExpect(jsonPath("$.totale").value(0));
        mvc.perform(get("/api/timeline").param("caricataDa", "anna").with(oidcLogin()))
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
    void googleFotoSoloDopoIlLoginEIlTakeoutSoloPerLAmministratore() throws Exception {
        for (String indirizzo : new String[] {"/api/google", "/api/google/collega", "/api/google/callback",
                "/api/google/scelta", "/api/google/takeout"}) {
            mvc.perform(get(indirizzo)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/google/scelta").with(csrf())).andExpect(status().isUnauthorized());

        // Chi è del realm: Google Foto sì (col suo account), il Takeout no.
        String corpo = mvc.perform(get("/api/google").with(ANNA)).andExpect(status().isOk())
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
        mvc.perform(post("/api/google/scelta").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/scelta").with(ANNA).with(csrf())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Collega Google")));
        mvc.perform(post("/api/google/scollega").with(ANNA)).andExpect(status().isForbidden());
        mvc.perform(post("/api/google/scollega").with(ANNA).with(csrf())).andExpect(status().isNoContent());

        // Il consenso: a Google con lo state in sessione, mai il secret nell'indirizzo.
        var sessione = new org.springframework.mock.web.MockHttpSession();
        String verso = mvc.perform(get("/api/google/collega").with(ANNA).session(sessione))
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
        mvc.perform(get("/api/google/callback").param("code", "c").param("state", "falso").with(ANNA).session(sessione))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/?google=errore"));
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", stato).with(ANNA)
                .session(sessione))
                .andExpect(status().isFound()).andExpect(redirectedUrl("/?google=errore"));
        // State giusto ma consenso negato: si torna all'app senza chiamare Google.
        mvc.perform(get("/api/google/collega").with(ANNA).session(sessione)).andExpect(status().isFound());
        String nuovo = ((it.fototimeline.google.SceltaGoogleFoto.StatoOAuth) sessione
                .getAttribute("it.fototimeline.google.GoogleController.stato")).valore();
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", nuovo).with(MARCO)
                .session(sessione))
                .andExpect(redirectedUrl("/?google=errore"));
        mvc.perform(get("/api/google/collega").with(ANNA).session(sessione)).andExpect(status().isFound());
        nuovo = ((it.fototimeline.google.SceltaGoogleFoto.StatoOAuth) sessione
                .getAttribute("it.fototimeline.google.GoogleController.stato")).valore();
        mvc.perform(get("/api/google/callback").param("error", "access_denied").param("state", nuovo).with(ANNA)
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
