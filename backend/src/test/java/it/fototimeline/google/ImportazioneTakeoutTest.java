package it.fototimeline.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.manyTimes;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

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
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.google.ImportazioneTakeout.Avanzamento;
import it.fototimeline.google.ImportazioneTakeout.Fase;
import it.fototimeline.google.ImportazioneTakeout.Modifica;
import it.fototimeline.google.ZipTakeout.StatoZip;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;

/**
 * Il lavoro "Importa da Google Takeout" su zip veri generati qui, con un
 * rclone finto che tiene Google Drive in una mappa e "scarica" copiando il
 * file nella cartella temporanea, e l'archivio vero su disco.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class ImportazioneTakeoutTest {

    private static final CloudProperties CLOUD = new CloudProperties("http://rclone:5572", "fototimeline",
            "password-di-prova", "lifetime:", "/cloud", false, 0);
    private static final String FOTO = "Takeout/Google Foto/";

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @TempDir
    Path lavoro;

    @Autowired
    MongoTemplate mongo;
    @Autowired
    FotoService fotoService;
    @Autowired
    FotoRepository repository;

    private FintoRclone rclone;
    private Orologio orologio;
    private final AtomicBoolean cloudMontato = new AtomicBoolean(true);
    private Path cartella;
    private Path drive;

    @BeforeEach
    void prepara() throws IOException {
        mongo.remove(new Query(), ZipTakeout.class);
        mongo.remove(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id")
                .is(ImpostazioniTakeout.ID)), ImpostazioniTakeout.class);
        repository.findAll().forEach(f -> fotoService.elimina(f.getId()));
        cartella = lavoro.resolve("takeout");
        drive = Files.createDirectories(lavoro.resolve("drive"));
        orologio = new Orologio(Instant.parse("2026-05-01T10:00:00Z"));
        cloudMontato.set(true);
    }

    private ImportazioneTakeout takeout(DataSize riserva) {
        var builder = RestClient.builder();
        rclone = new FintoRclone(builder, drive);
        var properties = new TakeoutProperties("gdrive:Takeout", cartella, null, riserva, Duration.ofMillis(10),
                null);
        return new ImportazioneTakeout(new Rclone(CLOUD, builder), CLOUD, properties, cloudMontato::get, fotoService,
                mongo, orologio, Duration.ofMillis(5));
    }

    private ImportazioneTakeout takeout() {
        return takeout(DataSize.ofBytes(0));
    }

    // ------------------------------------------------------------ i casi

    @Test
    void unoZipConTuttiICasiStrani() throws IOException {
        // Un album che l'app ha già, scritto in un altro modo: si riusa.
        fotoService.importa("vecchia.png", png(Color.GRAY), null, "VACANZE AL MARE", "marco");
        byte[] img1 = jpg(Color.RED);
        String anno = FOTO + "Photos from 2019/";
        Map<String, Object> voci = new LinkedHashMap<>();
        voci.put("Takeout/archive_browser.html", "<html></html>");
        voci.put(anno + "IMG_0001.jpg", img1);
        voci.put(anno + "IMG_0001.jpg.supplemental-metadata.json",
                json("2019-08-15T10:00:00Z", 41.9028, 12.4964, "Al mare", false));
        voci.put(anno + "IMG_0002.jpg", jpg(Color.GREEN));
        voci.put(anno + "IMG_0002.jpg.json", json("2019-08-16T08:00:00Z", 0, 0, null, false));
        voci.put(anno + "IMG_0002(1).jpg", jpg(Color.BLUE));
        voci.put(anno + "IMG_0002.jpg(1).json", json("2019-08-16T09:00:00Z", 0, 0, null, true));
        voci.put(anno + "IMG_0002-edited.jpg", jpg(Color.YELLOW));
        voci.put(anno + "senza.jpg", new Datata(jpg(Color.CYAN), Instant.parse("2018-05-05T12:00:00Z")));
        voci.put(anno + "rotta.jpg", "non sono una foto".getBytes(StandardCharsets.UTF_8));
        voci.put(anno + "rotta.jpg.json", json("2019-01-01T00:00:00Z", 0, 0, null, false));
        voci.put(anno + "film.avi", new byte[] {1, 2, 3});
        voci.put(FOTO + "Vacanze/metadata.json", "{\"title\": \"Vacanze al mare\"}");
        voci.put(FOTO + "Vacanze/IMG_0001.jpg", img1);
        voci.put(FOTO + "Vacanze/IMG_0001.jpg.json", json("2019-08-15T10:00:00Z", 0, 0, null, false));
        voci.put(FOTO + "Vacanze/IMG_0003.jpg", jpg(Color.MAGENTA));
        voci.put(FOTO + "Vacanze/IMG_0003.jpg.supplemental-metadata.json",
                json("2020-01-01T12:00:00Z", 45.46, 9.19, null, false));
        zip("takeout-20260501T100000Z-001.zip", voci);

        ImportazioneTakeout takeout = takeout();
        Avanzamento fine = takeout.esegui("marco");

        assertThat(fine.fase()).isEqualTo(Fase.FINITO);
        assertThat(fine.zipTotali()).isEqualTo(1);
        assertThat(fine.fileTotali()).isEqualTo(8);
        assertThat(fine.nuove()).isEqualTo(6);
        assertThat(fine.giaPresenti()).isEqualTo(1);
        assertThat(fine.senzaJson()).isEqualTo(1);
        assertThat(fine.saltati()).isEqualTo(1);
        assertThat(fine.errori()).isEqualTo(1);
        assertThat(fine.messaggi()).singleElement().asString().contains("rotta.jpg");

        ZipTakeout z = mongo.findOne(new Query(), ZipTakeout.class);
        assertThat(z.stato()).isEqualTo(StatoZip.CON_ERRORI);
        assertThat(z.nome()).isEqualTo("takeout-20260501T100000Z-001.zip");
        assertThat(z.fatti()).isEqualTo(8);
        assertThat(z.messaggi()).anyMatch(m -> m.contains("film.avi"));

        Foto uno = foto("IMG_0001.jpg");
        assertThat(uno.getScattataIl()).isEqualTo(LocalDateTime.of(2019, 8, 15, 10, 0));
        assertThat(uno.getOrigineData()).isEqualTo(OrigineData.GOOGLE);
        assertThat(uno.getPercorso()).startsWith("2019/08/15/");
        assertThat(uno.getLatitudine()).isEqualTo(41.9028);
        assertThat(uno.getDescrizione()).isEqualTo("Al mare");
        assertThat(uno.getCaricataDa()).isEqualTo("marco");
        assertThat(uno.getAlbum()).isNull();
        assertThat(uno.isPreferita()).isFalse();

        Foto numerata = foto("IMG_0002(1).jpg");
        assertThat(numerata.getScattataIl()).isEqualTo(LocalDateTime.of(2019, 8, 16, 9, 0));
        assertThat(numerata.isPreferita()).isTrue();
        assertThat(foto("IMG_0002.jpg").getLatitudine()).isNull();
        assertThat(foto("IMG_0002-edited.jpg").getScattataIl()).isEqualTo(LocalDateTime.of(2019, 8, 16, 8, 0));

        Foto album = foto("IMG_0003.jpg");
        assertThat(album.getAlbum()).isEqualTo("VACANZE AL MARE");
        assertThat(album.getLongitudine()).isEqualTo(9.19);

        Foto senza = foto("senza.jpg");
        assertThat(senza.getOrigineData()).isEqualTo(OrigineData.FILE);
        assertThat(senza.getGiorno()).isEqualTo("2018-05-05");

        // Scaricato con rclone (asincrono) nella cartella sul disco, poi tolto.
        assertThat(rclone.chiamate("operations/copyfile")).singleElement().satisfies(p -> {
            assertThat(p).containsEntry("srcFs", "gdrive:")
                    .containsEntry("srcRemote", "Takeout/takeout-20260501T100000Z-001.zip")
                    .containsEntry("dstFs", cartella.toString())
                    .containsEntry("_async", true);
        });
        try (Stream<Path> s = Files.list(cartella)) {
            assertThat(s.toList()).isEmpty();
        }

        // Il secondo giro non lo rifà.
        rclone.chiamate.clear();
        assertThat(takeout.esegui("marco").zipTotali()).isZero();
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(takeout.stato().registro()).hasSize(1);

        // "Riprova": tolto dal registro, si rifà; le foto entrate sono doppioni.
        takeout.dimentica(z.id());
        Avanzamento ancora = takeout.esegui("marco");
        assertThat(ancora.nuove()).isZero();
        assertThat(ancora.giaPresenti()).isEqualTo(7);
    }

    @Test
    void riparteDalFileACuiEraArrivatoEPoiLoTogleDaDrive() throws IOException {
        Map<String, Object> voci = new LinkedHashMap<>();
        for (int i = 1; i <= 3; i++) {
            voci.put(FOTO + "Photos from 2020/IMG_" + i + ".jpg", jpg(new Color(40 * i, 10, 200)));
            voci.put(FOTO + "Photos from 2020/IMG_" + i + ".jpg.json", json("2020-02-0" + i + "T10:00:00Z", 0, 0, null, false));
        }
        zip("takeout-001.zip", voci);
        ImportazioneTakeout takeout = takeout();
        takeout.salva(new Modifica(null, "anna", 3));

        takeout.annullaAlFile(1);
        Avanzamento prima = takeout.esegui("marco");
        assertThat(prima.fase()).isEqualTo(Fase.ANNULLATO);
        assertThat(prima.nuove()).isEqualTo(1);
        ZipTakeout aMeta = mongo.findOne(new Query(), ZipTakeout.class);
        assertThat(aMeta.stato()).isEqualTo(StatoZip.IN_CORSO);
        assertThat(aMeta.fatti()).isEqualTo(1);

        takeout.annullaAlFile(-1);
        Avanzamento dopo = takeout.esegui("marco");
        assertThat(dopo.fase()).isEqualTo(Fase.FINITO);
        assertThat(dopo.nuove()).isEqualTo(3);
        assertThat(dopo.giaPresenti()).isZero();
        assertThat(repository.findAll()).hasSize(3).allSatisfy(f -> assertThat(f.getCaricataDa()).isEqualTo("anna"));
        assertThat(mongo.findOne(new Query(), ZipTakeout.class).stato()).isEqualTo(StatoZip.FATTO);
        assertThat(takeout.impostazioni().richiesto()).isFalse();

        // Prima dei 3 giorni resta su Drive, dopo si toglie.
        takeout.controllaDaTogliere();
        assertThat(Files.exists(drive.resolve("takeout-001.zip"))).isTrue();
        orologio.avanti(Duration.ofDays(4));
        takeout.controllaDaTogliere();
        assertThat(Files.exists(drive.resolve("takeout-001.zip"))).isFalse();
        assertThat(mongo.findOne(new Query(), ZipTakeout.class).cancellatoIl()).isNotNull();
    }

    @Test
    void senzaSpazioSiFermaConUnMessaggioChiaro() throws IOException {
        zip("takeout-001.zip", Map.of(FOTO + "Photos from 2020/IMG_1.jpg", jpg(Color.RED)));
        ImportazioneTakeout takeout = takeout(DataSize.ofTerabytes(10_000));

        Avanzamento fine = takeout.esegui("marco");

        assertThat(fine.fase()).isEqualTo(Fase.FALLITO);
        assertThat(fine.errore()).contains("Spazio insufficiente").contains("takeout-001.zip");
        assertThat(rclone.chiamate("operations/copyfile")).isEmpty();
        assertThat(mongo.findOne(new Query(), ZipTakeout.class).stato()).isEqualTo(StatoZip.FALLITO);
        assertThat(takeout.impostazioni().richiesto()).isFalse();
        assertThat(repository.count()).isZero();
    }

    @Test
    void colCloudSmontatoAspetta() throws Exception {
        zip("takeout-001.zip", Map.of(FOTO + "Photos from 2020/IMG_1.jpg", jpg(Color.RED),
                FOTO + "Photos from 2020/IMG_1.jpg.json", json("2020-02-01T10:00:00Z", 0, 0, null, false)));
        ImportazioneTakeout takeout = takeout();
        cloudMontato.set(false);
        List<Avanzamento> fine = new ArrayList<>();
        Thread t = new Thread(() -> fine.add(takeout.esegui("marco")));
        t.start();
        long limite = System.currentTimeMillis() + 10_000;
        while (takeout.avanzamento().map(Avanzamento::fase).orElse(null) != Fase.ATTESA_CLOUD
                && System.currentTimeMillis() < limite) {
            Thread.sleep(10);
        }
        assertThat(takeout.avanzamento().orElseThrow().fase()).isEqualTo(Fase.ATTESA_CLOUD);
        assertThat(repository.count()).isZero();
        cloudMontato.set(true);
        t.join(10_000);
        assertThat(fine).singleElement().satisfies(a -> {
            assertThat(a.fase()).isEqualTo(Fase.FINITO);
            assertThat(a.nuove()).isEqualTo(1);
        });
    }

    @Test
    void unoZipGuastoNonFermaGliAltri() throws IOException {
        Files.writeString(drive.resolve("takeout-001.zip"), "non è uno zip");
        zip("takeout-002.zip", Map.of(FOTO + "Photos from 2020/IMG_1.jpg", jpg(Color.RED)));
        ImportazioneTakeout takeout = takeout();

        Avanzamento fine = takeout.esegui("marco");

        assertThat(fine.fase()).isEqualTo(Fase.FINITO);
        assertThat(fine.nuove()).isEqualTo(1);
        assertThat(fine.messaggi()).anyMatch(m -> m.contains("takeout-001.zip") && m.contains("zip illeggibile"));
        assertThat(takeout.riepilogo().falliti()).isEqualTo(1);
        assertThat(takeout.riepilogo().problemi()).singleElement()
                .satisfies(z -> assertThat(z.errore()).contains("illeggibile"));
    }

    // ------------------------------------------------------------ zip e immagini finte

    private Foto foto(String nome) {
        return repository.findAll().stream().filter(f -> f.getNomeOriginale().equals(nome)).findFirst().orElseThrow();
    }

    /** Un file con la sua data nello zip. */
    private record Datata(byte[] contenuto, Instant data) {
    }

    private void zip(String nome, Map<String, Object> voci) throws IOException {
        try (OutputStream file = Files.newOutputStream(drive.resolve(nome));
                ZipOutputStream zip = new ZipOutputStream(file, StandardCharsets.UTF_8)) {
            for (var v : voci.entrySet()) {
                ZipEntry e = new ZipEntry(v.getKey());
                byte[] contenuto;
                if (v.getValue() instanceof Datata d) {
                    e.setLastModifiedTime(FileTime.from(d.data()));
                    contenuto = d.contenuto();
                } else if (v.getValue() instanceof String s) {
                    contenuto = s.getBytes(StandardCharsets.UTF_8);
                } else {
                    contenuto = (byte[]) v.getValue();
                }
                zip.putNextEntry(e);
                zip.write(contenuto);
                zip.closeEntry();
            }
        }
    }

    private static String json(String scattata, double lat, double lon, String descrizione, boolean preferita) {
        long secondi = Instant.parse(scattata).getEpochSecond();
        return """
                {"title": "x", "description": "%s", "photoTakenTime": {"timestamp": "%d"},
                 "geoData": {"latitude": %s, "longitude": %s}, "favorited": %s}
                """.formatted(descrizione == null ? "" : descrizione, secondi, lat, lon, preferita);
    }

    private static byte[] jpg(Color colore) throws IOException {
        return immagine(colore, "jpg");
    }

    private static byte[] png(Color colore) throws IOException {
        return immagine(colore, "png");
    }

    private static byte[] immagine(Color colore, String formato) throws IOException {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 64, 48);
        g.setColor(Color.BLACK);
        g.fillRect(colore.getRed() % 50, colore.getGreen() % 30, 10, 10);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }

    // ------------------------------------------------------------ rclone finto

    /** Google Drive in una cartella: list, copyfile asincrono verso una cartella locale, job/status, stat, deletefile. */
    static final class FintoRclone {
        private static final ObjectMapper JSON = new ObjectMapper();

        private final Path drive;
        final List<Map<String, Object>> chiamate = new ArrayList<>();

        FintoRclone(RestClient.Builder builder, Path drive) {
            this.drive = drive;
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
                    assertThat(p).containsEntry("fs", "gdrive:").containsEntry("remote", "Takeout");
                    List<Map<String, Object>> voci = new ArrayList<>();
                    try (Stream<Path> s = Files.list(drive)) {
                        for (Path f : s.sorted().toList()) {
                            voci.add(Map.of("Path", f.getFileName().toString(), "Name", f.getFileName().toString(),
                                    "Size", Files.size(f), "ModTime", "2026-04-30T18:22:05.123456789+02:00",
                                    "IsDir", false));
                        }
                    }
                    yield Map.of("list", voci);
                }
                case "operations/copyfile" -> {
                    String da = ((String) p.get("srcRemote")).substring("Takeout/".length());
                    Path destinazione = Path.of((String) p.get("dstFs")).resolve((String) p.get("dstRemote"));
                    Files.copy(drive.resolve(da), destinazione);
                    yield Map.of("jobid", 7);
                }
                case "job/status" -> Map.of("finished", true, "success", true, "error", "");
                case "core/stats" -> Map.of("bytes", 1);
                case "operations/stat" -> {
                    Path f = drive.resolve(((String) p.get("remote")).substring("Takeout/".length()));
                    Map<String, Object> voce = new HashMap<>();
                    voce.put("item", Files.exists(f) ? Map.of("Size", Files.size(f)) : null);
                    yield voce;
                }
                case "operations/deletefile" -> {
                    Files.delete(drive.resolve(((String) p.get("remote")).substring("Takeout/".length())));
                    yield Map.of();
                }
                default -> throw new IllegalArgumentException(comando);
            };
            return withSuccess(JSON.writeValueAsString(risposta), MediaType.TEXT_PLAIN).createResponse(richiesta);
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
