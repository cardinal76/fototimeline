package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.Cloud;
import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.dominio.StatoConversione;
import it.fototimeline.media.InfoVideo;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.ConversioneVideo;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Risultati.Esito;

/**
 * La versione compatibile dei video, con ffmpeg vero: dove manca questi test
 * si saltano. I video non compatibili si generano qui: HEVC se ffmpeg ha
 * libx265, altrimenti MPEG-4 Part 2 (anche quello i browser non lo leggono).
 * Il cloud è finto, per smontarlo a metà.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.video.risoluzione-massima=120",
        "fototimeline.video.thread=1",
        "fototimeline.video.controllo=PT1H" })
@AutoConfigureMockMvc
class ConversioneVideoTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
        r.add("fototimeline.miniature", () -> disco.resolve("miniature").toString());
    }

    @MockitoBean
    Cloud cloud;

    @MockitoSpyBean
    StrumentiMedia media;

    @Autowired
    ConversioneVideo conversione;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    ArchivioFile archivio;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    MockMvc mvc;

    private volatile boolean montato = true;
    private final AtomicInteger contemporanee = new AtomicInteger();
    private final AtomicInteger massimo = new AtomicInteger();
    private static int generati;

    @BeforeEach
    void prepara() throws Exception {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        montato = true;
        when(cloud.disponibile()).thenAnswer(i -> montato);
        doAnswer(i -> {
            if (!montato) {
                throw new ArchivioNonDisponibile();
            }
            return null;
        }).when(cloud).verifica();
        // Conta le conversioni contemporanee; la pausa le allunga, così si vedono e si annullano.
        doAnswer(i -> {
            massimo.accumulateAndGet(contemporanee.incrementAndGet(), Math::max);
            try {
                Thread.sleep(150);
                return i.callRealMethod();
            } finally {
                contemporanee.decrementAndGet();
            }
        }).when(media).converti(any(), any(), anyInt(), anyInt(), any(), any());
        conversione.annulla();
        aspetta(() -> !conversione.stato().inCorso(), "la coda non si ferma");
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        massimo.set(0);
    }

    @Test
    void unH264ConAacNonSiConverte() throws Exception {
        var video = service.importa("VID_0001.mp4", risorsa("/video.mp4"), null, null).foto();

        assertThat(video.getCodecVideo()).isEqualTo("h264");
        assertThat(video.getCompatibile()).isTrue();
        assertThat(video.getConversione()).isNull();
        assertThat(service.fileDaServire(video, false).file()).isEqualTo(service.fileOriginale(video));
        assertThat(service.fileDaServire(video, false).definitivo()).isTrue();
    }

    @Test
    void unVideoNonCompatibileSiConverteRuotatoERimpicciolito() throws Exception {
        var esito = service.importa("IMG_0001.MOV", nonCompatibile(320, 240, 90), null, null);
        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getCompatibile()).isFalse();
        assertThat(foto.getCodecVideo()).isIn("hevc", "mpeg4");
        assertThat(foto.getCodecAudio()).isEqualTo("aac");

        Foto fatta = aspettaConversione(foto.getId(), StatoConversione.FATTA);

        Path compatibile = archivio.compatibile(fatta.getId());
        assertThat(compatibile).startsWith(disco.resolve("archivio/.compatibili")).exists();
        InfoVideo info = media.leggiVideo(compatibile, ZoneId.systemDefault());
        assertThat(info.compatibile()).isTrue();
        assertThat(info.codecAudio()).isEqualTo("aac");
        // Verticale (ruotato di 90°) e col lato corto a 120: 240×320 → 120×160, già dritto.
        assertThat(info.larghezza()).isEqualTo(120);
        assertThat(info.altezza()).isEqualTo(160);
        assertThat(info.durata()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.2));
    }

    @Test
    void lEndpointServeLaCompatibileARichiestaConRange() throws Exception {
        Foto foto = service.importa("IMG_0002.MOV", nonCompatibile(320, 240, 0), null, null).foto();
        aspettaConversione(foto.getId(), StatoConversione.FATTA);
        long peso = Files.size(archivio.compatibile(foto.getId()));
        long pesoOriginale = Files.size(service.fileOriginale(foto));

        mvc.perform(get("/api/foto/{id}/file", foto.getId()).header("Range", "bytes=0-99"))
                .andExpect(status().isPartialContent())
                .andExpect(content().contentType("video/mp4"))
                .andExpect(header().string("Content-Range", "bytes 0-99/" + peso))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("immutable")));
        mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("originale", "true").header("Range", "bytes=0-9"))
                .andExpect(status().isPartialContent())
                .andExpect(content().contentType("video/quicktime"))
                .andExpect(header().string("Content-Range", "bytes 0-9/" + pesoOriginale));
        mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Length", String.valueOf(pesoOriginale)))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("IMG_0002.MOV")));

        // Non ancora convertito: l'originale, ma senza cache "per sempre" (poi arriverà la compatibile).
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())),
                new Update().set("conversione", StatoConversione.ERRORE), Foto.class);
        mvc.perform(get("/api/foto/{id}/file", foto.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType("video/quicktime"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-cache")));
    }

    @Test
    void unoAllaVoltaEGliErroriNonFermanoLaCoda() throws Exception {
        // Un video rotto in testa alla coda: va in errore, gli altri passano lo stesso.
        Foto rotto = schedaInCoda("2020/01/01/rotto.mov", "non è un video".getBytes(StandardCharsets.UTF_8));
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(service.importa("IMG_10" + i + ".MOV", nonCompatibile(160 + 16 * i, 120, 0), null, null).foto().getId());
        }
        conversione.segnala();

        for (String id : ids) {
            aspettaConversione(id, StatoConversione.FATTA);
        }
        Foto errore = aspettaConversione(rotto.getId(), StatoConversione.ERRORE);
        assertThat(errore.getErroreConversione()).isNotBlank();
        assertThat(massimo.get()).isEqualTo(1);
        var stato = conversione.stato();
        assertThat(stato.daFare()).isZero();
        assertThat(stato.convertiti()).isEqualTo(3);
    }

    @Test
    void dopoUnRiavvioRiprendeQuelloCheEraInCorso() throws Exception {
        Foto foto = service.importa("IMG_0003.MOV", nonCompatibile(200, 120, 0), null, null).foto();
        aspettaConversione(foto.getId(), StatoConversione.FATTA);
        // Come se l'app si fosse fermata a metà: scheda IN_CORSO, file non ancora scritto.
        Files.delete(archivio.compatibile(foto.getId()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())),
                new Update().set("conversione", StatoConversione.IN_CORSO), Foto.class);

        conversione.riprendi();

        aspettaConversione(foto.getId(), StatoConversione.FATTA);
        assertThat(archivio.compatibile(foto.getId())).exists();
    }

    @Test
    void colCloudSmontatoLaCodaAspettaENonFallisce() throws Exception {
        Foto foto = service.importa("IMG_0004.MOV", nonCompatibile(208, 120, 0), null, null).foto();
        aspettaConversione(foto.getId(), StatoConversione.FATTA);
        Files.delete(archivio.compatibile(foto.getId()));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())),
                new Update().set("conversione", StatoConversione.IN_CODA), Foto.class);

        montato = false;
        conversione.segnala();
        aspetta(() -> conversione.stato().inAttesa(), "la coda non si è messa in attesa");
        TimeUnit.MILLISECONDS.sleep(300);
        assertThat(repository.findById(foto.getId()).orElseThrow().getConversione()).isEqualTo(StatoConversione.IN_CODA);

        montato = true;
        conversione.controlla();
        aspettaConversione(foto.getId(), StatoConversione.FATTA);
    }

    @Test
    void convertiVideoAnalizzaQuelliDiPrimaEConverteSoloQuelliCheServono() throws Exception {
        Foto h264 = service.importa("VID_0005.mp4", risorsa("/video.mp4"), null, null).foto();
        Foto hevc = service.importa("IMG_0005.MOV", nonCompatibile(224, 120, 0), null, null).foto();
        aspettaConversione(hevc.getId(), StatoConversione.FATTA);
        Files.delete(archivio.compatibile(hevc.getId()));
        // Come le schede di prima di questa versione: niente codec né compatibilità.
        mongo.updateMulti(Query.query(Criteria.where("_id").in(h264.getId(), hevc.getId())),
                new Update().unset("codecVideo").unset("codecAudio").unset("compatibile").unset("conversione"), Foto.class);

        var partito = conversione.avvia();
        assertThat(partito.daFare()).isBetween(0L, 2L);

        Foto convertito = aspettaConversione(hevc.getId(), StatoConversione.FATTA);
        assertThat(convertito.getCompatibile()).isFalse();
        assertThat(archivio.compatibile(hevc.getId())).exists();
        aspetta(() -> repository.findById(h264.getId()).orElseThrow().getCompatibile() != null, "h264 non analizzato");
        Foto analizzato = repository.findById(h264.getId()).orElseThrow();
        assertThat(analizzato.getCompatibile()).isTrue();
        assertThat(analizzato.getCodecVideo()).isEqualTo("h264");
        assertThat(analizzato.getConversione()).isNull();
    }

    @Test
    void annullareFermaQuelloInCorsoESvuotaLaCoda() throws Exception {
        String a = service.importa("IMG_0006.MOV", nonCompatibile(240, 120, 0), null, null).foto().getId();
        String b = service.importa("IMG_0007.MOV", nonCompatibile(256, 120, 0), null, null).foto().getId();
        aspetta(() -> conversione.stato().inCorso(), "la coda non è partita");

        assertThat(conversione.annulla()).isTrue();

        aspetta(() -> !conversione.stato().inCorso(), "la conversione non si ferma");
        assertThat(conversione.stato().daFare()).isZero();
        Foto primo = repository.findById(a).orElseThrow();
        Foto secondo = repository.findById(b).orElseThrow();
        // Al massimo uno era già finito prima dell'annullamento; l'altro resta con l'originale.
        assertThat(List.of(primo.getConversione() == null, secondo.getConversione() == null)).contains(true);
        assertThat(secondo.getErroreConversione()).isNull();
    }

    // ------------------------------------------------------------ utilità

    /** Una scheda di video da convertire messa a mano, col suo file nell'archivio. */
    private Foto schedaInCoda(String percorso, byte[] contenuto) throws IOException {
        Path file = disco.resolve("archivio").resolve(percorso);
        Files.createDirectories(file.getParent());
        Files.write(file, contenuto);
        Foto f = new Foto();
        f.setId(UUID.randomUUID().toString());
        f.setNomeOriginale(file.getFileName().toString());
        f.setContentType("video/quicktime");
        f.setHash(UUID.randomUUID().toString());
        f.setPercorso(percorso);
        f.setVideo(true);
        f.setDurata(1.0);
        f.setScattataIl(LocalDateTime.of(2020, 1, 1, 12, 0));
        f.setOrigineData(OrigineData.CARTELLA);
        f.setCaricataIl(Instant.EPOCH);
        f.setCompatibile(false);
        f.setConversione(StatoConversione.IN_CODA);
        return repository.insert(f);
    }

    private Foto aspettaConversione(String id, StatoConversione atteso) throws InterruptedException {
        aspetta(() -> repository.findById(id).map(f -> f.getConversione() == atteso).orElse(false),
                "conversione di " + id + " non arrivata a " + atteso);
        return repository.findById(id).orElseThrow();
    }

    private static void aspetta(BooleanSupplier condizione, String messaggio) throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(limite)) {
            if (condizione.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError(messaggio);
    }

    /**
     * Un secondo di video non compatibile con audio AAC, in MOV, ogni volta
     * diverso (l'archivio non accetta due volte lo stesso file).
     */
    private static Path nonCompatibile(int larghezza, int altezza, int rotazione) throws Exception {
        int n = ++generati;
        Path grezzo = Files.createTempFile("grezzo", ".mov");
        Path video = Files.createTempFile("non-compatibile", ".mov");
        List<String> codec = haLibx265()
                ? List.of("-c:v", "libx265", "-x265-params", "log-level=error", "-tag:v", "hvc1")
                : List.of("-c:v", "mpeg4");
        List<String> comando = new ArrayList<>(List.of("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc=size=%dx%d:rate=10".formatted(larghezza, altezza),
                "-f", "lavfi", "-i", "sine=frequency=" + (200 + 50 * n), "-t", "1"));
        comando.addAll(codec);
        comando.addAll(List.of("-c:a", "aac", grezzo.toString()));
        esegui(comando);
        // La rotazione del telefono è un'opzione dell'ingresso: un secondo passaggio, senza ricodificare.
        esegui(List.of("ffmpeg", "-y", "-v", "error", "-display_rotation", String.valueOf(rotazione),
                "-i", grezzo.toString(), "-c", "copy", video.toString()));
        Files.delete(grezzo);
        return video;
    }

    private static Boolean libx265;

    private static boolean haLibx265() throws Exception {
        if (libx265 == null) {
            Process p = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders").redirectErrorStream(true).start();
            libx265 = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("libx265");
            p.waitFor();
        }
        return libx265;
    }

    private static void esegui(List<String> comando) throws Exception {
        Process p = new ProcessBuilder(comando).redirectErrorStream(true).start();
        String uscita = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException(String.join(" ", comando) + ": " + uscita);
        }
    }

    private Path risorsa(String nome) throws IOException {
        Path copia = Files.createTempFile("risorsa", nome.substring(nome.lastIndexOf('.')));
        try (var in = getClass().getResourceAsStream(nome)) {
            Files.copy(in, copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return copia;
    }
}
