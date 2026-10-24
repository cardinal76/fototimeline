package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

/** La copia girata dei JPEG: solo il tag Orientation cambia, pixel e resto del file uguali. */
class JpegRuotatoTest {

    @TempDir
    Path cartella;

    @Test
    void laRotazioneSiSommaAllOrientamentoExif() {
        // Dritta: 90 a destra = 6, 180 = 3, 270 = 8.
        assertThat(Rotazione.componi(1, 90)).isEqualTo(6);
        assertThat(Rotazione.componi(1, 180)).isEqualTo(3);
        assertThat(Rotazione.componi(1, 270)).isEqualTo(8);
        assertThat(Rotazione.componi(1, -90)).isEqualTo(8);
        // Già girata di 90 (6): altri 90 = 180 (3), altri 270 = dritta (1).
        assertThat(Rotazione.componi(6, 90)).isEqualTo(3);
        assertThat(Rotazione.componi(6, 270)).isEqualTo(1);
        assertThat(Rotazione.componi(8, 90)).isEqualTo(1);
        // Specchiate: lo specchio resta, gira il resto (2 → 7 → 4 → 5 → 2).
        assertThat(Rotazione.componi(2, 90)).isEqualTo(7);
        assertThat(Rotazione.componi(7, 90)).isEqualTo(4);
        assertThat(Rotazione.componi(4, 90)).isEqualTo(5);
        assertThat(Rotazione.componi(5, 90)).isEqualTo(2);
        // Quattro quarti di giro: com'era, per ogni orientamento.
        for (int o = 1; o <= 8; o++) {
            assertThat(Rotazione.componi(o, 360)).isEqualTo(o);
            assertThat(Rotazione.componi(Rotazione.componi(o, 90), 270)).isEqualTo(o);
        }
        assertThat(Rotazione.normalizza(-90)).isEqualTo(270);
        assertThat(Rotazione.normalizza(450)).isEqualTo(90);
        assertThatThrownBy(() -> Rotazione.normalizza(45)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void cambiaIlTagCheCeGiaETieneDataEGps() throws Exception {
        Path jpeg = copia("/con-exif.jpg");
        byte[] prima = Files.readAllBytes(jpeg);

        byte[] girato = gira(jpeg, 90);

        // Orientation 6 (90°) + 90 = 3 (180°).
        Metadata m = ImageMetadataReader.readMetadata(new ByteArrayInputStream(girato));
        assertThat(m.getFirstDirectoryOfType(ExifIFD0Directory.class).getInt(ExifIFD0Directory.TAG_ORIENTATION))
                .isEqualTo(3);
        assertThat(m.getFirstDirectoryOfType(ExifSubIFDDirectory.class).getDateOriginal()).isNotNull();
        assertThat(m.getFirstDirectoryOfType(GpsDirectory.class).getGeoLocation().getLatitude()).isEqualTo(41.9);
        // Stessa lunghezza, cambiano solo i due byte del valore.
        assertThat(girato).hasSameSizeAs(prima);
        int diversi = 0;
        for (int i = 0; i < prima.length; i++) {
            diversi += prima[i] != girato[i] ? 1 : 0;
        }
        assertThat(diversi).isEqualTo(1);
        stessiPixel(prima, girato);
        // L'originale è com'era.
        assertThat(Files.readAllBytes(jpeg)).isEqualTo(prima);
    }

    @Test
    void senzaExifAggiungeUnSegmentoDopoJfif() throws Exception {
        Path jpeg = cartella.resolve("senza-exif.jpg");
        Files.write(jpeg, jpeg(64, 32));
        byte[] prima = Files.readAllBytes(jpeg);
        assertThat(orientamento(prima)).isNull();

        byte[] girato = gira(jpeg, 90);

        assertThat(orientamento(girato)).isEqualTo(6);
        // L'APP0 JFIF resta il primo segmento.
        assertThat(girato[2] & 0xFF).isEqualTo(0xFF);
        assertThat(girato[3] & 0xFF).isEqualTo(0xE0);
        stessiPixel(prima, girato);
        // L'intestazione cresce di un segmento; dopo, il file è lo stesso fino in fondo.
        assertThat(coda(girato, prima.length - 20)).isEqualTo(coda(prima, prima.length - 20));
    }

    @Test
    void exifSenzaOrientamentoRicopiaLaIfd0ConLaVoceInPiu() throws Exception {
        // Un EXIF little-endian con la sola voce Make ("Apple", fuori dalla voce perché è più di 4 byte).
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + 6).order(ByteOrder.LITTLE_ENDIAN);
        tiff.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(8);
        tiff.putShort((short) 1);
        tiff.putShort((short) 0x010F).putShort((short) 2).putInt(6).putInt(8 + 2 + 12 + 4);
        tiff.putInt(0);
        tiff.put("Apple\0".getBytes(StandardCharsets.ISO_8859_1));
        byte[] exif = concat("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1), tiff.array());
        byte[] base = jpeg(48, 16);
        byte[] conExif = concat(Arrays.copyOf(base, 2), segmento(0xE1, exif), Arrays.copyOfRange(base, 2, base.length));
        Path jpeg = cartella.resolve("exif-senza-orientamento.jpg");
        Files.write(jpeg, conExif);

        byte[] girato = gira(jpeg, 270);

        Metadata m = ImageMetadataReader.readMetadata(new ByteArrayInputStream(girato));
        ExifIFD0Directory ifd0 = m.getFirstDirectoryOfType(ExifIFD0Directory.class);
        assertThat(ifd0.getInt(ExifIFD0Directory.TAG_ORIENTATION)).isEqualTo(8);
        assertThat(ifd0.getString(ExifIFD0Directory.TAG_MAKE)).isEqualTo("Apple");
        stessiPixel(conExif, girato);
    }

    @Test
    void cambiaAncheLOrientamentoDellXmp() throws Exception {
        byte[] xmp = concat("http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1),
                "<x:xmpmeta><rdf:Description tiff:Orientation=\"1\"/></x:xmpmeta>".getBytes(StandardCharsets.UTF_8));
        byte[] base = jpeg(16, 16);
        Path jpeg = cartella.resolve("xmp.jpg");
        Files.write(jpeg, concat(Arrays.copyOf(base, 2), segmento(0xE1, xmp), Arrays.copyOfRange(base, 2, base.length)));

        String girato = new String(gira(jpeg, 180), StandardCharsets.ISO_8859_1);

        assertThat(girato).contains("tiff:Orientation=\"3\"").doesNotContain("tiff:Orientation=\"1\"");
        assertThat(orientamento(girato.getBytes(StandardCharsets.ISO_8859_1))).isEqualTo(3);
    }

    @Test
    void unFileCheNonEJpegNonSiGira() throws IOException {
        Path png = cartella.resolve("a.png");
        ImageIO.write(new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        assertThatThrownBy(() -> JpegRuotato.prepara(png, 90)).isInstanceOf(JpegRuotato.NonRuotabile.class);
    }

    @Test
    void laLunghezzaDettaEQuellaVera() throws IOException {
        Path jpeg = copia("/con-exif.jpg");
        var testa = JpegRuotato.prepara(jpeg, 90);
        assertThat(JpegRuotato.lunghezza(jpeg, testa)).isEqualTo(gira(jpeg, 90).length);
    }

    // ---------------------------------------------------------------- aiuti

    private Path copia(String risorsa) throws IOException {
        Path p = cartella.resolve(risorsa.substring(1));
        try (InputStream in = getClass().getResourceAsStream(risorsa)) {
            Files.copy(in, p);
        }
        return p;
    }

    static byte[] gira(Path jpeg, int gradi) throws IOException {
        var testa = JpegRuotato.prepara(jpeg, gradi);
        var uscita = new ByteArrayOutputStream();
        JpegRuotato.scrivi(jpeg, testa, uscita);
        return uscita.toByteArray();
    }

    /** I pixel decodificati (ImageIO non applica l'orientamento): uguali, cioè senza perdita. */
    static void stessiPixel(byte[] a, byte[] b) throws IOException {
        BufferedImage ia = ImageIO.read(new ByteArrayInputStream(a));
        BufferedImage ib = ImageIO.read(new ByteArrayInputStream(b));
        assertThat(ib.getWidth()).isEqualTo(ia.getWidth());
        assertThat(ib.getHeight()).isEqualTo(ia.getHeight());
        for (int y = 0; y < ia.getHeight(); y++) {
            for (int x = 0; x < ia.getWidth(); x++) {
                assertThat(ib.getRGB(x, y)).isEqualTo(ia.getRGB(x, y));
            }
        }
    }

    static Integer orientamento(byte[] jpeg) throws Exception {
        var ifd0 = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg))
                .getFirstDirectoryOfType(ExifIFD0Directory.class);
        return ifd0 == null ? null : ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
    }

    private static byte[] coda(byte[] b, int quanti) {
        return Arrays.copyOfRange(b, b.length - quanti, b.length);
    }

    static byte[] jpeg(int l, int a) throws IOException {
        BufferedImage img = new BufferedImage(l, a, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, l / 2, a);
        g.setColor(Color.BLUE);
        g.fillRect(l / 2, 0, l - l / 2, a);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static byte[] segmento(int marker, byte[] contenuto) {
        byte[] b = new byte[4 + contenuto.length];
        b[0] = (byte) 0xFF;
        b[1] = (byte) marker;
        b[2] = (byte) ((contenuto.length + 2) >> 8);
        b[3] = (byte) (contenuto.length + 2);
        System.arraycopy(contenuto, 0, b, 4, contenuto.length);
        return b;
    }

    private static byte[] concat(byte[]... pezzi) {
        var out = new ByteArrayOutputStream();
        for (byte[] p : pezzi) {
            out.writeBytes(p);
        }
        return out.toByteArray();
    }
}
