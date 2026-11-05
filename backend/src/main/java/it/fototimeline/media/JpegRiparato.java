package it.fototimeline.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Un JPEG con un SOI ({@code FFD8}) fuori posto, che libjpeg (e con lei
 * ImageIO e i browser) rifiuta: "Invalid JPEG file structure: two SOI
 * markers" o "No SOF segment in stream". Succede con alcune foto dei telefoni
 * Xiaomi, in due modi:
 * <ul>
 * <li>nella testa: un SOI ripetuto, o un'immagine intera (la miniatura, la
 * mappa HDR) infilata tra i segmenti invece che in un APP;
 * <li>dopo i pixel: l'immagine principale finisce senza EOI ({@code FFD9}) e
 * subito dopo ne comincia un'altra (la mappa del guadagno Ultra HDR), o tra
 * una scansione e l'altra di una progressiva c'è un SOI. Qui la testa è
 * buona: le dimensioni si leggono, è decodificando i pixel che libjpeg
 * incontra il secondo SOI.
 * </ul>
 * ffmpeg e i programmi del PC li leggono perché ripartono dal SOI.
 *
 * <p>{@link #ripara} riscrive il file con la stessa testa senza quei SOI (e
 * senza l'immagine che inizia lì, se è un'immagine intera seguita da altri
 * segmenti della principale) e poi, byte per byte, tutto da SOS in avanti:
 * i pixel compressi, l'EXIF (orientamento compreso), le immagini in coda
 * (MPF, Ultra HDR) restano quelli. Se dopo i pixel c'è un SOI prima dell'EOI,
 * l'immagine si chiude lì con un EOI e il resto non si copia. L'originale non
 * si tocca.
 */
public final class JpegRiparato {

    /** Oltre questi byte di testa (prima del SOS) non si cerca: non è più una testa. */
    private static final long TESTA_MASSIMA = 32L * 1024 * 1024;
    private static final byte[] EOI = {(byte) 0xFF, (byte) 0xD9};

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
            Riparazione r = pezzi(l);
            if (r == null) {
                return false;
            }
            try (FileChannel uscita = FileChannel.open(destinazione, StandardOpenOption.WRITE,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (long[] p : r.pezzi()) {
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
                if (r.chiudi()) {
                    ByteBuffer fine = ByteBuffer.wrap(EOI);
                    while (fine.hasRemaining()) {
                        uscita.write(fine);
                    }
                }
            }
            return true;
        }
    }

    /**
     * True se c'è un SOI fuori posto, nella testa o dopo i pixel: {@link #ripara}
     * farebbe qualcosa. Per guardare dopo i pixel legge il file fino all'EOI
     * (in blocchi, senza tenerlo in memoria).
     */
    public static boolean daRiparare(Path file) {
        try (FileChannel canale = FileChannel.open(file, StandardOpenOption.READ)) {
            return pezzi(new Lettore(canale)) != null;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** I pezzi del file da copiare, {da, fine}, e se dopo l'ultimo va scritto un EOI. */
    private record Riparazione(List<long[]> pezzi, boolean chiudi) {
    }

    /**
     * I pezzi del file da tenere, {da, fine}: SOI, i segmenti della testa e
     * tutto da SOS alla fine (o fino al SOI dopo i pixel). Null se non c'è
     * niente da riparare.
     */
    private static Riparazione pezzi(Lettore l) throws IOException {
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
                long soiDopo = soiDopoIPixel(l, pos);
                if (soiDopo > 0) {
                    pezzi.add(new long[] {pos, soiDopo});
                    return new Riparazione(pezzi, true);
                }
                pezzi.add(new long[] {pos, l.dimensione});
                return riparato ? new Riparazione(pezzi, false) : null;
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
     * Dal primo SOS in {@code sos}: la posizione di un SOI che viene prima
     * dell'EOI dell'immagine, o -1 se l'immagine finisce bene (EOI, anche con
     * altre immagini dopo, come MPF e Ultra HDR) o il file finisce prima (un
     * JPEG troncato, che libjpeg legge lo stesso con un avviso).
     *
     * <p>Nei dati compressi un {@code FF} vero è seguito da {@code 00} (byte
     * di riempimento) o da un RST; ogni altro marcatore è un segmento tra due
     * scansioni (DHT, SOS di una progressiva, DRI...) e si salta con la sua
     * lunghezza.
     */
    private static long soiDopoIPixel(Lettore l, long sos) throws IOException {
        long pos = sos;
        while (true) {
            // pos è su un marcatore con la sua lunghezza (SOS o un segmento tra le scansioni).
            if (pos + 3 >= l.dimensione) {
                return -1;
            }
            int lunghezza = (l.at(pos + 2) << 8) | l.at(pos + 3);
            if (lunghezza < 2) {
                return -1;
            }
            long i = pos + 2 + lunghezza;
            while (true) {
                i = l.prossimoFF(i);
                if (i < 0 || i + 1 >= l.dimensione) {
                    return -1;
                }
                int m = l.at(i + 1);
                while (m == 0xFF) {
                    i++;
                    if (i + 1 >= l.dimensione) {
                        return -1;
                    }
                    m = l.at(i + 1);
                }
                if (m == 0x00 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) {
                    i += 2;
                    continue;
                }
                if (m == 0xD9) {
                    return -1;
                }
                if (m == 0xD8) {
                    return i;
                }
                break;
            }
            pos = i;
        }
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

        /** La posizione del primo {@code FF} da {@code da} in avanti, o -1: un blocco alla volta. */
        long prossimoFF(long da) throws IOException {
            long pos = da;
            while (pos < dimensione) {
                at(pos);
                byte[] b = buffer.array();
                int inizio = (int) (pos - inizioBuffer);
                for (int k = inizio; k < fineBuffer; k++) {
                    if (b[k] == (byte) 0xFF) {
                        return inizioBuffer + k;
                    }
                }
                pos = inizioBuffer + fineBuffer;
            }
            return -1;
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
