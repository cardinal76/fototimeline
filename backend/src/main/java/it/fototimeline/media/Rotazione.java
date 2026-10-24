package it.fototimeline.media;

import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * La rotazione scelta a mano ({@code Foto.rotazione}: 0, 90, 180, 270 gradi
 * in senso orario), che vale <b>sopra</b> l'orientamento EXIF del file.
 *
 * <p>Gli otto orientamenti EXIF sono "prima specchia in orizzontale (o no),
 * poi gira di k quarti di giro in senso orario": girare ancora vuol dire solo
 * aggiungere quarti di giro, lo specchio resta. Così si calcola
 * l'orientamento da scrivere nella copia scaricata ({@link JpegRuotato}) o da
 * applicare alle miniature ({@link Riduzione}).
 */
public final class Rotazione {

    private Rotazione() {
    }

    /** Per ogni orientamento EXIF (1–8): quarti di giro in senso orario dopo lo specchio. */
    private static final int[] QUARTI = {0, 0, 2, 2, 3, 1, 1, 3};
    /** Per ogni orientamento EXIF (1–8): specchiata in orizzontale prima di girare. */
    private static final boolean[] SPECCHIO = {false, true, false, true, true, false, true, false};
    /** L'orientamento EXIF di [specchio][quarti]. */
    private static final int[][] ORIENTAMENTO = {{1, 6, 3, 8}, {2, 7, 4, 5}};

    /**
     * Gradi qualsiasi multipli di 90 (anche negativi: -90 è a sinistra) in
     * 0, 90, 180, 270.
     *
     * @throws IllegalArgumentException se non sono un multiplo di 90
     */
    public static int normalizza(int gradi) {
        if (gradi % 90 != 0) {
            throw new IllegalArgumentException("La rotazione va a quarti di giro (90°), non di " + gradi + "°");
        }
        return Math.floorMod(gradi, 360);
    }

    /** True se dopo {@code gradi} larghezza e altezza si scambiano (90 e 270). */
    public static boolean scambiaLati(int gradi) {
        return normalizza(gradi) % 180 != 0;
    }

    /**
     * L'orientamento EXIF (1–8) che dà la foto com'era con {@code orientamento}
     * e poi girata di {@code rotazione} gradi in senso orario. Un valore fuori
     * da 1–8 vale 1 (dritta), come fanno i browser.
     */
    public static int componi(int orientamento, int rotazione) {
        int o = orientamento >= 1 && orientamento <= 8 ? orientamento : 1;
        int quarti = (QUARTI[o - 1] + normalizza(rotazione) / 90) % 4;
        return ORIENTAMENTO[SPECCHIO[o - 1] ? 1 : 0][quarti];
    }

    /** Come {@link #componi(int, int)} con gli orientamenti di Thumbnailator (null = dritta). */
    public static Orientation componi(Orientation orientamento, int rotazione) {
        // L'enum è in ordine di valore EXIF: TOP_LEFT = 1 … LEFT_BOTTOM = 8.
        return Orientation.typeOf(componi(orientamento == null ? 1 : orientamento.ordinal() + 1, rotazione));
    }
}
