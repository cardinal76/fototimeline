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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 *
 * <p>Importazione e indicizzazione leggono {@code fototimeline.indicizzazione.paralleli}
 * file insieme ({@link #inParallelo}): dal cloud ogni file è soprattutto attesa
 * di rete. Quello che due file possono fare nello stesso momento è al sicuro
 * altrove: i doppioni li ferma l'indice unico su {@code hash}, i nomi dei file
 * e le cartelle {@link ArchivioFile}, la memoria {@link FotoService} (le parti
 * pesanti una alla volta).
 */
@Service
public class ImportazioneCartelle {

    private static final Logger log = LoggerFactory.getLogger(ImportazioneCartelle.class);
    private static final int MAX_MESSAGGI = 50;

    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final Path radice;
    private final IndicizzazioneProperties parallelismo;

    public ImportazioneCartelle(FotoService fotoService, ArchivioFile archivio, ArchivioProperties properties) {
        this(fotoService, archivio, properties, IndicizzazioneProperties.PREDEFINITE);
    }

    @Autowired
    public ImportazioneCartelle(FotoService fotoService, ArchivioFile archivio, ArchivioProperties properties,
            IndicizzazioneProperties parallelismo) {
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.radice = properties.importazione() == null ? null : properties.importazione().toAbsolutePath().normalize();
        this.parallelismo = parallelismo;
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

    /**
     * Chi segue l'importazione: avanzamento e annullamento (LavoriImportazione).
     * {@link #fatta} e {@link #sparita} arrivano da più thread insieme
     * ({@code fototimeline.indicizzazione.paralleli}): chi li implementa li
     * sincronizza.
     */
    public interface Avanzamento {
        Avanzamento NESSUNO = new Avanzamento() { };

        /**
         * Mentre si cercano i file, prima di {@link #inizio}: quanti trovati
         * finora e la cartella dove si è arrivati. Sul cloud l'elenco di un
         * archivio grande dura anche un'ora: così si vede che va avanti.
         */
        default void cercando(int trovati, Path cartella) {
        }

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

        /**
         * Aspetta {@code quanto} (la riprova dopo un errore di lettura), ma
         * smette appena il lavoro viene annullato. True se è stato annullato.
         */
        default boolean aspetta(Duration quanto) throws InterruptedException {
            Thread.sleep(quanto);
            return annullata();
        }

        /**
         * Quando cominciano i file: chi li sta lavorando in questo momento,
         * con fase e tempo, da chiedere quando serve (lo stato del lavoro).
         * Alla fine dei file arriva {@code null}.
         */
        default void sorveglia(Supplier<List<FileInLavorazione.Vista>> inLavorazione) {
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
        List<Path> file = media(base, avanzamento);
        if (avanzamento.annullata()) {
            // Elenco a metà: niente scelta, che toglierebbe i ricordi dei file non ancora visti.
            return new Riepilogo().importazione(file.size());
        }
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
        Map<Path, BasicFileAttributes> attributiScelti = attributi;
        Set<Path> cambiateScelte = cambiate;
        inParallelo(file, avanzamento, (p, controllo) -> {
            BasicFileAttributes attr = attributiScelti.get(p);
            if (automatica != null && !Files.exists(p)) {
                // Tolto da altri mentre si leggevano quelli prima: niente di sbagliato.
                if (controllo.concludi()) {
                    riepilogo.rimosso();
                    avanzamento.sparita(p);
                }
                return;
            }
            String album = albumDaCartella && !p.getParent().equals(base)
                    ? p.getParent().getFileName().toString()
                    : null;
            Caricamento esito;
            try {
                esito = conRiprova(p, avanzamento, controllo, () -> {
                    try {
                        Instant modificatoIl = attr != null ? attr.lastModifiedTime().toInstant()
                                : Files.getLastModifiedTime(p).toInstant();
                        return fotoService.importa(p.getFileName().toString(), p, modificatoIl, album,
                                caricataDa.apply(p), sposta, controllo);
                    } catch (IOException e) {
                        return new Caricamento(p.toString(), Risultati.Esito.ERRORE, null, FotoService.illeggibile(e));
                    }
                });
            } catch (FotoService.Annullata a) {
                // Annullata mentre aspettava, o abbandonato: il file è dov'era, intatto, e non si conta qui.
                return;
            }
            if (controllo.abbandonato()) {
                // Tornato dopo il tempo massimo: l'ha già contato chi l'ha abbandonato.
                return;
            }
            controllo.fase(ControlloFile.Fase.FINE);
            if (automatica != null && esito.esito() == Risultati.Esito.ERRORE) {
                if (!Files.exists(p)) {
                    // Sparito mentre lo si leggeva: l'errore viene da lì.
                    if (controllo.concludi()) {
                        riepilogo.rimosso();
                        avanzamento.sparita(p);
                    }
                    return;
                }
                // Solo gli errori del file: uno di lettura (il cloud che non risponde) si riprova al giro dopo.
                if (attr != null && FotoService.erroreDelFile(esito.messaggio())) {
                    automatica.memoria().ricorda(p, attr.size(), attr.lastModifiedTime().toInstant(),
                            esito.messaggio());
                }
            } else if (cambiateScelte.contains(p)) {
                // Era fallito, ma è cambiato e stavolta è andato.
                automatica.memoria().dimentica(p);
            }
            boolean rimossa = false;
            if (sposta && esito.esito() == Risultati.Esito.CARICATA) {
                // Spostata nell'archivio, o copiata e tolta da FotoService dopo la scheda.
                rimossa = !Files.exists(p);
            } else if (sposta && esito.esito() == Risultati.Esito.DUPLICATA && inArchivio(esito.foto())
                    && !controllo.abbandonato()) {
                // Il contenuto c'è già: la copia nell'origine non serve.
                try {
                    Files.delete(p);
                    rimossa = true;
                } catch (IOException e) {
                    log.warn("Già in archivio ma non riesco a togliere {}: {}", p, e.getMessage());
                }
            }
            if (!controllo.concludi()) {
                return;
            }
            riepilogo.conta(relativo(p), esito);
            if (rimossa) {
                riepilogo.rimosso();
            }
            avanzamento.fatta(p, esito, rimossa);
        }, (p, esito) -> {
            riepilogo.conta(relativo(p), esito);
            avanzamento.fatta(p, esito, false);
        });
        // Dopo Annulla no: un file abbandonato può essere ancora appeso lì sotto.
        if (sposta && !avanzamento.annullata()) {
            pulisciSottocartelleVuote(base);
        }
        return riepilogo.importazione(file.size());
    }

    /**
     * Le foto e i video sotto {@code base}, fuori dall'archivio, in ordine.
     * Riferisce a {@code avanzamento} i file trovati mentre cammina e si
     * ferma se lo si annulla.
     */
    private List<Path> media(Path base, Avanzamento avanzamento) throws IOException {
        int[] trovati = {0};
        try (Stream<Path> s = Files.walk(base)) {
            return s.takeWhile(p -> !avanzamento.annullata())
                    .filter(p -> !dentroArchivio(p))
                    .filter(Files::isRegularFile)
                    .filter(p -> FotoService.TIPI.containsKey(FotoService.estensione(p.getFileName().toString())))
                    .peek(p -> avanzamento.cercando(++trovati[0], p.getParent()))
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
            } else if (scartato != null && scartato.uguale(attr.size(), modificatoIl)
                    && FotoService.erroreDelFile(scartato.motivo()) && scartato.conLetturaAttuale()) {
                giaFallite++;
            } else {
                // Anche un ricordo il cui motivo oggi non è più "del file" (la memoria finita), o
                // fallito con una lettura di prima (FotoService.VERSIONE_LETTURA): si rilegge.
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
                && !scegli(base, media(base, Avanzamento.NESSUNO), automatica).daLeggere().isEmpty();
    }

    /**
     * "Indicizza archivio": dà una scheda ai file che stanno già nell'archivio
     * ma che l'app non conosce (caricati nel cloud da fuori), senza copiarli
     * ({@link FotoService#indicizza}). Le cartelle e i file che iniziano col
     * punto ({@code .backup}, {@code .miniature}) si saltano. Mentre cerca i
     * file li conta ({@link Avanzamento#cercando}); annullata, smette di cercare.
     */
    public Importazione indicizza(Avanzamento avanzamento) throws IOException {
        archivio.verificaDisponibile();
        Path base = archivio.radice();
        List<Path> file = new ArrayList<>();
        if (Files.isDirectory(base)) {
            Files.walkFileTree(base, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path cartella, BasicFileAttributes attributi) {
                    if (avanzamento.annullata()) {
                        return FileVisitResult.TERMINATE;
                    }
                    boolean nascosta = !cartella.equals(base) && cartella.getFileName().toString().startsWith(".");
                    if (nascosta || cartella.equals(archivio.cartellaMiniature())) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    avanzamento.cercando(file.size(), cartella);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path p, BasicFileAttributes attributi) {
                    if (avanzamento.annullata()) {
                        return FileVisitResult.TERMINATE;
                    }
                    String nome = p.getFileName().toString();
                    if (attributi.isRegularFile() && !nome.startsWith(".")
                            && FotoService.TIPI.containsKey(FotoService.estensione(nome))) {
                        file.add(p);
                        avanzamento.cercando(file.size(), p.getParent());
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
        // Un file che ha già la scheda (stesso percorso) FotoService.indicizza lo salta prima di aprirlo.
        PerFile.Abbandono abbandono = (p, esito) -> {
            riepilogo.conta(relativo(p), esito);
            avanzamento.fatta(p, esito, false);
        };
        inParallelo(file, avanzamento, (p, controllo) -> {
            Caricamento esito;
            try {
                esito = conRiprova(p, avanzamento, controllo, () -> fotoService.indicizza(p, controllo));
            } catch (FotoService.Annullata a) {
                return;
            }
            if (controllo.concludi()) {
                abbandono.conta(p, esito);
            }
        }, abbandono);
        return riepilogo.importazione(file.size());
    }

    /** Il lavoro su un file: gli errori del file finiscono nell'esito, un'eccezione ferma tutto. */
    @FunctionalInterface
    interface PerFile {
        /**
         * Lavora {@code file}. Prima di contarlo chiama {@link FileInLavorazione#concludi}:
         * se dice false il file è stato abbandonato (già contato) e non si conta né si tocca più.
         */
        void fai(Path file, FileInLavorazione controllo) throws IOException;

        /** Come si conta un file: lo usa anche chi lo abbandona, con un errore di lettura. */
        @FunctionalInterface
        interface Abbandono {
            void conta(Path file, Caricamento esito);
        }
    }

    /** Ogni quanto chi sorveglia i file guarda scadenze e Annulla, se nessuno lo sveglia prima. */
    private static final long GIRO_SORVEGLIANZA_MS = 250;

    /**
     * Passa i file a {@code lavoro}, {@code paralleli} alla volta, ognuno su
     * un thread suo (piattaforma, col nome del file: si vede anche in un dump
     * con {@code kill -3}). Il thread che chiama li sorveglia: ne fa partire
     * uno nuovo appena uno finisce e abbandona ({@link FileInLavorazione#abbandona})
     * quelli che superano il tempo massimo
     * ({@link IndicizzazioneProperties#tempoMassimo}): contati come errore di
     * lettura (non un errore del file: la cartella automatica non li ricorda),
     * il lavoro va avanti con gli altri. Un thread appeso in una lettura dal
     * mount non si può interrompere: resta, ma ha già restituito il turno per
     * le parti pesanti e, se torna, non scrive né conta niente.
     *
     * <p>Annullando non parte nessun file nuovo; quelli in corso si aspettano
     * al massimo {@link IndicizzazioneProperties#attesaAnnullamento}, poi si
     * abbandonano anche loro. Se il lavoro su un file lancia (per esempio il
     * cloud smontato a metà), non partono altri file e l'eccezione arriva a
     * chi ha chiamato, finiti (o abbandonati) quelli in corso.
     */
    void inParallelo(List<Path> file, Avanzamento avanzamento, PerFile lavoro, PerFile.Abbandono abbandono)
            throws IOException {
        if (file.isEmpty()) {
            return;
        }
        int n = Math.min(parallelismo.paralleliPerHeap(Runtime.getRuntime().maxMemory()), file.size());
        if (n < Math.min(parallelismo.paralleli(), file.size())) {
            log.warn("Heap di {} MB: {} file insieme invece di {}", Runtime.getRuntime().maxMemory() >> 20, n,
                    parallelismo.paralleli());
        }
        Object segnale = new Object();
        Set<FileInLavorazione> attivi = ConcurrentHashMap.newKeySet();
        AtomicReference<Throwable> errore = new AtomicReference<>();
        String nome = Thread.currentThread().getName();
        avanzamento.sorveglia(() -> attivi.stream()
                .sorted(Comparator.comparing(FileInLavorazione::iniziatoIl))
                .map(FileInLavorazione::vista)
                .toList());
        int prossimo = 0;
        long annullatoDal = 0;
        boolean annullato = false;
        boolean interrotto = false;
        try {
            while (true) {
                List<FileInLavorazione> scaduti = new ArrayList<>();
                boolean dopoAnnulla = false;
                synchronized (segnale) {
                    long adesso = System.nanoTime();
                    if (avanzamento.annullata() && !annullato) {
                        annullato = true;
                        annullatoDal = adesso;
                    }
                    for (FileInLavorazione f : attivi) {
                        if (f.scaduto(adesso)) {
                            scaduti.add(f);
                        }
                    }
                    if (annullato && !attivi.isEmpty()
                            && adesso - annullatoDal >= parallelismo.attesaAnnullamento().toNanos()) {
                        // Annulla ha aspettato abbastanza: chi è ancora appeso si lascia lì.
                        dopoAnnulla = true;
                        for (FileInLavorazione f : attivi) {
                            if (!scaduti.contains(f)) {
                                scaduti.add(f);
                            }
                        }
                    }
                    scaduti.forEach(attivi::remove);
                    if (scaduti.isEmpty()) {
                        boolean basta = annullato || errore.get() != null || interrotto;
                        if (!basta && prossimo < file.size() && attivi.size() < n) {
                            FileInLavorazione f = new FileInLavorazione(file.get(prossimo++),
                                    relativo(file.get(prossimo - 1)), avanzamento::annullata, parallelismo);
                            attivi.add(f);
                            try {
                                avvia(f, nome, lavoro, errore, attivi, segnale);
                            } catch (RuntimeException | Error e) {
                                // Niente thread (memoria finita?): si ferma tutto, e lo si dice.
                                attivi.remove(f);
                                errore.compareAndSet(null, e);
                            }
                            continue;
                        }
                        if (attivi.isEmpty() && (basta || prossimo >= file.size())) {
                            break;
                        }
                        long attesa = GIRO_SORVEGLIANZA_MS;
                        for (FileInLavorazione f : attivi) {
                            attesa = Math.min(attesa, Math.max(1, f.mancano(adesso) / 1_000_000 + 1));
                        }
                        try {
                            segnale.wait(attesa);
                        } catch (InterruptedException e) {
                            // Niente file nuovi; quelli in corso finiscono o scadono.
                            interrotto = true;
                        }
                    }
                }
                for (FileInLavorazione f : scaduti) {
                    abbandona(f, dopoAnnulla && !f.scaduto(System.nanoTime()), abbandono);
                }
            }
        } finally {
            avanzamento.sorveglia(null);
        }
        if (interrotto) {
            Thread.currentThread().interrupt();
        }
        switch (errore.get()) {
            case null -> {
            }
            case IOException e -> throw e;
            case RuntimeException e -> throw e;
            case Error e -> throw e;
            case Throwable e -> throw new IllegalStateException(e);
        }
    }

    /** Il thread di un file: lo lavora, e alla fine sveglia chi sorveglia. */
    private void avvia(FileInLavorazione f, String nome, PerFile lavoro, AtomicReference<Throwable> errore,
            Set<FileInLavorazione> attivi, Object segnale) {
        Thread t = Thread.ofPlatform().name(nome + "-file " + f.nome()).daemon().unstarted(() -> {
            try {
                try {
                    // Per il tempo massimo, che cresce coi GB. Anche questo può restare appeso sul mount.
                    f.dimensione(Files.size(f.file()));
                } catch (IOException | RuntimeException e) {
                    // Lo dirà la lettura.
                }
                lavoro.fai(f.file(), f);
            } catch (FotoService.Annullata a) {
                // Annullato o abbandonato: niente da contare.
            } catch (Throwable e) {
                if (f.abbandonato()) {
                    log.info("{}, già abbandonato, è finito con {}", f.nome(), e.toString());
                } else {
                    log.warn("Errore inatteso su {}: il lavoro si ferma", f.nome(), e);
                    errore.compareAndSet(null, e);
                }
            } finally {
                // Se non l'ha concluso il lavoro (annullato, errore) nessuno deve più abbandonarlo.
                f.concludi();
                synchronized (segnale) {
                    attivi.remove(f);
                    segnale.notifyAll();
                }
            }
        });
        f.thread(t);
        t.start();
    }

    /** Il tempo massimo è passato (o Annulla ha aspettato abbastanza): il file si conta come errore di lettura e si va avanti. */
    private void abbandona(FileInLavorazione f, boolean perAnnulla, PerFile.Abbandono abbandono) {
        String fase = f.faseCorrente().etichetta();
        String quanto = durata(f.durata());
        if (!f.abbandona()) {
            // Il suo thread l'ha appena concluso: l'ha contato lui.
            return;
        }
        String motivo = perAnnulla
                ? "fermo da %s in «%s» dopo Annulla: abbandonato".formatted(quanto, fase)
                : "fermo da %s in «%s», oltre il tempo massimo: abbandonato, si riprova al prossimo giro"
                        .formatted(quanto, fase);
        Thread t = f.thread();
        StringBuilder pila = new StringBuilder();
        if (t != null) {
            for (StackTraceElement e : t.getStackTrace()) {
                pila.append("\n\tat ").append(e);
            }
        }
        log.warn("{}: {}. Il thread {} resta dov'è:{}", f.nome(), motivo, t != null ? t.getName() : "?", pila);
        abbandono.conta(f.file(),
                new Caricamento(f.file().getFileName().toString(), Risultati.Esito.ERRORE, null,
                        FotoService.ILLEGGIBILE + " (" + motivo + ")"));
    }

    /** "14 min", "35 s", "2 h 5 min". */
    static String durata(Duration d) {
        long s = d.toSeconds();
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min";
        }
        return (s / 3600) + " h " + (s % 3600 / 60) + " min";
    }

    /**
     * Un errore di lettura (il cloud montato che per un po' non risponde:
     * "Input/output error") si riprova una volta dopo
     * {@code fototimeline.indicizzazione.attesa-riprova}. Gli errori del file
     * (cifrato, formato, immagine rotta) no: darebbero lo stesso errore. Se
     * il lavoro viene annullato (o il file abbandonato) durante l'attesa si
     * smette subito ({@link FotoService.Annullata}: il file non conta).
     */
    Caricamento conRiprova(Path p, Avanzamento avanzamento, ControlloFile controllo, Supplier<Caricamento> lettura) {
        Caricamento esito = lettura.get();
        if (esito.esito() != Risultati.Esito.ERRORE || !FotoService.erroreDiLettura(esito.messaggio())
                || !Files.exists(p)) {
            return esito;
        }
        if (controllo.fermato()) {
            throw new FotoService.Annullata();
        }
        log.info("{}: {}; riprovo tra {}", relativo(p), esito.messaggio(), parallelismo.attesaRiprova());
        controllo.fase(ControlloFile.Fase.RIPROVA);
        try {
            if (avanzamento.aspetta(parallelismo.attesaRiprova()) || controllo.fermato()) {
                throw new FotoService.Annullata();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return esito;
        }
        return lettura.get();
    }

    /** I conti di un'importazione o di un'indicizzazione, file per file, da più thread. */
    private static final class Riepilogo {
        private int importate;
        private int duplicate;
        private int errori;
        private int rimossi;
        private final List<String> messaggi = new ArrayList<>();

        synchronized void rimosso() {
            rimossi++;
        }

        synchronized void conta(String file, Caricamento esito) {
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

        synchronized Importazione importazione(int trovate) {
            // In ordine di percorso: con più file insieme arrivano in un ordine qualunque.
            return new Importazione(trovate, importate, duplicate, errori, rimossi, messaggi.stream().sorted().toList());
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
