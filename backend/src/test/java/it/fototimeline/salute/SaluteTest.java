package it.fototimeline.salute;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.backup.BackupMetadati;
import it.fototimeline.backup.BackupProperties;
import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.google.ImportazioneTakeout;
import it.fototimeline.google.ZipTakeout;
import it.fototimeline.google.ZipTakeout.StatoZip;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.telefono.SincronizzazioneTelefono;
import it.fototimeline.telefono.SorgenteTelefono;
import it.fototimeline.telefono.SorgenteTelefono.Esito;
import it.fototimeline.telefono.SorgenteTelefono.Giro;

/** Su MongoDB embedded, con rclone e Telegram finti. */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class SaluteTest {

    private static final CloudProperties CLOUD = new CloudProperties("http://rclone:5572", "fototimeline",
            "password-di-prova", "lifetime:", "/cloud", false, 0);
    private static final ImportazioneAutomaticaProperties AUTOMATICA =
            new ImportazioneAutomaticaProperties(Path.of("/cloud/telefono"), null, null);
    /** Finto, costruito qui: deve restare fuori da log e messaggi. */
    private static final String TOKEN = "123456:" + "token-segreto-di-prova";

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    MongoTemplate mongo;
    @Autowired
    ArchivioFile archivioFile;
    @Autowired
    BackupMetadati backup;
    @Autowired
    BackupProperties backupProperties;
    @Autowired
    SincronizzazioneTelefono telefono;
    @Autowired
    LavoriImportazione lavori;
    @Autowired
    ImportazioneTakeout takeout;

    private final Instant adesso = Instant.now();
    private FintoRclone rclone;
    private ExecutorService esecutore;
    private Salute salute;

    @BeforeEach
    void prepara() throws IOException {
        mongo.remove(Query.query(Criteria.where("_id").in("sincronizzazione-telefono", "backup-metadati",
                StatiAvvisati.ID)), "impostazioni");
        mongo.remove(new Query(), SorgenteTelefono.class);
        mongo.remove(new Query(), ZipTakeout.class);
        if (Files.isDirectory(backup.cartella())) {
            try (Stream<Path> s = Files.list(backup.cartella())) {
                for (Path p : s.toList()) {
                    Files.delete(p);
                }
            }
        }
        var builder = RestClient.builder();
        rclone = new FintoRclone(builder);
        // Un thread solo: il server finto risponde a una richiesta alla volta.
        esecutore = Executors.newSingleThreadExecutor();
        salute = new Salute(CLOUD, new Rclone(CLOUD, builder), archivioFile, backup, backupProperties, telefono,
                lavori, AUTOMATICA, takeout, mongo, Clock.fixed(adesso, ZoneOffset.UTC), esecutore);
    }

    @AfterEach
    void chiudi() {
        esecutore.shutdownNow();
    }

    /** Un telefono nel database, come lo salva la pagina "Telefoni". */
    private void telefono(String id, String nome, boolean attiva, String sorgente, int intervalloOre, Giro giro) {
        mongo.save(new SorgenteTelefono(id, nome, id, attiva, sorgente, intervalloOre, 7, 6,
                adesso.minus(Duration.ofDays(30)), giro));
    }

    private Giro giroOk(Duration fa) {
        return new Giro(adesso.minus(fa), adesso.minus(fa), Esito.OK, "Fatto", 3, 3, 0, 0, 0, List.of());
    }

    @Test
    void tuttoAPosto() throws IOException {
        backup.esegui();
        telefono("marco", "Telefono di Marco", true, "pcloud:Automatic Upload", 6, giroOk(Duration.ofHours(1)));
        rclone.inAttesa = List.of("IMG_1.jpg", "VID_2.mp4", "note.txt");

        Salute.Rapporto r = salute.controlla();

        assertThat(r.voci()).extracting(VoceSalute::chiave).contains("rclone", "cloud", "backup", "importazione",
                "disco", "spazio-lifetime", "spazio-pcloud", "archivio");
        assertThat(voce(r, "rclone").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "rclone").messaggio()).contains("v1.68.2");
        assertThat(voce(r, "cloud").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "backup").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "spazio-lifetime").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "spazio-lifetime").messaggio()).contains("Liberi").contains("(50%)");
        // pCloud qui non dice lo spazio: va bene così.
        assertThat(voce(r, "spazio-pcloud").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "importazione").dettagli()).anySatisfy(d -> assertThat(d).startsWith("2 file in attesa"));
        assertThat(voce(r, "archivio").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "telefono").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "telefono").messaggio()).contains("Telefono di Marco").contains("3 copiati");
        assertThat(rclone.chiamate).contains("config/listremotes");
        assertThat(rclone.parametri.get("operations/list")).containsEntry("remote", "telefono")
                .containsEntry("fs", "lifetime:");
    }

    private ZipTakeout zip(String nome, StatoZip stato, int errori, String errore) {
        return new ZipTakeout(nome, "gdrive:Takeout", nome, 1000, adesso, stato, adesso, adesso, 10, 10,
                10 - errori, 0, 0, 0, errori, errori > 0 ? List.of("IMG_1.jpg: Immagine illeggibile") : List.of(),
                errore, null);
    }

    @Test
    void googleTakeoutSoloSeUsatoERossoSeUnoZipNonEntrato() {
        assertThat(salute.controlla().voci()).extracting(VoceSalute::chiave).doesNotContain("google-takeout");

        mongo.insert(zip("takeout-001.zip", StatoZip.FATTO, 0, null));
        VoceSalute v = voce(salute.controlla(), "google-takeout");
        assertThat(v.stato()).isEqualTo(StatoSalute.OK);
        assertThat(v.messaggio()).contains("1 zip importati");

        mongo.insert(zip("takeout-002.zip", StatoZip.CON_ERRORI, 1, null));
        v = voce(salute.controlla(), "google-takeout");
        assertThat(v.stato()).isEqualTo(StatoSalute.ATTENZIONE);
        assertThat(v.dettagli()).anySatisfy(d -> assertThat(d).contains("takeout-002.zip"));

        mongo.insert(zip("takeout-003.zip", StatoZip.FALLITO, 0, "zip illeggibile"));
        v = voce(salute.controlla(), "google-takeout");
        assertThat(v.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(v.messaggio()).contains("1 zip non importati").contains("zip illeggibile");
    }

    @Test
    void cloudSmontatoEGiallo() {
        rclone.montato = false;
        Salute.Rapporto r = salute.controlla();
        assertThat(voce(r, "cloud").stato()).isEqualTo(StatoSalute.ATTENZIONE);
        assertThat(r.stato()).isNotEqualTo(StatoSalute.OK);
    }

    @Test
    void backupMaiFattoEGialloTroppoVecchioERosso() throws IOException {
        assertThat(voce(salute.controlla(), "backup").stato()).isEqualTo(StatoSalute.ATTENZIONE);

        Files.createDirectories(backup.cartella());
        Path vecchio = Files.write(backup.cartella().resolve("fototimeline-2020-01-01-000000.json.gz"), new byte[0]);
        Files.setLastModifiedTime(vecchio, FileTime.from(adesso.minus(Duration.ofDays(3))));
        VoceSalute v = voce(salute.controlla(), "backup");
        assertThat(v.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(v.messaggio()).contains("troppo vecchio");

        // Un giorno e mezzo: meno di due intervalli, ancora verde.
        Files.setLastModifiedTime(vecchio, FileTime.from(adesso.minus(Duration.ofHours(36))));
        assertThat(voce(salute.controlla(), "backup").stato()).isEqualTo(StatoSalute.OK);
    }

    @Test
    void senzaTelefoniNienteVoceTelefonoSulPc() {
        // Qui (nel test) la sincronizzazione non può girare: senza telefoni usati la voce non c'è.
        telefono("anna", "Telefono di Anna", false, "pcloud-anna:Automatic Upload", 6, null);
        assertThat(salute.controlla().voci()).extracting(VoceSalute::chiave).doesNotContain("telefono");
    }

    @Test
    void telefonoConIlTokenScadutoERossoELoDice() {
        Giro giro = new Giro(adesso.minus(Duration.ofHours(1)), adesso, Esito.ERRORE,
                "operations/list: couldn't list files: Invalid 'access_token' (2094)", 0, 0, 0, 0, 0, List.of());
        telefono("marco", "Telefono di Marco", true, "pcloud:Automatic Upload", 6, giroOk(Duration.ofHours(1)));
        telefono("anna", "Telefono di Anna", true, "pcloud-anna:Automatic Upload", 6, giro);
        VoceSalute v = voce(salute.controlla(), "telefono");
        assertThat(v.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(v.messaggio()).contains("Telefono di Anna").contains("token").contains("2094")
                .doesNotContain("Telefono di Marco");
        assertThat(v.dettagli()).anySatisfy(d -> assertThat(d).startsWith("Telefono di Marco: Ultimo giro"));
    }

    @Test
    void telefonoAttivoCheNonGiraERosso() {
        telefono("marco", "Telefono di Marco", true, "pcloud:Automatic Upload", 6, giroOk(Duration.ofHours(1)));
        telefono("anna", "Telefono di Anna", true, "pcloud-anna:Automatic Upload", 6, giroOk(Duration.ofHours(13)));
        VoceSalute v = voce(salute.controlla(), "telefono");
        assertThat(v.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(v.messaggio()).contains("Telefono di Anna").contains("Non gira da più di 12 ore")
                .doesNotContain("Telefono di Marco");

        // Ogni 12 ore, 13 ore fa va bene: tutti e due a posto.
        telefono("anna", "Telefono di Anna", true, "pcloud-anna:Automatic Upload", 12, giroOk(Duration.ofHours(13)));
        v = voce(salute.controlla(), "telefono");
        assertThat(v.stato()).isEqualTo(StatoSalute.OK);
        assertThat(v.messaggio()).isEqualTo("2 telefoni a posto");

        // Spento (solo a mano) non è "fermo", anche se è vecchio.
        telefono("anna", "Telefono di Anna", false, "pcloud-anna:Automatic Upload", 6, giroOk(Duration.ofDays(10)));
        assertThat(voce(salute.controlla(), "telefono").stato()).isEqualTo(StatoSalute.OK);
    }

    @Test
    void rcloneGiuERossoMaLeAltreVociCiSono() throws IOException {
        backup.esegui();
        rclone.giu = true;

        Salute.Rapporto r = salute.controlla();

        assertThat(r.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(voce(r, "rclone").stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(voce(r, "rclone").messaggio()).contains("rclone non risponde");
        assertThat(voce(r, "cloud").stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(voce(r, "spazio-cloud").stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(voce(r, "backup").stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce(r, "archivio").stato()).isEqualTo(StatoSalute.OK);
        assertThat(r.voci()).extracting(VoceSalute::chiave).contains("disco", "importazione");
    }

    // ------------------------------------------------------------ avvisi

    @Test
    void avvisaSoloAiCambiDiStato() {
        FintoTelegram finto = new FintoTelegram();
        AvvisiSalute avvisi = avvisi(new TelegramProperties(TOKEN, "42", false, "foto.example.it"), finto);
        rclone.giu = true;

        assertThat(avvisi.controlla()).isPositive();
        assertThat(avvisi.controlla()).isZero();
        assertThat(finto.messaggi).hasSize(1);
        assertThat(finto.indirizzi).singleElement().asString().endsWith("/bot" + TOKEN + "/sendMessage");
        assertThat(finto.messaggi.get(0)).containsEntry("chat_id", "42").containsEntry("parse_mode", "HTML");
        assertThat((String) finto.messaggi.get(0).get("text")).contains("<b>rclone</b>: errore")
                .contains("https://foto.example.it");

        rclone.giu = false;
        assertThat(avvisi.controlla()).isPositive();
        assertThat(finto.messaggi).hasSize(2);
        assertThat((String) finto.messaggi.get(1).get("text")).contains("<b>rclone</b>: risolto");
        assertThat(avvisi.controlla()).isZero();
        assertThat(finto.messaggi).hasSize(2);
    }

    @Test
    void ilGialloSoloSeRichiesto() {
        FintoTelegram finto = new FintoTelegram();
        rclone.montato = false;
        avvisi(new TelegramProperties(TOKEN, "42", false, null), finto).controlla();
        assertThat(finto.messaggi).allSatisfy(m -> assertThat((String) m.get("text")).doesNotContain("Cloud"));

        mongo.remove(Query.query(Criteria.where("_id").is(StatiAvvisati.ID)), "impostazioni");
        avvisi(new TelegramProperties(TOKEN, "42", true, null), finto).controlla();
        assertThat(finto.messaggi).anySatisfy(m -> assertThat((String) m.get("text")).contains("<b>Cloud</b>: attenzione"));
    }

    @Test
    void senzaTokenNienteInvii() {
        FintoTelegram finto = new FintoTelegram();
        rclone.giu = true;
        AvvisiSalute avvisi = avvisi(new TelegramProperties("", "42", false, null), finto);
        avvisi.seNecessario();
        assertThat(avvisi.controlla()).isZero();
        assertThatThrownBy(avvisi::prova).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOTOTIMELINE_TELEGRAM_TOKEN");
        assertThat(finto.messaggi).isEmpty();
        assertThat(mongo.findById(StatiAvvisati.ID, StatiAvvisati.class)).isNull();
    }

    @Test
    void seTelegramNonRispondeIlTokenNonEsceERiprova() {
        FintoTelegram finto = new FintoTelegram();
        finto.giu = true;
        rclone.giu = true;
        AvvisiSalute avvisi = avvisi(new TelegramProperties(TOKEN, "42", false, null), finto);

        assertThatThrownBy(avvisi::controlla).isInstanceOf(Telegram.TelegramNonRiesce.class)
                .hasMessageContaining("Telegram non risponde")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("token-segreto"));
        // Gli stati non si sono salvati: al giro dopo si riprova.
        finto.giu = false;
        assertThat(avvisi.controlla()).isPositive();
        assertThat(finto.messaggi).hasSize(1);
    }

    @Test
    void telegramRifiutaConLaSuaDescrizione() {
        FintoTelegram finto = new FintoTelegram();
        finto.rifiuta = true;
        AvvisiSalute avvisi = avvisi(new TelegramProperties(TOKEN, "42", false, null), finto);
        assertThatThrownBy(avvisi::prova).isInstanceOf(Telegram.TelegramNonRiesce.class)
                .hasMessageContaining("chat not found");
    }

    private AvvisiSalute avvisi(TelegramProperties properties, FintoTelegram finto) {
        return new AvvisiSalute(salute, new Telegram(properties, finto.builder), properties, mongo);
    }

    private static VoceSalute voce(Salute.Rapporto r, String chiave) {
        return r.voci().stream().filter(v -> v.chiave().equals(chiave)).findFirst()
                .orElseThrow(() -> new AssertionError("manca la voce " + chiave + " in " + r.voci()));
    }

    // ------------------------------------------------------------ finti

    /** rclone: risponde JSON con Content-Type text/plain, come quello vero. */
    static final class FintoRclone {
        private static final ObjectMapper JSON = new ObjectMapper();

        volatile boolean giu;
        volatile boolean montato = true;
        volatile List<String> inAttesa = List.of();
        final List<String> chiamate = new ArrayList<>();
        final Map<String, Map<String, Object>> parametri = new HashMap<>();

        FintoRclone(RestClient.Builder builder) {
            MockRestServiceServer.bindTo(builder).build()
                    .expect(manyTimes(), anything())
                    .andRespond(this::rispondi);
        }

        @SuppressWarnings("unchecked")
        private synchronized ClientHttpResponse rispondi(ClientHttpRequest richiesta) throws IOException {
            if (giu) {
                throw new ConnectException("Connection refused");
            }
            String comando = richiesta.getURI().getPath().substring(1);
            chiamate.add(comando);
            Map<String, Object> p = JSON.readValue(((MockClientHttpRequest) richiesta).getBodyAsString(), Map.class);
            parametri.put(comando, p);
            if ("operations/about".equals(comando) && "pcloud:".equals(p.get("fs"))) {
                return withStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body("{\"error\":\"pcloud root '': doesn't support about\"}")
                        .createResponse(richiesta);
            }
            Object risposta = switch (comando) {
                case "core/version" -> Map.of("version", "v1.68.2");
                case "mount/listmounts" -> Map.of("mountPoints", montato
                        ? List.of(Map.of("Fs", "lifetime", "MountPoint", "/cloud"))
                        : List.of());
                case "config/listremotes" -> Map.of("remotes", List.of("lifetime", "pcloud"));
                case "operations/about" ->
                    Map.of("total", 2_000_000_000_000L, "used", 1_000_000_000_000L, "free", 1_000_000_000_000L);
                case "operations/list" -> Map.of("list", inAttesa.stream()
                        .map(n -> Map.of("Path", "telefono/" + n, "Name", n, "Size", 10, "IsDir", false))
                        .toList());
                default -> throw new IllegalStateException("comando inatteso " + comando);
            };
            return withSuccess(JSON.writeValueAsString(risposta), MediaType.TEXT_PLAIN).createResponse(richiesta);
        }
    }

    /** L'API dei bot: registra i messaggi; può non rispondere o rifiutare. */
    static final class FintoTelegram {
        private static final ObjectMapper JSON = new ObjectMapper();

        final RestClient.Builder builder = RestClient.builder();
        final List<Map<String, Object>> messaggi = new ArrayList<>();
        final List<String> indirizzi = new ArrayList<>();
        volatile boolean giu;
        volatile boolean rifiuta;

        FintoTelegram() {
            MockRestServiceServer.bindTo(builder).build()
                    .expect(manyTimes(), anything())
                    .andRespond(this::rispondi);
        }

        @SuppressWarnings("unchecked")
        private synchronized ClientHttpResponse rispondi(ClientHttpRequest richiesta) throws IOException {
            if (giu) {
                throw new ConnectException("Connection refused");
            }
            if (rifiuta) {
                return withStatus(HttpStatus.BAD_REQUEST)
                        .body("{\"ok\":false,\"error_code\":400,\"description\":\"Bad Request: chat not found\"}")
                        .createResponse(richiesta);
            }
            indirizzi.add(richiesta.getURI().toString());
            messaggi.add(JSON.readValue(((MockClientHttpRequest) richiesta).getBodyAsString(), Map.class));
            return withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON).createResponse(richiesta);
        }
    }
}
