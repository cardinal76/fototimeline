package it.fototimeline.telefono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.telefono.ImpostazioniTelefono.Esito;
import it.fototimeline.telefono.ImpostazioniTelefono.Giro;
import it.fototimeline.telefono.SincronizzazioneTelefono.Modifica;

/** Su MongoDB embedded, con un rclone finto che tiene in memoria pCloud e LifetimeCloud. */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class SincronizzazioneTelefonoTest {

    private static final CloudProperties CLOUD = new CloudProperties("http://rclone:5572", "fototimeline",
            "password-di-prova", "lifetime:", "/cloud", false, 0);
    private static final ImportazioneAutomaticaProperties AUTOMATICA =
            new ImportazioneAutomaticaProperties(Path.of("/cloud/telefono"), null);

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    MongoTemplate mongo;

    private FintoRclone rclone;
    private Orologio orologio;
    private SincronizzazioneTelefono sincronizzazione;

    @BeforeEach
    void prepara() {
        mongo.remove(new Query(), ImpostazioniTelefono.class);
        mongo.remove(new Query(), CopiaTelefono.class);
        var builder = RestClient.builder();
        rclone = new FintoRclone(builder);
        orologio = new Orologio(Instant.parse("2026-05-01T10:00:00Z"));
        sincronizzazione = new SincronizzazioneTelefono(new Rclone(CLOUD, builder), CLOUD, AUTOMATICA, mongo,
                Optional.of(orologio));
    }

    @Test
    void primoGiroCopiaSoloFotoEVideoSenzaSottocartelle() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/VID_0002.mp4", 2000L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/bolletta.pdf", 50L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/.trashed-IMG.jpg", 70L);

        Giro giro = sincronizzazione.sincronizza();

        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.trovati()).isEqualTo(2);
        assertThat(giro.copiati()).isEqualTo(2);
        assertThat(rclone.chiamate("operations/list")).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("fs", "pcloud:").containsEntry("remote", "Automatic Upload");
            assertThat(p.get("opt")).isEqualTo(Map.of("recurse", true, "filesOnly", true));
        });
        assertThat(rclone.chiamate("operations/copyfile")).containsExactlyInAnyOrder(
                Map.of("srcFs", "pcloud:", "srcRemote", "Automatic Upload/Pixel 8/IMG_0001.jpg",
                        "dstFs", "lifetime:", "dstRemote", "telefono/Pixel 8 - IMG_0001.jpg"),
                Map.of("srcFs", "pcloud:", "srcRemote", "Automatic Upload/Pixel 8/VID_0002.mp4",
                        "dstFs", "lifetime:", "dstRemote", "telefono/Pixel 8 - VID_0002.mp4"));
        assertThat(rclone.lifetime).containsOnlyKeys("telefono/Pixel 8 - IMG_0001.jpg", "telefono/Pixel 8 - VID_0002.mp4");
        assertThat(mongo.findAll(CopiaTelefono.class))
                .extracting(CopiaTelefono::percorso, CopiaTelefono::dimensione, CopiaTelefono::destinazione)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Pixel 8/IMG_0001.jpg", 100L, "telefono/Pixel 8 - IMG_0001.jpg"),
                        org.assertj.core.groups.Tuple.tuple("Pixel 8/VID_0002.mp4", 2000L, "telefono/Pixel 8 - VID_0002.mp4"));
        assertThat(sincronizzazione.stato().ultimoGiro()).isEqualTo(giro);
    }

    @Test
    void secondoGiroNonRicopiaNienteAncheSeLImportazioneLiHaGiaSpostati() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0002.jpg", 120L);
        sincronizzazione.sincronizza();
        rclone.lifetime.clear();
        rclone.chiamate.clear();

        Giro giro = sincronizzazione.sincronizza();

        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(giro.copiati()).isZero();
        assertThat(giro.giaCopiati()).isEqualTo(2);
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isEqualTo(2);
    }

    @Test
    void unNomeGiaPresenteInDestinazionePrendeIlNumero() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.lifetime.put("telefono/Pixel 8 - IMG_0001.jpg", 999L);

        sincronizzazione.sincronizza();

        assertThat(rclone.chiamate("operations/copyfile")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("dstRemote", "telefono/Pixel 8 - IMG_0001 (2).jpg"));
        assertThat(rclone.lifetime).containsEntry("telefono/Pixel 8 - IMG_0001.jpg", 999L)
                .containsEntry("telefono/Pixel 8 - IMG_0001 (2).jpg", 100L);
    }

    @Test
    void moltiFileInParalleloTuttiCopiatiConNomiDiversi() {
        for (int i = 1; i <= 40; i++) {
            rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_%04d.jpg".formatted(i), 100L + i);
        }
        // Due percorsi diversi che appiattiti danno lo stesso nome: copiati in parallelo non si pestano.
        rclone.pcloud.put("Automatic Upload/a/b - c.jpg", 7L);
        rclone.pcloud.put("Automatic Upload/a - b/c.jpg", 8L);

        Giro giro = sincronizzazione.sincronizza();

        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.copiati()).isEqualTo(42);
        assertThat(rclone.lifetime).hasSize(42)
                .containsKeys("telefono/a - b - c.jpg", "telefono/a - b - c (2).jpg");
        assertThat(rclone.lifetime.values()).contains(7L, 8L);
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isEqualTo(42);
        assertThat(sincronizzazione.stato().giroInCorso()).isNull();
    }

    @Test
    void unaCopiaIncompletaEUnErroreENonVaNelRegistro() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0002.jpg", 120L);
        rclone.troncati.add("Automatic Upload/Pixel 8/IMG_0002.jpg");

        Giro giro = sincronizzazione.sincronizza();

        assertThat(giro.esito()).isEqualTo(Esito.ERRORE);
        assertThat(giro.copiati()).isEqualTo(1);
        assertThat(giro.errori()).isEqualTo(1);
        assertThat(giro.messaggiErrori()).singleElement().asString().contains("IMG_0002.jpg", "incompleta");
        assertThat(mongo.findAll(CopiaTelefono.class)).extracting(CopiaTelefono::percorso)
                .containsExactly("Pixel 8/IMG_0001.jpg");
        // La copia a metà non resta lì ad aspettare l'importazione.
        assertThat(rclone.lifetime).containsOnlyKeys("telefono/Pixel 8 - IMG_0001.jpg");
    }

    @Test
    void cancellaDallaSorgenteSoloQuelloCheLImportazioneHaPreso() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/importata.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/non-importata.jpg", 120L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/gia-tolta.jpg", 130L);
        sincronizzazione.sincronizza();
        // L'importazione ha spostato due file su tre; uno l'ha già tolto Marco da pCloud.
        rclone.lifetime.remove("telefono/Pixel 8 - importata.jpg");
        rclone.lifetime.remove("telefono/Pixel 8 - gia-tolta.jpg");
        rclone.pcloud.remove("Automatic Upload/Pixel 8/gia-tolta.jpg");

        // Dopo 6 giorni con 7 impostati: niente.
        orologio.avanti(Duration.ofDays(6));
        assertThat(sincronizzazione.sincronizza().cancellati()).isZero();
        assertThat(rclone.chiamate("operations/deletefile")).isEmpty();

        orologio.avanti(Duration.ofDays(2));
        Giro giro = sincronizzazione.sincronizza();

        assertThat(giro.cancellati()).isEqualTo(1);
        assertThat(rclone.chiamate("operations/deletefile")).containsExactly(
                Map.of("fs", "pcloud:", "remote", "Automatic Upload/Pixel 8/importata.jpg"));
        assertThat(rclone.pcloud).containsOnlyKeys("Automatic Upload/Pixel 8/non-importata.jpg");
        assertThat(mongo.findAll(CopiaTelefono.class))
                .filteredOn(c -> c.cancellatoIl() != null)
                .extracting(CopiaTelefono::percorso)
                .containsExactlyInAnyOrder("Pixel 8/importata.jpg", "Pixel 8/gia-tolta.jpg");
    }

    @Test
    void conZeroGiorniNonCancellaMai() {
        sincronizzazione.salva(new Modifica(null, null, null, 0, null));
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza();
        rclone.lifetime.clear();

        orologio.avanti(Duration.ofDays(400));
        sincronizzazione.sincronizza();

        assertThat(rclone.chiamate("operations/deletefile")).isEmpty();
        assertThat(rclone.pcloud).containsKey("Automatic Upload/Pixel 8/IMG_0001.jpg");
    }

    @Test
    void pianificazioneSoloSeAttivaEDopoLIntervallo() throws InterruptedException {
        rclone.pcloud.put("Automatic Upload/IMG_0001.jpg", 100L);

        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate).isEmpty();

        sincronizzazione.salva(new Modifica(true, null, 6, null, null));
        assertThat(sincronizzazione.stato().prossimoGiroIl()).isEqualTo(orologio.instant());
        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate("operations/list")).hasSize(1);
        assertThat(sincronizzazione.stato().prossimoGiroIl()).isEqualTo(orologio.instant().plus(Duration.ofHours(6)));

        orologio.avanti(Duration.ofHours(5));
        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate("operations/list")).hasSize(1);

        orologio.avanti(Duration.ofHours(1));
        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate("operations/list")).hasSize(2);
    }

    @Test
    void impostazioniFuoriMisuraRifiutate() {
        assertThat(sincronizzazione.stato()).satisfies(s -> {
            assertThat(s.attiva()).isFalse();
            assertThat(s.sorgente()).isEqualTo("pcloud:Automatic Upload");
            assertThat(s.intervalloOre()).isEqualTo(6);
            assertThat(s.giorniPrimaDiCancellare()).isEqualTo(7);
            assertThat(s.copieInParallelo()).isEqualTo(6);
            assertThat(s.disponibile()).isTrue();
            assertThat(s.destinazione()).isEqualTo("lifetime:telefono");
        });
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, null, 0, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, null, 169, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, null, null, -1, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, "Automatic Upload", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, null, null, null, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sincronizzazione.salva(new Modifica(null, null, null, null, 33)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(sincronizzazione.salva(new Modifica(null, null, null, null, 12)).copieInParallelo()).isEqualTo(12);

        var salvato = sincronizzazione.salva(new Modifica(true, "pcloud:/Automatic Upload/Pixel 8/", 168, 30, null));
        assertThat(salvato.attiva()).isTrue();
        assertThat(salvato.intervalloOre()).isEqualTo(168);
        assertThat(salvato.giorniPrimaDiCancellare()).isEqualTo(30);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza();
        assertThat(rclone.chiamate("operations/copyfile")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("dstRemote", "telefono/IMG_0001.jpg"));
    }

    @Test
    void sulPcOFuoriDalMontaggioNonSiAttiva() {
        var pc = new SincronizzazioneTelefono(new Rclone(new CloudProperties("", null, null, null, null, false, 0),
                RestClient.builder()), new CloudProperties("", null, null, null, null, false, 0), AUTOMATICA, mongo,
                Optional.empty());
        assertThat(pc.stato().disponibile()).isFalse();
        assertThat(pc.motivo()).contains("solo sul server");
        assertThatThrownBy(() -> pc.salva(new Modifica(true, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non si può attivare");
        assertThatThrownBy(pc::avvia).isInstanceOf(IllegalStateException.class);

        var fuori = new SincronizzazioneTelefono(new Rclone(CLOUD, RestClient.builder()), CLOUD,
                new ImportazioneAutomaticaProperties(Path.of("/altrove/telefono"), null), mongo, Optional.empty());
        assertThat(fuori.motivo()).contains("non sta dentro il punto di montaggio");
        var senza = new SincronizzazioneTelefono(new Rclone(CLOUD, RestClient.builder()), CLOUD,
                new ImportazioneAutomaticaProperties(null, null), mongo, Optional.empty());
        assertThat(senza.motivo()).contains("non è impostata");
    }

    private void aspetta() throws InterruptedException {
        for (int i = 0; i < 200 && sincronizzazione.inCorso(); i++) {
            Thread.sleep(25);
        }
        assertThat(sincronizzazione.inCorso()).isFalse();
    }

    /**
     * rclone finto: pCloud ({@code pcloud:}) e LifetimeCloud ({@code lifetime:})
     * come mappe percorso → dimensione. Risponde JSON con Content-Type
     * text/plain, come quello vero.
     */
    static final class FintoRclone {
        private static final ObjectMapper JSON = new ObjectMapper();

        final Map<String, Long> pcloud = new LinkedHashMap<>();
        final Map<String, Long> lifetime = new LinkedHashMap<>();
        /** File della sorgente che arrivano in destinazione a metà. */
        final List<String> troncati = new ArrayList<>();
        final List<Map<String, Object>> chiamate = new ArrayList<>();

        FintoRclone(RestClient.Builder builder) {
            MockRestServiceServer.bindTo(builder).build()
                    .expect(manyTimes(), anything())
                    .andRespond(this::rispondi);
        }

        synchronized List<Map<String, Object>> chiamate(String comando) {
            return chiamate.stream().filter(c -> comando.equals(c.get("comando")))
                    .map(c -> {
                        Map<String, Object> p = new HashMap<>(c);
                        p.remove("comando");
                        return p;
                    })
                    .toList();
        }

        @SuppressWarnings("unchecked")
        private synchronized ClientHttpResponse rispondi(ClientHttpRequest richiesta) throws IOException {
            String comando = richiesta.getURI().getPath().substring(1);
            Map<String, Object> p = JSON.readValue(((MockClientHttpRequest) richiesta).getBodyAsString(), Map.class);
            Map<String, Object> registrata = new HashMap<>(p);
            registrata.put("comando", comando);
            chiamate.add(registrata);
            Object risposta = switch (comando) {
                case "operations/list" -> {
                    String radice = p.get("remote") + "/";
                    List<Map<String, Object>> voci = new ArrayList<>();
                    remoto(p.get("fs")).forEach((percorso, dimensione) -> {
                        if (percorso.startsWith(radice)) {
                            String relativo = percorso.substring(radice.length());
                            voci.add(Map.of("Path", relativo, "Name", relativo.substring(relativo.lastIndexOf('/') + 1),
                                    "Size", dimensione, "ModTime", "2026-04-30T18:22:05.123456789+02:00",
                                    "IsDir", false, "MimeType", "image/jpeg"));
                        }
                    });
                    yield Map.of("list", voci);
                }
                case "operations/stat" -> {
                    Long dimensione = remoto(p.get("fs")).get((String) p.get("remote"));
                    Map<String, Object> voce = new HashMap<>();
                    voce.put("item", dimensione == null ? null : Map.of("Path", p.get("remote"), "Size", dimensione,
                            "IsDir", false));
                    yield voce;
                }
                case "operations/copyfile" -> {
                    String da = (String) p.get("srcRemote");
                    long dimensione = remoto(p.get("srcFs")).get(da);
                    remoto(p.get("dstFs")).put((String) p.get("dstRemote"), troncati.contains(da) ? dimensione / 2 : dimensione);
                    yield Map.of();
                }
                case "operations/deletefile" -> {
                    remoto(p.get("fs")).remove((String) p.get("remote"));
                    yield Map.of();
                }
                default -> throw new IllegalArgumentException(comando);
            };
            return withSuccess(JSON.writeValueAsString(risposta), MediaType.TEXT_PLAIN).createResponse(richiesta);
        }

        private Map<String, Long> remoto(Object fs) {
            return switch ((String) fs) {
                case "pcloud:" -> pcloud;
                case "lifetime:" -> lifetime;
                default -> throw new IllegalArgumentException("remote sconosciuto: " + fs);
            };
        }
    }

    /** Un orologio che va avanti solo quando lo dice il test. */
    static final class Orologio extends Clock {
        private volatile Instant adesso;

        Orologio(Instant adesso) {
            this.adesso = adesso;
        }

        void avanti(Duration d) {
            adesso = adesso.plus(d);
        }

        @Override
        public Instant instant() {
            return adesso;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
