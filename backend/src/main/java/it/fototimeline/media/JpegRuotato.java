package it.fototimeline.media;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.SequenceInputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Un JPEG girato <b>senza perdita</b>: la copia ha lo stesso contenuto
 * compresso dell'originale (gli stessi pixel, byte per byte) e cambia solo il
 * tag EXIF Orientation, che browser, telefoni e programmi di foto rispettano.
 *
 * <p>L'originale nell'archivio non si tocca mai: il suo SHA-256 è la sua
 * identità ({@code Foto.hash}, il confronto con pCloud, i "già presenti"
 * delle importazioni). Qui si legge solo l'inizio del file (i segmenti APP,
 * pochi KB) e si prepara una {@link Testa} nuova; il resto si copia così
 * com'è, in streaming, senza mai tenere la foto in memoria.
 *
 * <p>Perché non jpegtran (rotazione vera dei blocchi DCT): nel container non
 * c'è, è un processo esterno per ogni download, e senza {@code -perfect}
 * taglia i bordi che non sono multipli di 8 o 16 pixel. Cambiare il tag non
 * perde niente, è istantaneo e non scarica il file due volte dal cloud.
 *
 * <ul>
 *   <li>EXIF con Orientation: si cambiano i suoi due byte;</li>
 *   <li>EXIF senza Orientation: una IFD0 nuova (le voci di prima più
 *       Orientation) in fondo al segmento, e l'intestazione TIFF punta lì.
 *       Gli offset dell'EXIF sono relativi all'inizio del TIFF e niente si
 *       sposta: i dati di prima (data, GPS, miniatura) restano validi;</li>
 *   <li>niente EXIF: un segmento APP1 minimo, dopo SOI (e dopo l'APP0 JFIF);</li>
 *   <li>XMP con {@code tiff:Orientation}: si cambia la cifra (stessa
 *       lunghezza), così Lightroom e simili non dicono il contrario.</li>
 * </ul>
 */
public final class JpegRuotato {

    private JpegRuotato() {
    }

    /** Il file non è un JPEG (o non si capisce dove sta l'EXIF): si dà l'originale. */
    public static final class NonRuotabile extends IOException {
        NonRuotabile(String messaggio) {
            super(messaggio);
        }
    }

    /**
     * L'inizio della copia girata ({@code testa}) e da dove l'originale
     * continua uguale ({@code resto}, offset nel file).
     *
     * @param orientamento quello scritto nella copia (1–8)
     */
    public record Testa(byte[] testa, long resto, int orientamento) {
    }

    /** Oltre questi byte di intestazioni si rinuncia: una foto vera ne ha pochi KB (ICC e MPF compresi). */
    static final int INTESTAZIONI_MASSIME = 4 * 1024 * 1024;
    private static final int TAG_ORIENTAMENTO = 0x0112;
    private static final byte[] EXIF = "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] XMP = "http://ns.adobe.com/xap/1.0/\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] JFIF = "JFIF\0".getBytes(StandardCharsets.ISO_8859_1);
    private static final Pattern XMP_ORIENTAMENTO = Pattern.compile(
            "tiff:Orientation\\s*=\\s*[\"']([1-8])[\"']|<tiff:Orientation>\\s*([1-8])\\s*</tiff:Orientation>");

    /** Un segmento APPn/COM dell'intestazione: dove comincia il marker e il suo contenuto. */
    private record Segmento(long inizio, int marker, byte[] contenuto) {
        long fine() {
            return inizio + 4 + contenuto.length;
        }
    }

    /** Un pezzo da sostituire: i byte [inizio, fine) dell'originale diventano {@code byte}. */
    private record Cambio(long inizio, long fine, byte[] nuovi) {
    }

    /**
     * Prepara la copia di {@code jpeg} girata di {@code rotazione} gradi in
     * senso orario, sopra l'orientamento che ha già.
     *
     * @throws NonRuotabile se non è un JPEG o l'intestazione non si capisce
     */
    public static Testa prepara(Path jpeg, int rotazione) throws IOException {
        try (SeekableByteChannel canale = Files.newByteChannel(jpeg)) {
            long dimensione = canale.size();
            if (dimensione < 4 || u16(leggi(canale, 0, 2), 0, true) != 0xFFD8) {
                throw new NonRuotabile("Non è un JPEG");
            }
            List<Segmento> segmenti = segmenti(canale, dimensione);
            List<Cambio> cambi = new ArrayList<>();
            Segmento exif = segmenti.stream().filter(s -> s.marker() == 0xE1 && inizia(s.contenuto(), EXIF))
                    .findFirst().orElse(null);
            int orientamento;
            if (exif != null) {
                byte[][] nuovo = new byte[1][];
                orientamento = cambiaExif(exif.contenuto(), rotazione, nuovo);
                cambi.add(new Cambio(exif.inizio(), exif.fine(), segmento(0xE1, nuovo[0])));
            } else {
                orientamento = Rotazione.componi(1, rotazione);
                // Dopo l'APP0 JFIF, se c'è subito dopo SOI (deve restare il primo); altrimenti subito dopo SOI.
                long dove = !segmenti.isEmpty() && segmenti.getFirst().marker() == 0xE0
                        && segmenti.getFirst().inizio() == 2 && inizia(segmenti.getFirst().contenuto(), JFIF)
                                ? segmenti.getFirst().fine()
                                : 2;
                cambi.add(new Cambio(dove, dove, segmento(0xE1, exifMinimo(orientamento))));
            }
            for (Segmento s : segmenti) {
                if (s.marker() == 0xE1 && inizia(s.contenuto(), XMP)) {
                    byte[] xmp = cambiaXmp(s.contenuto(), orientamento);
                    if (xmp != null) {
                        cambi.add(new Cambio(s.inizio(), s.fine(), segmento(0xE1, xmp)));
                    }
                }
            }
            cambi.sort((a, b) -> Long.compare(a.inizio(), b.inizio()));
            long resto = cambi.getLast().fine();
            ByteArrayOutputStream testa = new ByteArrayOutputStream((int) resto + 256);
            long letto = 0;
            for (Cambio c : cambi) {
                testa.write(leggi(canale, letto, (int) (c.inizio() - letto)));
                testa.write(c.nuovi());
                letto = c.fine();
            }
            return new Testa(testa.toByteArray(), resto, orientamento);
        }
    }

    /** La copia girata: la testa nuova, poi l'originale da {@code resto} in poi. */
    public static InputStream apri(Path jpeg, Testa testa) throws IOException {
        SeekableByteChannel canale = Files.newByteChannel(jpeg);
        try {
            canale.position(testa.resto());
        } catch (IOException e) {
            canale.close();
            throw e;
        }
        return new SequenceInputStream(new ByteArrayInputStream(testa.testa()), Channels.newInputStream(canale));
    }

    /** Quanti byte è lunga la copia girata. */
    public static long lunghezza(Path jpeg, Testa testa) throws IOException {
        return testa.testa().length + (Files.size(jpeg) - testa.resto());
    }

    /** Scrive la copia girata su {@code uscita} (lo zip dei link condivisi), a pezzi, senza tenerla in memoria. */
    public static void scrivi(Path jpeg, Testa testa, OutputStream uscita) throws IOException {
        try (InputStream in = apri(jpeg, testa)) {
            in.transferTo(uscita);
        }
    }

    // ---------------------------------------------------------------- intestazione

    /** I segmenti APPn e COM dall'inizio, fino al primo che non lo è (tabelle, SOF, SOS). */
    private static List<Segmento> segmenti(SeekableByteChannel canale, long dimensione) throws IOException {
        List<Segmento> segmenti = new ArrayList<>();
        long pos = 2;
        while (pos + 4 <= dimensione) {
            byte[] marker = leggi(canale, pos, 4);
            if ((marker[0] & 0xFF) != 0xFF) {
                throw new NonRuotabile("Intestazione JPEG non valida");
            }
            int tipo = marker[1] & 0xFF;
            boolean app = tipo >= 0xE0 && tipo <= 0xEF || tipo == 0xFE;
            if (!app) {
                break;
            }
            int lunghezza = u16(marker, 2, true);
            if (lunghezza < 2 || pos + 2 + lunghezza > dimensione) {
                throw new NonRuotabile("Segmento JPEG troncato");
            }
            if (pos + 2 + lunghezza > INTESTAZIONI_MASSIME) {
                // Trovato l'EXIF, il resto si copia com'è; senza, non si sa se arriva più avanti.
                if (segmenti.stream().anyMatch(s -> s.marker() == 0xE1 && inizia(s.contenuto(), EXIF))) {
                    break;
                }
                throw new NonRuotabile("Intestazioni JPEG troppo grandi");
            }
            segmenti.add(new Segmento(pos, tipo, leggi(canale, pos + 4, lunghezza - 2)));
            pos += 2 + lunghezza;
        }
        return segmenti;
    }

    /**
     * L'APP1 EXIF con l'orientamento girato; restituisce l'orientamento
     * scritto e mette il contenuto nuovo in {@code nuovo[0]}.
     */
    private static int cambiaExif(byte[] contenuto, int rotazione, byte[][] nuovo) throws NonRuotabile {
        final int tiff = EXIF.length;
        if (contenuto.length < tiff + 8) {
            throw new NonRuotabile("EXIF troppo corto");
        }
        boolean be;
        if (contenuto[tiff] == 'M' && contenuto[tiff + 1] == 'M') {
            be = true;
        } else if (contenuto[tiff] == 'I' && contenuto[tiff + 1] == 'I') {
            be = false;
        } else {
            throw new NonRuotabile("EXIF senza ordine dei byte");
        }
        if (u16(contenuto, tiff + 2, be) != 42) {
            throw new NonRuotabile("EXIF non TIFF");
        }
        long ifd0 = u32(contenuto, tiff + 4, be);
        if (ifd0 < 8 || tiff + ifd0 + 2 > contenuto.length) {
            throw new NonRuotabile("IFD0 fuori dall'EXIF");
        }
        int voci = u16(contenuto, (int) (tiff + ifd0), be);
        int primaVoce = (int) (tiff + ifd0 + 2);
        if (primaVoce + 12L * voci + 4 > contenuto.length) {
            throw new NonRuotabile("IFD0 troncata");
        }
        for (int i = 0; i < voci; i++) {
            int voce = primaVoce + 12 * i;
            if (u16(contenuto, voce, be) == TAG_ORIENTAMENTO) {
                int tipo = u16(contenuto, voce + 2, be);
                int vecchio = tipo == 4 ? (int) u32(contenuto, voce + 8, be) : u16(contenuto, voce + 8, be);
                int orientamento = Rotazione.componi(vecchio, rotazione);
                byte[] copia = contenuto.clone();
                // SHORT, uno, nel campo del valore (allineato a sinistra): com'è quasi sempre.
                metti16(copia, voce + 2, 3, be);
                metti32(copia, voce + 4, 1, be);
                metti16(copia, voce + 8, orientamento, be);
                metti16(copia, voce + 10, 0, be);
                nuovo[0] = copia;
                return orientamento;
            }
        }
        // Niente Orientation: la IFD0 si ricopia in fondo con la voce in più, al suo posto in ordine di tag.
        int orientamento = Rotazione.componi(1, rotazione);
        int fine = contenuto.length - tiff;
        int allineamento = fine % 2;
        int lunghezzaIfd = 2 + 12 * (voci + 1) + 4;
        if (contenuto.length + allineamento + lunghezzaIfd > 0xFFFF - 2) {
            throw new NonRuotabile("EXIF troppo grande per aggiungere l'orientamento");
        }
        byte[] copia = Arrays.copyOf(contenuto, contenuto.length + allineamento + lunghezzaIfd);
        int scrivi = contenuto.length + allineamento;
        metti16(copia, scrivi, voci + 1, be);
        scrivi += 2;
        boolean messa = false;
        for (int i = 0; i < voci; i++) {
            int voce = primaVoce + 12 * i;
            if (!messa && u16(contenuto, voce, be) > TAG_ORIENTAMENTO) {
                scrivi = vociOrientamento(copia, scrivi, orientamento, be);
                messa = true;
            }
            System.arraycopy(contenuto, voce, copia, scrivi, 12);
            scrivi += 12;
        }
        if (!messa) {
            scrivi = vociOrientamento(copia, scrivi, orientamento, be);
        }
        // La IFD dopo (IFD1, la miniatura dell'EXIF) resta quella.
        System.arraycopy(contenuto, primaVoce + 12 * voci, copia, scrivi, 4);
        metti32(copia, tiff + 4, fine + allineamento, be);
        nuovo[0] = copia;
        return orientamento;
    }

    private static int vociOrientamento(byte[] b, int dove, int orientamento, boolean be) {
        metti16(b, dove, TAG_ORIENTAMENTO, be);
        metti16(b, dove + 2, 3, be);
        metti32(b, dove + 4, 1, be);
        metti16(b, dove + 8, orientamento, be);
        metti16(b, dove + 10, 0, be);
        return dove + 12;
    }

    /** "Exif\0\0" + TIFF big-endian con la sola IFD0 e la sola voce Orientation. */
    private static byte[] exifMinimo(int orientamento) {
        byte[] b = new byte[EXIF.length + 8 + 2 + 12 + 4];
        System.arraycopy(EXIF, 0, b, 0, EXIF.length);
        int t = EXIF.length;
        b[t] = 'M';
        b[t + 1] = 'M';
        metti16(b, t + 2, 42, true);
        metti32(b, t + 4, 8, true);
        metti16(b, t + 8, 1, true);
        vociOrientamento(b, t + 10, orientamento, true);
        metti32(b, t + 22, 0, true);
        return b;
    }

    /** L'XMP con la cifra di {@code tiff:Orientation} cambiata; null se non ce l'ha o è già quella. */
    private static byte[] cambiaXmp(byte[] contenuto, int orientamento) {
        String testo = new String(contenuto, StandardCharsets.ISO_8859_1);
        Matcher m = XMP_ORIENTAMENTO.matcher(testo);
        if (!m.find()) {
            return null;
        }
        int gruppo = m.group(1) != null ? 1 : 2;
        byte[] copia = contenuto.clone();
        copia[m.start(gruppo)] = (byte) ('0' + orientamento);
        return Arrays.equals(copia, contenuto) ? null : copia;
    }

    /** Marker, lunghezza e contenuto. */
    private static byte[] segmento(int marker, byte[] contenuto) {
        byte[] b = new byte[4 + contenuto.length];
        b[0] = (byte) 0xFF;
        b[1] = (byte) marker;
        metti16(b, 2, contenuto.length + 2, true);
        System.arraycopy(contenuto, 0, b, 4, contenuto.length);
        return b;
    }

    // ---------------------------------------------------------------- byte

    private static byte[] leggi(SeekableByteChannel canale, long da, int quanti) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(quanti);
        canale.position(da);
        while (buffer.hasRemaining()) {
            if (canale.read(buffer) < 0) {
                throw new NonRuotabile("JPEG troncato");
            }
        }
        return buffer.array();
    }

    private static boolean inizia(byte[] b, byte[] prefisso) {
        return b.length >= prefisso.length && Arrays.equals(b, 0, prefisso.length, prefisso, 0, prefisso.length);
    }

    private static int u16(byte[] b, int i, boolean be) {
        int a = b[i] & 0xFF;
        int c = b[i + 1] & 0xFF;
        return be ? a << 8 | c : c << 8 | a;
    }

    private static long u32(byte[] b, int i, boolean be) {
        long alto = u16(b, be ? i : i + 2, be);
        long basso = u16(b, be ? i + 2 : i, be);
        return alto << 16 | basso;
    }

    private static void metti16(byte[] b, int i, int v, boolean be) {
        b[be ? i : i + 1] = (byte) (v >>> 8);
        b[be ? i + 1 : i] = (byte) v;
    }

    private static void metti32(byte[] b, int i, long v, boolean be) {
        metti16(b, be ? i : i + 2, (int) (v >>> 16) & 0xFFFF, be);
        metti16(b, be ? i + 2 : i, (int) v & 0xFFFF, be);
    }
}
