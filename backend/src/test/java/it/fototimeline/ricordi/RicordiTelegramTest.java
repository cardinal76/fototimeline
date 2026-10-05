package it.fototimeline.ricordi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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

import it.fototimeline.condivisione.CondivisioniService;
import it.fototimeline.dominio.Foto;
import it.fototimeline.quasiuguali.QuasiUgualiProperties;
import it.fototimeline.salute.Telegram;
import it.fototimeline.salute.TelegramProperties;
import it.fototimeline.service.ArchivioFile;

/** Su MongoDB embedded, con Telegram finto e un orologio che si sposta a mano. */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
@ExtendWith(OutputCaptureExtension.class)
class RicordiTelegramTest {

    /** Finto, costruito qui: deve restare fuori da log, messaggi e risposte. */
    private static final String TOKEN = "123456:" + "token-segreto-di-prova";
    private static final ZoneId ROMA = ZoneId.of("Europe/Rome");

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
    CondivisioniService condivisioni;
    @Autowired
    QuasiUgualiProperties quasiUguali;

    private final Orologio orologio = new Orologio();
    private FintoTelegram finto;

    @BeforeEach
    void prepara() {
        mongo.remove(new Query(), Foto.class);
        mongo.remove(Query.query(Criteria.where("_id").is(ImpostazioniRicordi.ID)), "impostazioni");
        finto = new FintoTelegram();
        alle(2026, 10, 5, 8, 0);
    }

    // ------------------------------------------------------------ scelta e didascalia

    @Test
    void unaPerAnnoPrimaLePreferiteNienteVideoEIlLimite() throws IOException {
        foto("a25", 2025, 10, false, null);
        foto("b25", 2025, 12, true, null);
        foto("c25", 2025, 14, false, null);
        foto("a23", 2023, 9, false, null);
        foto("b23", 2023, 11, false, null);
        foto("a20", 2020, 9, false, null);
        foto("a19", 2019, 18, true, null);
        video("v24", 2024, 10);

        // Tre: un giro, prima gli anni con una preferita, poi il più recente; in ordine di scatto.
        assertThat(ids(ricordi(true, "foto.example.it").scegli(tutte(), 3, this::fileFinto)))
                .containsExactly("a19", "a23", "b25");
        // Sei: un giro completo (quattro anni, niente 2024: solo un video), poi il secondo giro.
        assertThat(ids(ricordi(true, null).scegli(tutte(), 6, this::fileFinto)))
                .containsExactly("a19", "a20", "a23", "b23", "a25", "b25");
        // Senza file sul disco una foto non si sceglie: si passa alla prossima dello stesso anno.
        assertThat(ids(ricordi(true, null).scegli(tutte(), 4, f -> f.getId().equals("b25") ? null : fileFinto(f))))
                .containsExactly("a19", "a20", "a23", "a25");
    }

    @Test
    void nienteQuasiDoppioni() {
        foto("uno", 2025, 9, false, 0L);
        foto("copia", 2025, 10, false, 1L);
        foto("altra", 2025, 11, false, -1L);
        assertThat(ids(ricordi(true, null).scegli(tutte(), 2, this::fileFinto))).containsExactly("uno", "altra");
    }

    @Test
    void didascaliaColLuogoEIlLink() {
        Foto sperlonga = foto("s", 2023, 10, false, null);
        sperlonga.setLuogo("Sperlonga");
        mongo.save(sperlonga);
        RicordiTelegram r = ricordi(true, "foto.example.it");
        String d = r.didascalia(orologio.oggi(), List.of(sperlonga), List.of(sperlonga), 1);
        assertThat(d).startsWith("📅 <b>5 ottobre</b> · 3 anni fa a Sperlonga")
                .contains("<a href=\"https://foto.example.it/?ricordi=oggi\">Tutte le foto di oggi</a>")
                .doesNotContain("(1)");

        Foto roma = foto("r", 2025, 10, false, null);
        roma.setLuogo("Roma & dintorni");
        Foto senza = foto("x", 2025, 11, false, null);
        d = r.didascalia(orologio.oggi(), List.of(sperlonga, roma), List.of(sperlonga, roma, senza), 2);
        assertThat(d).contains("\n• 3 anni fa a Sperlonga\n• un anno fa a Roma &amp; dintorni")
                .contains("Tutte le foto di oggi</a> (3)");
        // Senza indirizzo dell'app niente link.
        assertThat(ricordi(true, null).didascalia(orologio.oggi(), List.of(roma), List.of(roma), 1)).doesNotContain("href");
    }

    // ------------------------------------------------------------ invio di ogni mattina

