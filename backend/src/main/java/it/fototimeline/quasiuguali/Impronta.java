package it.fototimeline.quasiuguali;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

import javax.imageio.ImageIO;

/**
 * Impronta percettiva di una foto: un pHash a 64 bit. L'immagine si riduce a
 * 32×32 riquadri di luminosità (media di ogni riquadro, così non conta la
 * risoluzione), se ne fa la trasformata del coseno e si tengono le 8×8
 * frequenze più basse, la "forma" della foto: ogni bit dice se una frequenza
 * sta sopra o sotto la mediana. Due foto quasi uguali (la stessa ridimensionata
 * o ricompressa da WhatsApp) hanno impronte che differiscono in pochi bit, due
 * foto diverse in una trentina.
 *
 * <p>Meglio del dHash (confronto tra riquadri vicini) nelle prove: con le zone
 * piatte (cielo, muri) il rumore del JPEG fa girare i bit del dHash, mentre le
 * basse frequenze restano ferme (ImprontaTest).
 *
 * <p>Si calcola dalla miniatura, che sta sul disco del server: niente cloud.
 */
public final class Impronta {

    private static final int LATO = 32;
    private static final int FREQUENZE = 8;
    /** cos((2x+1)uπ/2N): la trasformata si fa per righe e poi per colonne. */
    private static final double[][] COSENI = new double[FREQUENZE][LATO];

    static {
        for (int u = 0; u < FREQUENZE; u++) {
            for (int x = 0; x < LATO; x++) {
                COSENI[u][x] = Math.cos((2 * x + 1) * u * Math.PI / (2 * LATO));
            }
        }
    }

    private Impronta() {
    }

    /** L'impronta di un file immagine (la miniatura JPEG). */
    public static long calcola(Path immagine) throws IOException {
        BufferedImage img = ImageIO.read(immagine.toFile());
        if (img == null) {
            throw new IOException("Immagine illeggibile: " + immagine.getFileName());
        }
        return di(img);
    }

    public static long di(BufferedImage img) {
        double[][] luce = luminosita(img);
        // Per righe: le prime 8 frequenze orizzontali di ogni riga.
        double[][] righe = new double[LATO][FREQUENZE];
        for (int y = 0; y < LATO; y++) {
            for (int v = 0; v < FREQUENZE; v++) {
                double s = 0;
                for (int x = 0; x < LATO; x++) {
                    s += luce[y][x] * COSENI[v][x];
                }
                righe[y][v] = s;
            }
        }
        // Per colonne: le prime 8 frequenze verticali.
        double[] frequenze = new double[FREQUENZE * FREQUENZE];
        for (int u = 0; u < FREQUENZE; u++) {
            for (int v = 0; v < FREQUENZE; v++) {
                double s = 0;
                for (int y = 0; y < LATO; y++) {
                    s += righe[y][v] * COSENI[u][y];
                }
                frequenze[u * FREQUENZE + v] = s;
            }
        }
        // La mediana senza la componente continua (la luminosità media), che è molto più grande.
        double[] ordinate = Arrays.copyOfRange(frequenze, 1, frequenze.length);
        Arrays.sort(ordinate);
        double mediana = ordinate[ordinate.length / 2];
        long impronta = 0;
        for (double f : frequenze) {
            impronta = (impronta << 1) | (f > mediana ? 1 : 0);
        }
        return impronta;
    }

    /** Luminosità media di LATO×LATO riquadri. */
    private static double[][] luminosita(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixel = img.getRGB(0, 0, w, h, null, 0, w);
        double[][] luce = new double[LATO][LATO];
        for (int r = 0; r < LATO; r++) {
            int y0 = Math.min(r * h / LATO, h - 1);
            int y1 = Math.max(y0 + 1, (r + 1) * h / LATO);
            for (int c = 0; c < LATO; c++) {
                int x0 = Math.min(c * w / LATO, w - 1);
                int x1 = Math.max(x0 + 1, (c + 1) * w / LATO);
                double somma = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = pixel[y * w + x];
                        somma += 0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff);
                    }
                }
                luce[r][c] = somma / ((y1 - y0) * (x1 - x0));
            }
        }
        return luce;
    }

    /** Distanza di Hamming: in quanti bit differiscono. */
    public static int distanza(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
