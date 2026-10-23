package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * La miniatura di una foto grande senza decodificarla intera: l'OutOfMemoryError
 * delle importazioni sul server (heap di 384 MB) veniva da {@code Thumbnails.of(file)},
 * che legge tutta l'immagine. Qui si misura quanto heap alloca il thread.
 */
class RiduzioneTest {

    @TempDir
    static Path cartella;

    /** 24 MP, come una foto di telefono a piena risoluzione (quelle da 50 MP sono il doppio). */
    static Path grande;

    @BeforeAll
    static void preparaFotoGrande() throws IOException {
        var img = new BufferedImage(6000, 4000, BufferedImage.TYPE_3BYTE_BGR);
        var g = img.createGraphics();
        g.setPaint(new GradientPaint(0, 0, Color.ORANGE, 6000, 4000, Color.BLUE));
        g.fillRect(0, 0, 6000, 4000);
        g.dispose();
        grande = cartella.resolve("grande.jpg");
        ImageIO.write(img, "jpg", grande.toFile());
    }

    @Test
    void laMiniaturaDiUnaFotoGrandeNonLaDecodificaIntera() throws IOException {
        Path miniatura = cartella.resolve("miniatura.jpg");
        long ridotta = allocati(() -> Riduzione.jpeg(grande, miniatura, 480, true, 0.85, true));

        BufferedImage letta = ImageIO.read(miniatura.toFile());
        assertThat(letta.getWidth()).isEqualTo(480);
        assertThat(letta.getHeight()).isEqualTo(320);
        // Intera sarebbero 72 MB solo per i pixel (6000×4000×3), e Thumbnailator ne fa altre copie.
        assertThat(ridotta).isLessThan(40L << 20);

        // Com'era prima: la stessa miniatura da Thumbnails.of(file) alloca molte volte tanto.
        long intera = allocati(() -> Thumbnails.of(grande.toFile()).useExifOrientation(true).size(480, 480)
                .imageType(BufferedImage.TYPE_INT_RGB).outputFormat("jpg").outputQuality(0.85)
                .toFile(cartella.resolve("prima.jpg").toFile()));
        assertThat(intera).isGreaterThan(72L << 20).isGreaterThan(4 * ridotta);
    }

    @Test
    void laVistaCondivisaSiLeggeAncheLeiRidotta() throws IOException {
        Path vista = cartella.resolve("vista.jpg");
        long allocati = allocati(() -> Riduzione.jpeg(grande, vista, 2048, true, 0.88, false));

        BufferedImage letta = ImageIO.read(vista.toFile());
        assertThat(letta.getWidth()).isEqualTo(2048);
        // Letta a 3000×2000 invece che intera: meno della metà di quello che allocava prima.
        long intera = allocati(() -> Thumbnails.of(grande.toFile()).useExifOrientation(true).size(2048, 2048)
                .imageType(BufferedImage.TYPE_INT_RGB).outputFormat("jpg").outputQuality(0.88)
                .toFile(cartella.resolve("vista-prima.jpg").toFile()));
        assertThat(2 * allocati).isLessThan(intera);
    }

    @Test
    void ilPassoLasciaAlmenoIlDoppioDelLato() {
        assertThat(Riduzione.passo(8160, 6144, 480)).isEqualTo(8);
        assertThat(Riduzione.passo(6000, 4000, 480)).isEqualTo(6);
        // La vista condivisa (2048): mai più di 8 MP letti.
        assertThat(Riduzione.passo(3264, 2448, 2048)).isEqualTo(1);
        assertThat(Riduzione.passo(6000, 4000, 2048)).isEqualTo(2);
        assertThat(Riduzione.passo(8160, 6144, 2048)).isEqualTo(3);
        assertThat(Riduzione.passo(640, 480, 480)).isEqualTo(1);
        assertThat(Riduzione.passo(0, 0, 0)).isEqualTo(1);
    }

    @Test
    void lOrientamentoExifSiApplica() throws IOException {
        // 64x32 con orientamento 6: la miniatura è verticale.
        Path conExif = Path.of("src/test/resources/con-exif.jpg");
        assertThat(Riduzione.orientamento(conExif)).isEqualTo(Orientation.RIGHT_TOP);

        Path dritta = cartella.resolve("dritta.jpg");
        Riduzione.jpeg(conExif, dritta, 480, true, 0.85, true);
        BufferedImage letta = ImageIO.read(dritta.toFile());
        assertThat(letta.getHeight()).isGreaterThan(letta.getWidth());

        // Già dritta (da heif-convert o ffmpeg): non si gira.
        Path comE = cartella.resolve("come-e.jpg");
        Riduzione.jpeg(conExif, comE, 480, false, 0.85, true);
        BufferedImage nonGirata = ImageIO.read(comE.toFile());
        assertThat(nonGirata.getWidth()).isGreaterThan(nonGirata.getHeight());
    }

    @Test
    void unaFotoPiuPiccolaDelLatoSiIngrandisceSoloSeRichiesto() throws IOException {
        Path conExif = Path.of("src/test/resources/con-exif.jpg");
        Path vista = cartella.resolve("piccola.jpg");
        Riduzione.jpeg(conExif, vista, 2048, false, 0.88, false);
        BufferedImage letta = ImageIO.read(vista.toFile());
        assertThat(letta.getWidth()).isEqualTo(64);
        assertThat(letta.getHeight()).isEqualTo(32);
    }

    @Test
    void unFileCheNonEUnImmagineLoDice() throws IOException {
        Path rotto = Files.writeString(cartella.resolve("rotto.jpg"), "non sono un jpeg");
        assertThatThrownBy(
                () -> Riduzione.jpeg(rotto, cartella.resolve("x.jpg"), 480, true, 0.85, true))
                .isInstanceOf(IOException.class);
    }

    interface Lavoro {
        void fai() throws IOException;
    }

    /** I byte di heap allocati da questo thread mentre fa {@code lavoro}. */
    private static long allocati(Lavoro lavoro) throws IOException {
        var thread = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long prima = thread.getCurrentThreadAllocatedBytes();
        lavoro.fai();
        return thread.getCurrentThreadAllocatedBytes() - prima;
    }
}
