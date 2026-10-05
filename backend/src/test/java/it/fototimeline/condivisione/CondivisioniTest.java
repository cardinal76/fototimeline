package it.fototimeline.condivisione;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.Cloud;
import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;

/** I link pubblici con il login acceso, come su server2 (vedi SicurezzaTest). */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.login.attivo=true",
        "server.address=0.0.0.0",
        "fototimeline.condivisioni.token-sbagliati=40",
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
class CondivisioniTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static int ultimoIp;

    @Autowired
    MockMvc mvc;

    @Autowired
    FotoService fotoService;

    @Autowired
    FotoRepository fotoRepository;

    @Autowired
    CondivisioneRepository repository;

    @Autowired
    CondivisioniService service;

    @MockitoSpyBean
    Cloud cloud;

    /** Ogni test da un indirizzo suo: il freno sui token sbagliati non si porta dietro i 404 degli altri. */
    private String ip;
    private Foto conExif;
    private Foto rossa;
    private Foto verde;

    @BeforeEach
    void prepara() throws IOException {
        ip = "10.0.0." + (++ultimoIp);
        repository.deleteAll();
        fotoRepository.findAll().forEach(f -> fotoService.elimina(f.getId()));
        conExif = fotoService.importa("IMG_0001.JPG", getClass().getResourceAsStream("/con-exif.jpg").readAllBytes(),
                Instant.parse("2025-01-01T00:00:00Z"), "festa").foto();
        rossa = fotoService.importa("rossa.jpg", jpeg(Color.RED), Instant.parse("2024-05-17T10:00:00Z"), "festa").foto();
        verde = fotoService.importa("verde-segreta.jpg", jpeg(Color.GREEN), Instant.parse("2023-01-01T10:00:00Z"), null)
                .foto();
        fotoService.modifica(conExif.getId(), new FotoService.Modifica("Al mare", "nota privata", List.of("segreto"),
                "festa", false, null));
    }

    // ------------------------------------------------------------ creazione e galleria

    @Test
    void chiHaIlLinkVedeSoloLeFotoSceltaESenzaDatiSensibili() throws Exception {
        String token = crea(utente("anna"), Map.of("titolo", "Festa", "ids", List.of(conExif.getId(), rossa.getId())));
        assertThat(token).hasSize(43).matches("[A-Za-z0-9_-]+");

        String risposta = mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titolo").value("Festa"))
                .andExpect(jsonPath("$.download").value(false))
                .andExpect(jsonPath("$.foto.length()").value(2))
                // In ordine di scatto: la foto con l'EXIF è del 2021.
                .andExpect(jsonPath("$.foto[0].id").value(conExif.getId()))
                .andExpect(jsonPath("$.foto[0].titolo").value("Al mare"))
                .andExpect(jsonPath("$.foto[0].latitudine").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        for (String vietato : List.of(conExif.getPercorso(), conExif.getHash(), "IMG_0001", "Apple", "nota privata",
                "segreto", "festa", "anna", "percorso", "nomeOriginale", "fotocamera", "hash", "descrizione",
                "tag", "album", "creataDa", "token", "visite", "41.9")) {
            assertThat(risposta).as(vietato).doesNotContain(vietato);
        }

        // La visita si conta, e l'elenco di Anna la mostra.
        mvc.perform(get("/api/condivisioni").with(utente("anna")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].foto").value(2))
                .andExpect(jsonPath("$[0].visite").value(1))
                .andExpect(jsonPath("$[0].ultimoAccesso").isNotEmpty())
                .andExpect(jsonPath("$[0].token").value(token));
    }

    @Test
    void laPosizioneSoloSeScelta() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(conExif.getId()), "posizione", true));
        mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(jsonPath("$.foto[0].latitudine").value(41.9))
                .andExpect(jsonPath("$.foto[0].longitudine").value(12.5));
    }

    @Test
    void miniaturaEVistaSenzaEXIFMaSoloPerLeFotoDelLink() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(conExif.getId())));
        String base = "/api/condivise/" + token + "/foto/";

        mvc.perform(get(base + conExif.getId() + "/miniatura").with(da(ip)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG));
        byte[] vista = mvc.perform(get(base + conExif.getId() + "/vista").with(da(ip)))
                .andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(vista))).isNotNull();
        // L'originale ha l'EXIF con il GPS; la vista no.
        assertThat(new String(Files.readAllBytes(fotoService.fileOriginale(conExif)), StandardCharsets.ISO_8859_1))
                .contains("Exif");
        assertThat(new String(vista, StandardCharsets.ISO_8859_1)).doesNotContain("Exif");

        // Niente IDOR: una foto vera ma fuori dal link è 404 come un id inventato.
        for (String fuori : List.of(verde.getId(), rossa.getId(), "000000000000000000000000", "x")) {
            for (String cosa : List.of("miniatura", "vista", "video", "originale")) {
                mvc.perform(get(base + fuori + "/" + cosa).with(da(ip))).andExpect(status().isNotFound());
            }
        }
        // Il firewall di Spring Security ferma i percorsi con "..".
        mvc.perform(get(base + "../../foto/" + verde.getId() + "/miniatura").with(da(ip)))
                .andExpect(status().is4xxClientError());
        // Un video si guarda da /video, una foto non da lì.
        mvc.perform(get(base + conExif.getId() + "/video").with(da(ip))).andExpect(status().isNotFound());
    }

    @Test
    void albumEDate() throws Exception {
        String album = crea(utente("anna"), Map.of("album", "festa", "giorni", 7));
        mvc.perform(get("/api/condivise/" + album).with(da(ip)))
                .andExpect(jsonPath("$.titolo").value("festa"))
                .andExpect(jsonPath("$.scadeIl").isNotEmpty())
                .andExpect(jsonPath("$.foto[*].id").value(org.hamcrest.Matchers.containsInAnyOrder(
                        conExif.getId(), rossa.getId())));

        String giorno = crea(utente("anna"), Map.of("dal", verde.getGiorno(), "al", verde.getGiorno()));
        mvc.perform(get("/api/condivise/" + giorno).with(da(ip)))
                .andExpect(jsonPath("$.foto.length()").value(1))
                .andExpect(jsonPath("$.foto[0].id").value(verde.getId()));

        // Una sola fonte, scadenze fisse, almeno una foto.
        richiesta(utente("anna"), Map.of("album", "festa", "ids", List.of(verde.getId()))).andExpect(status().isBadRequest());
        richiesta(utente("anna"), Map.of("album", "festa", "giorni", 3)).andExpect(status().isBadRequest());
        richiesta(utente("anna"), Map.of("album", "non-esiste")).andExpect(status().isBadRequest());
        richiesta(utente("anna"), Map.of("titolo", "vuota")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------ validità del link

    @Test
    void scadutoRevocatoOSconosciutoNonApreNiente() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId()), "giorni", 1));
        String miniatura = "/api/condivise/" + token + "/foto/" + rossa.getId() + "/miniatura";
        mvc.perform(get(miniatura).with(da(ip))).andExpect(status().isOk());

        Condivisione c = repository.findByToken(token).orElseThrow();
        c.setScadeIl(Instant.now().minusSeconds(1));
        repository.save(c);
        mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("scaduto")));
        mvc.perform(get(miniatura).with(da(ip))).andExpect(status().isGone());

        c.setScadeIl(null);
        c.setRevocata(true);
        repository.save(c);
        mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("revocato")));
        mvc.perform(get(miniatura).with(da(ip))).andExpect(status().isGone());

        mvc.perform(get("/api/condivise/" + token.substring(1) + "x").with(da(ip))).andExpect(status().isNotFound());
        mvc.perform(get("/api/condivise/corto").with(da(ip))).andExpect(status().isNotFound());
    }

    @Test
    void chiProvaTokenACasoVieneFermato() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId())));
        for (int i = 0; i < 40; i++) {
            mvc.perform(get("/api/condivise/" + CondivisioniService.nuovoToken()).with(da(ip)))
                    .andExpect(status().isNotFound());
        }
        // Bloccato anche per un link buono, e anche la pagina.
        mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"));
        mvc.perform(get("/c/" + token).with(da(ip))).andExpect(status().isTooManyRequests());
        // Un altro indirizzo no.
        mvc.perform(get("/api/condivise/" + token).with(da(ip + "9"))).andExpect(status().isOk());
        // Un IPv6 conta per la sua /64: cambiare indirizzo lì dentro non aggira il blocco.
        assertThat(LimiteRichieste.chiave("2001:db8:1:2::1")).isEqualTo(LimiteRichieste.chiave("2001:db8:1:2:ffff::9"))
                .isNotEqualTo(LimiteRichieste.chiave("2001:db8:1:3::1"));
        assertThat(LimiteRichieste.chiave("10.0.0.1")).isEqualTo("10.0.0.1");
    }

    // ------------------------------------------------------------ download

    @Test
    void senzaPermessoNienteOriginaliNeZip() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId())));
        mvc.perform(get("/api/condivise/" + token + "/foto/" + rossa.getId() + "/originale").with(da(ip)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/condivise/" + token + "/zip").with(da(ip))).andExpect(status().isForbidden());
    }

    @Test
    void loZipHaGliOriginaliDelLinkEBasta() throws Exception {
        String token = crea(utente("anna"), Map.of("titolo", "Festa: sabato/sera", "download", true,
                "ids", List.of(conExif.getId(), rossa.getId())));

        mvc.perform(get("/api/condivise/" + token + "/foto/" + rossa.getId() + "/originale").with(da(ip)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.startsWith("attachment"),
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("rossa")))))
                .andExpect(content().bytes(Files.readAllBytes(fotoService.fileOriginale(rossa))));

        var risposta = mvc.perform(get("/api/condivise/" + token + "/zip").with(da(ip)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andReturn().getResponse();
        assertThat(ContentDisposition.parse(risposta.getHeader("Content-Disposition")).getFilename())
                .isEqualTo("Festa_ sabato_sera.zip");
        List<String> nomi = new ArrayList<>();
        List<byte[]> contenuti = new ArrayList<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(risposta.getContentAsByteArray()))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                nomi.add(e.getName());
                contenuti.add(zip.readAllBytes());
            }
        }
        assertThat(nomi).containsExactly("2021-07-14 18.30.05.jpg", "2024-05-17 " + ora(rossa) + ".jpg");
        assertThat(contenuti.get(0)).isEqualTo(Files.readAllBytes(fotoService.fileOriginale(conExif)));
        assertThat(contenuti.get(1)).isEqualTo(Files.readAllBytes(fotoService.fileOriginale(rossa)));
    }

    @Test
    void loZipSiScriveAPezziSenzaTenereIFileInMemoria() throws Exception {
        BufferedImage rumore = new BufferedImage(1600, 1600, BufferedImage.TYPE_INT_RGB);
        Random caso = new Random(1);
        for (int y = 0; y < 1600; y++) {
            for (int x = 0; x < 1600; x++) {
                rumore.setRGB(x, y, caso.nextInt());
            }
        }
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        ImageIO.write(rumore, "jpg", jpg);
        Foto grande = fotoService.importa("grande.jpg", jpg.toByteArray(), Instant.now(), null).foto();
        long dimensione = Files.size(fotoService.fileOriginale(grande));
        assertThat(dimensione).isGreaterThan(1_000_000);

        long[] scritti = {0};
        int[] pezzoPiuGrande = {0};
        OutputStream uscita = new OutputStream() {
            @Override
            public void write(int b) {
                scritti[0]++;
            }

            @Override
            public void write(byte[] b, int da, int quanti) {
                scritti[0] += quanti;
                pezzoPiuGrande[0] = Math.max(pezzoPiuGrande[0], quanti);
            }
        };
        service.scriviZip(List.of(grande, rossa), uscita);

        // Tutto il file è passato, a pezzi piccoli; senza compressione per un JPEG.
        assertThat(scritti[0]).isBetween(dimensione, dimensione + 100_000);
        assertThat(pezzoPiuGrande[0]).isLessThanOrEqualTo(128 * 1024);
    }

    // ------------------------------------------------------------ cloud smontato

    @Test
    void colCloudSmontatoLeMiniatureSiVedonoGliOriginaliNo() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId()), "download", true));
        doReturn(false).when(cloud).disponibile();
        doThrow(new ArchivioNonDisponibile()).when(cloud).verifica();

        mvc.perform(get("/api/condivise/" + token).with(da(ip)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivioDisponibile").value(false));
        String base = "/api/condivise/" + token + "/foto/" + rossa.getId();
        mvc.perform(get(base + "/miniatura").with(da(ip))).andExpect(status().isOk());
        for (String percorso : List.of(base + "/vista", base + "/originale", "/api/condivise/" + token + "/zip")) {
            mvc.perform(get(percorso).with(da(ip)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Riprova più tardi")));
        }
    }

    // ------------------------------------------------------------ sicurezza

    @Test
    void ilRestoRestaProtetto() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId())));
        mvc.perform(get("/api/foto")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/foto/" + rossa.getId() + "/miniatura")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/condivisioni")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/condivisioni").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[\"" + rossa.getId() + "\"]}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/condivisioni/x").with(csrf())).andExpect(status().isUnauthorized());
        // Solo GET sui path pubblici.
        mvc.perform(post("/api/condivise/" + token).with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/condivise").with(da(ip))).andExpect(status().isNotFound());
        mvc.perform(get("/c/" + token + "/altro")).andExpect(status().isFound());
        // La pagina e i file dell'app si caricano senza login: 200 se il frontend è compilato, 404 in CI.
        for (String pubblico : List.of("/c/" + token, "/main-ABCDEF.js", "/chunk-ABCDEF.js", "/styles-ABCDEF.css")) {
            mvc.perform(get(pubblico).with(da(ip)))
                    .andExpect(r -> assertThat(r.getResponse().getStatus()).as(pubblico).isIn(200, 404));
        }
        // Ma non altri file: index.html e il resto vogliono il login.
        mvc.perform(get("/index.html")).andExpect(status().isFound());
        mvc.perform(get("/altro.js")).andExpect(status().isFound());
    }

    @Test
    void ognunoVedeERevocaISuoiLAmministratoreTutti() throws Exception {
        String token = crea(utente("anna"), Map.of("ids", List.of(rossa.getId())));
        String id = repository.findByToken(token).orElseThrow().getId();
        crea(utente("bruno"), Map.of("ids", List.of(verde.getId())));
        var admin = oidcLogin().idToken(t -> t.subject("capo"))
                .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));

        mvc.perform(get("/api/condivisioni").with(utente("bruno")))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].creataDa").value("bruno"));
        mvc.perform(get("/api/condivisioni").with(admin)).andExpect(jsonPath("$.length()").value(2));

        // Bruno non revoca quella di Anna (e non sa nemmeno che c'è), né senza CSRF si revoca.
        mvc.perform(delete("/api/condivisioni/" + id).with(utente("bruno")).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/condivisioni/" + id).with(utente("anna"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/condivise/" + token).with(da(ip))).andExpect(status().isOk());

        mvc.perform(delete("/api/condivisioni/" + id).with(admin).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/condivise/" + token).with(da(ip))).andExpect(status().isGone());
        mvc.perform(get("/api/condivisioni").with(utente("anna")))
                .andExpect(jsonPath("$[0].revocata").value(true));

        String suo = crea(utente("anna"), Map.of("ids", List.of(rossa.getId())));
        String suoId = repository.findByToken(suo).orElseThrow().getId();
        mvc.perform(delete("/api/condivisioni/" + suoId).with(utente("anna")).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/condivise/" + suo).with(da(ip))).andExpect(status().isGone());
    }

    // ------------------------------------------------------------ utilità

    private static RequestPostProcessor utente(String nome) {
        return oidcLogin().idToken(t -> t.subject(nome));
    }

    private static RequestPostProcessor da(String ip) {
        return r -> {
            r.setRemoteAddr(ip);
            return r;
        };
    }

    private org.springframework.test.web.servlet.ResultActions richiesta(RequestPostProcessor chi, Map<String, Object> corpo)
            throws Exception {
        return mvc.perform(post("/api/condivisioni").with(chi).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(corpo)));
    }

    private String crea(RequestPostProcessor chi, Map<String, Object> corpo) throws Exception {
        String risposta = richiesta(chi, corpo).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JSON.readTree(risposta).get("token").asText();
    }

    private static String ora(Foto f) {
        return "%02d.%02d.%02d".formatted(f.getScattataIl().getHour(), f.getScattataIl().getMinute(),
                f.getScattataIl().getSecond());
    }

    private static byte[] jpeg(Color colore) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
