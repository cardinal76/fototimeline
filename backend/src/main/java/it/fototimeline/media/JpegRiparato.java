package it.fototimeline.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Un JPEG con un SOI ({@code FFD8}) fuori posto nella testa, che libjpeg (e
 * con lei ImageIO e i browser) rifiuta: "Invalid JPEG file structure: two SOI
 * markers" o "No SOF segment in stream". Succede con alcune foto dei telefoni
 * Xiaomi: un SOI ripetuto, o un'immagine intera (la miniatura, la mappa HDR)
 * infilata tra i segmenti della testa invece che in un APP. ffmpeg e i
 * programmi del PC li leggono perché ripartono dal SOI.
 *
 * <p>{@link #ripara} riscrive il file con la stessa testa senza quei SOI (e
 * senza l'immagine che inizia lì, se è un'immagine intera seguita da altri
 * segmenti della principale) e poi, byte per byte, tutto da SOS in avanti:
 * i pixel compressi, l'EXIF (orientamento compreso), le immagini in coda
 * (MPF, Ultra HDR) restano quelli. L'originale non si tocca.
 */
public final class JpegRiparato {

    /** Oltre questi byte di testa (prima del SOS) non si cerca: non è più una testa. */
    private static final long TESTA_MASSIMA = 32L * 1024 * 1024;

    private JpegRiparato() {
    }

    /**
     * Scrive in {@code destinazione} il JPEG riparato.
     *
     * @return false (e {@code destinazione} non toccata) se non è un JPEG, se la
     *         testa non ha SOI fuori posto o se non si capisce dov'è l'immagine
     */
    public static boolean ripara(Path sorgente, Path destinazione) throws IOException {
        try (FileChannel canale = FileChannel.open(sorgente, StandardOpenOption.READ)) {
            Lettore l = new Lettore(canale);
            List<long[]> pezzi = pezzi(l);
            if (pezzi == null) {
                return false;
            }
            try (FileChannel uscita = FileChannel.open(destinazione, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (long[] p : pezzi) {
                    long da = p[0];
                    long fine = p[1];
                    while (da < fine) {
                        long scritti = canale.transferTo(da, fine - da, uscita);
                        if (scritti <= 0) {
                            throw new IOException("Copia del JPEG interrotta");
                        }
                        da += scritti;
                    }
                }
            }
            return true;
        }
    }

    /** True se la testa ha SOI fuori posto: {@link #ripara} farebbe qualcosa. */
    public static boolean daRiparare(Path file) {
        try (FileChannel canale = FileChannel.open(file, StandardOpenOption.READ)) {
            return pezzi(new Lettore(canale)) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * I pezzi del file da tenere, {da, fine}: SOI, i segmenti della testa e
     * tutto da SOS alla fine. Null se non c'è niente da riparare.
     */
    private static List<long[]> pezzi(Lettore l) throws IOException {
        if (l.dimensione < 4 || l.at(0) != 0xFF || l.at(1) != 0xD8) {
            return null;
        }
        List<long[]> pezzi = new ArrayList<>();
        pezzi.add(new long[] {0, 2});
        boolean riparato = false;
        long pos = 2;
        while (pos + 1 < l.dimensione && pos < TESTA_MASSIMA) {
            if (l.at(pos) != 0xFF) {
                // Byte di troppo tra i segmenti: libjpeg li salta con un avviso, qui non si copiano.
                pos++;
                continue;
            }
            int m = l.at(pos + 1);
            if (m == 0xFF) {
                pos++;
                continue;
            }
            if (m == 0xD8) {
                long dopo = fineImmagineInclusa(l, pos);
                pos = dopo > 0 ? dopo : pos + 2;
                riparato = true;
                continue;
            }
            if (m == 0xDA) {
                pezzi.add(new long[] {pos, l.dimensione});
                return riparato ? pezzi : null;
            }
            if (m == 0xD9 || m == 0x00) {
                return null;
            }
            if ((m >= 0xD0 && m <= 0xD7) || m == 0x01) {
                pos += 2;
                continue;
            }
            if (pos + 3 >= l.dimensione) {
                return null;
            }
            int lunghezza = (l.at(pos + 2) << 8) | l.at(pos + 3);
            if (lunghezza < 2) {
                return null;
            }
            pezzi.add(new long[] {pos, Math.min(l.dimensione, pos + 2 + lunghezza)});
            pos += 2 + lunghezza;
        }
        return null;
    }

    /**
     * Se dal SOI in {@code soi} inizia un'immagine intera (segmenti, SOS,
     * dati, EOI) seguita da altri segmenti della testa principale, la
     * posizione dopo il suo EOI; altrimenti -1 (il SOI è solo ripetuto: quello
     * che segue è l'immagine principale, o l'immagine dopo è in coda al file).
     */
    private static long fineImmagineInclusa(Lettore l, long soi) throws IOException {
        long pos = soi + 2;
        while (true) {
            while (pos + 1 < l.dimensione && l.at(pos) == 0xFF && l.at(pos + 1) == 0xFF) {
                pos++;
            }
            if (pos + 3 >= l.dimensione || l.at(pos) != 0xFF) {
                return -1;
            }
            int m = l.at(pos + 1);
            if (m == 0xD8 || m == 0xD9) {
                return -1;
            }
            int lunghezza = (l.at(pos + 2) << 8) | l.at(pos + 3);
            if (lunghezza < 2) {
                return -1;
            }
            pos += 2 + lunghezza;
            if (m == 0xDA) {
                break;
            }
        }
        // I dati compressi: un FF vero è seguito da 00 (byte di riempimento) o da un RST;
        // un'immagine progressiva ha altri segmenti in mezzo. Finisce al primo EOI.
        long fine = -1;
        for (long i = pos; i + 1 < l.dimensione; i++) {
            if (l.at(i) == 0xFF && l.at(i + 1) == 0xD9) {
                fine = i + 2;
                break;
            }
        }
        if (fine < 0) {
            return -1;
        }
        long dopo = fine;
        while (dopo + 1 < l.dimensione && l.at(dopo) == 0xFF && l.at(dopo + 1) == 0xFF) {
            dopo++;
        }
        if (dopo + 1 >= l.dimensione || l.at(dopo) != 0xFF) {
            return -1;
        }
        int seguente = l.at(dopo + 1);
        boolean segmentoDellaTesta = seguente >= 0xC0 && seguente <= 0xFE && seguente != 0xD8 && seguente != 0xD9
                && (seguente < 0xD0 || seguente > 0xD7);
        return segmentoDellaTesta ? fine : -1;
    }

    /** Legge byte qua e là dal file con un buffer, senza caricarlo tutto. */
    private static final class Lettore {
        private final FileChannel canale;
        private final long dimensione;
        private final ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
        private long inizioBuffer = -1;
        private int fineBuffer;

        Lettore(FileChannel canale) throws IOException {
            this.canale = canale;
            this.dimensione = canale.size();
        }

        int at(long pos) throws IOException {
            if (inizioBuffer < 0 || pos < inizioBuffer || pos >= inizioBuffer + fineBuffer) {
                buffer.clear();
                int letti = 0;
                while (buffer.hasRemaining()) {
                    int n = canale.read(buffer, pos + letti);
                    if (n < 0) {
                        break;
                    }
                    letti += n;
                }
                if (letti == 0) {
                    throw new IOException("Fine del file a " + pos);
                }
                inizioBuffer = pos;
                fineBuffer = letti;
            }
            return buffer.get((int) (pos - inizioBuffer)) & 0xFF;
        }
    }
}
