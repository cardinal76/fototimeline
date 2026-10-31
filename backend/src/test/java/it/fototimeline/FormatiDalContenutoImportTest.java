package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;

/**
 * File buoni che "Indicizza archivio" dava per illeggibili: il formato si
 * prende dal contenuto, non dal nome (i "_n.heic" di Facebook sono JPEG, un
 * HEIC chiamato .jpg), i JPEG Xiaomi con un SOI fuori posto, i video da cui
 * il primo tentativo di fotogramma non tira fuori niente.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class FormatiDalContenutoImportTest {

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

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
    }

    @Test
    void unJpegColNomeHeicEntraComeJpegSenzaCambiareNome() throws IOException {
        // Come i download di Facebook: JPEG/JFIF col nome .heic. Prima: heif-convert "Possibly could be a JPEG".
        String nome = "440882063_909728770842686_4294696282222404793_n.heic";
        Path file = scrivi("2024/04/30/" + nome, getClass().getResourceAsStream("/con-exif.jpg").readAllBytes());

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getContentType()).isEqualTo("image/jpeg");
        assertThat(foto.isVideo()).isFalse();
        // L'EXIF si legge lo stesso: la data e la cartella del suo giorno, col nome di prima.
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(foto.getScattataIl()).isEqualTo(LocalDateTime.of(2021, 7, 14, 18, 30, 5));
        assertThat(foto.getPercorso()).isEqualTo("2021/07/14/" + nome);
        // Il browser riceve l'originale, che è un JPEG.
        assertThat(service.contentTypeVista(foto)).isEqualTo("image/jpeg");
        assertThat(service.fileVista(foto)).isEqualTo(service.fileOriginale(foto));
        assertThat(service.fileDaServire(foto, true).contentType()).isEqualTo("image/jpeg");
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();

        Files.delete(service.fileMiniatura(foto));
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();
    }

    @Test
    void unJpegXiaomiConDueSoiEntraConMiniaturaEVistaRiparata() throws IOException {
        byte[] principale = jpeg(640, 480);
        byte[] rotto = conDentro(principale, jpeg(160, 120));
        String nome = "Xiaomi 25080RABDG - DCIM - Camera - IMG_20260318_213435 (6).jpg";
        Path file = scrivi("2026/03/18/" + nome, rotto);
        assertThatThrownBy(() -> ImageIO.read(file.toFile())).hasMessageContaining("two SOI markers");

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getLarghezza()).isEqualTo(640);
        assertThat(foto.getAltezza()).isEqualTo(480);
        assertThat(foto.getPercorso()).isEqualTo("2026/03/18/" + nome);
        // L'originale resta com'è; il browser riceve la copia riparata.
        assertThat(Files.readAllBytes(service.fileOriginale(foto))).isEqualTo(rotto);
        Path vista = service.fileVista(foto);
        assertThat(vista).isNotEqualTo(service.fileOriginale(foto));
        assertThat(ImageIO.read(vista.toFile()).getWidth()).isEqualTo(640);
        assertThat(service.contentTypeVista(foto)).isEqualTo("image/jpeg");
        assertThat(ImageIO.read(service.fileMiniatura(foto).toFile()).getWidth()).isGreaterThan(160);

        // Rifacendo la miniatura si rifà anche la vista riparata.
        Files.delete(service.fileMiniatura(foto));
        Files.delete(vista);
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();
        assertThat(ImageIO.read(service.fileVista(foto).toFile()).getWidth()).isEqualTo(640);
    }

    @Test
    void unHeicColNomeJpgPassaDaHeifConvert() throws IOException {
        assumeTrue(media.heicDisponibile(), "heif-convert non installato");
        // Prima: "Nessun lettore per questa immagine" (ImageIO non legge gli HEIC).
        Path file = scrivi("2025/08/29/IMG_20250829_151514.jpg", getClass().getResourceAsStream("/foto.heic").readAllBytes());

        Caricamento esito = service.indicizza(file);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getContentType()).isEqualTo("image/heic");
        assertThat(foto.getLarghezza()).isEqualTo(64);
        assertThat(foto.getPercorso()).endsWith("/IMG_20250829_151514.jpg");
        assertThat(service.contentTypeVista(foto)).isEqualTo("image/jpeg");
        assertThat(leggibile(service.fileVista(foto))).isTrue();
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();
    }

    @Test
    void unVideoConIlFlussoVideoPiuCortoDelFileHaLaMiniatura() throws IOException {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        // Mezzo secondo di video e sei di audio: il file dura 6 s, a 1 s di video non ce n'è più.
        // ffmpeg finiva bene senza scrivere niente: "ffmpeg non ha estratto il fotogramma".
        Path video = fuori.resolve("VID_20251022_193522.mp4");
        assumeTrue(ffmpeg("-f", "lavfi", "-i", "testsrc=size=160x90:rate=25:duration=0.5",
                "-f", "lavfi", "-i", "sine=duration=6", "-c:v", "libx264", "-pix_fmt", "yuv420p",
                "-c:a", "aac", video.toString()), "ffmpeg non sa fare il video di prova");

        Caricamento esito = service.importa(video.getFileName().toString(), video, null, null);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        assertThat(esito.foto().getDurata()).isGreaterThan(5.0);
        assertThat(leggibile(service.fileMiniatura(esito.foto()))).isTrue();
    }

    @Test
    void unVideoHevcA10BitGiratoHaLaMiniaturaDritta() throws IOException {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        Path video = fuori.resolve("VID_20210626_102519.mp4");
        assumeTrue(ffmpeg("-display_rotation", "90", "-f", "lavfi", "-i", "testsrc=size=160x96:rate=25:duration=1",
                "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-x265-params", "log-level=error",
                "-tag:v", "hvc1", video.toString()), "ffmpeg senza libx265 a 10 bit o -display_rotation");

        Caricamento esito = service.importa(video.getFileName().toString(), video, null, null);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        Foto foto = esito.foto();
        assertThat(foto.getLarghezza()).isEqualTo(96);
        assertThat(foto.getAltezza()).isEqualTo(160);
        BufferedImage miniatura = ImageIO.read(service.fileMiniatura(foto).toFile());
        assertThat(miniatura.getHeight()).isGreaterThan(miniatura.getWidth());
    }

    @Test
    void unVideoCheNonSiDecodificaDiceCosaHaDettoFfmpeg() throws IOException {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        Path finto = fuori.resolve("finto.mp4");
        Files.write(finto, "non è un video".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> media.fotogramma(finto, 3.0, fuori.resolve("fotogramma.jpg")))
                .hasMessageStartingWith("ffmpeg non ha estratto il fotogramma (a 1.0 s: ffmpeg è uscito con")
                .hasMessageContaining("dall'inizio");
    }

    // ------------------------------------------------------------ file di prova

    private Path scrivi(String relativo, byte[] contenuto) throws IOException {
        Path file = archivio.resolve(relativo);
        Files.createDirectories(file.getParent());
        Files.write(file, contenuto);
        return file;
    }

    private static boolean leggibile(Path jpeg) throws IOException {
        return Files.exists(jpeg) && ImageIO.read(jpeg.toFile()) != null;
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

    /** {@code dentro} intera subito prima del SOS di {@code principale}: libjpeg vede due SOI. */
    private static byte[] conDentro(byte[] principale, byte[] dentro) {
        int sos = 2;
        while ((principale[sos + 1] & 0xFF) != 0xDA) {
            sos += 2 + (((principale[sos + 2] & 0xFF) << 8) | (principale[sos + 3] & 0xFF));
        }
        byte[] tutto = new byte[principale.length + dentro.length];
        System.arraycopy(principale, 0, tutto, 0, sos);
        System.arraycopy(dentro, 0, tutto, sos, dentro.length);
        System.arraycopy(principale, sos, tutto, sos + dentro.length, principale.length - sos);
        return tutto;
    }

    /** Un video di prova con ffmpeg; false se questo ffmpeg non ce la fa (codec che manca). */
    private static boolean ffmpeg(String... argomenti) {
        List<String> comando = new ArrayList<>(List.of("ffmpeg", "-y", "-nostdin", "-v", "error"));
        comando.addAll(List.of(argomenti));
        try {
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
}
