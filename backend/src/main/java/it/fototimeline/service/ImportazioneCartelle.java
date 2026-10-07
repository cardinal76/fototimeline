package it.fototimeline.service;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Cartella;
import it.fototimeline.service.Risultati.Importazione;

/**
 * "Importa cartella": legge le immagini di una cartella del server (sul server
 * è il cloud montato con rclone) e le mette in archivio, copiandole o
 * spostandole.
 *
 * <p>Le cartelle accettate stanno sotto {@code fototimeline.importazione}, se
 * impostata: sul server l'app è su internet e non deve leggere il resto del
 * disco. L'archivio e le miniature si saltano sempre, anche quando stanno
 * dentro la cartella scelta.
 */
@Service
public class ImportazioneCartelle {

    private static final Logger log = LoggerFactory.getLogger(ImportazioneCartelle.class);
    private static final int MAX_MESSAGGI = 50;

    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final Path radice;

    public ImportazioneCartelle(FotoService fotoService, ArchivioFile archivio, ArchivioProperties properties) {
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.radice = properties.importazione() == null ? null : properties.importazione().toAbsolutePath().normalize();
    }

    /** La radice consentita, o null se si può leggere ovunque (sul PC). */
    public Path radice() {
        return radice;
    }

    /**
     * @param sposta se true, dopo l'importazione l'originale viene tolto dalla
     *               cartella di origine. Le foto già in archivio (stesso
     *               contenuto) si tolgono anche loro: il contenuto è identico.
     */
    public Importazione importa(Path cartella, boolean albumDaCartella, boolean sposta) throws IOException {
        return importa(cartella, albumDaCartella, sposta, Avanzamento.NESSUNO);
    }

    /** Chi segue l'importazione: avanzamento e annullamento (LavoriImportazione). */
    public interface Avanzamento {
        Avanzamento NESSUNO = new Avanzamento() { };

        default void inizio(int totale) {
        }

        default void fatta(Path file, Caricamento esito, boolean rimossa) {
        }

        /**
         * Importazione automatica, prima di cominciare: i file lasciati stare
         * senza leggerli, perché già falliti uguali ({@link MemoriaImportazione})
         * o ancora in arrivo. Non sono nel totale di {@link #inizio}.
         */
        default void saltate(int giaFallite, int inArrivo) {
        }

        /** Importazione automatica: il file è sparito prima che lo si leggesse (tolto da altri). */
        default void sparita(Path file) {
        }

        default boolean annullata() {
            return false;
        }
    }

    public Importazione importa(Path cartella, boolean albumDaCartella, boolean sposta, Avanzamento avanzamento)
            throws IOException {
        return importa(cartella, albumDaCartella, sposta, avanzamento, file -> null);
    }

    /**
     * @param caricataDa chi ha portato ogni file: per "Importa cartella" chi
     *                   l'ha lanciata, per la cartella automatica il proprietario
     *                   del telefono che l'ha copiato ({@link ProvenienzaFile})
     */
    public Importazione importa(Path cartella, boolean albumDaCartella, boolean sposta, Avanzamento avanzamento,
            Function<Path, String> caricataDa) throws IOException {
        return importa(cartella, albumDaCartella, sposta, avanzamento, caricataDa, null);
    }

    /**
     * Le regole in più della cartella automatica, che gira da sola ogni
     * pochi minuti su una cartella dove altri stanno ancora scrivendo.
     *
     * @param memoria  i file già falliti per colpa loro: non si rileggono
     *                 finché dimensione e data di modifica restano quelle
     * @param inArrivo i file che qualcuno sta ancora copiando (i telefoni)
     * @param adesso   per {@code attesa}
     * @param attesa   un file modificato da meno di così (prima o dopo
     *                 {@code adesso}) potrebbe essere a metà: aspetta il giro dopo
     */
    public record Automatica(MemoriaImportazione memoria, Predicate<Path> inArrivo, Instant adesso,
            Duration attesa) {

        boolean recente(Instant modificatoIl) {
            return !modificatoIl.isBefore(adesso.minus(attesa)) && !modificatoIl.isAfter(adesso.plus(attesa));
        }
    }

