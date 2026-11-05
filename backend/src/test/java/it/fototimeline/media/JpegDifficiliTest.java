package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * I JPEG che Java non legge: un SOI dopo i pixel (lo Xiaomi che la riparazione
 * della testa non vedeva) e quelli che solo ImageMagick sa leggere, come i JPEG
 * a codifica aritmetica (che anche ffmpeg rifiuta).
 */
class JpegDifficiliTest {

    private static final MediaProperties PREDEFINITI = new MediaProperties(null, null, null, null);

    @TempDir
    Path cartella;

    @Test
    void senzaEoiESubitoUnAltraImmagineSiChiudePrimaDelSecondoSoi() throws IOException {
        // Come IMG_20260318_213435 (6).jpg dello Xiaomi: la principale finisce senza EOI e
        // comincia la mappa Ultra HDR. La testa è buona (le dimensioni si leggono), i pixel no.
        byte[] principale = jpeg(640, 480, false);
        byte[] senzaEoi = Arrays.copyOf(principale, principale.length - 2);
        Path file = scrivi("IMG_20260318_213435 (6).jpg", unisci(senzaEoi, jpeg(160, 120, false)));
        assertThat(Riduzione.dimensioni(file)).containsExactly(640, 480);
        assertThatThrownBy(() -> ImageIO.read(file.toFile())).hasMessageContaining("two SOI markers");

        assertThat(JpegRiparato.daRiparare(file)).isTrue();
        Path riparato = cartella.resolve("riparato.jpg");
        assertThat(JpegRiparato.ripara(file, riparato)).isTrue();
        // La stessa testa e gli stessi pixel compressi, più l'EOI; la mappa dopo non c'è più.
        assertThat(Files.readAllBytes(riparato)).isEqualTo(principale);
        assertThat(ImageIO.read(riparato.toFile()).getWidth()).isEqualTo(640);

        // La miniatura passa da sola dalla copia riparata.
        Path miniatura = cartella.resolve("miniatura.jpg");
        Riduzione.jpeg(file, miniatura, 320, true, 0.85, true);
        assertThat(ImageIO.read(miniatura.toFile()).getWidth()).isEqualTo(320);
    }

    @Test
    void unSoiFraLeScansioniDiUnaProgressivaSiTaglia() throws IOException {
        byte[] progressiva = jpeg(640, 480, true);
        int secondoSos = secondoSos(progressiva);
        byte[] rotta = unisci(Arrays.copyOf(progressiva, secondoSos), new byte[] {(byte) 0xFF, (byte) 0xD8},
                Arrays.copyOfRange(progressiva, secondoSos, progressiva.length));
        Path file = scrivi("progressiva.jpg", rotta);
        assertThat(Riduzione.dimensioni(file)).containsExactly(640, 480);
        assertThatThrownBy(() -> ImageIO.read(file.toFile())).hasMessageContaining("two SOI markers");

        // Resta la prima scansione (l'immagine sfocata, intera): meglio di niente, e si vede.
        Path riparato = cartella.resolve("riparato.jpg");
        assertThat(JpegRiparato.ripara(file, riparato)).isTrue();
        assertThat(ImageIO.read(riparato.toFile()).getWidth()).isEqualTo(640);
    }

    @Test
    void iJpegBuoniEQuelliConLaCodaDopoLEoiNonSiToccano() throws IOException {
        assertThat(JpegRiparato.daRiparare(scrivi("progressiva.jpg", jpeg(64, 48, true)))).isFalse();
        // Ultra HDR fatto bene: EOI e poi la mappa.
        assertThat(JpegRiparato.daRiparare(scrivi("hdr.jpg", unisci(jpeg(64, 48, false), jpeg(32, 24, false)))))
                .isFalse();
        // Troncato a metà senza niente dopo: libjpeg lo legge con un avviso, non c'è da riparare.
        byte[] intero = jpeg(64, 48, false);
        assertThat(JpegRiparato.daRiparare(scrivi("tronco.jpg", Arrays.copyOf(intero, intero.length * 2 / 3))))
                .isFalse();
    }

    @Test
    void unJpegAritmeticoLoConverteImageMagick() throws IOException {
        StrumentiMedia media = new StrumentiMedia(PREDEFINITI);
        assumeTrue(media.imagemagickDisponibile(), "ImageMagick non installato");
        // Fatto con jpegtran -arithmetic (SOF9): ffmpeg non lo legge, e nemmeno il libjpeg di Temurin.
        Path aritmetica = risorsa("/aritmetica.jpg");
        assertThat(Files.readAllBytes(aritmetica)).containsSequence(0xFF, 0xC9);

        Path jpeg = cartella.resolve("convertita.jpg");
        media.conImageMagick(aritmetica, jpeg);
        BufferedImage letta = ImageIO.read(jpeg.toFile());
        assertThat(letta.getWidth()).isEqualTo(64);
        assertThat(letta.getHeight()).isEqualTo(48);

        // La strada di tutti: ffmpeg (se c'è) non ce la fa, ImageMagick sì. L'originale non cambia.
        byte[] prima = Files.readAllBytes(aritmetica);
        Path ancora = cartella.resolve("ancora.jpg");
        media.immagineInJpeg(aritmetica, ancora);
        assertThat(ImageIO.read(ancora.toFile()).getWidth()).isEqualTo(64);
        assertThat(Files.readAllBytes(aritmetica)).isEqualTo(prima);
    }

