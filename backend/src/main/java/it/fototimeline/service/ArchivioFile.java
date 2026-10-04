package it.fototimeline.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;

import org.springframework.stereotype.Component;

import it.fototimeline.cloud.Cloud;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Originali e miniature su disco. Gli originali stanno in una cartella per
 * giorno, col nome che avevano:
 * <pre>
 *   2024/08/15/IMG_0001.jpg
 *   2024/08/15/IMG_0001 (2).jpg   ← stesso nome, foto diversa
 *   .miniature/&lt;id&gt;.jpg     ← o fototimeline.miniature, se impostata
 * </pre>
 */
@Component
public class ArchivioFile {

    static final String MINIATURE = ".miniature";

    private final Path radice;
    private final Path miniature;
    private final int latoMiniatura;

    private final Cloud cloud;

    public ArchivioFile(ArchivioProperties properties, Cloud cloud) {
        this.cloud = cloud;
        this.radice = properties.archivio().toAbsolutePath().normalize();
        this.miniature = properties.miniature() == null
                ? radice.resolve(MINIATURE)
                : properties.miniature().toAbsolutePath().normalize();
        this.latoMiniatura = properties.miniaturaLato();
        try {
            // La radice no: se è nel cloud smontato finirebbe sul disco del server.
            Files.createDirectories(miniature);
        } catch (IOException e) {
            throw new UncheckedIOException("Non riesco a creare l'archivio in " + radice, e);
        }
    }

    public Path radice() {
        return radice;
    }

    /** Lancia {@link it.fototimeline.cloud.ArchivioNonDisponibile} se il cloud è smontato. */
    public void verificaDisponibile() {
        cloud.verifica();
    }

    public boolean disponibile() {
        return cloud.disponibile();
    }

    /** Dove stanno le miniature: dentro l'archivio, o sul disco locale se l'archivio è nel cloud. */
    public Path cartellaMiniature() {
        return miniature;
    }

    /** "2024/08/15": la cartella del giorno, relativa alla radice. */
    public static String cartella(LocalDate giorno) {
        return "%04d/%02d/%02d".formatted(giorno.getYear(), giorno.getMonthValue(), giorno.getDayOfMonth());
    }

    /** True se l'originale sta già nella cartella del suo giorno. */
    public static boolean alSuoPosto(String percorso, LocalDate giorno) {
        return percorso != null && percorso.startsWith(cartella(giorno) + "/") && percorso.indexOf('/', 11) < 0;
    }

    /** Salva l'originale nella cartella del giorno e restituisce il percorso relativo alla radice. */
    public String salvaOriginale(String nome, String riserva, String estensione, LocalDate giorno, byte[] contenuto)
            throws IOException {
        cloud.verifica();
        Path cartella = radice.resolve(cartella(giorno));
        Files.createDirectories(cartella);
        String pulito = nomeSicuro(nome, riserva, estensione);
        for (int n = 1; ; n++) {
            Path destinazione = cartella.resolve(conNumero(pulito, n));
            try {
                Files.write(destinazione, contenuto, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return relativo(destinazione);
            } catch (FileAlreadyExistsException e) {
                // Nome già preso da un'altra foto dello stesso giorno: si prova "nome (2).jpg".
            }
        }
    }

    /**
     * Sposta l'originale nella cartella di un giorno, col nome indicato (reso
     * unico se serve); restituisce il nuovo percorso relativo.
     */
    public String sposta(String percorso, LocalDate giorno, String nome) throws IOException {
        Path origine = originale(percorso);
        Path cartella = radice.resolve(cartella(giorno));
        Files.createDirectories(cartella);
        for (int n = 1; ; n++) {
            Path destinazione = cartella.resolve(conNumero(nome, n));
            if (destinazione.equals(origine)) {
                return percorso;
            }
            try {
                Files.move(origine, destinazione);
                pulisciCartelleVuote(origine.getParent());
                return relativo(destinazione);
            } catch (FileAlreadyExistsException e) {
                // Prossimo numero.
            }
        }
    }

    /** Scrive la miniatura JPEG, già ruotata secondo l'EXIF. */
    public void creaMiniatura(String id, byte[] contenuto) throws IOException {
        Thumbnails.of(new ByteArrayInputStream(contenuto))
                .size(latoMiniatura, latoMiniatura)
                .imageType(BufferedImage.TYPE_INT_RGB)
                .outputFormat("jpg")
                .outputQuality(0.85)
                .toFile(miniatura(id).toFile());
    }

    public Path originale(String percorsoRelativo) {
        cloud.verifica();
        Path p = radice.resolve(percorsoRelativo).normalize();
        if (!p.startsWith(radice) || p.equals(radice)) {
            throw new IllegalArgumentException("Percorso fuori dall'archivio: " + percorsoRelativo);
        }
        return p;
    }

    public Path miniatura(String id) {
        return miniature.resolve(id + ".jpg");
    }

    public void elimina(String id, String percorsoRelativo) {
        if (percorsoRelativo != null) {
            cloud.verifica();
        }
        try {
            Files.deleteIfExists(miniatura(id));
            if (percorsoRelativo != null) {
                Path originale = originale(percorsoRelativo);
                Files.deleteIfExists(originale);
                pulisciCartelleVuote(originale.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Toglie le cartelle rimaste vuote risalendo verso la radice (giorno, poi mese, poi anno). */
    void pulisciCartelleVuote(Path cartella) {
        pulisciCartelleVuote(cartella, radice);
    }

    /** Come sopra, fermandosi a {@code limite} (che non viene mai cancellata). */
    static void pulisciCartelleVuote(Path cartella, Path limite) {
        Path c = cartella;
        while (c != null && c.startsWith(limite) && !c.equals(limite)) {
            try {
                Files.deleteIfExists(c);
            } catch (IOException e) {
                // Non vuota (DirectoryNotEmptyException) o non cancellabile: ci si ferma qui.
                return;
            }
            c = c.getParent();
        }
    }

    private String relativo(Path p) {
        return radice.relativize(p).toString().replace('\\', '/');
    }

    /**
     * Il nome del file senza cartelle, senza caratteri che Windows non accetta
     * e con l'estensione riconosciuta; se non resta niente, la riserva (l'id).
     */
    static String nomeSicuro(String nome, String riserva, String estensione) {
        String base = nome == null ? "" : nome.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\p{Cntrl}]", "_");
        int punto = base.lastIndexOf('.');
        String senzaEstensione = (punto < 0 ? base : base.substring(0, punto))
                .replaceAll("^[.\\s]+|[.\\s]+$", "");
        if (senzaEstensione.isEmpty()) {
            senzaEstensione = riserva;
        } else if (senzaEstensione.length() > 150) {
            senzaEstensione = senzaEstensione.substring(0, 150).strip();
        }
        return senzaEstensione + "." + estensione;
    }

    /** "IMG_0001.jpg", 2 → "IMG_0001 (2).jpg". */
    static String conNumero(String nome, int n) {
        if (n == 1) {
            return nome;
        }
        int punto = nome.lastIndexOf('.');
        return punto < 0 ? "%s (%d)".formatted(nome, n) : "%s (%d)%s".formatted(nome.substring(0, punto), n, nome.substring(punto));
    }
}
