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
 * <p>Da solo il pHash non basta (con 140.000 foto proponeva troppe foto
 * diverse): {@link #analizza} dà anche la {@link Firma} (dHash, colori,
 * luminosità, contrasto) che le quasi uguali usano per confermarlo
 * ({@link Confronto}).
 *
 * <p>Si calcola dalla miniatura, che sta sul disco del server: niente cloud.
 */
public final class Impronta {

    /**
     * Versione del calcolo della {@link Firma}: se cambia, "Calcola impronte"
     * rifà la firma delle foto che hanno quella vecchia (il pHash resta).
     */
    public static final int VERSIONE = 1;

    /** pHash e firma di una miniatura. */
    public record Analisi(long impronta, Firma firma) {
    }

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

    /** pHash e firma di un file immagine (la miniatura JPEG), con una lettura sola. */
    public static Analisi analizza(Path immagine) throws IOException {
        BufferedImage img = ImageIO.read(immagine.toFile());
        if (img == null) {
            throw new IOException("Immagine illeggibile: " + immagine.getFileName());
        }
        return analizza(img);
    }

    public static Analisi analizza(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int[] pixel = img.getRGB(0, 0, w, h, null, 0, w);
        double[][] luce = medie(pixel, w, h, LATO, LATO, -1);
        return new Analisi(phash(luce), firma(pixel, w, h, luce));
    }

    public static long di(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        return phash(medie(img.getRGB(0, 0, w, h, null, 0, w), w, h, LATO, LATO, -1));
    }

    private static long phash(double[][] luce) {
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

    private static Firma firma(int[] pixel, int w, int h, double[][] luce) {
        // dHash: 9 colonne × 8 righe di luminosità, ogni riquadro contro quello a destra.
        double[][] d = medie(pixel, w, h, 8, 9, -1);
        long dhash = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                dhash = (dhash << 1) | (d[r][c] > d[r][c + 1] ? 1 : 0);
            }
        }
        byte[] colori = new byte[4 * 4 * 3];
        for (int canale = 0; canale < 3; canale++) {
            double[][] m = medie(pixel, w, h, 4, 4, canale);
            for (int r = 0; r < 4; r++) {
                for (int c = 0; c < 4; c++) {
                    colori[(r * 4 + c) * 3 + canale] = (byte) Math.clamp(Math.round(m[r][c]), 0, 255);
                }
            }
        }
        double somma = 0;
        int chiari = 0;
        for (double[] riga : luce) {
            for (double l : riga) {
                somma += l;
                if (l > 225) {
                    chiari++;
                }
            }
        }
        double media = somma / (LATO * LATO);
        double varianza = 0;
        for (double[] riga : luce) {
            for (double l : riga) {
                varianza += (l - media) * (l - media);
            }
        }
        int contrasto = (int) Math.round(Math.sqrt(varianza / (LATO * LATO)));
        return new Firma(VERSIONE, dhash, colori, (int) Math.round(media), contrasto,
                Math.round(chiari * 100f / (LATO * LATO)));
    }

    /**
     * Media di {@code righe}×{@code colonne} riquadri (così non conta la
     * risoluzione): della luminosità con {@code canale} -1, altrimenti di R (0),
     * G (1) o B (2).
     */
    private static double[][] medie(int[] pixel, int w, int h, int righe, int colonne, int canale) {
        double[][] valori = new double[righe][colonne];
        for (int r = 0; r < righe; r++) {
            int y0 = Math.min(r * h / righe, h - 1);
            int y1 = Math.max(y0 + 1, (r + 1) * h / righe);
            for (int c = 0; c < colonne; c++) {
                int x0 = Math.min(c * w / colonne, w - 1);
                int x1 = Math.max(x0 + 1, (c + 1) * w / colonne);
                double somma = 0;
                for (int y = y0; y < y1; y++) {
                    for (int x = x0; x < x1; x++) {
                        int p = pixel[y * w + x];
                        somma += switch (canale) {
                            case 0 -> (p >> 16) & 0xff;
                            case 1 -> (p >> 8) & 0xff;
                            case 2 -> p & 0xff;
                            default -> 0.299 * ((p >> 16) & 0xff) + 0.587 * ((p >> 8) & 0xff) + 0.114 * (p & 0xff);
                        };
                    }
                }
                valori[r][c] = somma / ((y1 - y0) * (x1 - x0));
            }
        }
        return valori;
    }

    /** Distanza di Hamming: in quanti bit differiscono. */
    public static int distanza(long a, long b) {
        return Long.bitCount(a ^ b);
    }
}
