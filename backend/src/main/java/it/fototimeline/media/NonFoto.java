package it.fototimeline.media;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * I file col nome di una foto che una foto non sono, riconosciuti dal
 * contenuto: non c'è niente da leggere e niente da riprovare, vanno solo
 * buttati. Il messaggio lo dice in chiaro, al posto di "Immagine illeggibile".
 *
 * <ul>
 * <li>{@link #VUOTO}: 0 byte;
 * <li>{@link #ZERI}: solo byte 0, come i PICT0xxx.jpg di una scheda rovinata;
 * <li>{@link #MAC}: i file AppleDouble ({@code ._nome}) che il Mac scrive
 * accanto ai file veri sui dischi non suoi, a volte rinominati da un'importazione;
 * <li>{@link #PAGINA_WEB}: una pagina HTML (o un XML) salvata col nome di una foto.
 * </ul>
 */
public enum NonFoto {

    VUOTO("File vuoto (0 byte)"),
    ZERI("File vuoto: solo zeri, la foto non c'è più"),
    MAC("File del Mac (._), non è una foto"),
    PAGINA_WEB("Pagina web, non una foto");

    /** Il "magic number" dei file AppleDouble. */
    private static final byte[] APPLE_DOUBLE = {0x00, 0x05, 0x16, 0x07};
    /** Fin qui il file si legge tutto per dire se è fatto di zeri; oltre, a campione. */
    static final long LETTURA_INTERA = 4L * 1024 * 1024;
    /** Quanto è grande ogni campione. */
    static final int BLOCCO = 64 * 1024;
    /** Quanti campioni in un file grande, sparsi tra l'inizio e la fine. */
    static final int CAMPIONI = 16;

    private final String messaggio;

    NonFoto(String messaggio) {
        this.messaggio = messaggio;
    }

    /** Quello che vede chi importa, al posto di "Immagine illeggibile". */
    public String messaggio() {
        return messaggio;
    }

    /** True se il messaggio d'errore è quello di un file da buttare. */
    public static boolean daButtare(String messaggio) {
        if (messaggio == null) {
            return false;
        }
        for (NonFoto n : values()) {
            if (n.messaggio.equals(messaggio)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Dai primi byte (bastano i primi 64) e dalla dimensione: 0 byte,
     * AppleDouble, pagina web. Gli zeri no, perché i primi byte non bastano:
     * se sono tutti zero la risposta la dà {@link #soloZeri(Path)}.
     *
     * @return null se dall'inizio non si capisce (cioè quasi sempre)
     */
    public static NonFoto dallInizio(byte[] inizio, long dimensione) {
        if (dimensione == 0 || inizio == null || inizio.length == 0) {
            return dimensione == 0 ? VUOTO : null;
        }
        if (inizio.length >= APPLE_DOUBLE.length
                && Arrays.equals(inizio, 0, APPLE_DOUBLE.length, APPLE_DOUBLE, 0, APPLE_DOUBLE.length)) {
            return MAC;
        }
        return paginaWeb(inizio) ? PAGINA_WEB : null;
    }

    /** True se i primi byte ci sono e sono tutti zero: vale la pena di guardare il resto. */
    public static boolean iniziaConZeri(byte[] inizio) {
        if (inizio == null || inizio.length == 0) {
            return false;
        }
        for (byte b : inizio) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Il file intero, o per un file grande l'inizio, la fine e
     * {@link #CAMPIONI} blocchi sparsi, senza scaricarlo tutto: null se il
     * file non si riconosce o non si legge (lo dirà chi lo legge davvero).
     */
    public static NonFoto riconosci(Path file) {
        try (FileChannel canale = FileChannel.open(file, StandardOpenOption.READ)) {
            long dimensione = canale.size();
            ByteBuffer inizio = ByteBuffer.allocate((int) Math.min(Contenuto.INIZIO, dimensione));
            leggi(canale, inizio, 0);
            byte[] primi = Arrays.copyOf(inizio.array(), inizio.position());
            NonFoto n = dallInizio(primi, dimensione);
            if (n != null) {
                return n;
            }
            return iniziaConZeri(primi) && soloZeri(canale) ? ZERI : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Come {@link #riconosci} ma solo per gli zeri: il file intero se è piccolo, a campione se è grande. */
    public static boolean soloZeri(Path file) {
        try (FileChannel canale = FileChannel.open(file, StandardOpenOption.READ)) {
            return canale.size() > 0 && soloZeri(canale);
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static boolean soloZeri(FileChannel canale) throws IOException {
        long dimensione = canale.size();
        ByteBuffer blocco = ByteBuffer.allocate(BLOCCO);
        if (dimensione <= LETTURA_INTERA) {
            for (long da = 0; da < dimensione; da += BLOCCO) {
                if (!zeri(canale, blocco, da)) {
                    return false;
                }
            }
            return true;
        }
        // L'inizio (dove stanno le intestazioni), la fine, e blocchi a distanze uguali in mezzo.
        long ultimo = dimensione - BLOCCO;
        for (int k = 0; k <= CAMPIONI + 1; k++) {
            long da = ultimo * k / (CAMPIONI + 1);
            if (!zeri(canale, blocco, da)) {
                return false;
            }
        }
        return true;
    }

    private static boolean zeri(FileChannel canale, ByteBuffer blocco, long da) throws IOException {
        blocco.clear();
        leggi(canale, blocco, da);
        byte[] b = blocco.array();
        for (int i = 0; i < blocco.position(); i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static void leggi(FileChannel canale, ByteBuffer buffer, long da) throws IOException {
        long pos = da;
        while (buffer.hasRemaining()) {
            int n = canale.read(buffer, pos);
            if (n < 0) {
                return;
            }
            pos += n;
        }
    }

    /** {@code <!DOCTYPE}, {@code <html}, {@code <?xml}... dopo eventuali spazi e il BOM di UTF-8. */
    static boolean paginaWeb(byte[] inizio) {
        int i = 0;
        if (inizio.length >= 3 && (inizio[0] & 0xFF) == 0xEF && (inizio[1] & 0xFF) == 0xBB
                && (inizio[2] & 0xFF) == 0xBF) {
            i = 3;
        }
        while (i < inizio.length && (inizio[i] == ' ' || inizio[i] == '\t' || inizio[i] == '\r' || inizio[i] == '\n'
                || inizio[i] == '\f')) {
            i++;
        }
        String testo = new String(inizio, i, inizio.length - i, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        return testo.startsWith("<!doctype") || testo.startsWith("<html") || testo.startsWith("<?xml")
                || testo.startsWith("<head") || testo.startsWith("<body") || testo.startsWith("<!--");
    }
}
