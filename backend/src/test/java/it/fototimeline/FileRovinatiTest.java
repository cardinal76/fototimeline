package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.media.NonFoto;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FileScartato;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.MemoriaImportazione;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;

/**
 * I 264 errori dell'ultimo "Indicizza archivio": i file che una foto non sono
 * (zeri, AppleDouble, pagina web, vuoti) lo dicono e si contano a parte; i JPEG
 * che Java non legge (lo Xiaomi con il SOI dopo i pixel, l'aritmetico, quelli che
 * legge solo ImageMagick) entrano con una scheda normale.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class FileRovinatiTest {

    @TempDir
    static Path archivio;

    @TempDir
    Path fuori;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    StrumentiMedia media;

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    ImportazioneCartelle importazione;

    @Autowired
    MemoriaImportazione memoria;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        memoria.dimenticaTutti();
        try (Stream<Path> s = Files.list(archivio)) {
            for (Path p : s.filter(p -> !p.getFileName().toString().startsWith(".")).toList()) {
                cancellaTutto(p);
            }
        }
    }

    // ------------------------------------------------------------ file da buttare

    @Test
    void iFileCheNonSonoFotoDiconoCosaSono() throws IOException {
        assertThat(service.indicizza(zeri("2016/01/25/PICT0042.jpg", 600 * 1024)).messaggio())
                .isEqualTo("File vuoto: solo zeri, la foto non c'è più");
        assertThat(service.indicizza(scrivi("2016/01/25/foto1018._20160125_121852-1.jpg", appleDouble())).messaggio())
                .isEqualTo("File del Mac (._), non è una foto");
        assertThat(service.indicizza(scrivi("2016/01/25/6.png", pagina())).messaggio())
                .isEqualTo("Pagina web, non una foto");
        assertThat(service.indicizza(scrivi("2016/01/25/vuoto.jpg", new byte[0])).messaggio())
                .isEqualTo("File vuoto (0 byte)");
        // Anche un file da 40 MB di zeri, guardato a campione.
        Caricamento grande = service.indicizza(zeri("2016/01/25/grande.jpg", 40L * 1024 * 1024));
        assertThat(grande.esito()).isEqualTo(Esito.ERRORE);
        assertThat(grande.messaggio()).isEqualTo(NonFoto.ZERI.messaggio());
        // Sono errori del file: la cartella automatica li ricorda.
        assertThat(FotoService.erroreDelFile(NonFoto.MAC.messaggio())).isTrue();
        assertThat(repository.count()).isZero();
    }

    @Test
    void lIndicizzazioneLiContaAParteENonLiRilegge() throws Exception {
        zeri("2016/01/25/PICT0001.jpg", 600 * 1024);
        zeri("2016/01/25/PICT0002.jpg", 600 * 1024);
        scrivi("2016/01/25/foto1018._20160125_121852-1.jpg", appleDouble());
        scrivi("2016/01/25/6.png", pagina());
        scrivi("2016/01/25/vuoto.jpg", new byte[0]);
        scrivi("2016/01/25/rotta.jpg", "non è un JPEG".getBytes(StandardCharsets.UTF_8));
        scrivi("2016/01/25/buona.jpg", jpeg(64, 48));

        lavori.avviaIndicizzazione();
        StatoLavoro primo = aspettaFine();
        assertThat(primo.stato()).isEqualTo(Stato.FINITA);
        assertThat(primo.importate()).isEqualTo(1);
        assertThat(primo.errori()).isEqualTo(6);
        assertThat(primo.daButtare()).isEqualTo(5);
        assertThat(primo.veriErrori()).isEqualTo(1);
        assertThat(primo.erroriInBreve()).isEqualTo("1 errori · 5 file da buttare");
        assertThat(primo.messaggi()).anyMatch(m -> m.endsWith("PICT0001.jpg: " + NonFoto.ZERI.messaggio()))
                .anyMatch(m -> m.endsWith("6.png: " + NonFoto.PAGINA_WEB.messaggio()));
        assertThat(memoria.elenco()).extracting(FileScartato::motivo).hasSize(5).allMatch(NonFoto::daButtare);

        // Al giro dopo i file da buttare non si rileggono; la "rotta" sì, come prima.
        lavori.avviaIndicizzazione();
        StatoLavoro secondo = aspettaFine();
        assertThat(secondo.saltate()).isEqualTo(5);
        assertThat(secondo.trovate()).isEqualTo(2);
        assertThat(secondo.errori()).isEqualTo(1);
        assertThat(secondo.daButtare()).isZero();

        // Cambiato (una foto vera al posto degli zeri): si rilegge, entra, e il ricordo se ne va.
        Path cambiato = scrivi("2016/01/25/PICT0001.jpg", jpeg(80, 60));
        Files.setLastModifiedTime(cambiato, FileTime.from(Instant.now().plusSeconds(60)));
        // Buttato a mano: il ricordo se ne va anche lui.
        Files.delete(archivio.resolve("2016/01/25/6.png"));
        lavori.avviaIndicizzazione();
        StatoLavoro terzo = aspettaFine();
        assertThat(terzo.saltate()).isEqualTo(3);
        assertThat(terzo.importate()).isEqualTo(1);
        assertThat(memoria.elenco()).hasSize(3);
    }

    @Test
    void importaCartellaSaltaIFileDelMacELiContaSeRinominati() throws IOException {
        Path cartella = fuori.resolve("dal Mac");
        Files.createDirectories(cartella);
        Files.write(cartella.resolve("IMG_0001.jpg"), jpeg(64, 48));
        Files.write(cartella.resolve("._IMG_0001.jpg"), appleDouble());
        Files.write(cartella.resolve("foto7._IMG_0002.jpg"), appleDouble());
        Files.write(cartella.resolve("PICT0003.jpg"), new byte[100_000]);

        Importazione esito = importazione.importa(cartella, false, false);

        // Il "._" non si prende nemmeno; quello rinominato lo riconosce il contenuto.
        assertThat(esito.trovate()).isEqualTo(3);
        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.errori()).isEqualTo(2);
        assertThat(esito.daButtare()).isEqualTo(2);
        assertThat(esito.messaggi()).hasSize(2)
                .anyMatch(m -> m.endsWith("/foto7._IMG_0002.jpg: " + NonFoto.MAC.messaggio()))
                .anyMatch(m -> m.endsWith("/PICT0003.jpg: " + NonFoto.ZERI.messaggio()));
    }

    // ------------------------------------------------------------ JPEG difficili

    @Test
    void loXiaomiConIlSoiDopoIPixelEntraConMiniaturaEVista() throws IOException {
        // La principale senza EOI e subito la mappa Ultra HDR: le dimensioni si leggono, i pixel no.
        // Prima l'errore veniva dalla miniatura ("two SOI markers"), dove non c'era riparazione.
        byte[] principale = jpeg(640, 480);
        byte[] rotto = unisci(Arrays.copyOf(principale, principale.length - 2), jpeg(160, 120));
        String nome = "Xiaomi 25080RABDG - DCIM - Camera - IMG_20260318_213435 (6).jpg";
        Path file = scrivi("2026/03/18/" + nome, rotto);

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getLarghezza()).isEqualTo(640);
        assertThat(foto.getAltezza()).isEqualTo(480);
        assertThat(Files.readAllBytes(service.fileOriginale(foto))).isEqualTo(rotto);
        Path vista = service.fileVista(foto);
        assertThat(vista).isNotEqualTo(service.fileOriginale(foto));
        assertThat(ImageIO.read(vista.toFile()).getWidth()).isEqualTo(640);
        assertThat(ImageIO.read(service.fileMiniatura(foto).toFile()).getWidth()).isGreaterThan(160);
        assertThat(foto.getImpronta()).isNotNull();

        // Rifacendo la miniatura si rifà anche la vista.
        Files.delete(service.fileMiniatura(foto));
        Files.delete(vista);
        assertThat(ImageIO.read(service.fileMiniatura(foto).toFile())).isNotNull();
        assertThat(ImageIO.read(service.fileVista(foto).toFile()).getWidth()).isEqualTo(640);
    }

    @Test
    void unJpegAritmeticoEntraComeUnaFotoNormale() throws IOException {
        // Java lo legge solo col libjpeg di sistema (Ubuntu); col suo (Temurin, il container) no, e
        // ffmpeg nemmeno: lì lo converte ImageMagick. In tutti e due i casi una scheda normale.
        assumeTrue(media.imagemagickDisponibile() || ImageIO.read(getClass().getResource("/aritmetica.jpg")) != null,
                "né ImageMagick né un Java che legge i JPEG aritmetici");
        Path file = scrivi("2024/02/10/aritmetica.jpg", getClass().getResourceAsStream("/aritmetica.jpg").readAllBytes());

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        assertThat(esito.foto().getLarghezza()).isEqualTo(64);
        assertThat(esito.foto().getAltezza()).isEqualTo(48);
        assertThat(ImageIO.read(service.fileMiniatura(esito.foto()).toFile())).isNotNull();
        assertThat(ImageIO.read(service.fileVista(esito.foto()).toFile())).isNotNull();
    }

    @Test
    void unaFotoCheLeggeSoloImageMagickHaSchedaMiniaturaEVista() throws IOException {
        assumeTrue(media.imagemagickDisponibile(), "ImageMagick non installato");
        // Col nome .jpg un MIFF di ImageMagick: Java e ffmpeg non lo leggono.
        Path file = archivio.resolve("2024/02/11/solo-magick.jpg");
        Files.createDirectories(file.getParent());
        assumeTrue(convert("-size", "120x90", "gradient:red-blue", "miff:" + file), "niente MIFF di prova");
        byte[] prima = Files.readAllBytes(file);

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getLarghezza()).isEqualTo(120);
        assertThat(foto.getAltezza()).isEqualTo(90);
        assertThat(ImageIO.read(service.fileMiniatura(foto).toFile())).isNotNull();
        assertThat(ImageIO.read(service.fileVista(foto).toFile()).getWidth()).isEqualTo(120);
        assertThat(service.contentTypeVista(foto)).isEqualTo("image/jpeg");
        assertThat(Files.readAllBytes(service.fileOriginale(foto))).isEqualTo(prima);
    }

    // ------------------------------------------------------------ file di prova

    private static Path zeri(String percorso, long dimensione) throws IOException {
        Path p = archivio.resolve(percorso);
        Files.createDirectories(p.getParent());
        try (var f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(dimensione);
        }
        return p;
    }

    /** Un AppleDouble come quelli del Mac: 00 05 16 07, versione 2, 4096 byte. */
    private static byte[] appleDouble() {
        byte[] b = new byte[4096];
        b[1] = 0x05;
        b[2] = 0x16;
        b[3] = 0x07;
        b[5] = 0x02;
        System.arraycopy("Mac OS X        ".getBytes(StandardCharsets.US_ASCII), 0, b, 8, 16);
        return b;
    }

    private static byte[] pagina() {
        return "\n  <!DOCTYPE html>\n<html><head><title>Pagina non trovata</title></head></html>"
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jpeg(int larghezza, int altezza) throws IOException {
        var img = new BufferedImage(larghezza, altezza, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.GREEN, larghezza, altezza, Color.MAGENTA));
        g.fillRect(0, 0, larghezza, altezza);
        g.dispose();
        var uscita = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", uscita);
        return uscita.toByteArray();
    }

    private static byte[] unisci(byte[]... pezzi) {
        var b = new ByteArrayOutputStream();
        for (byte[] p : pezzi) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    private static Path scrivi(String percorso, byte[] contenuto) throws IOException {
        Path p = archivio.resolve(percorso);
        Files.createDirectories(p.getParent());
        return Files.write(p, contenuto);
    }

    private static boolean convert(String... argomenti) {
        String programma = Files.isExecutable(Path.of("/usr/bin/magick")) ? "magick" : "convert";
        try {
            var comando = new java.util.ArrayList<>(List.of(programma));
            comando.addAll(List.of(argomenti));
            Process p = new ProcessBuilder(comando).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static void cancellaTutto(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }

    private StatoLavoro aspettaFine() throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(limite)) {
            var stato = lavori.corrente();
            if (stato.isEmpty() || stato.get().stato() != Stato.IN_CORSO) {
                return stato.orElse(null);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Indicizzazione ancora in corso dopo 60 secondi");
    }
}