    @Test
    void senzaFfmpegBastaImageMagick() throws IOException {
        StrumentiMedia media = new StrumentiMedia(new MediaProperties(null, "/non/esiste/ffmpeg",
                "/non/esiste/ffprobe", null));
        assumeTrue(media.imagemagickDisponibile(), "ImageMagick non installato");
        assertThat(media.videoDisponibile()).isFalse();
        assertThat(media.riservaImmaginiDisponibile()).isTrue();
        // Un'immagine che Java e ffmpeg non leggono, col nome .jpg: un MIFF di ImageMagick.
        Path miff = cartella.resolve("miff.jpg");
        assumeTrue(imagemagick(media, "-size", "80x60", "gradient:red-blue", "miff:" + miff), "niente MIFF di prova");
        assertThat(ImageIO.read(miff.toFile())).isNull();

        Path jpeg = cartella.resolve("convertita.jpg");
        media.immagineInJpeg(miff, jpeg);
        assertThat(ImageIO.read(jpeg.toFile()).getWidth()).isEqualTo(80);

        // Un file che non legge nessuno: l'errore lo dice, e niente JPEG a metà.
        Path rotto = scrivi("rotto.jpg", new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x01, 0x02});
        Path niente = cartella.resolve("niente.jpg");
        assertThatThrownBy(() -> media.immagineInJpeg(rotto, niente))
                .hasMessageStartingWith("ImageMagick non ha convertito l'immagine");
        assertThat(niente).doesNotExist();
    }

    @Test
    void senzaProgrammiLErroreDiceCosaServe() {
        StrumentiMedia media = new StrumentiMedia(new MediaProperties(null, "/non/esiste/ffmpeg",
                "/non/esiste/ffprobe", "/non/esiste/magick"));
        assertThat(media.riservaImmaginiDisponibile()).isFalse();
        assertThatThrownBy(() -> media.immagineInJpeg(cartella.resolve("a.jpg"), cartella.resolve("b.jpg")))
                .hasMessage("Per convertire l'immagine servono ffmpeg o ImageMagick sul server");
        // Memoria e tempo di ImageMagick sono sempre limitati.
        assertThat(StrumentiMedia.LIMITI_IMAGEMAGICK).containsSubsequence("-limit", "memory")
                .containsSubsequence("-limit", "map").containsSubsequence("-limit", "time");
    }

    // ------------------------------------------------------------ file di prova

    static byte[] jpeg(int larghezza, int altezza, boolean progressiva) throws IOException {
        var img = new BufferedImage(larghezza, altezza, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, larghezza, altezza, Color.BLUE));
        g.fillRect(0, 0, larghezza, altezza);
        g.dispose();
        ImageWriter scrittore = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam parametri = scrittore.getDefaultWriteParam();
        if (progressiva) {
            parametri.setProgressiveMode(ImageWriteParam.MODE_DEFAULT);
        }
        var uscita = new ByteArrayOutputStream();
        try (var flusso = new MemoryCacheImageOutputStream(uscita)) {
            scrittore.setOutput(flusso);
            scrittore.write(null, new IIOImage(img, null, null), parametri);
        } finally {
            scrittore.dispose();
        }
        return uscita.toByteArray();
    }

    /** Dove comincia la seconda scansione (il secondo SOS) di una progressiva. */
    private static int secondoSos(byte[] jpeg) {
        int trovati = 0;
        for (int i = 0; i + 1 < jpeg.length; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xDA && ++trovati == 2) {
                return i;
            }
        }
        throw new IllegalStateException("Non è progressiva");
    }

    private static byte[] unisci(byte[]... pezzi) {
        var b = new ByteArrayOutputStream();
        for (byte[] p : pezzi) {
            b.writeBytes(p);
        }
        return b.toByteArray();
    }

    /** ImageMagick per fare un file di prova (lo stesso comando che usa l'app). */
    private static boolean imagemagick(StrumentiMedia media, String... argomenti) {
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

    private Path scrivi(String nome, byte[] contenuto) throws IOException {
        Path file = cartella.resolve(nome);
        Files.write(file, contenuto);
        return file;
    }

    private Path risorsa(String nome) throws IOException {
        Path copia = cartella.resolve(nome.substring(1));
        try (InputStream in = getClass().getResourceAsStream(nome)) {
            Files.copy(in, copia);
        }
        return copia;
    }
}
