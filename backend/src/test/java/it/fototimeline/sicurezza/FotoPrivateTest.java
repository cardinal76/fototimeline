package it.fototimeline.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.contenuto.Visione;
import it.fototimeline.contenuto.Visione.Somiglianza;
import it.fototimeline.dominio.Foto;
import it.fototimeline.eventi.EventiService;
import it.fototimeline.quasiuguali.QuasiUguali;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Visibilita;
import it.fototimeline.volti.Persona;
import it.fototimeline.volti.VisioneVolti;
import it.fototimeline.volti.VisioneVolti.ImmagineVolti;
import it.fototimeline.volti.VisioneVolti.Rilevamento;
import it.fototimeline.volti.VisioneVolti.VoltoRilevato;
import it.fototimeline.volti.Volto;

/**
 * Le foto private (album "Privato", {@link Visibilita}) con il login acceso,
 * come su server2: Anna (gestore) mette una foto nel suo privato; Bruno
 * (gestore), il capo (amministratore) e la nonna (utilizzatrice) non la
 * trovano più da nessuna parte, e non la toccano; Anna sì.
 *
 * <p>{@link #ogniLetturaEControllataODichiarataSenzaFoto()} tiene il conto
 * degli indirizzi: ogni GET di {@link SicurezzaTest#INVENTARIO} è provato qui
 * ({@link #CONTROLLATI}) o dichiarato senza foto ({@link #SENZA_FOTO}). Un
 * indirizzo nuovo che manca fa fallire: decidi se mostra foto, e se sì
 * provalo qui con una privata.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.login.attivo=true",
        "server.address=0.0.0.0",
        "fototimeline.luoghi.cartella=classpath:geonames",
        // Visione finta (sotto): ricerca per contenuto e volti accesi, senza servizio vero.
        "fototimeline.visione.url=http://visione.invalid:8000",
        "fototimeline.visione.segreto=prova",
        "fototimeline.volti.attesa=PT0S",
        // Un evento anche con due foto: la privata non deve contare né dare la copertina.
        "fototimeline.eventi.minimo-foto=2",
        "fototimeline.condivisioni.token-sbagliati=1000",
        "spring.security.oauth2.client.registration.keycloak.client-id=fototimeline",
        "spring.security.oauth2.client.registration.keycloak.client-secret=segreto",
        "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.keycloak.scope=openid",
        "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://kc.invalid/auth",
        "spring.security.oauth2.client.provider.keycloak.token-uri=https://kc.invalid/token",
        "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://kc.invalid/certs",
        "spring.security.oauth2.client.provider.keycloak.user-name-attribute=sub",
})
@AutoConfigureMockMvc
class FotoPrivateTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final RequestPostProcessor ANNA = gestore("anna");
    private static final RequestPostProcessor BRUNO = gestore("bruno");
    private static final RequestPostProcessor CAPO = oidcLogin()
            .idToken(t -> t.subject("capo").claim("preferred_username", "capo"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
    private static final RequestPostProcessor NONNA = oidcLogin()
            .idToken(t -> t.subject("nonna").claim("preferred_username", "nonna"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-utilizzatore"));

    /** Quelli che la privata di Anna non la devono vedere. */
    private static final List<RequestPostProcessor> ALTRI = List.of(BRUNO, CAPO, NONNA);

    @Autowired
    MockMvc mvc;

    @Autowired
    FotoService fotoService;

    @Autowired
    FotoRepository fotoRepository;

    @Autowired
    EventiService eventi;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    QuasiUguali quasiUguali;

    @MockitoBean
    VisioneVolti visioneVolti;

    @MockitoBean
    Visione clip;

    /** La foto che Anna mette nel privato, e due di tutti dello stesso giorno (una quasi uguale a lei). */
    private Foto privata;
    private Foto quasiUguale;
    private Foto altra;

    @BeforeEach
    void prepara() throws Exception {
        // Un volto per foto, ognuno per conto suo (nessun vicino): ogni volto fa da sé.
        when(visioneVolti.rileva(anyMap(), anyInt(), anyDouble())).thenAnswer(i -> {
            Map<String, Path> miniature = i.getArgument(0);
            Map<String, ImmagineVolti> risultati = new java.util.LinkedHashMap<>();
            miniature.keySet().forEach(id -> risultati.put(id, new ImmagineVolti(40, 30, List.of(
                    new VoltoRilevato(new Volto.Riquadro(0.25, 0.25, 0.5, 0.5), 0.9, new float[] {1, 0, 0})))));
            return new Rilevamento("finto", risultati, Map.of());
        });
        when(visioneVolti.vicini(anyList(), anyInt(), anyDouble())).thenAnswer(i -> {
            List<?> vettori = i.getArgument(0);
            return vettori.stream().map(v -> List.<VisioneVolti.Vicino>of()).toList();
        });

        // Via tutto, anche le private (che per chi non è nessuno non si vedono, quindi non si eliminano).
        mongo.updateMulti(new Query(), new Update().unset(Visibilita.CAMPO).unset("privataIl"), Foto.class);
        fotoRepository.findAll().forEach(f -> {
            fotoService.elimina(f.getId());
            fotoService.eliminaPerSempre(f.getId());
        });
        mongo.remove(new Query(), Volto.class);
        mongo.remove(new Query(), Persona.class);
        mongo.remove(new Query(), "eventi");
        mongo.remove(new Query(), "condivisioni");
        // I gruppi in cache si rifanno se cambia il numero di foto: qui resta tre, da un test all'altro.
        quasiUguali.invalida();

        privata = importa("diario.jpg", immagine(0), "2020-05-10T10:00:00Z", "Diario di Anna");
        quasiUguale = importa("mare.jpg", immagine(1), "2020-05-10T11:00:00Z", "Vacanze");
        altra = importa("spiaggia.jpg", immagine(2), "2020-05-10T12:00:00Z", "Vacanze");
        fotoService.modifica(privata.getId(), new FotoService.Modifica("Solo mia", "una nota", List.of("segreto-anna"),
                "Diario di Anna", false, null));
        for (Foto f : List.of(privata, quasiUguale, altra)) {
            // Il GPS e il luogo: la mappa e l'elenco dei luoghi.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(f.getId())), new Update()
                    .set("latitudine", 41.9).set("longitudine", 12.5).set("luogo", "Roma").set("regione", "Lazio")
                    .set("nazione", "Italia").set("codiceNazione", "IT"), Foto.class);
        }
        aspetta(() -> mongo.count(Query.query(Criteria.where("voltiCercati").ne(null)), Foto.class) == 3);
        eventi.calcola(true, EventiService.Avanzamento.NESSUNO);
        when(clip.cerca(anyString(), anyInt(), anyDouble())).thenReturn(List.of(
                new Somiglianza(privata.getId(), 0.9), new Somiglianza(quasiUguale.getId(), 0.89),
                new Somiglianza(altra.getId(), 0.88)));
    }

    // ------------------------------------------------------------ chi la vede

    @Test
    void laFotoPrivataSparisceDaOgniElencoPerTuttiGliAltriAncheLAmministratore() throws Exception {
        // Prima: la vedono tutti, anche nella quasi uguale e nell'evento.
        for (RequestPostProcessor chi : List.of(ANNA, BRUNO, CAPO, NONNA)) {
            assertThat(leggi(chi, "/api/foto")).contains(privata.getId());
        }
        assertThat(leggi(CAPO, "/api/quasi-uguali")).contains(privata.getId());
        String evento = JSON.readTree(leggi(BRUNO, "/api/eventi")).get("eventi").get(0).get("id").asText();
        assertThat(conteggioEvento(BRUNO, evento)).isEqualTo(3);

        mettiNelPrivato(ANNA, privata, 1);
        // Rifatto senza di lei (mettiNelPrivato): l'id cambia, è "a-" + la prima foto.
        evento = JSON.readTree(leggi(BRUNO, "/api/eventi")).get("eventi").get(0).get("id").asText();

        for (RequestPostProcessor chi : ALTRI) {
            for (String indirizzo : List.of("/api/foto", "/api/foto?q=Solo", "/api/foto?q=diario",
                    "/api/foto?tag=segreto-anna", "/api/foto?album=Diario di Anna", "/api/foto?privato=true",
                    "/api/foto?luogo=Roma", "/api/foto?periodo=2020-05", "/api/foto?evento=" + evento,
                    "/api/mappa", "/api/ricordi?data=2025-05-10", "/api/foto/cerca-contenuto?q=diario",
                    "/api/tag", "/api/album")) {
                String risposta = leggi(chi, indirizzo);
                assertThat(risposta).as(indirizzo).doesNotContain(privata.getId()).doesNotContain("segreto-anna")
                        .doesNotContain("Diario di Anna");
            }
            assertThat(JSON.readTree(leggi(chi, "/api/foto")).get("totale").asLong()).isEqualTo(2);
            assertThat(leggi(chi, "/api/foto?privato=true")).contains("\"totale\":0");
            // Colonna dei mesi, luoghi, "Caricate da": i conteggi senza di lei.
            assertThat(JSON.readTree(leggi(chi, "/api/timeline")).get(0).get("conteggio").asLong()).isEqualTo(2);
            assertThat(JSON.readTree(leggi(chi, "/api/luoghi")).get("nazioni").get(0).get("conteggio").asLong())
                    .isEqualTo(2);
            assertThat(JSON.readTree(leggi(chi, "/api/caricate-da")).get(0).get("conteggio").asLong()).isEqualTo(2);
            // L'evento: due foto, la copertina non è lei.
            assertThat(conteggioEvento(chi, evento)).isEqualTo(2);
            assertThat(leggi(chi, "/api/eventi")).doesNotContain(privata.getId());
            assertThat(leggi(chi, "/api/eventi/" + evento)).doesNotContain(privata.getId());
            // Persone: il suo volto non conta.
            assertThat(leggi(chi, "/api/persone?tipo=GRUPPI&limite=100")).doesNotContain(privata.getId());
        }
        // Le quasi uguali e le pagine dell'admin.
        assertThat(leggi(CAPO, "/api/quasi-uguali")).doesNotContain(privata.getId());
        for (JsonNode persona : JSON.readTree(leggi(CAPO, "/api/persone?tipo=GRUPPI&limite=100")).get("persone")) {
            assertThat(leggi(CAPO, "/api/persone/" + persona.get("id").asText() + "/volti"))
                    .doesNotContain(privata.getId());
        }

        // Anna la vede: nella timeline (col suo nome in privataDi), nel suo album "Privato", nei conteggi.
        String sue = leggi(ANNA, "/api/foto?privato=true");
        assertThat(JSON.readTree(sue).get("totale").asLong()).isEqualTo(1);
        assertThat(JSON.readTree(sue).get("foto").get(0).get("id").asText()).isEqualTo(privata.getId());
        assertThat(JSON.readTree(sue).get("foto").get(0).get("privataDi").asText()).isEqualTo("anna");
        for (String indirizzo : List.of("/api/foto", "/api/foto?tag=segreto-anna", "/api/mappa",
                "/api/ricordi?data=2025-05-10", "/api/foto/cerca-contenuto?q=diario")) {
            assertThat(leggi(ANNA, indirizzo)).as(indirizzo).contains(privata.getId());
        }
        // Sulla mappa col segno "privata", che il frontend sfoca; le altre no.
        for (var punto : JSON.readTree(leggi(ANNA, "/api/mappa"))) {
            assertThat(punto.get("privata").asBoolean()).as(punto.get("id").asText())
                    .isEqualTo(punto.get("id").asText().equals(privata.getId()));
        }
        assertThat(leggi(ANNA, "/api/tag")).contains("segreto-anna");
        assertThat(leggi(ANNA, "/api/album")).contains("Diario di Anna");
        assertThat(JSON.readTree(leggi(ANNA, "/api/timeline")).get(0).get("conteggio").asLong()).isEqualTo(3);
        // Gli eventi no, nemmeno per lei: il riepilogo lo vedono tutti.
        assertThat(conteggioEvento(ANNA, evento)).isEqualTo(2);
    }

    @Test
    void unaPersonaNascostaLaVedeSoloLAmministratoreESenzaIVoltiDellePrivate() throws Exception {
        // Uno sconosciuto nascosto con due volti: quello della privata di Anna e quello di una foto di tutti.
        Persona sconosciuto = new Persona();
        sconosciuto.setNome("Il vicino");
        sconosciuto.setNascosta(true);
        sconosciuto = mongo.insert(sconosciuto);
        String id = sconosciuto.getId();
        String voltoPrivata = mongo.findOne(Query.query(Criteria.where("fotoId").is(privata.getId())), Volto.class).getId();
        String voltoAltra = mongo.findOne(Query.query(Criteria.where("fotoId").is(altra.getId())), Volto.class).getId();
        mongo.updateMulti(Query.query(Criteria.where("_id").in(voltoPrivata, voltoAltra)),
                new Update().set("personaId", id), Volto.class);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("copertina", voltoPrivata),
                Persona.class);
        mettiNelPrivato(ANNA, privata, 1);

        // L'admin la ritrova in "Persone nascoste": un volto solo, e la copertina non è quello della privata.
        JsonNode nascoste = JSON.readTree(leggi(CAPO, "/api/persone/nascoste"));
        assertThat(nascoste.get("totale").asLong()).isEqualTo(1);
        assertThat(nascoste.get("persone").get(0).get("id").asText()).isEqualTo(id);
        assertThat(nascoste.get("persone").get(0).get("volti").asLong()).isEqualTo(1);
        assertThat(nascoste.get("persone").get(0).get("copertina").asText()).isEqualTo(voltoAltra);
        assertThat(leggi(CAPO, "/api/persone/nascoste")).doesNotContain(voltoPrivata).doesNotContain(privata.getId());
        assertThat(leggi(CAPO, "/api/persone/" + id + "/volti")).contains(altra.getId()).doesNotContain(privata.getId());
        leggi(CAPO, "/api/persone/" + id);

        // Gli altri non la vedono: né l'elenco, né per id (dagli id di Foto.persone), né il volto nel visore.
        for (RequestPostProcessor chi : List.of(ANNA, BRUNO, NONNA)) {
            mvc.perform(get("/api/persone/nascoste").with(chi)).andExpect(status().isForbidden());
            mvc.perform(get("/api/persone/" + id).with(chi)).andExpect(status().isNotFound());
            mvc.perform(get("/api/persone").param("tipo", "NASCOSTE").with(chi)).andExpect(status().isBadRequest());
            assertThat(leggi(chi, "/api/persone")).doesNotContain(id).doesNotContain("Il vicino");
            assertThat(leggi(chi, "/api/foto/" + altra.getId() + "/volti")).isEqualTo("[]");
            // La foto sì, come prima.
            assertThat(leggi(chi, "/api/foto")).contains(altra.getId());
        }
        // Rimetterla è dell'admin, come nasconderla.
        mvc.perform(post("/api/persone/" + id + "/nascondi").with(BRUNO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nascosta\":false}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/persone/" + id + "/nascondi").with(CAPO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"nascosta\":false}")).andExpect(status().isOk());
        assertThat(leggi(BRUNO, "/api/persone/" + id)).contains("Il vicino");
        assertThat(leggi(BRUNO, "/api/foto/" + altra.getId() + "/volti")).contains("Il vicino");
    }

    @Test
    void perIdLaPrivataRisponde404AGliAltriEAnnaSi() throws Exception {
        mettiNelPrivato(ANNA, privata, 1);
        String volto = mongo.findOne(Query.query(Criteria.where("fotoId").is(privata.getId())), Volto.class).getId();
        List<String> indirizzi = List.of("/api/foto/" + privata.getId(), "/api/foto/" + privata.getId() + "/miniatura",
                "/api/foto/" + privata.getId() + "/vista", "/api/foto/" + privata.getId() + "/file",
                "/api/foto/" + privata.getId() + "/volti", "/api/volti/" + volto + "/ritaglio");
        for (RequestPostProcessor chi : ALTRI) {
            for (String indirizzo : indirizzi) {
                mvc.perform(get(indirizzo).with(chi)).andExpect(status().isNotFound());
            }
        }
        for (RequestPostProcessor chi : List.of(BRUNO, CAPO)) {
            mvc.perform(get("/api/foto/" + privata.getId() + "/file").param("scarica", "true").with(chi))
                    .andExpect(status().isNotFound());
        }
        for (String indirizzo : indirizzi) {
            mvc.perform(get(indirizzo).with(ANNA)).andExpect(status().isOk());
        }
        mvc.perform(get("/api/foto/" + privata.getId() + "/file").param("scarica", "true").with(ANNA))
                .andExpect(status().isOk());
        // La foto di tutti resta com'era per tutti.
        for (RequestPostProcessor chi : ALTRI) {
            mvc.perform(get("/api/foto/" + altra.getId() + "/miniatura").with(chi)).andExpect(status().isOk());
        }
    }

    @Test
    void gliAltriNonLaToccanoEAnnaLaToglieDalPrivato() throws Exception {
        mettiNelPrivato(ANNA, privata, 1);
        String id = privata.getId();
        for (RequestPostProcessor chi : List.of(BRUNO, CAPO)) {
            mvc.perform(put("/api/foto/" + id).with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"titolo\":\"preso\",\"tag\":[]}")).andExpect(status().isNotFound());
            mvc.perform(delete("/api/foto/" + id).with(chi).with(csrf())).andExpect(status().isNotFound());
            mvc.perform(post("/api/foto/" + id + "/ruota").with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"gradi\":90}")).andExpect(status().isNotFound());
            mvc.perform(put("/api/foto/" + id + "/luogo").with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"chiave\":null}")).andExpect(status().isNotFound());
            for (String operazione : List.of("AGGIUNGI_TAG", "IMPOSTA_ALBUM", "PREFERITA", "ELIMINA", "IMPOSTA_LUOGO",
                    "METTI_NEL_PRIVATO", "TOGLI_DAL_PRIVATO")) {
                multipla(chi, List.of(id), operazione, "preso", 0);
            }
            mvc.perform(post("/api/foto/ruota").with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("ids", List.of(id), "gradi", 90))))
                    .andExpect(jsonPath("$.modificate").value(0));
            // Negli eventi non la mettono, né la tolgono.
            mvc.perform(post("/api/eventi").with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                    .content(JSON.writeValueAsString(Map.of("nome", "Rubata", "ids", List.of(id)))))
                    .andExpect(status().isBadRequest());
        }
        // Un evento per date di Bruno su quel giorno non la prende.
        mvc.perform(post("/api/eventi/per-date").with(BRUNO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Il dieci\",\"dal\":\"2020-05-10\",\"al\":\"2020-05-10\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.conteggio").value(2));
        Foto ricaricata = fotoRepository.findById(id).orElseThrow();
        assertThat(ricaricata.getTitolo()).isEqualTo("Solo mia");
        assertThat(ricaricata.getTag()).containsExactly("segreto-anna");
        assertThat(ricaricata.getRotazione()).isZero();
        assertThat(ricaricata.isPreferita()).isFalse();
        assertThat(ricaricata.getEliminataIl()).isNull();
        assertThat(ricaricata.getEvento()).isNull();
        assertThat(ricaricata.getPrivataDi()).isEqualTo("anna");

        // Anna la cambia, e la toglie dal privato: torna di tutti.
        multipla(ANNA, List.of(id), "AGGIUNGI_TAG", "nuovo", 1);
        multipla(ANNA, List.of(id), "TOGLI_DAL_PRIVATO", null, 1);
        assertThat(fotoRepository.findById(id).orElseThrow().getPrivataDi()).isNull();
        for (RequestPostProcessor chi : ALTRI) {
            assertThat(leggi(chi, "/api/foto")).contains(id);
            mvc.perform(get("/api/foto/" + id + "/miniatura").with(chi)).andExpect(status().isOk());
        }
        // L'evento per date dei suoi giorni la riprende al ricalcolo leggero.
        eventi.ricalcolaGiorniToccati();
        assertThat(fotoRepository.findById(id).orElseThrow().getEvento()).isNotNull();
    }

    @Test
    void lUtilizzatoreNonHaUnPrivatoESenzaLoginNonSiMetteNiente() throws Exception {
        mvc.perform(post("/api/foto/multiple").with(NONNA).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("ids", List.of(altra.getId()), "operazione",
                        "METTI_NEL_PRIVATO")))).andExpect(status().isForbidden());
        // In sottofondo (nessuna richiesta, nessuno che guarda) non si mette nel privato di nessuno.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fotoService.mettiNelPrivato(List.of(altra.getId())))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fotoRepository.findById(altra.getId()).orElseThrow().getPrivataDi()).isNull();
    }

    // ------------------------------------------------------------ cestino

    @Test
    void nelCestinoLaPrivataLaVedeERipristinaSoloAnna() throws Exception {
        mettiNelPrivato(ANNA, privata, 1);
        mvc.perform(delete("/api/foto/" + privata.getId()).with(ANNA).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/foto/" + altra.getId()).with(BRUNO).with(csrf())).andExpect(status().isNoContent());

        // La pagina Cestino del capo: solo quella di tutti.
        String cestino = leggi(CAPO, "/api/cestino");
        assertThat(cestino).contains(altra.getId()).doesNotContain(privata.getId());
        // Il capo non la ripristina né la elimina per sempre, neanche per id, e "Svuota" la lascia.
        mvc.perform(post("/api/cestino/ripristina").with(CAPO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("ids", List.of(privata.getId())))))
                .andExpect(jsonPath("$.fatte").value(0));
        mvc.perform(post("/api/cestino/elimina").with(CAPO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("ids", List.of(privata.getId())))))
                .andExpect(jsonPath("$.fatte").value(0));
        mvc.perform(post("/api/cestino/svuota").with(CAPO).with(csrf())).andExpect(jsonPath("$.fatte").value(1));
        assertThat(fotoRepository.findById(privata.getId())).isPresent();

        // Anna la trova nel suo "Privato" del cestino (Bruno no) e la riprende: è ancora privata.
        assertThat(leggi(BRUNO, "/api/privato/cestino")).doesNotContain(privata.getId());
        assertThat(leggi(ANNA, "/api/privato/cestino")).contains(privata.getId());
        mvc.perform(post("/api/privato/cestino/ripristina").with(BRUNO).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("ids", List.of(privata.getId())))))
                .andExpect(jsonPath("$.fatte").value(0));
        mvc.perform(post("/api/privato/cestino/ripristina").with(ANNA).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("ids", List.of(privata.getId())))))
                .andExpect(jsonPath("$.fatte").value(1));
        Foto tornata = fotoRepository.findById(privata.getId()).orElseThrow();
        assertThat(tornata.nelCestino()).isFalse();
        assertThat(tornata.getPrivataDi()).isEqualTo("anna");
        assertThat(leggi(BRUNO, "/api/foto")).doesNotContain(privata.getId());
    }

    // ------------------------------------------------------------ link condivisi

    @Test
    void iLinkDegliAltriNonLaServonoPiuQuelliDiAnnaSi() throws Exception {
        String diBruno = condividi(BRUNO, List.of(privata.getId(), altra.getId()));
        String diAnna = condividi(ANNA, List.of(privata.getId(), altra.getId()));
        mettiNelPrivato(ANNA, privata, 1);

        String galleria = mvc.perform(get("/api/condivise/" + diBruno)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(galleria).contains(altra.getId()).doesNotContain(privata.getId());
        for (String parte : List.of("miniatura", "vista", "video", "originale")) {
            mvc.perform(get("/api/condivise/" + diBruno + "/foto/" + privata.getId() + "/" + parte))
                    .andExpect(status().isNotFound());
        }
        assertThat(vociZip(diBruno)).isEqualTo(1);

        // Il link di Anna (che l'ha messa nel privato) la mostra ancora.
        assertThat(mvc.perform(get("/api/condivise/" + diAnna)).andReturn().getResponse().getContentAsString())
                .contains(privata.getId());
        mvc.perform(get("/api/condivise/" + diAnna + "/foto/" + privata.getId() + "/miniatura"))
                .andExpect(status().isOk());
        assertThat(vociZip(diAnna)).isEqualTo(2);

        // Un link nuovo di Bruno non la prende nemmeno scelta per id, né per album.
        String nuovo = condividi(BRUNO, List.of(privata.getId(), altra.getId()));
        assertThat(mvc.perform(get("/api/condivise/" + nuovo)).andReturn().getResponse().getContentAsString())
                .doesNotContain(privata.getId());
        mvc.perform(post("/api/condivisioni").with(BRUNO).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("album", "Diario di Anna"))))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ l'inventario

    /** Le letture provate qui con una foto privata. */
    static final Set<String> CONTROLLATI = Set.of(
            "/api/foto", "/api/timeline", "/api/mappa", "/api/ricordi", "/api/foto/{id}", "/api/tag", "/api/album",
            "/api/caricate-da", "/api/foto/{id}/miniatura", "/api/foto/{id}/vista", "/api/foto/{id}/file",
            "/api/foto/cerca-contenuto", "/api/luoghi", "/api/eventi", "/api/eventi/{id}", "/api/foto/{id}/volti",
            "/api/volti/{id}/ritaglio", "/api/persone", "/api/persone/nascoste", "/api/persone/{id}/volti", "/api/cestino",
            "/api/privato/cestino", "/api/quasi-uguali", "/api/condivise/{token}",
            "/api/condivise/{token}/foto/{id}/miniatura", "/api/condivise/{token}/foto/{id}/vista",
            "/api/condivise/{token}/foto/{id}/video", "/api/condivise/{token}/foto/{id}/originale",
            "/api/condivise/{token}/zip");

    /**
     * Le letture che non danno foto, né id né metadati: stati dei lavori,
     * impostazioni, utenti, file system, oppure conteggi fatti solo sulle foto
     * di tutti (FotoService.diFamiglia: Salute, persone ed eventi, che le
     * private non le contano).
     */
    static final Set<String> SENZA_FOTO = Set.of(
            "/error", "/salute", "/accesso", "/c/{token}", "/api/io", "/api/cloud", "/api/contenuto",
            "/api/eventi/nomi", "/api/volti", "/api/persone/{id}", "/api/persone/{id}/proposte",
            "/api/persone/{id}/simili", "/api/luoghi/cerca", "/api/utenti", "/api/gestione-utenti", "/api/cartelle",
            "/api/importazioni/corrente", "/api/importazioni/scartati", "/api/archivio/video", "/api/notte",
            "/api/quasi-uguali/calcola", "/api/telefoni", "/api/google", "/api/google/collega",
            "/api/google/callback", "/api/google/scelta", "/api/google/takeout", "/api/condivisioni", "/api/backup",
            "/api/salute", "/api/diagnostica/thread", "/api/ricordi-telegram");

    @Test
    void ogniLetturaEControllataODichiarataSenzaFoto() {
        Set<String> letture = SicurezzaTest.inventario().stream()
                .filter(v -> v.metodo().equals("GET") || v.metodo().equals("*"))
                .map(SicurezzaTest.Voce::mappatura)
                .collect(Collectors.toCollection(TreeSet::new));
        Set<String> classificate = new TreeSet<>(CONTROLLATI);
        classificate.addAll(SENZA_FOTO);
        assertThat(letture).as("GET dell'inventario da provare con una foto privata (o da dichiarare senza foto)")
                .isSubsetOf(classificate);
        assertThat(classificate).as("indirizzi qui che non sono più nell'inventario").isSubsetOf(letture);
        assertThat(CONTROLLATI).doesNotContainAnyElementsOf(SENZA_FOTO);
    }

    // ------------------------------------------------------------ utilità

    private static RequestPostProcessor gestore(String nome) {
        return oidcLogin().idToken(t -> t.subject(nome).claim("preferred_username", nome))
                .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));
    }

    private String leggi(RequestPostProcessor chi, String indirizzo) throws Exception {
        return mvc.perform(get(indirizzo).with(chi)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private void mettiNelPrivato(RequestPostProcessor chi, Foto foto, int attese) throws Exception {
        multipla(chi, List.of(foto.getId()), "METTI_NEL_PRIVATO", null, attese);
        // I volti la tolgono in sottofondo (thread "volti"): si aspetta che lo abbiano fatto.
        aspetta(() -> mongo.count(Query.query(Criteria.where("fotoId").is(foto.getId()).and("nelCestino").is(false)),
                Volto.class) == 0);
        // Il ricalcolo leggero degli eventi, che di solito fa lo scheduler: l'evento automatico non porta più il
        // suo id ("a-" + la prima foto).
        eventi.ricalcolaGiorniToccati();
    }

    private void multipla(RequestPostProcessor chi, List<String> ids, String operazione, String valore, long attese)
            throws Exception {
        Map<String, Object> corpo = new java.util.HashMap<>(Map.of("ids", ids, "operazione", operazione));
        if (valore != null) {
            corpo.put("valore", operazione.equals("IMPOSTA_LUOGO") ? "" : valore);
        }
        mvc.perform(post("/api/foto/multiple").with(chi).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(corpo)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modificate").value(attese));
    }

    private long conteggioEvento(RequestPostProcessor chi, String evento) throws Exception {
        return JSON.readTree(leggi(chi, "/api/eventi/" + evento)).get("conteggio").asLong();
    }

    private String condividi(RequestPostProcessor chi, List<String> ids) throws Exception {
        String risposta = mvc.perform(post("/api/condivisioni").with(chi).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(Map.of("ids", ids, "download", true))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(risposta).get("token").asText();
    }

    private int vociZip(String token) throws Exception {
        byte[] zip = mvc.perform(get("/api/condivise/" + token + "/zip")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        int voci = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            while (in.getNextEntry() != null) {
                voci++;
            }
        }
        return voci;
    }

    private Foto importa(String nome, byte[] contenuto, String quando, String album) {
        var esito = fotoService.importa(nome, contenuto, Instant.parse(quando), album, "anna");
        assertThat(esito.foto()).as(esito.messaggio()).isNotNull();
        return esito.foto();
    }

    /** Riquadri a caso (impronta sensata): 0 e 1 differiscono in un punto (quasi uguali), 2 è un'altra. */
    private static byte[] immagine(int quale) throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        java.util.Random caso = new java.util.Random(quale == 2 ? 7 : 3);
        var g = img.createGraphics();
        for (int x = 0; x < 64; x += 8) {
            for (int y = 0; y < 48; y += 8) {
                int v = caso.nextInt(256);
                g.setColor(new Color(v, v, v));
                g.fillRect(x, y, 8, 8);
            }
        }
        g.dispose();
        if (quale == 1) {
            img.setRGB(0, 0, Color.WHITE.getRGB());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static void aspetta(BooleanSupplier condizione) {
        long fine = System.currentTimeMillis() + 10_000;
        while (!condizione.getAsBoolean()) {
            if (System.currentTimeMillis() > fine) {
                throw new AssertionError("Non è successo entro 10 secondi");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }
}
