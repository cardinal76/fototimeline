package it.fototimeline.media;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;

import net.coobird.thumbnailator.Thumbnails;
import net.coobird.thumbnailator.util.exif.ExifFilterUtils;
import net.coobird.thumbnailator.util.exif.Orientation;

/**
 * Un JPEG ridotto (miniatura, vista dei link condivisi) da un'immagine che
 * ImageIO sa leggere, senza mai decodificarla a piena risoluzione.
 *
 * <p>{@code Thumbnails.of(file)} decodifica tutta l'immagine e poi la
 * rimpicciolisce a passi: una foto da 12 MP sono ~100 MB di heap, una da
 * 50 MP (i telefoni di oggi) ~460 MB, più dei 384 che la JVM aveva sul
 * server. Qui l'immagine si legge già sottocampionata
 * ({@link ImageReadParam#setSourceSubsampling}: il lettore salta righe e
 * colonne mentre decodifica), quanto basta per avere almeno il doppio del
 * lato voluto, e solo quella si passa a Thumbnailator per la riduzione
 * buona. Con 480 px di lato una foto da 50 MP occupa così pochi MB.
 *
 * <p>L'orientamento EXIF lo legge metadata-extractor (solo le intestazioni)
 * e lo applica Thumbnailator dopo la riduzione, come prima; la rotazione
 * scelta a mano ({@link Rotazione}) si somma a lui.
 */
public final class Riduzione {

    private Riduzione() {
    }

    /**
     * Scrive in {@code destinazione} un JPEG che sta in {@code lato}×{@code lato}.
     *
     * @param exif       true per raddrizzarla secondo l'EXIF; false se è già
     *                   dritta (un JPEG da heif-convert, un fotogramma di ffmpeg)
     * @param ingrandisci false per lasciare com'è un'immagine già più piccola del lato
     */
    public static void jpeg(Path sorgente, Path destinazione, int lato, boolean exif, double qualita,
            boolean ingrandisci) throws IOException {
        jpeg(sorgente, destinazione, lato, exif, qualita, ingrandisci, 0);
    }

    /**
     * Come sopra, girata in più di {@code rotazione} gradi in senso orario
     * ({@code Foto.rotazione}), sopra l'orientamento EXIF: una sola
     * trasformazione, dopo la riduzione.
     */
    public static void jpeg(Path sorgente, Path destinazione, int lato, boolean exif, double qualita,
            boolean ingrandisci, int rotazione) throws IOException {
        Orientation orientamento = Rotazione.componi(exif ? orientamento(sorgente) : null, rotazione);
        BufferedImage immagine = leggi(sorgente, lato);
        var costruttore = Thumbnails.of(immagine);
        int lungo = Math.max(immagine.getWidth(), immagine.getHeight());
        if (!ingrandisci && lungo <= lato) {
            costruttore.scale(1.0);
        } else {
            costruttore.size(lato, lato);
        }
        if (orientamento != null && orientamento != Orientation.TOP_LEFT) {
            costruttore.addFilter(ExifFilterUtils.getFilterForOrientation(orientamento));
        }
        costruttore.imageType(BufferedImage.TYPE_INT_RGB)
                .outputFormat("jpg")
                .outputQuality(qualita)
                .toFile(destinazione.toFile());
    }

    /**
     * Quanti pixel si leggono al massimo: 8 MP sono 24 MB di pixel, e con le
     * copie che fa Thumbnailator per ridurla meno di 100 MB di heap.
     */
    static final long PIXEL_MASSIMI = 8_000_000;

    /**
     * Ogni quanti pixel se ne legge uno (1 = tutta l'immagine): il lato lungo
     * letto resta almeno il doppio di {@code lato}, perché la riduzione buona
     * (con l'interpolazione) la faccia Thumbnailator; ma mai più di
     * {@link #PIXEL_MASSIMI} pixel, anche per la vista dei link condivisi
     * (2048 px) da una foto da 50 MP.
     */
    static int passo(int larghezza, int altezza, int lato) {
        long lungo = Math.max(larghezza, altezza);
        long perIlLato = lungo / (2L * Math.max(1, lato));
        long perLaMemoria = (long) Math.ceil(Math.sqrt((double) larghezza * altezza / PIXEL_MASSIMI));
        return (int) Math.max(1, Math.max(perIlLato, perLaMemoria));
    }

    /** L'immagine sottocampionata secondo {@link #passo}. */
    static BufferedImage leggi(Path sorgente, int lato) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(sorgente.toFile())) {
            Iterator<ImageReader> lettori = in == null ? null : ImageIO.getImageReaders(in);
            if (lettori == null || !lettori.hasNext()) {
                throw new IOException("Nessun lettore per questa immagine");
            }
            ImageReader lettore = lettori.next();
            try {
                lettore.setInput(in, true, true);
                int passo = passo(lettore.getWidth(0), lettore.getHeight(0), lato);
                ImageReadParam parametri = lettore.getDefaultReadParam();
                if (passo > 1) {
                    parametri.setSourceSubsampling(passo, passo, 0, 0);
                }
                BufferedImage immagine = lettore.read(0, parametri);
                if (immagine == null) {
                    throw new IOException("Immagine illeggibile");
                }
                return immagine;
            } finally {
                lettore.dispose();
            }
        }
    }

    /** L'orientamento EXIF, o null se non c'è o non si legge. */
    static Orientation orientamento(Path file) {
        try {
            var ifd0 = ImageMetadataReader.readMetadata(file.toFile())
                    .getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer o = ifd0 == null ? null : ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return o == null ? null : Orientation.typeOf(o);
        } catch (Exception e) {
            return null;
        }
    }
}
