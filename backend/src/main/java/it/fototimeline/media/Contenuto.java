package it.fototimeline.media;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

/**
 * Il formato vero di un file dai suoi primi byte, non dal nome: i download
 * di Facebook ({@code ..._n.heic}) sono spesso JPEG, qualche telefono salva
 * un HEIC come {@code .jpg}. Chi decide come leggere, convertire e servire
 * il file guarda qui; l'estensione vale solo se il contenuto non si riconosce
 * (file rotti, vuoti, formati nuovi), come prima.
 */
public final class Contenuto {

    /** I "brand" HEIF del box {@code ftyp} (HEIC e simili), da leggere con heif-convert. */
    private static final Set<String> HEIF = Set.of("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "mif2");
    private static final Set<String> AVIF = Set.of("avif", "avis");
    /** I primi box di un MOV vecchio, senza {@code ftyp}. */
    private static final Set<String> QUICKTIME = Set.of("moov", "mdat", "wide", "free", "skip", "pnot");

    /** Quanti byte servono: il box ftyp con i suoi brand compatibili. */
    public static final int INIZIO = 64;

    private Contenuto() {
    }

    /** Il tipo MIME dal contenuto, o null se non si riconosce (o il file non si legge). */
    public static String tipo(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return tipo(in.readNBytes(INIZIO));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Come {@link #tipo(Path)}, dai primi byte. */
    public static String tipo(byte[] b) {
        if (b == null || b.length < 4) {
            return null;
        }
        if (u(b, 0) == 0xFF && u(b, 1) == 0xD8 && u(b, 2) == 0xFF) {
            return "image/jpeg";
        }
        if (u(b, 0) == 0x89 && testo(b, 1, 3).equals("PNG")) {
            return "image/png";
        }
        if (testo(b, 0, 4).equals("GIF8")) {
            return "image/gif";
        }
        if (b.length >= 12 && testo(b, 0, 4).equals("RIFF") && testo(b, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        if (b.length >= 12 && testo(b, 4, 4).equals("ftyp")) {
            return isoBmff(b);
        }
        if (b.length >= 8 && QUICKTIME.contains(testo(b, 4, 4))) {
            return "video/quicktime";
        }
        // "BM" e basta è poco: si guarda anche che la testa dica dove iniziano i pixel.
        if (b.length >= 14 && testo(b, 0, 2).equals("BM") && u(b, 10) + (u(b, 11) << 8) >= 26) {
            return "image/bmp";
        }
        return null;
    }

    /** Un file ISO/MP4 (HEIC, AVIF, MP4, MOV): lo dicono il brand principale e quelli compatibili. */
    private static String isoBmff(byte[] b) {
        long lunghezza = ((long) u(b, 0) << 24) | (u(b, 1) << 16) | (u(b, 2) << 8) | u(b, 3);
        int fine = (int) Math.min(b.length, lunghezza < 16 ? 16 : lunghezza);
        String principale = testo(b, 8, 4);
        boolean heif = HEIF.contains(principale);
        boolean avif = AVIF.contains(principale);
        for (int i = 16; i + 4 <= fine; i += 4) {
            String brand = testo(b, i, 4);
            avif |= AVIF.contains(brand);
            heif |= brand.equals("heic") || brand.equals("heix");
        }
        // "mif1" da solo è HEIF generico: AVIF se tra i compatibili c'è avif, altrimenti HEIC.
        if (AVIF.contains(principale) || (avif && !principale.startsWith("he"))) {
            return "image/avif";
        }
        if (heif) {
            return "image/heic";
        }
        return principale.equals("qt  ") ? "video/quicktime" : "video/mp4";
    }

    private static int u(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static String testo(byte[] b, int da, int quanti) {
        return da + quanti > b.length ? "" : new String(b, da, quanti, StandardCharsets.ISO_8859_1);
    }
}
