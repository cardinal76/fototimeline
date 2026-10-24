package it.fototimeline.media;

/**
 * Quello che serve di un video per ricomprimerlo: la durata (per controllare
 * il risultato) e dimensioni e formato dei pixel (per stimare la memoria di
 * x265, {@link #memoriaX265()}).
 *
 * @param durata    in secondi
 * @param pixel     il {@code pix_fmt} di ffprobe, per esempio {@code yuv420p10le}
 */
public record FormatoVideo(Double durata, Integer larghezza, Integer altezza, String pixel) {

    /** MB per megapixel con 8 bit e con 10 o 12 (misurati con i parametri predefiniti, DEPLOY.md). */
    private static final long MB_PER_MP_8_BIT = 115;
    private static final long MB_PER_MP_10_BIT = 185;
    private static final long MB_BASE = 50;

    /** True se i campioni sono a più di 8 bit (x265 lavora a 16 bit per campione: quasi il doppio della memoria). */
    public boolean piuDi8Bit() {
        return pixel != null && pixel.matches(".*(p10|p12|p16|10le|12le|10be|12be).*");
    }

    /**
     * Quanta memoria (byte) prenderà ffmpeg con x265 su questo video, stimata
     * dalle misure: 4K a 8 bit ~0,9 GB, a 10 bit ~1,5 GB, 8K a 8 bit ~3,4 GB.
     * Null se ffprobe non dice le dimensioni.
     */
    public Long memoriaX265() {
        if (larghezza == null || altezza == null) {
            return null;
        }
        double megapixel = larghezza * (double) altezza / 1_000_000;
        long mb = MB_BASE + Math.round(megapixel * (piuDi8Bit() ? MB_PER_MP_10_BIT : MB_PER_MP_8_BIT));
        return mb * 1024 * 1024;
    }
}