    @Test
    void unSoloInvioAlGiornoAncheDopoUnRiavvio() throws IOException {
        foto("a25", 2025, 10, false, null);
        foto("a23", 2023, 10, true, null);
        miniatura("a25");
        miniatura("a23");

        alle(2026, 10, 5, 7, 59);
        assertThat(ricordi(true, "foto.example.it").controlla()).isFalse();
        assertThat(finto.chiamate).isEmpty();

        alle(2026, 10, 5, 8, 0);
        RicordiTelegram r = ricordi(true, "foto.example.it");
        assertThat(r.controlla()).isTrue();
        assertThat(r.controlla()).isFalse();
        alle(2026, 10, 5, 22, 0);
        r.seNecessario();
        // "Riavvio": un'altra istanza, stesso Mongo.
        assertThat(ricordi(true, "foto.example.it").controlla()).isFalse();

        assertThat(finto.chiamate).singleElement().satisfies(c -> {
            assertThat(c.metodo()).isEqualTo("sendMediaGroup");
            assertThat(c.campo("chat_id")).isEqualTo("42");
            assertThat(c.campo("media")).contains("attach://foto0").contains("attach://foto1")
                    .contains("📅 <b>5 ottobre</b>").contains("3 anni fa").contains("un anno fa")
                    .contains("?ricordi=oggi");
            // Le miniature, mai gli originali.
            assertThat(c.corpo()).contains("MINIATURA-a25").contains("MINIATURA-a23").doesNotContain("ORIGINALE");
        });
        assertThat(r.stato().ultimoGiorno()).isEqualTo("2026-10-05");
        assertThat(r.stato().ultimoEsito()).isEqualTo("Mandate 2 foto");

        // Il giorno dopo non c'è niente: nessun messaggio. Quello dopo ancora sì.
        alle(2026, 10, 6, 8, 0);
        assertThat(r.controlla()).isTrue();
        assertThat(finto.chiamate).hasSize(1);
        foto("a24", 2024, 10, false, null);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("a24")),
                new org.springframework.data.mongodb.core.query.Update()
                        .set("scattataIl", LocalDateTime.of(2024, 10, 7, 10, 0)).set("giorno", "2024-10-07"), Foto.class);
        miniatura("a24");
        alle(2026, 10, 7, 9, 0);
        assertThat(r.controlla()).isTrue();
        assertThat(finto.chiamate).hasSize(2);
        assertThat(finto.chiamate.get(1).metodo()).isEqualTo("sendPhoto");
        assertThat(finto.chiamate.get(1).campo("caption")).startsWith("📅 <b>7 ottobre</b> · 2 anni fa");
    }

    @Test
    void laVistaSenzaExifSeCeGiaEUnaChatAParte() throws IOException {
        foto("a25", 2025, 10, false, null);
        miniatura("a25");
        Path vista = archivioFile.vistaCondivisa("a25");
        Files.createDirectories(vista.getParent());
        Files.writeString(vista, "VISTA-a25");

        RicordiTelegram r = new RicordiTelegram(mongo, telegram(null), proprietaTelegram(null),
                new RicordiTelegramProperties(true, "08:00", 6, "-100777", "Europe/Rome", false),
                archivioFile, condivisioni, quasiUguali, Optional.of(orologio));
        assertThat(r.controlla()).isTrue();
        assertThat(finto.chiamate).singleElement().satisfies(c -> {
            assertThat(c.metodo()).isEqualTo("sendPhoto");
            assertThat(c.campo("chat_id")).isEqualTo("-100777");
            assertThat(c.corpo()).contains("VISTA-a25").doesNotContain("MINIATURA");
        });
        assertThat(r.stato().chatSeparata()).isTrue();
    }

    @Test
    void senzaRicordiNessunMessaggioSalvoSeRichiesto() {
        RicordiTelegram r = ricordi(true, null);
        assertThat(r.controlla()).isTrue();
        assertThat(r.controlla()).isFalse();
        assertThat(finto.chiamate).isEmpty();
        assertThat(r.stato().ultimoEsito()).contains("niente messaggio");

        r.salva(new RicordiTelegram.Impostazioni(true, "08:00", 6, true));
        alle(2026, 10, 6, 8, 30);
        assertThat(r.controlla()).isTrue();
        assertThat(finto.chiamate).singleElement().satisfies(c -> {
            assertThat(c.metodo()).isEqualTo("sendMessage");
            assertThat(c.corpo()).contains("Nessun ricordo oggi");
        });
    }

    @Test
    void seLAppEraGiuAllOraGiustaMandaAppenaRiparte() throws IOException {
        foto("a25", 2025, 10, false, null);
        miniatura("a25");
        alle(2026, 10, 5, 21, 45);
        assertThat(ricordi(true, null).controlla()).isTrue();
        assertThat(finto.chiamate).hasSize(1);
    }

    @Test
    void seTelegramNonRispondeRiprovaEIlTokenNonEsce(CapturedOutput log) throws IOException {
        foto("a25", 2025, 10, false, null);
        miniatura("a25");
        finto.giu = true;
        RicordiTelegram r = ricordi(true, null);
        assertThatThrownBy(r::controlla).isInstanceOf(Telegram.TelegramNonRiesce.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain("token-segreto").doesNotContain("123456"));
        r.seNecessario();
        assertThat(log.getAll()).contains("Ricordi su Telegram non mandati").doesNotContain("token-segreto");
        assertThat(r.stato().ultimoGiorno()).isNull();
        assertThat(r.stato().ultimoEsito()).startsWith("Non mandati").doesNotContain("token-segreto");
        assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(r.stato()))
                .doesNotContain("token-segreto").doesNotContain("123456");

        // Telegram torna: al controllo dopo si manda, una volta.
        finto.giu = false;
        assertThat(r.controlla()).isTrue();
        assertThat(r.controlla()).isFalse();
        assertThat(finto.chiamate).hasSize(1);
    }

    @Test
    void spentoNonMandaMaLaProvaSiAncheDopoLInvio() throws IOException {
        foto("a25", 2025, 10, false, null);
        miniatura("a25");
        RicordiTelegram r = ricordi(true, null);
        r.salva(new RicordiTelegram.Impostazioni(false, "8:00", 3, false));
        assertThat(r.controlla()).isFalse();
        assertThat(finto.chiamate).isEmpty();

        assertThat(r.prova().messaggio()).isEqualTo("Mandata 1 foto");
        r.salva(new RicordiTelegram.Impostazioni(true, "08:00", 3, false));
        assertThat(r.controlla()).isTrue();
        assertThat(r.prova().messaggio()).isEqualTo("Mandata 1 foto");
        assertThat(finto.chiamate).hasSize(3);
        assertThat(r.stato().ultimoEsito()).startsWith("Prova:");

        // Prova senza ricordi: il messaggio "Nessun ricordo oggi", così si vede che arriva.
        alle(2026, 10, 6, 6, 0);
        assertThat(r.prova().messaggio()).contains("Nessun ricordo oggi");
        assertThat(finto.chiamate).hasSize(4);
    }

    @Test
    void impostazioniDaMongoSopraLEnvEValidate() {
        RicordiTelegram r = ricordi(true, null);
        assertThat(r.stato()).satisfies(s -> {
            assertThat(s.attivo()).isTrue();
            assertThat(s.ora()).isEqualTo("08:00");
            assertThat(s.foto()).isEqualTo(6);
            assertThat(s.telegramConfigurato()).isTrue();
            assertThat(s.chatSeparata()).isFalse();
        });
        assertThat(r.salva(new RicordiTelegram.Impostazioni(true, "7:05", 4, false)).ora()).isEqualTo("07:05");
        assertThat(ricordi(true, null).stato().foto()).isEqualTo(4);
        assertThatThrownBy(() -> r.salva(new RicordiTelegram.Impostazioni(true, "25:00", 4, false)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> r.salva(new RicordiTelegram.Impostazioni(true, "08:00", 11, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void senzaTelegramNienteEProvaRifiutata() {
        foto("a25", 2025, 10, false, null);
        TelegramProperties spento = new TelegramProperties("", "", false, null);
        RicordiTelegram r = new RicordiTelegram(mongo, new Telegram(spento, finto.builder), spento,
                new RicordiTelegramProperties(true, "08:00", 6, null, null, false),
                archivioFile, condivisioni, quasiUguali, Optional.of(orologio));
        assertThat(r.controlla()).isFalse();
        assertThat(r.stato().telegramConfigurato()).isFalse();
        // IllegalStateException: 409 (GestoreErrori).
        assertThatThrownBy(r::prova).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FOTOTIMELINE_TELEGRAM_TOKEN");
        assertThat(finto.chiamate).isEmpty();
    }

    // ------------------------------------------------------------ aiuti

    private RicordiTelegram ricordi(boolean attivo, String indirizzo) {
        return new RicordiTelegram(mongo, telegram(indirizzo), proprietaTelegram(indirizzo),
                new RicordiTelegramProperties(attivo, "08:00", 6, "", "Europe/Rome", false),
                archivioFile, condivisioni, quasiUguali, Optional.of(orologio));
    }

    private TelegramProperties proprietaTelegram(String indirizzo) {
        return new TelegramProperties(TOKEN, "42", false, indirizzo);
    }

    private Telegram telegram(String indirizzo) {
        return new Telegram(proprietaTelegram(indirizzo), finto.builder);
    }

    private void alle(int anno, int mese, int giorno, int ora, int minuto) {
        orologio.adesso = LocalDateTime.of(anno, mese, giorno, ora, minuto).atZone(ROMA).toInstant();
    }

    /** Una foto il 5 ottobre di {@code anno}, con un originale finto (non va mai mandato). */
    private Foto foto(String id, int anno, int ora, boolean preferita, Long impronta) {
        Foto f = new Foto();
        f.setId(id);
        f.setHash("hash-" + id);
        f.setNomeOriginale(id + ".jpg");
        f.setContentType("image/jpeg");
        f.setScattataIl(LocalDateTime.of(anno, 10, 5, ora, 0));
        f.setPreferita(preferita);
        f.setImpronta(impronta);
        f.setPercorso("%d/10/05/%s.jpg".formatted(anno, id));
        try {
            Path originale = archivio.resolve(f.getPercorso());
            Files.createDirectories(originale.getParent());
            Files.writeString(originale, "ORIGINALE-" + id);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return mongo.save(f);
    }

    private void video(String id, int anno, int ora) {
        Foto f = foto(id, anno, ora, true, null);
        f.setVideo(true);
        mongo.save(f);
    }

    private void miniatura(String id) throws IOException {
        Files.writeString(archivioFile.miniatura(id), "MINIATURA-" + id);
    }

    private List<Foto> tutte() {
        return mongo.find(new Query().with(org.springframework.data.domain.Sort.by("scattataIl")), Foto.class);
    }

    private Path fileFinto(Foto f) {
        return Path.of(f.getId() + ".jpg");
    }

    private static List<String> ids(List<RicordiTelegram.Scelta> scelte) {
        return scelte.stream().map(s -> s.foto().getId()).toList();
    }

    // ------------------------------------------------------------ finti

    /** Un orologio che si sposta a mano. */
    static final class Orologio extends Clock {
        volatile Instant adesso = Instant.now();

        java.time.LocalDate oggi() {
            return adesso.atZone(ROMA).toLocalDate();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            Orologio questo = this;
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return zona;
                }

                @Override
                public Clock withZone(ZoneId altra) {
                    return questo.withZone(altra);
                }

                @Override
                public Instant instant() {
                    return questo.instant();
                }
            };
        }

        @Override
        public Instant instant() {
            return adesso;
        }
    }

    /** Una chiamata all'API dei bot: il metodo e il corpo (JSON o multipart). */
    record Chiamata(String metodo, String corpo) {

        /** Un campo del multipart, o del JSON per sendMessage. */
        String campo(String nome) {
            Matcher m = Pattern.compile("name=\"" + Pattern.quote(nome) + "\"[^\\r\\n]*\\r\\n(?:[^\\r\\n]+\\r\\n)*\\r\\n(.*?)\\r\\n--",
                    Pattern.DOTALL).matcher(corpo);
            if (m.find()) {
                return m.group(1);
            }
            try {
                Object v = new ObjectMapper().readTree(corpo).path(nome);
                return v.toString().replaceAll("^\"|\"$", "");
            } catch (IOException e) {
                return null;
            }
        }
    }

    /** L'API dei bot: registra le chiamate; può non rispondere. */
    static final class FintoTelegram {
        final RestClient.Builder builder = RestClient.builder();
        final List<Chiamata> chiamate = new ArrayList<>();
        volatile boolean giu;

        FintoTelegram() {
            MockRestServiceServer.bindTo(builder).build()
                    .expect(manyTimes(), anything())
                    .andRespond(this::rispondi);
        }

        private synchronized ClientHttpResponse rispondi(ClientHttpRequest richiesta) throws IOException {
            if (giu) {
                throw new ConnectException("Connection refused: " + richiesta.getURI());
            }
            String percorso = richiesta.getURI().getPath();
            if (!percorso.startsWith("/bot" + TOKEN + "/")) {
                return withStatus(HttpStatus.NOT_FOUND).body("{\"ok\":false}").createResponse(richiesta);
            }
            byte[] corpo = ((MockClientHttpRequest) richiesta).getBodyAsBytes();
            chiamate.add(new Chiamata(percorso.substring(percorso.lastIndexOf('/') + 1),
                    new String(corpo, StandardCharsets.UTF_8)));
            return withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON).createResponse(richiesta);
        }
    }
}