    /** I file di un giro automatico: quelli da leggere (con dimensione e data) e quanti lasciati stare. */
    record Scelta(List<Path> daLeggere, Map<Path, BasicFileAttributes> attributi, Set<Path> cambiate,
            int giaFallite, int inArrivo) {
    }

    /**
     * @param automatica null per "Importa cartella"; per la cartella
     *                   automatica, le regole in più ({@link Automatica})
     */
    public Importazione importa(Path cartella, boolean albumDaCartella, boolean sposta, Avanzamento avanzamento,
            Function<Path, String> caricataDa, Automatica automatica) throws IOException {
        archivio.verificaDisponibile();
        Path base = consentita(cartella);
        if (!Files.isDirectory(base)) {
            throw new IllegalArgumentException("Cartella inesistente: " + cartella);
        }
        if (dentroArchivio(base)) {
            throw new IllegalArgumentException("Questa cartella fa già parte dell'archivio");
        }
        List<Path> file = media(base);
        Map<Path, BasicFileAttributes> attributi = Map.of();
        Set<Path> cambiate = Set.of();
        if (automatica != null) {
            Scelta scelta = scegli(base, file, automatica);
            file = scelta.daLeggere();
            attributi = scelta.attributi();
            cambiate = scelta.cambiate();
            avanzamento.saltate(scelta.giaFallite(), scelta.inArrivo());
        }

        Riepilogo riepilogo = new Riepilogo();
        avanzamento.inizio(file.size());
        for (Path p : file) {
            if (avanzamento.annullata()) {
                break;
            }
            BasicFileAttributes attr = attributi.get(p);
            if (automatica != null && !Files.exists(p)) {
                // Tolto da altri mentre si leggevano quelli prima: niente di sbagliato.
                riepilogo.rimossi++;
                avanzamento.sparita(p);
                continue;
            }
            Caricamento esito;
            try {
                String album = albumDaCartella && !p.getParent().equals(base)
                        ? p.getParent().getFileName().toString()
                        : null;
                Instant modificatoIl = attr != null ? attr.lastModifiedTime().toInstant()
                        : Files.getLastModifiedTime(p).toInstant();
                esito = fotoService.importa(p.getFileName().toString(), p, modificatoIl, album, caricataDa.apply(p));
            } catch (IOException e) {
                esito = new Caricamento(p.toString(), Risultati.Esito.ERRORE, null, e.getMessage());
            }
            if (automatica != null && esito.esito() == Risultati.Esito.ERRORE) {
                if (!Files.exists(p)) {
                    // Sparito mentre lo si leggeva: l'errore viene da lì.
                    riepilogo.rimossi++;
                    avanzamento.sparita(p);
                    continue;
                }
                if (attr != null && FotoService.erroreDelFile(esito.messaggio())) {
                    automatica.memoria().ricorda(p, attr.size(), attr.lastModifiedTime().toInstant(),
                            esito.messaggio());
                }
            } else if (cambiate.contains(p)) {
                // Era fallito, ma è cambiato e stavolta è andato.
                automatica.memoria().dimentica(p);
            }
            riepilogo.conta(relativo(p), esito);
            boolean rimossa = false;
            if (sposta && esito.esito() != Risultati.Esito.ERRORE && inArchivio(esito.foto())) {
                try {
                    Files.delete(p);
                    riepilogo.rimossi++;
                    rimossa = true;
                } catch (IOException e) {
                    log.warn("Importata ma non riesco a togliere {}: {}", p, e.getMessage());
                }
            }
            avanzamento.fatta(p, esito, rimossa);
        }
        if (sposta) {
            pulisciSottocartelleVuote(base);
        }
        return riepilogo.importazione(file.size());
    }

