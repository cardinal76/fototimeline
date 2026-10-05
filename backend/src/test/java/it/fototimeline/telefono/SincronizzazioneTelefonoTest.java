package it.fototimeline.telefono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
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

import javax.imageio.ImageIO;

import org.bson.Document;
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
import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.sicurezza.Utente;
import it.fototimeline.sicurezza.UtenteRegistrato;
import it.fototimeline.telefono.SorgenteTelefono.Esito;
import it.fototimeline.telefono.SorgenteTelefono.Giro;
import it.fototimeline.telefono.SincronizzazioneTelefono.Modifica;

/** Su MongoDB embedded, con un rclone finto che tiene in memoria pCloud (due account) e LifetimeCloud. */
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

    @Autowired
    FotoService fotoService;

    @Autowired
    FotoRepository fotoRepository;

    @Autowired
    ImportazioneCartelle importazione;

    private RestClient.Builder builder;
    private FintoRclone rclone;
    private Orologio orologio;
    private SincronizzazioneTelefono sincronizzazione;
    /** Il telefono di Marco, su {@code pcloud:Automatic Upload}. */
    private String marco;

    @BeforeEach
    void prepara() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        mongo.remove(new Query(), ImpostazioniTelefono.class);
        mongo.remove(new Query(), CopiaTelefono.class);
        mongo.remove(new Query(), Utente.class);
        fotoRepository.findAll().forEach(f -> fotoService.elimina(f.getId()));
        builder = RestClient.builder();
        rclone = new FintoRclone(builder);
        orologio = new Orologio(Instant.parse("2026-05-01T10:00:00Z"));
        sincronizzazione = nuova(CLOUD, AUTOMATICA, "");
        marco = sincronizzazione.crea(new Modifica("Telefono di Marco", "marco", null, null, null, null, null)).id();
    }

    private SincronizzazioneTelefono nuova(CloudProperties cloud, ImportazioneAutomaticaProperties automatica,
            String proprietarioMigrato) {
        return new SincronizzazioneTelefono(new Rclone(cloud, builder), cloud, automatica, mongo, proprietarioMigrato,
                Optional.of(orologio));
    }

    private SorgenteTelefono sorgente(String id) {
        return sincronizzazione.sorgente(id).orElseThrow();
    }

    @Test
    void primoGiroCopiaSoloFotoEVideoSenzaSottocartelle() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/VID_0002.mp4", 2000L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/bolletta.pdf", 50L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/.trashed-IMG.jpg", 70L);

        Giro giro = sincronizzazione.sincronizza(marco);

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
                .extracting(CopiaTelefono::percorso, CopiaTelefono::dimensione, CopiaTelefono::destinazione,
                        CopiaTelefono::idSorgente, CopiaTelefono::proprietario)
                .containsExactlyInAnyOrder(
                        tuple("Pixel 8/IMG_0001.jpg", 100L, "telefono/Pixel 8 - IMG_0001.jpg", marco, "marco"),
                        tuple("Pixel 8/VID_0002.mp4", 2000L, "telefono/Pixel 8 - VID_0002.mp4", marco, "marco"));
        assertThat(sincronizzazione.stato(sorgente(marco)).ultimoGiro()).isEqualTo(giro);
    }

    @Test
    void secondoGiroNonRicopiaNienteAncheSeLImportazioneLiHaGiaSpostati() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0002.jpg", 120L);
        sincronizzazione.sincronizza(marco);
        rclone.lifetime.clear();
        rclone.chiamate.clear();

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(giro.copiati()).isZero();
        assertThat(giro.giaCopiati()).isEqualTo(2);
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isEqualTo(2);
    }

    @Test
    void unNomeGiaPresenteInDestinazionePrendeIlNumero() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.lifetime.put("telefono/Pixel 8 - IMG_0001.jpg", 999L);

        sincronizzazione.sincronizza(marco);

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

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.copiati()).isEqualTo(42);
        assertThat(rclone.lifetime).hasSize(42)
                .containsKeys("telefono/a - b - c.jpg", "telefono/a - b - c (2).jpg");
        assertThat(rclone.lifetime.values()).contains(7L, 8L);
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isEqualTo(42);
        assertThat(sincronizzazione.stato(sorgente(marco)).giroInCorso()).isNull();
    }

    @Test
    void unaCopiaIncompletaEUnErroreENonVaNelRegistro() {
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0002.jpg", 120L);
        rclone.troncati.add("Automatic Upload/Pixel 8/IMG_0002.jpg");

        Giro giro = sincronizzazione.sincronizza(marco);

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
        sincronizzazione.sincronizza(marco);
        // L'importazione ha spostato due file su tre; uno l'ha già tolto Marco da pCloud.
        rclone.lifetime.remove("telefono/Pixel 8 - importata.jpg");
        rclone.lifetime.remove("telefono/Pixel 8 - gia-tolta.jpg");
        rclone.pcloud.remove("Automatic Upload/Pixel 8/gia-tolta.jpg");

        // Dopo 6 giorni con 7 impostati: niente.
        orologio.avanti(Duration.ofDays(6));
        assertThat(sincronizzazione.sincronizza(marco).cancellati()).isZero();
        assertThat(rclone.chiamate("operations/deletefile")).isEmpty();

        orologio.avanti(Duration.ofDays(2));
        Giro giro = sincronizzazione.sincronizza(marco);

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
        sincronizzazione.salva(marco, new Modifica(null, null, null, null, null, 0, null));
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza(marco);
        rclone.lifetime.clear();

        orologio.avanti(Duration.ofDays(400));
        sincronizzazione.sincronizza(marco);

        assertThat(rclone.chiamate("operations/deletefile")).isEmpty();
        assertThat(rclone.pcloud).containsKey("Automatic Upload/Pixel 8/IMG_0001.jpg");
    }

    @Test
    void pianificazioneSoloSeAttivaEDopoLIntervallo() throws InterruptedException {
        rclone.pcloud.put("Automatic Upload/IMG_0001.jpg", 100L);

        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate).isEmpty();

        sincronizzazione.salva(marco, new Modifica(null, null, true, null, 6, null, null));
        assertThat(sincronizzazione.stato(sorgente(marco)).prossimoGiroIl()).isEqualTo(orologio.instant());
        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate("operations/list")).hasSize(1);
        assertThat(sincronizzazione.stato(sorgente(marco)).prossimoGiroIl())
                .isEqualTo(orologio.instant().plus(Duration.ofHours(6)));

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
        assertThat(sincronizzazione.stato(sorgente(marco))).satisfies(s -> {
            assertThat(s.nome()).isEqualTo("Telefono di Marco");
            assertThat(s.proprietario()).isEqualTo("marco");
            assertThat(s.attiva()).isFalse();
            assertThat(s.sorgente()).isEqualTo("pcloud:Automatic Upload");
            assertThat(s.intervalloOre()).isEqualTo(6);
            assertThat(s.giorniPrimaDiCancellare()).isEqualTo(7);
            assertThat(s.copieInParallelo()).isEqualTo(6);
        });
        assertThat(sincronizzazione.elenco(s -> true)).satisfies(e -> {
            assertThat(e.disponibile()).isTrue();
            assertThat(e.destinazione()).isEqualTo("lifetime:telefono");
        });
        for (var sbagliata : List.of(
                new Modifica(null, null, null, null, 0, null, null),
                new Modifica(null, null, null, null, 169, null, null),
                new Modifica(null, null, null, null, null, -1, null),
                new Modifica(null, null, null, "Automatic Upload", null, null, null),
                new Modifica(null, null, null, null, null, null, 0),
                new Modifica(null, null, null, null, null, null, 33),
                new Modifica(" ", null, null, null, null, null, null),
                new Modifica(null, "", null, null, null, null, null))) {
            assertThatThrownBy(() -> sincronizzazione.salva(marco, sbagliata))
                    .as(sbagliata.toString()).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(sincronizzazione.salva(marco, new Modifica(null, null, null, null, null, null, 12)).copieInParallelo())
                .isEqualTo(12);

        var salvato = sincronizzazione.salva(marco,
                new Modifica(null, null, true, "pcloud:/Automatic Upload/Pixel 8/", 168, 30, null));
        assertThat(salvato.attiva()).isTrue();
        assertThat(salvato.intervalloOre()).isEqualTo(168);
        assertThat(salvato.giorniPrimaDiCancellare()).isEqualTo(30);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza(marco);
        assertThat(rclone.chiamate("operations/copyfile")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("dstRemote", "telefono/IMG_0001.jpg"));
    }

    @Test
    void unNuovoTelefonoVuoleNomeProprietarioEUnaCartellaSua() {
        assertThatThrownBy(() -> sincronizzazione.crea(new Modifica(null, "anna", null, "pcloud-anna:Foto", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nome");
        assertThatThrownBy(() -> sincronizzazione.crea(new Modifica("Telefono di Anna", null, null, "pcloud-anna:Foto",
                null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("proprietario");
        // La stessa cartella di Marco, scritta in un altro modo: copierebbe due volte.
        assertThatThrownBy(() -> sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", null,
                "pcloud:/Automatic Upload/", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Telefono di Marco");
        // Né una cartella dentro l'altra.
        assertThatThrownBy(() -> sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", null,
                "pcloud:Automatic Upload/Galaxy", null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Telefono di Marco");
        assertThat(sincronizzazione.crea(new Modifica("Vecchie", "anna", null, "pcloud:Automatic Upload vecchie",
                null, null, null)).sorgente()).isEqualTo("pcloud:Automatic Upload vecchie");

        var anna = sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", null, "pcloud-anna:Automatic Upload",
                null, null, null));
        assertThat(anna.intervalloOre()).isEqualTo(6);
        assertThat(anna.giorniPrimaDiCancellare()).isEqualTo(7);
        assertThat(anna.copieInParallelo()).isEqualTo(6);
        assertThat(anna.attiva()).isFalse();
        assertThat(sincronizzazione.elenco(s -> true).telefoni()).extracting(SincronizzazioneTelefono.Stato::nome)
                .containsExactly("Telefono di Marco", "Vecchie", "Telefono di Anna");
        assertThat(sincronizzazione.elenco(s -> "anna".equals(s.proprietario())).telefoni())
                .extracting(SincronizzazioneTelefono.Stato::nome).containsExactly("Vecchie", "Telefono di Anna");
    }

    @Test
    void piuTelefoniConStatiERegistriSeparati() {
        String anna = sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", null,
                "pcloud-anna:Automatic Upload", null, null, null)).id();
        rclone.pcloud.put("Automatic Upload/Camera/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Camera/IMG_0002.jpg", 110L);
        // Stesso nome sul telefono di Anna: un'altra foto.
        rclone.pcloudAnna.put("Automatic Upload/Camera/IMG_0001.jpg", 200L);
        rclone.troncati.add("Automatic Upload/Camera/IMG_0002.jpg");

        Giro diMarco = sincronizzazione.sincronizza(marco);
        Giro diAnna = sincronizzazione.sincronizza(anna);

        assertThat(diMarco.esito()).isEqualTo(Esito.ERRORE);
        assertThat(diMarco.copiati()).isEqualTo(1);
        assertThat(diAnna.esito()).isEqualTo(Esito.OK);
        assertThat(diAnna.copiati()).isEqualTo(1);
        assertThat(sorgente(marco).ultimoGiro()).isEqualTo(diMarco);
        assertThat(sorgente(anna).ultimoGiro()).isEqualTo(diAnna);
        // Tutto nella stessa cartella, piatta: niente sottocartelle per telefono.
        assertThat(rclone.lifetime).containsOnlyKeys("telefono/Camera - IMG_0001.jpg", "telefono/Camera - IMG_0001 (2).jpg");
        assertThat(mongo.findAll(CopiaTelefono.class))
                .extracting(CopiaTelefono::sorgente, CopiaTelefono::destinazione, CopiaTelefono::proprietario)
                .containsExactlyInAnyOrder(
                        tuple("pcloud:Automatic Upload", "telefono/Camera - IMG_0001.jpg", "marco"),
                        tuple("pcloud-anna:Automatic Upload", "telefono/Camera - IMG_0001 (2).jpg", "anna"));
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/Camera - IMG_0001.jpg"))).isEqualTo("marco");
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/Camera - IMG_0001 (2).jpg"))).isEqualTo("anna");
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/messa-a-mano.jpg"))).isNull();
        assertThat(sincronizzazione.caricataDa(Path.of("/altrove/Camera - IMG_0001.jpg"))).isNull();

        // Il secondo giro di Anna non tocca le copie di Marco, neanche per cancellare.
        rclone.lifetime.clear();
        rclone.chiamate.clear();
        orologio.avanti(Duration.ofDays(8));
        sincronizzazione.sincronizza(anna);
        assertThat(rclone.chiamate("operations/deletefile")).containsExactly(
                Map.of("fs", "pcloud-anna:", "remote", "Automatic Upload/Camera/IMG_0001.jpg"));
        assertThat(rclone.pcloud).containsKey("Automatic Upload/Camera/IMG_0001.jpg");
    }

    @Test
    void ilProprietarioPuoCambiareFinoAllImportazione() {
        rclone.pcloud.put("Automatic Upload/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza(marco);
        sincronizzazione.salva(marco, new Modifica(null, "papa", null, null, null, null, null));
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/IMG_0001.jpg"))).isEqualTo("papa");

        // Telefono tolto: vale il proprietario di quando è stato copiato.
        sincronizzazione.elimina(marco);
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/IMG_0001.jpg"))).isEqualTo("marco");
    }

    @Test
    void toltoERimessoConLaStessaCartellaNonRicopia() {
        rclone.pcloud.put("Automatic Upload/IMG_0001.jpg", 100L);
        sincronizzazione.sincronizza(marco);
        rclone.lifetime.clear();

        assertThat(sincronizzazione.elimina(marco)).isTrue();
        assertThat(sincronizzazione.elimina(marco)).isFalse();
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isEqualTo(1);
        String dinuovo = sincronizzazione.crea(new Modifica("Telefono", "marco", null, "pcloud:Automatic Upload",
                null, null, null)).id();
        Giro giro = sincronizzazione.sincronizza(dinuovo);

        assertThat(giro.giaCopiati()).isEqualTo(1);
        assertThat(rclone.chiamate("operations/copyfile")).hasSize(1);
    }

    @Test
    void loSchedulerGiraTutteLeAttiveUnaAllaVolta() throws InterruptedException {
        String anna = sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", true,
                "pcloud-anna:Automatic Upload", 12, null, null)).id();
        sincronizzazione.crea(new Modifica("Vecchio telefono", "marco", false, "pcloud:Vecchio", null, null, null));
        sincronizzazione.salva(marco, new Modifica(null, null, true, null, 6, null, null));
        for (int i = 0; i < 10; i++) {
            rclone.pcloud.put("Automatic Upload/IMG_%02d.jpg".formatted(i), 100L + i);
            rclone.pcloudAnna.put("Automatic Upload/IMG_%02d.jpg".formatted(i), 200L + i);
        }

        sincronizzazione.seNecessario();
        aspetta();

        assertThat(rclone.chiamate("operations/list")).extracting(p -> p.get("fs"))
                .containsExactly("pcloud:", "pcloud-anna:");
        // Una alla volta: tutte le copie di Marco prima della lista di Anna.
        List<Object> sequenza = rclone.chiamate.stream()
                .filter(c -> c.get("comando").equals("operations/copyfile") || c.get("comando").equals("operations/list"))
                .map(c -> c.get("comando").equals("operations/list") ? "list " + c.get("fs") : c.get("srcFs"))
                .toList();
        assertThat(sequenza.subList(0, 11)).containsOnly("list pcloud:", "pcloud:");
        assertThat(sequenza.subList(11, 22)).containsOnly("list pcloud-anna:", "pcloud-anna:");
        assertThat(sorgente(marco).ultimoGiro().copiati()).isEqualTo(10);
        assertThat(sorgente(anna).ultimoGiro().copiati()).isEqualTo(10);
        assertThat(sincronizzazione.sorgenti()).filteredOn(s -> !s.attiva()).singleElement()
                .satisfies(s -> assertThat(s.ultimoGiro()).isNull());

        // Dopo 6 ore tocca solo a Marco (Anna ogni 12).
        orologio.avanti(Duration.ofHours(6));
        rclone.chiamate.clear();
        sincronizzazione.seNecessario();
        aspetta();
        assertThat(rclone.chiamate("operations/list")).extracting(p -> p.get("fs")).containsExactly("pcloud:");
    }

    @Test
    void migrazioneDalTelefonoUnicoSenzaRicopiare() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        var giroVecchio = new Giro(Instant.parse("2026-04-30T08:00:00Z"), Instant.parse("2026-04-30T08:05:00Z"),
                Esito.OK, "Fatto", 2, 2, 0, 0, 0, List.of());
        // Il documento di prima, senza copieInParallelo (salvato prima che ci fosse).
        mongo.insert(new ImpostazioniTelefono(ImpostazioniTelefono.ID, true, "pcloud:Automatic Upload", 12, 30, null,
                giroVecchio));
        // Il registro di prima: senza idSorgente né proprietario.
        for (var voce : List.of(Map.of("percorso", "Pixel 8/IMG_0001.jpg", "dimensione", 100L),
                Map.of("percorso", "Pixel 8/IMG_0002.jpg", "dimensione", 120L))) {
            mongo.insert(new Document(voce).append("sorgente", "pcloud:Automatic Upload")
                    .append("destinazione", "telefono/" + ((String) voce.get("percorso")).replace("/", " - "))
                    .append("copiatoIl", java.util.Date.from(Instant.parse("2026-04-30T08:01:00Z"))), "copie_telefono");
        }
        mongo.insert(new Utente("anna", "Anna", null, Instant.parse("2026-03-01T00:00:00Z"), null, false));
        mongo.insert(new Utente("marco", "Marco", null, Instant.parse("2026-03-02T00:00:00Z"), null, true));
        mongo.insert(new Utente("papa", "Papà", null, Instant.parse("2026-03-03T00:00:00Z"), null, true));

        sincronizzazione.migra();
        sincronizzazione.migra();

        assertThat(sincronizzazione.sorgenti()).singleElement().satisfies(s -> {
            assertThat(s.id()).isEqualTo(SincronizzazioneTelefono.ID_MIGRATO);
            assertThat(s.nome()).isEqualTo("Telefono");
            assertThat(s.proprietario()).isEqualTo("marco");
            assertThat(s.attiva()).isTrue();
            assertThat(s.sorgente()).isEqualTo("pcloud:Automatic Upload");
            assertThat(s.intervalloOre()).isEqualTo(12);
            assertThat(s.giorniPrimaDiCancellare()).isEqualTo(30);
            assertThat(s.copie()).isEqualTo(6);
            assertThat(s.ultimoGiro()).isEqualTo(giroVecchio);
        });
        assertThat(mongo.findById(ImpostazioniTelefono.ID, ImpostazioniTelefono.class)).isNull();
        assertThat(mongo.findAll(CopiaTelefono.class)).extracting(CopiaTelefono::idSorgente)
                .containsOnly(SincronizzazioneTelefono.ID_MIGRATO);
        // Le copie in attesa d'importazione sanno già di chi sono.
        assertThat(sincronizzazione.caricataDa(Path.of("/cloud/telefono/Pixel 8 - IMG_0002.jpg"))).isEqualTo("marco");

        // Le chiavi del registro restano buone: si copia solo il nuovo.
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0001.jpg", 100L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0002.jpg", 120L);
        rclone.pcloud.put("Automatic Upload/Pixel 8/IMG_0003.jpg", 130L);
        Giro giro = sincronizzazione.sincronizza(SincronizzazioneTelefono.ID_MIGRATO);
        assertThat(giro.giaCopiati()).isEqualTo(2);
        assertThat(giro.copiati()).isEqualTo(1);
        assertThat(rclone.chiamate("operations/copyfile")).singleElement()
                .satisfies(p -> assertThat(p).containsEntry("srcRemote", "Automatic Upload/Pixel 8/IMG_0003.jpg"));
    }

    @Test
    void migrazioneSenzaUtentiAspettaIlPrimoAmministratore() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        String anna = sincronizzazione.crea(new Modifica("Telefono di Anna", "anna", null, "pcloud-anna:Foto",
                null, null, null)).id();
        mongo.insert(new ImpostazioniTelefono(ImpostazioniTelefono.ID, false, "pcloud:Automatic Upload", 6, 7, 4, null));

        sincronizzazione.migra();
        assertThat(sorgente(SincronizzazioneTelefono.ID_MIGRATO).proprietario()).isNull();
        assertThat(sorgente(SincronizzazioneTelefono.ID_MIGRATO).copie()).isEqualTo(4);
        // Si può salvare anche senza proprietario, finché nessuno lo sceglie.
        sincronizzazione.salva(SincronizzazioneTelefono.ID_MIGRATO, new Modifica(null, null, null, null, 8, null, null));

        sincronizzazione.utenteRegistrato(new UtenteRegistrato("anna", false));
        assertThat(sorgente(SincronizzazioneTelefono.ID_MIGRATO).proprietario()).isNull();
        sincronizzazione.utenteRegistrato(new UtenteRegistrato("marco", true));
        sincronizzazione.utenteRegistrato(new UtenteRegistrato("papa", true));
        assertThat(sorgente(SincronizzazioneTelefono.ID_MIGRATO).proprietario()).isEqualTo("marco");
        // Gli altri telefoni hanno già il loro.
        assertThat(sorgente(anna).proprietario()).isEqualTo("anna");
    }

    @Test
    void migrazioneColProprietarioImpostato() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        mongo.insert(new ImpostazioniTelefono(ImpostazioniTelefono.ID, false, "pcloud:Automatic Upload", 6, 7, null, null));
        mongo.insert(new Utente("marco", "Marco", null, Instant.parse("2026-03-02T00:00:00Z"), null, true));

        nuova(CLOUD, AUTOMATICA, " anna ").migra();

        assertThat(sorgente(SincronizzazioneTelefono.ID_MIGRATO).proprietario()).isEqualTo("anna");
    }

    @Test
    void lImportazioneAutomaticaSaDiChiESenzaAlbumPerTelefono(@TempDir Path montaggio) throws IOException {
        // Il cloud "montato" in una cartella vera, per far girare l'importazione sui file copiati.
        var cloud = new CloudProperties("http://rclone:5572", "fototimeline", "password-di-prova", "lifetime:",
                montaggio.toString(), false, 0);
        Path cartella = montaggio.resolve("telefono");
        var conDisco = nuova(cloud, new ImportazioneAutomaticaProperties(cartella, null), "");
        String anna = conDisco.crea(new Modifica("Telefono di Anna", "anna", null, "pcloud-anna:Automatic Upload",
                null, null, null)).id();
        rclone.pcloud.put("Automatic Upload/Camera/IMG_0001.png", 100L);
        rclone.pcloudAnna.put("Automatic Upload/Camera/IMG_0001.png", 200L);
        rclone.pcloudAnna.put("Automatic Upload/WhatsApp/IMG_0002.png", 300L);
        conDisco.sincronizza(marco);
        conDisco.sincronizza(anna);
        // Quello che rclone ha copiato compare nella cartella montata.
        Files.createDirectories(cartella);
        int colore = 0;
        for (String copiato : rclone.lifetime.keySet()) {
            Files.write(montaggio.resolve(copiato), immagine(new Color(40 * colore++, 120, 80)));
        }
        Files.write(cartella.resolve("messa-a-mano.png"), immagine(Color.ORANGE));
        assertThat(Files.list(cartella).filter(Files::isDirectory)).isEmpty();

        var esito = importazione.importa(cartella, true, true, Avanzamento.NESSUNO, conDisco::caricataDa);

        assertThat(esito.importate()).isEqualTo(4);
        assertThat(fotoRepository.findAll())
                .extracting(Foto::getNomeOriginale, Foto::getCaricataDa, Foto::getAlbum)
                .containsExactlyInAnyOrder(
                        tuple("Camera - IMG_0001.png", "marco", null),
                        tuple("Camera - IMG_0001 (2).png", "anna", null),
                        tuple("WhatsApp - IMG_0002.png", "anna", null),
                        tuple("messa-a-mano.png", null, null));
        assertThat(fotoService.album()).isEmpty();
    }

    private static byte[] immagine(Color colore) throws IOException {
        var img = new BufferedImage(32, 24, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 32, 24);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void sulPcOFuoriDalMontaggioNonSiAttiva() {
        var pc = nuova(new CloudProperties("", null, null, null, null, false, 0), AUTOMATICA, "");
        assertThat(pc.elenco(s -> true).disponibile()).isFalse();
        assertThat(pc.motivo()).contains("solo sul server");
        assertThatThrownBy(() -> pc.salva(marco, new Modifica(null, null, true, null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("non si può attivare");
        assertThatThrownBy(() -> pc.avvia(marco)).isInstanceOf(IllegalStateException.class);
        assertThat(pc.caricataDa(Path.of("/cloud/telefono/IMG_0001.jpg"))).isNull();

        var fuori = nuova(CLOUD, new ImportazioneAutomaticaProperties(Path.of("/altrove/telefono"), null), "");
        assertThat(fuori.motivo()).contains("non sta dentro il punto di montaggio");
        var senza = nuova(CLOUD, new ImportazioneAutomaticaProperties(null, null), "");
        assertThat(senza.motivo()).contains("non è impostata");
    }

    private void aspetta() throws InterruptedException {
        for (int i = 0; i < 400 && sincronizzazione.inCorso(); i++) {
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
        /** Il pCloud di Anna, un altro account: un altro remote di rclone. */
        final Map<String, Long> pcloudAnna = new LinkedHashMap<>();
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
                case "pcloud-anna:" -> pcloudAnna;
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
