package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Il formato dal contenuto ({@link Contenuto}) e i JPEG con un SOI fuori posto
 * ({@link JpegRiparato}), come certe foto Xiaomi che ImageIO rifiuta.
 */
class FormatiDalContenutoTest {

    @TempDir
    Path cartella;

    @Test
    void ilFormatoLoDiconoIPrimiByte() throws IOException {
        assertThat(Contenuto.tipo(jpeg(64, 48))).isEqualTo("image/jpeg");
        assertThat(Contenuto.tipo(risorsa("/foto.heic"))).isEqualTo("image/heic");
        assertThat(Contenuto.tipo(risorsa("/video.mp4"))).isEqualTo("video/mp4");
        assertThat(Contenuto.tipo(risorsa("/video.mov"))).isEqualTo("video/quicktime");

        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", png);
        assertThat(Contenuto.tipo(png.toByteArray())).isEqualTo("image/png");
        assertThat(Contenuto.tipo("GIF89a......".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("image/gif");
        assertThat(Contenuto.tipo("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.ISO_8859_1))).isEqualTo("image/webp");
        assertThat(Contenuto.tipo(ftyp("avif", "mif1", "miaf"))).isEqualTo("image/avif");
        assertThat(Contenuto.tipo(ftyp("mif1", "mif1", "avif"))).isEqualTo("image/avif");
        assertThat(Contenuto.tipo(ftyp("mif1", "mif1", "heic"))).isEqualTo("image/heic");
        assertThat(Contenuto.tipo(ftyp("heic", "mif1", "heic"))).isEqualTo("image/heic");
        assertThat(Contenuto.tipo(ftyp("isom", "isom", "mp42"))).isEqualTo("video/mp4");
        assertThat(Contenuto.tipo(ftyp("qt  ", "qt  ", "qt  "))).isEqualTo("video/quicktime");

        // Rotti, vuoti, sconosciuti: decide l'estensione, come prima.
        assertThat(Contenuto.tipo(new byte[64])).isNull();
        assertThat(Contenuto.tipo("non sono un jpeg".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(Contenuto.tipo(new byte[0])).isNull();
        assertThat(Contenuto.tipo(cartella.resolve("mancante.jpg"))).isNull();
    }

    @Test
    void unaMiniaturaInfilataPrimaDelSosSiToglie() throws IOException {
        // Come le foto Xiaomi: dopo SOF un'immagine intera, che libjpeg legge come un secondo SOI.
        Path file = scrivi("dentro.jpg", conDentro(jpeg(640, 480), jpeg(160, 120)));
        assertThatThrownBy(() -> ImageIO.read(file.toFile()))
                .isInstanceOf(IIOException.class).hasMessageContaining("two SOI markers");

        assertThat(Riduzione.dimensioni(file)).containsExactly(640, 480);
        Path miniatura = cartella.resolve("miniatura.jpg");
        Riduzione.jpeg(file, miniatura, 320, true, 0.85, true);
        BufferedImage letta = ImageIO.read(miniatura.toFile());
        assertThat(letta.getWidth()).isEqualTo(320);
        assertThat(letta.getHeight()).isEqualTo(240);

        Path riparato = cartella.resolve("riparato.jpg");
        assertThat(JpegRiparato.daRiparare(file)).isTrue();
        assertThat(JpegRiparato.ripara(file, riparato)).isTrue();
        assertThat(ImageIO.read(riparato.toFile()).getWidth()).isEqualTo(640);
    }

    @Test
    void unSoiRipetutoSiToglieELImmagineResta() throws IOException {
        byte[] base = jpeg(640, 480);
        byte[] doppio = new byte[base.length + 2];
        int sos = indice(base, 0xDA);
        System.arraycopy(base, 0, doppio, 0, sos);
        doppio[sos] = (byte) 0xFF;
        doppio[sos + 1] = (byte) 0xD8;
        System.arraycopy(base, sos, doppio, sos + 2, base.length - sos);
        Path file = scrivi("doppio.jpg", doppio);
        assertThatThrownBy(() -> ImageIO.read(file.toFile())).isInstanceOf(IIOException.class);

        assertThat(Riduzione.dimensioni(file)).containsExactly(640, 480);
        assertThat(Riduzione.leggi(file, 640).getWidth()).isEqualTo(640);
    }

    @Test
    void iJpegBuoniNonSiToccano() throws IOException {
        Path buono = scrivi("buono.jpg", jpeg(64, 48));
        assertThat(JpegRiparato.daRiparare(buono)).isFalse();
        Path destinazione = cartella.resolve("mai.jpg");
        assertThat(JpegRiparato.ripara(buono, destinazione)).isFalse();
        assertThat(destinazione).doesNotExist();

        // Ultra HDR / MPF: la seconda immagine in coda, dopo l'EOI, va bene così com'è.
        byte[] principale = jpeg(64, 48);
        byte[] mappa = jpeg(32, 24);
        byte[] coda = new byte[principale.length + mappa.length];
        System.arraycopy(principale, 0, coda, 0, principale.length);
        System.arraycopy(mappa, 0, coda, principale.length, mappa.length);
        Path hdr = scrivi("hdr.jpg", coda);
        assertThat(JpegRiparato.daRiparare(hdr)).isFalse();
        assertThat(Riduzione.dimensioni(hdr)).containsExactly(64, 48);

        // Non un JPEG: l'errore resta quello di prima.
        Path testo = scrivi("rotta.jpg", "non sono un jpeg".getBytes(StandardCharsets.UTF_8));
        assertThat(JpegRiparato.daRiparare(testo)).isFalse();
        assertThatThrownBy(() -> Riduzione.dimensioni(testo)).hasMessage("Nessun lettore per questa immagine");
    }

    @Test
    void iTentativiDelFotogramma() {
        assertThat(StrumentiMedia.prove(1.0)).extracting(Object::toString)
                .containsExactly("a 1.0 s", "dall'inizio", "dall'inizio, flusso scelto da ffmpeg");
        assertThat(StrumentiMedia.prove(0)).hasSize(2);
    }

    // ------------------------------------------------------------ file di prova

    static byte[] jpeg(int larghezza, int altezza) throws IOException {
        var img = new BufferedImage(larghezza, altezza, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, larghezza, altezza, Color.BLUE));
        g.fillRect(0, 0, larghezza, altezza);
        g.dispose();
        var uscita = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", uscita);
        return uscita.toByteArray();
    }

    /** {@code dentro} intera subito prima del SOS di {@code principale}. */
    static byte[] conDentro(byte[] principale, byte[] dentro) {
        int sos = indice(principale, 0xDA);
        byte[] tutto = new byte[principale.length + dentro.length];
        System.arraycopy(principale, 0, tutto, 0, sos);
        System.arraycopy(dentro, 0, tutto, sos, dentro.length);
        System.arraycopy(principale, sos, tutto, sos + dentro.length, principale.length - sos);
        return tutto;
    }

    /** Dove inizia il primo segmento {@code FF marcatore} della testa. */
    private static int indice(byte[] jpeg, int marcatore) {
        int i = 2;
        while ((jpeg[i + 1] & 0xFF) != marcatore) {
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return i;
    }

    private static byte[] ftyp(String principale, String... compatibili) {
        var b = new ByteArrayOutputStream();
        int lunghezza = 16 + 4 * compatibili.length;
        b.writeBytes(new byte[] {0, 0, 0, (byte) lunghezza});
        b.writeBytes(("ftyp" + principale + "\0\0\0\0" + String.join("", compatibili)).getBytes(StandardCharsets.ISO_8859_1));
        b.writeBytes(new byte[16]);
        return b.toByteArray();
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
