package it.fototimeline.quasiuguali;

import java.awt.AlphaComposite;
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

    /** Più chiara o più scura: ogni canale per {@code fattore}. */
    static BufferedImage luce(BufferedImage img, double fattore) {
        BufferedImage o = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int p = img.getRGB(x, y);
                int r = Math.min(255, (int) (((p >> 16) & 0xff) * fattore));
                int g = Math.min(255, (int) (((p >> 8) & 0xff) * fattore));
                int b = Math.min(255, (int) ((p & 0xff) * fattore));
                o.setRGB(x, y, (r << 16) | (g << 8) | b);
            }
        }
        return o;
    }

    /** Tolta la parte {@code quota} di ogni lato. */
    static BufferedImage ritaglia(BufferedImage img, double quota) {
        int dx = (int) (img.getWidth() * quota);
        int dy = (int) (img.getHeight() * quota);
        BufferedImage o = new BufferedImage(img.getWidth() - 2 * dx, img.getHeight() - 2 * dy,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(img, -dx, -dy, null);
        g.dispose();
        return o;
    }

    /** {@code a} con sopra {@code b} trasparente a {@code quanto} (0 = solo a, 1 = solo b). */
    static BufferedImage misto(BufferedImage a, BufferedImage b, double quanto) {
        BufferedImage o = new BufferedImage(a.getWidth(), a.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.drawImage(a, 0, 0, null);
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (float) quanto));
        g.drawImage(b, 0, 0, a.getWidth(), a.getHeight(), null);
        g.dispose();
        return o;
    }

    /** Una scena quasi al buio (una foto in tasca, di notte). */
    static BufferedImage buia(long seme) {
        return luce(scena(seme, 800, 600), 0.08);
    }

    /** Un colore solo con un filo di rumore, come un cielo grigio o un muro. */
    static BufferedImage uniforme(Color colore, long seme) {
        BufferedImage o = new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(seme);
        for (int y = 0; y < 600; y++) {
            for (int x = 0; x < 800; x++) {
                int n = r.nextInt(5) - 2;
                o.setRGB(x, y, (Math.clamp(colore.getRed() + n, 0, 255) << 16)
                        | (Math.clamp(colore.getGreen() + n, 0, 255) << 8) | Math.clamp(colore.getBlue() + n, 0, 255));
            }
        }
        return o;
    }

    /** Una pagina bianca con righe di "parole" nere: un documento, uno scontrino, una schermata. */
    static BufferedImage documento(long seme) {
        BufferedImage o = new BufferedImage(600, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = o.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 600, 800);
        g.setColor(Color.BLACK);
        Random r = new Random(seme);
        for (int y = 60; y < 760; y += 22) {
            int x = 50;
            while (x < 550) {
                int w = 10 + r.nextInt(50);
                g.fillRect(x, y, Math.min(w, 550 - x), 9);
                x += w + 8;
            }
        }
        g.dispose();
        return o;
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