    /** Le foto e i video sotto {@code base}, fuori dall'archivio, in ordine. */
    private List<Path> media(Path base) throws IOException {
        try (Stream<Path> s = Files.walk(base)) {
            return s.filter(p -> !dentroArchivio(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> FotoService.TIPI.containsKey(FotoService.estensione(p.getFileName().toString())))
                    .sorted()
                    .toList();
        }
    }

    /**
     * Divide i file della cartella automatica, senza aprirli: dimensione e
     * data vengono dall'elenco della cartella, che sul cloud montato è già
     * in memoria. I file nascosti (le copie a metà di molti programmi) non
     * si guardano nemmeno. I ricordi dei file che non ci sono più si tolgono.
     */
    Scelta scegli(Path base, List<Path> file, Automatica automatica) {
        Map<String, FileScartato> scartati = automatica.memoria().sotto(base);
        List<Path> daLeggere = new ArrayList<>();
        Map<Path, BasicFileAttributes> attributi = new HashMap<>();
        Set<String> presenti = new HashSet<>();
        Set<Path> cambiate = new HashSet<>();
        int giaFallite = 0;
        int inArrivo = 0;
        for (Path p : file) {
            if (p.getFileName().toString().startsWith(".")) {
                continue;
            }
            BasicFileAttributes attr;
            try {
                attr = Files.readAttributes(p, BasicFileAttributes.class);
            } catch (NoSuchFileException e) {
                continue;
            } catch (IOException e) {
                // Lo dirà la lettura, come errore.
                daLeggere.add(p);
                continue;
            }
            String percorso = p.toAbsolutePath().normalize().toString();
            presenti.add(percorso);
            Instant modificatoIl = attr.lastModifiedTime().toInstant();
            FileScartato scartato = scartati.get(percorso);
            if (automatica.inArrivo().test(p) || automatica.recente(modificatoIl)) {
                inArrivo++;
            } else if (scartato != null && scartato.uguale(attr.size(), modificatoIl)) {
                giaFallite++;
            } else {
                daLeggere.add(p);
                attributi.put(p, attr);
                if (scartato != null) {
                    cambiate.add(p);
                }
            }
        }
        automatica.memoria().dimenticaTranne(base, presenti);
        return new Scelta(daLeggere, attributi, cambiate, giaFallite, inArrivo);
    }

    /**
     * True se nella cartella automatica c'è qualcosa da leggere: non solo
     * file già falliti o ancora in arrivo, che non farebbero partire un giro a vuoto.
     */
    public boolean daImportare(Path cartella, Automatica automatica) throws IOException {
        Path base = consentita(cartella);
        return Files.isDirectory(base) && !dentroArchivio(base)
                && !scegli(base, media(base), automatica).daLeggere().isEmpty();
    }

    /**
     * "Indicizza archivio": dà una scheda ai file che stanno già nell'archivio
     * ma che l'app non conosce (caricati nel cloud da fuori), senza copiarli
     * ({@link FotoService#indicizza}). Le cartelle e i file che iniziano col
     * punto ({@code .backup}, {@code .miniature}) si saltano.
     */
    public Importazione indicizza(Avanzamento avanzamento) throws IOException {
        archivio.verificaDisponibile();
        Path base = archivio.radice();
        List<Path> file = new ArrayList<>();
        if (Files.isDirectory(base)) {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path cartella, BasicFileAttributes attributi) {
                    boolean nascosta = !cartella.equals(base) && cartella.getFileName().toString().startsWith(".");
                    return nascosta || cartella.equals(archivio.cartellaMiniature())
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attributi) {
                    String nome = p.getFileName().toString();
                    if (attributi.isRegularFile() && !nome.startsWith(".")
                            && FotoService.TIPI.containsKey(FotoService.estensione(nome))) {
                        file.add(p);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path p, IOException e) {
                    log.warn("Indicizzazione: non riesco a leggere {}: {}", p, e.getMessage());
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        file.sort(null);

        Riepilogo riepilogo = new Riepilogo();
        avanzamento.inizio(file.size());
        for (Path p : file) {
            if (avanzamento.annullata()) {
                break;
            }
            Caricamento esito = fotoService.indicizza(p);
            riepilogo.conta(relativo(p), esito);
            avanzamento.fatta(p, esito, false);
        }
        return riepilogo.importazione(file.size());
    }

    /** I conti di un'importazione o di un'indicizzazione, file per file. */
    private static final class Riepilogo {
        private int importate;
        private int duplicate;
        private int errori;
        private int rimossi;
        private final List<String> messaggi = new ArrayList<>();

        void conta(String file, Caricamento esito) {
            switch (esito.esito()) {
                case CARICATA -> importate++;
                case DUPLICATA -> duplicate++;
                case ERRORE -> {
                    errori++;
                    if (messaggi.size() < MAX_MESSAGGI) {
                        messaggi.add(file + ": " + esito.messaggio());
                    }
                }
            }
        }

        Importazione importazione(int trovate) {
            return new Importazione(trovate, importate, duplicate, errori, rimossi, messaggi);
        }
    }

    /** Le sottocartelle di una cartella consentita, per il navigatore. */
    public Cartella elenca(Path cartella) throws IOException {
        archivio.verificaDisponibile();
        Path c = cartella == null ? partenza() : consentita(cartella);
        if (!Files.isDirectory(c)) {
            throw new IllegalArgumentException("Cartella inesistente: " + cartella);
        }
        List<String> sottocartelle;
        int immagini;
        try (Stream<Path> s = Files.list(c)) {
            List<Path> voci = s.filter(p -> !dentroArchivio(p))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .toList();
            sottocartelle = voci.stream().filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
            immagini = (int) voci.stream().filter(Files::isRegularFile)
                    .filter(p -> FotoService.TIPI.containsKey(FotoService.estensione(p.getFileName().toString())))
                    .count();
        }
        boolean inCima = radice != null ? c.equals(radice) : c.getParent() == null;
        return new Cartella(c.toString(), inCima ? null : c.getParent().toString(), sottocartelle, immagini);
    }

    private Path partenza() {
        return radice != null ? radice : Path.of(System.getProperty("user.home"));
    }

    /** Normalizza e controlla che stia sotto la radice consentita. */
    public Path consentita(Path cartella) {
        Path c = cartella.toAbsolutePath().normalize();
        if (radice != null && !c.startsWith(radice)) {
            throw new IllegalArgumentException("Si può importare solo da " + radice);
        }
        return c;
    }

    boolean dentroArchivio(Path p) {
        Path n = p.toAbsolutePath().normalize();
        return n.startsWith(archivio.radice()) || n.startsWith(archivio.cartellaMiniature());
    }

    private boolean inArchivio(Foto foto) {
        return foto != null && foto.getPercorso() != null && Files.exists(fotoService.fileOriginale(foto));
    }

    String relativo(Path p) {
        return radice != null && p.startsWith(radice) ? radice.relativize(p).toString() : p.toString();
    }

    /** Dopo uno spostamento le sottocartelle svuotate spariscono; la cartella scelta resta. */
    private static void pulisciSottocartelleVuote(Path base) throws IOException {
        List<Path> cartelle;
        try (Stream<Path> s = Files.walk(base)) {
            cartelle = s.filter(Files::isDirectory).filter(p -> !p.equals(base))
                    .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .toList();
        }
        for (Path c : cartelle) {
            try (Stream<Path> dentro = Files.list(c)) {
                if (dentro.findAny().isEmpty()) {
                    Files.delete(c);
                }
            } catch (IOException e) {
                // Non vuota o non cancellabile: resta.
            }
        }
    }
}
