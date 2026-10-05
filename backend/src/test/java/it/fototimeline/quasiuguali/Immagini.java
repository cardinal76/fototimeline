package it.fototimeline.quasiuguali;

import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Random;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;

/** Immagini di prova: una "scena" diversa per ogni seme, e le sue copie peggiorate. */
final class Immagini {

    private Immagini() {
    }

    /** Sfondo sfumato e qualche forma colorata, sempre uguali per lo stesso seme. */
    static BufferedImage scena(long seme, int larghezza, int altezza) {
        Random r = new Random(seme);
        BufferedImage img = new BufferedImage(larghezza, altezza, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new GradientPaint(0, 0, colore(r), larghezza, altezza, colore(r)));
        g.fillRect(0, 0, larghezza, altezza);
        for (int i = 0; i < 14; i++) {
            g.setColor(colore(r));
            int w = larghezza / 8 + r.nextInt(larghezza / 3);
            int h = altezza / 8 + r.nextInt(altezza / 3);
            int x = r.nextInt(larghezza) - w / 2;
            int y = r.nextInt(altezza) - h / 2;
            if (r.nextBoolean()) {
                g.fillOval(x, y, w, h);
            } else {
                g.fillRect(x, y, w, h);
            }
        }
        g.dispose();
        return img;
    }

    private static Color colore(Random r) {
        return new Color(r.nextInt(256), r.nextInt(256), r.nextInt(256));
    }

    static BufferedImage ridimensiona(BufferedImage img, int larghezza, int altezza) {
        BufferedImage piccola = new BufferedImage(larghezza, altezza, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = piccola.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, larghezza, altezza, null);
        g.dispose();
        return piccola;
    }

    /** La scena spostata di qualche pixel con una forma in più: un altro scatto della raffica. */
    static BufferedImage altroScatto(BufferedImage img, int spostamento) {
        BufferedImage scatto = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scatto.createGraphics();
        g.drawImage(img, spostamento, 0, null);
        g.setColor(Color.WHITE);
        g.fillOval(img.getWidth() / 3, img.getHeight() / 3, img.getWidth() / 10, img.getHeight() / 10);
        g.dispose();
        return scatto;
    }

    static byte[] jpeg(BufferedImage img, float qualita) throws IOException {
        ImageWriter scrittore = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam parametri = scrittore.getDefaultWriteParam();
        parametri.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        parametri.setCompressionQuality(qualita);
        var out = new ByteArrayOutputStream();
        try (var ios = ImageIO.createImageOutputStream(out)) {
            scrittore.setOutput(ios);
            scrittore.write(null, new IIOImage(img, null, null), parametri);
        } finally {
            scrittore.dispose();
        }
        return out.toByteArray();
    }

    static byte[] png(BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    static BufferedImage leggi(byte[] dati) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(dati));
    }
}
