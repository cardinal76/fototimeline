package it.fototimeline.telefono;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.cloud.RcloneNonRiesce;
import it.fototimeline.dominio.Foto;
import it.fototimeline.media.FormatoVideo;
import it.fototimeline.media.MediaProperties;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.media.TurnoVideo;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.sicurezza.Utente;
import it.fototimeline.telefono.SincronizzazioneTelefono.Modifica;
import it.fototimeline.telefono.SincronizzazioneTelefonoTest.FintoRclone;
import it.fototimeline.telefono.SincronizzazioneTelefonoTest.Orologio;
import it.fototimeline.telefono.SorgenteTelefono.Esito;
import it.fototimeline.telefono.SorgenteTelefono.Giro;
import it.fototimeline.telefono.VideoGrande.Stato;

/**
 * I video del telefono troppo grandi per LifetimeCloud: riconosciuti,
 * ricompressi, importati e con l'originale spostato (mai cancellato) nella
 * cartella dedicata di pCloud. Con il rclone finto della sincronizzazione e,
 * per la ricompressione, un ffmpeg finto o (dove c'è) quello vero su un video
 * piccolo generato al momento.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class VideoGrandiTest {

    private static final String XIAOMI = "Automatic Upload/Xiaomi 25080RABDG/DCIM/Camera/";
    private static final long MB = 1024L * 1024;
    /** I due errori di produzione, così come li ha scritti la pagina Salute. */
    private static final String TROPPO_GRANDE_403 =
            "operations/copyfile: File too large. Max 20480MB on 2 TB Lifetime.: 403 Forbidden";
    private static final String TROPPO_GRANDE_413 = "operations/copyfile: <html>\r\n<head><title>413 Request Entity Too"
            + " Large</title></head>\r\n<body>\r\n<center><h1>413 Request Entity Too Large</h1></center>\r\n"
            + "<hr><center>nginx</center>\r\n</body>\r\n</html>: 413 Request Entity Too Large";

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

    @Autowired
    StrumentiMedia ffmpegVero;

    @TempDir
    Path montaggio;

    @TempDir
    Path lavoro;

    private FintoRclone rclone;
    private Orologio orologio;
    private FintoMedia finto;
    private VideoGrandi videoGrandi;
    private SincronizzazioneTelefono sincronizzazione;
    private String marco;
    private long spazio;

    @BeforeEach
    void prepara() {
        mongo.remove(new Query(), SorgenteTelefono.class);
        mongo.remove(new Query(), CopiaTelefono.class);
        mongo.remove(new Query(), VideoGrande.class);
        mongo.remove(new Query(), Utente.class);
        fotoRepository.findAll().forEach(f -> {
            fotoService.elimina(f.getId());
            fotoService.eliminaPerSempre(f.getId());
        });
        finto = new FintoMedia();
        spazio = Long.MAX_VALUE;
        prepara(finto);
    }

    /** Sincronizzazione e video grandi con questo ffmpeg; soglia 2 MB, cloud "montato" in {@link #montaggio}. */
    private void prepara(StrumentiMedia media) {
        mongo.remove(new Query(), SorgenteTelefono.class);
        RestClient.Builder builder = RestClient.builder();
        rclone = new FintoRclone(builder);
        rclone.lifetimeSuDisco = montaggio;
        orologio = new Orologio(Instant.parse("2026-03-02T08:00:00Z"));
        var cloud = new CloudProperties("http://rclone:5572", "fototimeline", "password-di-prova", "lifetime:",
                montaggio.toString(), false, 0);
        var properties = new VideoGrandiProperties(true, 2L, null, lavoro, null, DataSize.ofKilobytes(1), null, null,
                null, null, 1, null, null, null, null, null, null, null);
        videoGrandi = new VideoGrandi(new Rclone(cloud, builder), cloud, mongo, media, new TurnoVideo(), properties,
                Optional.of(orologio));
        videoGrandi.perTest(Duration.ofMillis(5), Duration.ofSeconds(30), p -> spazio);
        sincronizzazione = new SincronizzazioneTelefono(new Rclone(cloud, builder), cloud,
                new ImportazioneAutomaticaProperties(montaggio.resolve("telefono"), null, null), mongo, "",
                videoGrandi, Optional.of(orologio));
        marco = sincronizzazione.crea(new Modifica("Telefono di Marco", "marco", null, null, null, null, null)).id();
    }

    private VideoGrande unico() {
        List<VideoGrande> tutti = mongo.findAll(VideoGrande.class);
        assertThat(tutti).hasSize(1);
        return tutti.getFirst();
    }

    /** Nessun originale di pCloud tolto: niente deletefile su pCloud, mai. */
    private void nienteCancellatoDaPcloud() {
        assertThat(rclone.chiamate("operations/deletefile")).noneMatch(p -> "pcloud:".equals(p.get("fs")));
    }

    private List<Map<String, Object>> caricamentiDalDisco() {
        return rclone.chiamate("operations/copyfile").stream()
                .filter(p -> ((String) p.get("srcFs")).startsWith("/")).toList();
    }

    @Test
    void riconosceITroppoGrandiDiLifetimeEDiNginx() {
        assertThat(VideoGrandi.rifiutatoPerDimensione(new RcloneNonRiesce(TROPPO_GRANDE_403))).isTrue();
        assertThat(VideoGrandi.rifiutatoPerDimensione(new RcloneNonRiesce(TROPPO_GRANDE_413))).isTrue();
        assertThat(VideoGrandi.rifiutatoPerDimensione(
                new IllegalStateException("copia", new RcloneNonRiesce("413 Payload Too Large")))).isTrue();
        // Gli altri errori restano errori.
        assertThat(VideoGrandi.rifiutatoPerDimensione(new RcloneNonRiesce("operations/copyfile: Request cancelled")))
                .isFalse();
        assertThat(VideoGrandi.rifiutatoPerDimensione(new RcloneNonRiesce(
                "operations/list: couldn't list files: Invalid 'access_token' (2094)"))).isFalse();
        assertThat(VideoGrandi.rifiutatoPerDimensione(new RcloneNonRiesce("copia di VID_413.mp4: 500 Internal Error")))
                .isFalse();
        assertThat(VideoGrandi.rifiutatoPerDimensione(new IllegalStateException("copia incompleta"))).isFalse();
    }

    @Test
    void iDueVideoDiOggiNonSonoPiuErroriEVannoInCoda() {
        // Uno oltre la soglia (non si prova nemmeno), uno sotto che il cloud rifiuta col 413 di nginx.
        rclone.pcloud.put(XIAOMI + "20260301_195506_293.mp4", 5 * MB);
        rclone.pcloud.put(XIAOMI + "20260301_182645_998.mp4", MB + 1);
        rclone.pcloud.put(XIAOMI + "IMG_20260301_120000.jpg", 1000L);
        rclone.rifiutaOltre = MB;
        rclone.rifiuto = TROPPO_GRANDE_413.substring("operations/copyfile: ".length());
        // Se una prova di prima ha lasciato un pezzo nella cartella automatica, si toglie: non va importato.
        rclone.lifetime.put("telefono/Xiaomi 25080RABDG - DCIM - Camera - 20260301_195506_293.mp4", 4000L);

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.errori()).isZero();
        assertThat(giro.aMetaTolte()).isEqualTo(1);
        assertThat(giro.copiati()).isEqualTo(1);
        assertThat(giro.inRicompressione()).isEqualTo(2);
        assertThat(giro.videoGrandi()).hasSize(2).allMatch(r -> r.contains("in coda per la ricompressione"));
        // Il video oltre la soglia non si copia; quello rifiutato una volta sola, senza seconda prova.
        assertThat(rclone.chiamate("operations/copyfile")).extracting(p -> p.get("srcRemote"))
                .containsExactlyInAnyOrder(XIAOMI + "20260301_182645_998.mp4", XIAOMI + "IMG_20260301_120000.jpg");
        assertThat(mongo.findAll(VideoGrande.class))
                .extracting(VideoGrande::percorso, VideoGrande::stato, v -> v.motivo().contains("413")
                        || v.motivo().contains("oltre i 2 MB"))
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Xiaomi 25080RABDG/DCIM/Camera/20260301_195506_293.mp4",
                                Stato.IN_CODA, true),
                        org.assertj.core.groups.Tuple.tuple("Xiaomi 25080RABDG/DCIM/Camera/20260301_182645_998.mp4",
                                Stato.IN_CODA, true));
        // Niente nel registro: non sono "già copiati", e niente a metà nella cartella automatica.
        assertThat(mongo.findAll(CopiaTelefono.class)).extracting(CopiaTelefono::percorso)
                .containsExactly("Xiaomi 25080RABDG/DCIM/Camera/IMG_20260301_120000.jpg");
        assertThat(rclone.lifetime).containsOnlyKeys(
                "telefono/Xiaomi 25080RABDG - DCIM - Camera - IMG_20260301_120000.jpg");

        // Il giro dopo non riprova la copia: sono in mano alla ricompressione.
        rclone.chiamate.clear();
        Giro dopo = sincronizzazione.sincronizza(marco);
        assertThat(dopo.esito()).isEqualTo(Esito.OK);
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(mongo.count(new Query(), VideoGrande.class)).isEqualTo(2);

        // Ricompressi e caricati col nome dell'originale (più piccoli): il giro non li scambia per copie a metà.
        finto.dimensioni.put(24, 100_000L);
        videoGrandi.lavoraTutti();
        assertThat(mongo.findAll(VideoGrande.class)).extracting(VideoGrande::stato)
                .containsOnly(Stato.CARICATO);
        rclone.chiamate.clear();
        Giro caricati = sincronizzazione.sincronizza(marco);
        assertThat(caricati.aMetaTolte()).isZero();
        assertThat(caricati.esito()).isEqualTo(Esito.OK);
        assertThat(rclone.chiamate("operations/deletefile")).isEmpty();
        assertThat(rclone.lifetime).containsKeys(
                "telefono/Xiaomi 25080RABDG - DCIM - Camera - 20260301_195506_293.mp4",
                "telefono/Xiaomi 25080RABDG - DCIM - Camera - 20260301_182645_998.mp4");
    }

    @Test
    void ancheLErroreFileTooLargeDiLifetimeVaInCoda() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", MB + 1);
        rclone.rifiutaOltre = MB;

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.errori()).isZero();
        assertThat(unico().motivo()).contains("File too large");
        // Una foto troppo grande (non un video) resta un errore: non si ricomprime.
        rclone.pcloud.put(XIAOMI + "panorama.jpg", MB + 2);
        assertThat(sincronizzazione.sincronizza(marco).messaggiErrori()).singleElement().asString()
                .contains("panorama.jpg", "File too large");
    }

    @Test
    void ricompressoImportatoEOriginaleSpostatoNellaCartellaDedicata() throws Exception {
        assumeTrue(ffmpegVero.videoDisponibile(), "servono ffmpeg e ffprobe");
        prepara(ffmpegVero);
        Path video = videoDiProva(lavoro.resolve("grezzo.mp4"));
        long dimensione = Files.size(video);
        assertThat(dimensione).isGreaterThan(2 * MB);
        String percorso = XIAOMI + "20260301_195506_293.mp4";
        rclone.pcloud.put(percorso, dimensione);
        rclone.contenuti.put(percorso, video);

        sincronizzazione.sincronizza(marco);
        videoGrandi.lavoraTutti();

        VideoGrande v = unico();
        assertThat(v.stato()).isEqualTo(Stato.CARICATO);
        assertThat(v.crf()).isEqualTo(24);
        assertThat(v.dimensioneRicompresso()).isLessThan(2 * MB).isLessThan(dimensione);
        String destinazione = "telefono/Xiaomi 25080RABDG - DCIM - Camera - 20260301_195506_293.mp4";
        assertThat(v.destinazione()).isEqualTo(destinazione);
        assertThat(rclone.lifetime).containsEntry(destinazione, v.dimensioneRicompresso());
        assertThat(mongo.findAll(CopiaTelefono.class)).singleElement().satisfies(c -> {
            assertThat(c.ricompresso()).isTrue();
            assertThat(c.dimensione()).isEqualTo(dimensione);
            assertThat(c.destinazione()).isEqualTo(destinazione);
        });
        // Il disco del server torna vuoto.
        try (Stream<Path> dentro = Files.list(lavoro)) {
            assertThat(dentro.map(p -> p.getFileName().toString())).containsExactly("grezzo.mp4");
        }
        // Prima dell'importazione l'originale resta dov'è.
        Giro inAttesa = sincronizzazione.sincronizza(marco);
        assertThat(inAttesa.ricompressi()).isZero();
        assertThat(inAttesa.videoGrandi()).singleElement().asString().contains("aspetta l'importazione");
        assertThat(rclone.chiamate("operations/movefile")).isEmpty();
        assertThat(rclone.lifetime).containsKey(destinazione);

        // L'importazione automatica lo prende come un video qualunque del telefono.
        Path cartella = montaggio.resolve("telefono");
        var esito = importazione.importa(cartella, true, true, Avanzamento.NESSUNO, sincronizzazione::caricataDa);
        assertThat(esito.importate()).isEqualTo(1);
        Foto foto = fotoRepository.findAll().getFirst();
        assertThat(foto.getNomeOriginale()).isEqualTo("Xiaomi 25080RABDG - DCIM - Camera - 20260301_195506_293.mp4");
        assertThat(foto.getCaricataDa()).isEqualTo("marco");
        assertThat(foto.isVideo()).isTrue();
        assertThat(foto.getGiorno()).isEqualTo("2026-03-01");
        assertThat(foto.getHash()).isEqualTo(v.hash());
        rclone.lifetime.remove(destinazione);

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.ricompressi()).isEqualTo(1);
        assertThat(giro.videoGrandi()).singleElement().asString()
                .contains("20260301_195506_293.mp4: ricompresso", "→", "CRF 24",
                        "originale in pcloud:Video originali troppo grandi/20260301_195506_293.mp4");
        assertThat(rclone.chiamate("operations/movefile")).containsExactly(Map.of(
                "srcFs", "pcloud:", "srcRemote", percorso,
                "dstFs", "pcloud:", "dstRemote", "Video originali troppo grandi/20260301_195506_293.mp4"));
        assertThat(rclone.pcloud).containsEntry("Video originali troppo grandi/20260301_195506_293.mp4", dimensione)
                .doesNotContainKey(percorso);
        assertThat(unico().stato()).isEqualTo(Stato.FATTO);
        assertThat(unico().originaleIn()).isEqualTo("pcloud:Video originali troppo grandi/20260301_195506_293.mp4");
        nienteCancellatoDaPcloud();
        // Si vede nell'elenco del telefono, con prima e dopo.
        assertThat(sincronizzazione.stato(sincronizzazione.sorgente(marco).orElseThrow()).videoGrandi())
                .singleElement().satisfies(vista -> {
                    assertThat(vista.stato()).isEqualTo(Stato.FATTO);
                    assertThat(vista.dimensione()).isEqualTo(dimensione);
                    assertThat(vista.dimensioneRicompresso()).isEqualTo(v.dimensioneRicompresso());
                });
        // Il giro dopo non lo rivede: né da ricopiare né da rispostare.
        rclone.chiamate.clear();
        Giro ancora = sincronizzazione.sincronizza(marco);
        assertThat(ancora.ricompressi()).isZero();
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(rclone.chiamate("operations/movefile")).isEmpty();
    }

    @Test
    void ilRicompressoConservaDurataEDataDiRipresa() throws Exception {
        assumeTrue(ffmpegVero.videoDisponibile(), "servono ffmpeg e ffprobe");
        Path video = videoDiProva(lavoro.resolve("grezzo.mp4"));
        Path uscita = lavoro.resolve("uscita.mp4");

        ffmpegVero.ricomprimi(video, uscita, 24, "fast", 1, VideoGrandiProperties.PARAMETRI_X265, 0, s -> {
        }, () -> false);

        var prima = ffmpegVero.leggiVideo(video, java.time.ZoneOffset.UTC);
        var dopo = ffmpegVero.leggiVideo(uscita, java.time.ZoneOffset.UTC);
        assertThat(dopo.codecVideo()).isEqualTo("hevc");
        assertThat(dopo.codecAudio()).isEqualTo("aac");
        assertThat(dopo.ripresoIl()).isEqualTo(prima.ripresoIl()).isNotNull();
        assertThat(dopo.durata()).isCloseTo(prima.durata(), org.assertj.core.data.Offset.offset(0.1));
        assertThat(Files.size(uscita)).isLessThan(Files.size(video));
    }

    @Test
    void seLaRicompressioneNonRiesceNienteSiCancellaNeSiSposta() {
        sincronizzazione.salva(marco, new Modifica(null, null, null, null, null, 7, null));
        String percorso = XIAOMI + "VID_1.mp4";
        rclone.pcloud.put(percorso, 5 * MB);
        finto.fallisce = true;
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        assertThat(unico().stato()).isEqualTo(Stato.ERRORE);
        assertThat(unico().messaggio()).contains("ffmpeg è uscito con 1");
        assertThat(caricamentiDalDisco()).isEmpty();
        // Un errore vero resta un errore a ogni giro, anche dopo i giorni per cancellare.
        orologio.avanti(Duration.ofDays(30));
        Giro giro = sincronizzazione.sincronizza(marco);
        assertThat(giro.esito()).isEqualTo(Esito.ERRORE);
        assertThat(giro.messaggiErrori()).singleElement().asString()
                .contains("VID_1.mp4", "ricompressione non riuscita", "ffmpeg");
        assertThat(rclone.pcloud).containsEntry(percorso, 5 * MB);
        assertThat(rclone.chiamate("operations/movefile")).isEmpty();
        nienteCancellatoDaPcloud();
        assertThat(mongo.count(new Query(), CopiaTelefono.class)).isZero();

        // "Riprova": di nuovo in coda, e questa volta va.
        finto.fallisce = false;
        assertThat(videoGrandi.riprova(unico().id())).isTrue();
        videoGrandi.lavoraTutti();
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);
    }

    @Test
    void unaDurataDiversaEUnErroreENonSiCarica() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        finto.durataRicompresso = 7.5;
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        VideoGrande v = unico();
        assertThat(v.stato()).isEqualTo(Stato.ERRORE);
        assertThat(v.messaggio()).contains("durata", "7,5 s", "10,0 s");
        assertThat(caricamentiDalDisco()).isEmpty();
        assertThat(rclone.lifetime).isEmpty();
        assertThat(rclone.pcloud).containsKey(XIAOMI + "VID_1.mp4");
        nienteCancellatoDaPcloud();
    }

    @Test
    void senzaSpazioSulDiscoSiRimandaESiRiprovaDopo() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        sincronizzazione.sincronizza(marco);
        spazio = 6 * MB;

        videoGrandi.lavoraTutti();

        VideoGrande v = unico();
        assertThat(v.stato()).isEqualTo(Stato.RIMANDATO);
        assertThat(v.messaggio()).contains("spazio insufficiente", "servono", "liberi 6 MB");
        assertThat(v.riprovaIl()).isEqualTo(orologio.instant().plus(Duration.ofHours(1)));
        // Non ha nemmeno cominciato a scaricare.
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        // Si vede nel giro, ma non è un errore.
        Giro giro = sincronizzazione.sincronizza(marco);
        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.videoGrandi()).singleElement().asString().contains("rimandato", "spazio insufficiente");

        // Lo spazio che manca non diventa un errore: riprova ogni ora, anche dopo molte volte.
        for (int i = 0; i < 8; i++) {
            orologio.avanti(Duration.ofMinutes(61));
            videoGrandi.lavoraTutti();
        }
        assertThat(unico().stato()).isEqualTo(Stato.RIMANDATO);
        assertThat(unico().tentativi()).isZero();

        // Prima dell'ora non riprova; dopo, con lo spazio, sì.
        spazio = Long.MAX_VALUE;
        videoGrandi.lavoraTutti();
        assertThat(unico().stato()).isEqualTo(Stato.RIMANDATO);
        orologio.avanti(Duration.ofMinutes(61));
        videoGrandi.lavoraTutti();
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);
    }

    @Test
    void unNomeGiaPresenteNellaCartellaDedicataPrendeIlNumero() {
        String percorso = XIAOMI + "VID_1.mp4";
        rclone.pcloud.put(percorso, 5 * MB);
        rclone.pcloud.put("Video originali troppo grandi/VID_1.mp4", 7 * MB);
        sincronizzazione.sincronizza(marco);
        videoGrandi.lavoraTutti();
        importato(unico());

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.ricompressi()).isEqualTo(1);
        assertThat(rclone.pcloud)
                .containsEntry("Video originali troppo grandi/VID_1.mp4", 7 * MB)
                .containsEntry("Video originali troppo grandi/VID_1 (2).mp4", 5 * MB)
                .doesNotContainKey(percorso);
        assertThat(unico().originaleIn()).isEqualTo("pcloud:Video originali troppo grandi/VID_1 (2).mp4");
        nienteCancellatoDaPcloud();
    }

    @Test
    void unVideoCheVuolePiuMemoriaDiQuellaConcessaEUnErroreCheLoDice() {
        rclone.pcloud.put(XIAOMI + "VID_8K.mp4", 5 * MB);
        finto.originale = new FormatoVideo(null, 7680, 4320, "yuv420p");
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        assertThat(unico().stato()).isEqualTo(Stato.ERRORE);
        assertThat(unico().messaggio()).contains("7680×4320", "FOTOTIMELINE_MEMORIA_APP", "Riprova");
        assertThat(finto.crf).isEmpty();
        // Un 4K a 8 bit sta nei 1100 MB predefiniti, uno a 10 bit no.
        assertThat(new FormatoVideo(1.0, 3840, 2160, "yuv420p").memoriaX265()).isLessThan(1100 * MB);
        assertThat(new FormatoVideo(1.0, 3840, 2160, "yuv420p10le").memoriaX265()).isGreaterThan(1100 * MB);
    }

    @Test
    void ancoraTroppoGrandeSiRiprovaConUnCrfPiuAlto() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        finto.dimensioni.put(24, 3 * MB);
        finto.dimensioni.put(28, MB);
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        assertThat(finto.crf).containsExactly(24, 28);
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);
        assertThat(unico().crf()).isEqualTo(28);
        assertThat(rclone.lifetime).containsValue(MB);
    }

    @Test
    void troppoGrandeAncheColCrfMassimoEUnErrore() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        for (int crf = 0; crf <= 51; crf++) {
            finto.dimensioni.put(crf, 3 * MB);
        }
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        assertThat(finto.crf).containsExactly(24, 28, 32);
        assertThat(unico().stato()).isEqualTo(Stato.ERRORE);
        assertThat(unico().messaggio()).contains("ancora troppo grande", "CRF 32");
        assertThat(caricamentiDalDisco()).isEmpty();
    }

    @Test
    void seIlCloudRifiutaAncheIlRicompressoSiScendeSotto() {
        // Il cloud rifiuta sopra 1,5 MB: il ricompresso da 1,8 MB no, quello da 1 MB sì.
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        rclone.rifiutaOltre = 3 * MB / 2;
        finto.dimensioni.put(24, 18 * MB / 10);
        finto.dimensioni.put(28, MB);
        sincronizzazione.sincronizza(marco);

        videoGrandi.lavoraTutti();

        assertThat(finto.crf).containsExactly(24, 28);
        assertThat(caricamentiDalDisco()).hasSize(2);
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);
        assertThat(rclone.lifetime).containsOnlyKeys(unico().destinazione()).containsValue(MB);
    }

    @Test
    void unRicompressoSparitoSenzaSchedaNonToccaLOriginale() {
        sincronizzazione.salva(marco, new Modifica(null, null, null, null, null, 7, null));
        String percorso = XIAOMI + "VID_1.mp4";
        rclone.pcloud.put(percorso, 5 * MB);
        sincronizzazione.sincronizza(marco);
        videoGrandi.lavoraTutti();
        // Tolto dalla cartella automatica a mano, senza importarlo.
        rclone.lifetime.clear();

        // Subito: forse l'importazione sta salvando la scheda, si aspetta.
        Giro subito = sincronizzazione.sincronizza(marco);
        assertThat(subito.esito()).isEqualTo(Esito.OK);
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);

        // Dopo i giorni per cancellare: né cancellato (è un ricompresso) né spostato, ed è un errore.
        orologio.avanti(Duration.ofDays(8));
        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.cancellati()).isZero();
        assertThat(giro.esito()).isEqualTo(Esito.ERRORE);
        assertThat(giro.messaggiErrori()).singleElement().asString().contains("l'originale resta dov'è");
        assertThat(unico().stato()).isEqualTo(Stato.ERRORE);
        assertThat(rclone.pcloud).containsEntry(percorso, 5 * MB);
        assertThat(rclone.chiamate("operations/movefile")).isEmpty();
        nienteCancellatoDaPcloud();

        // "Riprova" lo rifà da capo: la voce del registro di prima si aggiorna, non si duplica.
        assertThat(videoGrandi.riprova(unico().id())).isTrue();
        videoGrandi.lavoraTutti();
        assertThat(unico().stato()).isEqualTo(Stato.CARICATO);
        assertThat(mongo.findAll(CopiaTelefono.class)).singleElement().satisfies(c -> {
            assertThat(c.ricompresso()).isTrue();
            assertThat(c.cancellatoIl()).isNull();
            assertThat(c.destinazione()).isEqualTo(unico().destinazione());
            assertThat(c.idSorgente()).isEqualTo(marco);
        });
        assertThat(rclone.lifetime).containsKey(unico().destinazione());
    }

    @Test
    void laCartellaDegliOriginaliNonSiSincronizza() {
        // Un telefono che guarda tutto pCloud: la cartella dedicata ci sta dentro, e si salta.
        sincronizzazione.salva(marco, new Modifica(null, null, null, "pcloud:", null, null, null));
        rclone.pcloud.put("Video originali troppo grandi/VID_1.mp4", 5 * MB);
        rclone.pcloud.put("Video originali troppo grandi/vecchi/VID_0.mp4", 5 * MB);
        rclone.pcloud.put("Camera/IMG_1.jpg", 100L);

        Giro giro = sincronizzazione.sincronizza(marco);

        assertThat(giro.trovati()).isEqualTo(1);
        assertThat(giro.copiati()).isEqualTo(1);
        assertThat(mongo.count(new Query(), VideoGrande.class)).isZero();
        assertThat(rclone.chiamate("operations/copyfile")).extracting(p -> p.get("srcRemote"))
                .containsExactly("Camera/IMG_1.jpg");
    }

    @Test
    void annullatoNonRipartePiuFinoARiprova() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        sincronizzazione.sincronizza(marco);

        assertThat(videoGrandi.annulla(unico().id())).isTrue();
        videoGrandi.lavoraTutti();

        assertThat(unico().stato()).isEqualTo(Stato.ANNULLATO);
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        Giro giro = sincronizzazione.sincronizza(marco);
        assertThat(giro.esito()).isEqualTo(Esito.OK);
        assertThat(giro.videoGrandi()).singleElement().asString().contains("annullato");
        assertThat(videoGrandi.riprova(unico().id())).isTrue();
        assertThat(unico().stato()).isEqualTo(Stato.IN_CODA);
    }

    @Test
    void siAnnullaAncheMentreScarica() throws Exception {
        String percorso = XIAOMI + "VID_1.mp4";
        rclone.pcloud.put(percorso, 5 * MB);
        rclone.lenti.add(percorso);
        sincronizzazione.sincronizza(marco);
        Thread coda = Thread.ofPlatform().start(videoGrandi::lavoraTutti);
        for (int i = 0; i < 400 && rclone.chiamate("job/status").isEmpty(); i++) {
            Thread.sleep(10);
        }
        assertThat(unico().stato()).isEqualTo(Stato.IN_CORSO);
        assertThat(sincronizzazione.stato(sincronizzazione.sorgente(marco).orElseThrow()).videoGrandi())
                .singleElement().satisfies(v -> assertThat(v.fase()).contains("scarico"));

        assertThat(videoGrandi.annulla(unico().id())).isTrue();
        coda.join(5000);

        assertThat(coda.isAlive()).isFalse();
        assertThat(unico().stato()).isEqualTo(Stato.ANNULLATO);
        assertThat(rclone.chiamate("job/stop")).hasSize(1);
        assertThat(rclone.pcloud).containsKey(percorso);
        try (Stream<Path> dentro = Files.list(lavoro)) {
            assertThat(dentro).isEmpty();
        }
        assertThat(finto.crf).isEmpty();
    }

    @Test
    void unVideoToltoDaPcloudPrimaDellaRicompressioneEsceDallaCoda() {
        rclone.pcloud.put(XIAOMI + "VID_1.mp4", 5 * MB);
        sincronizzazione.sincronizza(marco);
        rclone.pcloud.clear();

        sincronizzazione.sincronizza(marco);

        assertThat(mongo.count(new Query(), VideoGrande.class)).isZero();
    }

    /** Come se l'importazione automatica l'avesse preso: una scheda con il suo SHA-256, fuori dalla cartella automatica. */
    private void importato(VideoGrande v) {
        Foto foto = new Foto();
        foto.setNomeOriginale(v.nome());
        foto.setPercorso("2026/03/01/" + v.nome());
        foto.setHash(v.hash());
        foto.setCaricataIl(Instant.now());
        fotoRepository.save(foto);
        rclone.lifetime.remove(v.destinazione());
    }

    /** Un video da 4 secondi senza perdite (grande, si comprime bene), con la data di ripresa nei metadati. */
    private static Path videoDiProva(Path file) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("ffmpeg", "-y", "-v", "error",
                "-f", "lavfi", "-i", "testsrc2=size=640x480:rate=25",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
                "-t", "4", "-c:v", "libx264", "-qp", "0", "-preset", "ultrafast", "-c:a", "aac",
                "-metadata", "creation_time=2026-03-01T18:55:06.000000Z", file.toString())
                .redirectErrorStream(true).start();
        String uscita = new String(p.getInputStream().readAllBytes());
        assertThat(p.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(uscita).isZero();
        return file;
    }

    /**
     * ffmpeg e ffprobe finti: il "ricompresso" è un file della dimensione
     * scelta per quel CRF (predefinita 1 MB), le durate quelle dei campi.
     */
    static final class FintoMedia extends StrumentiMedia {
        final Map<Integer, Long> dimensioni = new HashMap<>();
        final List<Integer> crf = new ArrayList<>();
        double durataOriginale = 10;
        double durataRicompresso = 10;
        FormatoVideo originale = new FormatoVideo(null, 3840, 2160, "yuv420p");
        boolean fallisce;

        FintoMedia() {
            super(new MediaProperties(null, null, null));
        }

        @Override
        public boolean videoDisponibile() {
            return true;
        }

        @Override
        public void ricomprimi(Path video, Path mp4, int crf, String preset, int thread, String parametriX265,
                long limite, DoubleConsumer secondiFatti, BooleanSupplier annullata) throws IOException {
            this.crf.add(crf);
            if (fallisce) {
                throw new IOException("ffmpeg è uscito con 1: Conversion failed!");
            }
            try (var f = new RandomAccessFile(mp4.toFile(), "rw")) {
                f.setLength(dimensioni.getOrDefault(crf, MB));
            }
        }

        @Override
        public FormatoVideo formato(Path video) {
            boolean primo = video.getFileName().toString().startsWith("originale");
            return new FormatoVideo(primo ? durataOriginale : durataRicompresso, originale.larghezza(),
                    originale.altezza(), originale.pixel());
        }
    }
}
