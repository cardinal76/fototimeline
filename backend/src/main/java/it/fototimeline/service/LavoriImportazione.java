package it.fototimeline.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.eventi.EventiService;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.service.Risultati.Caricamento;

/**
 * Le importazioni da cartella girano in sottofondo, una alla volta: dal cloud
 * migliaia di foto richiedono minuti, e la pagina non deve restare appesa.
 * L'app chiede lo stato ({@link #corrente()}) e mostra la barra.
 *
 * <p>Se {@code fototimeline.importazione-automatica.cartella} è impostata, ogni
 * tanto la si controlla e, se dentro ci sono foto, si importano spostandole:
 * è la cartella dove il telefono carica le foto nel cloud.
 */
@Service
public class LavoriImportazione {

    private static final Logger log = LoggerFactory.getLogger(LavoriImportazione.class);
    /** Gli errori che l'app può elencare; oltre si contano soltanto. */
    private static final int MAX_MESSAGGI = 500;

    /**
     * MANUALE e AUTOMATICA importano una cartella; INDICIZZAZIONE cerca
     * nell'archivio i file senza scheda; LUOGHI dà il luogo alle foto con GPS
     * (nello stato: {@code importate} quelle con un luogo, {@code duplicate}
     * quelle senza, cioè in mare aperto). EVENTI raggruppa le foto in eventi
     * ({@code importate} quelle finite in un evento, {@code duplicate} le altre).
     */
    public enum Origine { MANUALE, AUTOMATICA, INDICIZZAZIONE, LUOGHI, EVENTI }

    public enum Stato { IN_CORSO, FINITA, ANNULLATA, FALLITA }

    /**
     * Fotografia dello stato di un lavoro, per l'API. Solo per la cartella
     * automatica: {@code saltate} i file già falliti uguali in un giro prima
     * (non si rileggono), {@code inArrivo} quelli ancora in copia (al giro
     * dopo); nessuno dei due è in {@code trovate}.
     *
     * <p>{@code ricerca} è vero finché si cercano i file (tutte le origini
     * tranne LUOGHI): intanto {@code trovate} conta quelli trovati finora e
     * {@code cercaIn} è la cartella dove si è arrivati, relativa a
     * {@code cartella}. Poi {@code trovate} è il totale da fare.
     *
     * <p>{@code annullamento}: Annulla è stato premuto e il lavoro sta finendo
     * i file già cominciati (nessuno nuovo). {@code inAttesa}: il lavoro
     * aspetta che l'importazione automatica, annullata per fargli posto,
     * finisca i suoi ({@link #avvia(Path, Origine, Compito)}).
     *
     * <p>{@code inLavorazione}: i file che si stanno leggendo adesso, con la
     * fase e da quanti secondi ({@link FileInLavorazione.Vista}); vuoto
     * quando il lavoro non è in corso.
     */
    public record StatoLavoro(String id, String cartella, Origine origine, Stato stato,
            Instant iniziatoIl, Instant finitoIl, int trovate, int fatte, int importate, int duplicate,
            int errori, int rimossi, List<String> messaggi, String errore, int saltate, int inArrivo,
            boolean ricerca, String cercaIn, boolean annullamento, boolean inAttesa,
            List<FileInLavorazione.Vista> inLavorazione) {
    }

    private final ImportazioneCartelle importazione;
    private final ImportazioneAutomaticaProperties automatica;
    private final MemoriaImportazione memoria;
    private final ArchivioFile archivio;
    private final List<ProvenienzaFile> provenienze;
    private final LuoghiService luoghi;
    private final EventiService eventi;
    private final IndicizzazioneProperties tempi;
    private final Clock clock;
    /**
     * Dopo Annulla controlla che il lavoro si sia chiuso davvero: se il suo
     * thread è appeso fuori dai file (l'elenco di una cartella del mount che
     * non torna), lo chiude lui ({@link #chiudiSeAppeso}).
     */
    private final ScheduledExecutorService guardiano = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "importazione-guardiano");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Lavoro> ultimo = new AtomicReference<>();

    public LavoriImportazione(ImportazioneCartelle importazione, ImportazioneAutomaticaProperties automatica,
            MemoriaImportazione memoria, ArchivioFile archivio, List<ProvenienzaFile> provenienze, LuoghiService luoghi,
            IndicizzazioneProperties tempi, Optional<Clock> clock, EventiService eventi) {
        this.importazione = importazione;
        this.automatica = automatica;
        this.memoria = memoria;
        this.archivio = archivio;
        this.provenienze = List.copyOf(provenienze);
        this.luoghi = luoghi;
        this.eventi = eventi;
        this.tempi = tempi;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * Lancia l'importazione in sottofondo. Una alla volta: con un'altra in
     * corso, IllegalStateException. Le foto nuove hanno {@code caricataDa}
     * (chi l'ha lanciata); dalla cartella automatica, chi ha portato il file.
     */
    public StatoLavoro avvia(Path cartella, boolean albumDaCartella, boolean sposta, Origine origine,
            String caricataDa) {
        if (origine == Origine.AUTOMATICA) {
            return avvia(cartella, origine, lavoro -> importazione.importa(cartella, albumDaCartella, sposta, lavoro,
                    this::provenienza, regoleAutomatica()));
        }
        return avvia(cartella, origine, lavoro -> importazione.importa(cartella, albumDaCartella, sposta, lavoro,
                file -> caricataDa));
    }

    private ImportazioneCartelle.Automatica regoleAutomatica() {
        return new ImportazioneCartelle.Automatica(memoria, this::inArrivo, clock.instant(), automatica.attesa());
    }

    /** True se qualcuno (un telefono) sta ancora copiando il file. */
    private boolean inArrivo(Path file) {
        for (ProvenienzaFile p : provenienze) {
            try {
                if (p.inArrivo(file)) {
                    return true;
                }
            } catch (RuntimeException e) {
                log.warn("Non so se {} è ancora in copia: {}", file, e.getMessage());
            }
        }
        return false;
    }

    /** Il primo che sa da chi arriva il file (oggi solo i telefoni). */
    private String provenienza(Path file) {
        for (ProvenienzaFile p : provenienze) {
            try {
                String chi = p.caricataDa(file);
                if (chi != null) {
                    return chi;
                }
            } catch (RuntimeException e) {
                log.warn("Non so da chi arriva {}: {}", file, e.getMessage());
            }
        }
        return null;
    }

    /**
     * "Indicizza archivio" in sottofondo, sullo stesso thread delle
     * importazioni: non gira mai insieme a una di loro.
     */
    public StatoLavoro avviaIndicizzazione() {
        return avvia(archivio.radice(), Origine.INDICIZZAZIONE, importazione::indicizza);
    }

    /**
     * "Calcola luoghi" in sottofondo, con la stessa barra. Non tocca il cloud:
     * gira anche da smontato. {@code tutte}: anche le foto che il luogo ce
     * l'hanno già.
     */
    public StatoLavoro avviaLuoghi(boolean tutte) {
        if (!luoghi.disponibile()) {
            throw new IllegalStateException(LuoghiService.SENZA_DATASET);
        }
        return avvia(archivio.radice(), Origine.LUOGHI, lavoro -> luoghi.calcola(tutte, lavoro));
    }

    /**
     * "Calcola eventi" in sottofondo, con la stessa barra: legge e scrive solo
     * MongoDB, gira anche col cloud smontato. Se il calcolo di prima si era
     * fermato a metà riprende da lì, salvo {@code daCapo}.
     */
    public StatoLavoro avviaEventi(boolean daCapo) {
        if (eventi.calcoloInCorso()) {
            throw new IllegalStateException("Il calcolo degli eventi è già in corso");
        }
        return avvia(archivio.radice(), Origine.EVENTI, lavoro -> eventi.calcola(daCapo, lavoro));
    }

    /** Quello che fa un lavoro, riferendo al lavoro stesso. */
    private interface Compito {
        void esegui(Lavoro lavoro) throws Exception;
    }

    /**
     * Un lavoro alla volta, ognuno su un thread suo ("importazione"). Quello
     * chiesto da qualcuno (importa, indicizza, luoghi) ha la precedenza
     * sull'importazione automatica, che parte da sola: se gira, la si annulla
     * (finisce i file già cominciati, i suoi sono al sicuro nella cartella) e
     * il lavoro nuovo parte appena lei si è chiusa: il suo thread aspetta
     * quello di prima. L'attesa ha una fine: un lavoro annullato si chiude al
     * più tardi dopo {@link IndicizzazioneProperties#attesaAnnullamento}, e
     * se resta appeso lo chiude il {@link #guardiano}. L'automatica riprende
     * al primo giro dopo la fine del lavoro nuovo.
     */
    private synchronized StatoLavoro avvia(Path cartella, Origine origine, Compito compito) {
        Lavoro attuale = ultimo.get();
        boolean dopoLAutomatica = false;
        if (attuale != null && attuale.inCorso()) {
            if (attuale.origine != Origine.AUTOMATICA || origine == Origine.AUTOMATICA) {
                throw new IllegalStateException(switch (attuale.origine) {
                    case INDICIZZAZIONE -> "C'è un'indicizzazione dell'archivio in corso: aspetta che finisca o annullala";
                    case LUOGHI -> "Il calcolo dei luoghi è in corso: aspetta che finisca o annullalo";
                    case EVENTI -> "Il calcolo degli eventi è in corso: aspetta che finisca o annullalo";
                    default -> "C'è già un'importazione in corso: aspetta che finisca o annullala";
                });
            }
            dopoLAutomatica = true;
        }
        if (origine != Origine.LUOGHI && origine != Origine.EVENTI) {
            archivio.verificaDisponibile();
        }
        Lavoro lavoro = new Lavoro(cartella.toString(), origine, clock.instant());
        Lavoro prima = attuale;
        if (dopoLAutomatica) {
            log.info("{} chiesta mentre gira l'importazione automatica: la annullo e parte appena ha finito i file in corso",
                    origine);
            annulla(attuale);
            lavoro.inAttesa = true;
        }
        ultimo.set(lavoro);
        Thread t = Thread.ofPlatform().name("importazione").daemon().unstarted(() -> {
            if (prima != null) {
                prima.aspettaChiusura();
            }
            esegui(lavoro, compito);
        });
        lavoro.thread = t;
        t.start();
        return lavoro.stato();
    }

    /** L'importazione in corso o l'ultima finita, se c'è. */
    public Optional<StatoLavoro> corrente() {
        Lavoro lavoro = ultimo.get();
        if (lavoro != null) {
            lavoro.controllaThread(clock.instant());
        }
        return Optional.ofNullable(lavoro).map(Lavoro::stato);
    }

    /**
     * Ferma l'importazione in corso: nessun file nuovo, quelli già cominciati
     * (fino a {@code fototimeline.indicizzazione.paralleli}) finiscono. Se sta
     * ancora cercando i file, subito.
     */
    public boolean annulla() {
        Lavoro lavoro = ultimo.get();
        if (lavoro == null || !lavoro.inCorso()) {
            return false;
        }
        annulla(lavoro);
        return true;
    }

    /** Annulla e, se dopo l'attesa dei file il lavoro non si è chiuso, lo chiude il guardiano. */
    private void annulla(Lavoro lavoro) {
        if (lavoro.annulla()) {
            // Il doppio: i file appesi si abbandonano già dopo un'attesa, qui si guarda solo chi è appeso fuori.
            long ms = tempi.attesaAnnullamento().multipliedBy(2).plusSeconds(1).toMillis();
            try {
                guardiano.schedule(() -> chiudiSeAppeso(lavoro), ms, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                // In chiusura.
            }
        }
    }

    /**
     * Il lavoro annullato non si è chiuso: il suo thread è appeso fuori dai
     * file (per esempio l'elenco di una cartella del cloud che non torna). Si
     * chiude lo stato, così il posto si libera, e si scrive nel log dove era
     * fermo. Se il thread poi torna trova il lavoro annullato e non comincia
     * file nuovi.
     */
    private void chiudiSeAppeso(Lavoro lavoro) {
        if (!lavoro.inCorso()) {
            return;
        }
        Thread t = lavoro.thread;
        StringBuilder pila = new StringBuilder();
        if (t != null) {
            for (StackTraceElement e : t.getStackTrace()) {
                pila.append("\n\tat ").append(e);
            }
        }
        log.warn("Lavoro {} di {} annullato ma ancora appeso dopo {}: lo chiudo. Il thread {} era fermo qui:{}",
                lavoro.origine, lavoro.cartella, tempi.attesaAnnullamento().multipliedBy(2), t != null ? t.getName() : "?",
                pila);
        lavoro.finisci(Stato.ANNULLATA, "Annullato: il lavoro non rispondeva più ("
                + (lavoro.ricerca ? "ricerca dei file" : "fuori dai file") + "), chiuso senza aspettarlo", clock.instant());
    }

    /** I file della cartella automatica che non si rileggono finché non cambiano, dal più recente. */
    public List<FileScartato> scartati() {
        return memoria.elenco();
    }

    /** "Riprova": al prossimo giro la cartella automatica rilegge anche i file già falliti. */
    public long riprovaScartati() {
        long quanti = memoria.dimenticaTutti();
        log.info("Importazione automatica: {} file falliti da rileggere al prossimo giro", quanti);
        return quanti;
    }

    /**
     * Ogni {@code fototimeline.importazione-automatica.intervallo}: se la
     * cartella automatica ha foto da leggere (non solo già fallite o ancora
     * in copia) e il cloud è montato, le importa spostandole.
     */
    @Scheduled(fixedDelayString = "${fototimeline.importazione-automatica.intervallo:PT15M}",
            initialDelayString = "${fototimeline.importazione-automatica.ritardo-iniziale:PT2M}")
    public void controllaCartellaAutomatica() {
        Path cartella = automatica.cartella();
        if (cartella == null || !archivio.disponibile()) {
            return;
        }
        Lavoro attuale = ultimo.get();
        if (attuale != null && attuale.inCorso()) {
            return;
        }
        try {
            if (!Files.isDirectory(cartella) || !importazione.daImportare(cartella, regoleAutomatica())) {
                return;
            }
            log.info("Foto nuove in {}: le importo", cartella);
            avvia(cartella, true, true, Origine.AUTOMATICA, null);
        } catch (IllegalStateException | ArchivioNonDisponibile e) {
            log.debug("Importazione automatica rimandata: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Controllo della cartella automatica non riuscito: {}", e.getMessage());
        }
    }

    private void esegui(Lavoro lavoro, Compito compito) {
        String nome = switch (lavoro.origine) {
            case INDICIZZAZIONE -> "Indicizzazione";
            case LUOGHI -> "Calcolo dei luoghi";
            case EVENTI -> "Calcolo degli eventi";
            default -> "Importazione";
        };
        if (!lavoro.inCorso()) {
            // Chiuso dal guardiano mentre aspettava quello di prima.
            return;
        }
        lavoro.comincia();
        try {
            compito.esegui(lavoro);
            lavoro.finisci(lavoro.annullata ? Stato.ANNULLATA : Stato.FINITA, null, clock.instant());
        } catch (Exception e) {
            log.warn("{} di {} fallita", nome, lavoro.cartella, e);
            lavoro.finisci(Stato.FALLITA, e.getMessage() != null ? e.getMessage() : e.toString(), clock.instant());
        } catch (Throwable e) {
            // Prima solo Exception: un OutOfMemoryError lasciava il lavoro
            // "in corso" per sempre, senza thread e senza una riga nel log.
            log.error("{} di {} fermata da un errore inatteso", nome, lavoro.cartella, e);
            lavoro.finisci(Stato.FALLITA, "Errore inatteso: " + e, clock.instant());
        }
        StatoLavoro fine = lavoro.stato();
        if (lavoro.origine == Origine.LUOGHI) {
            log.info("{} {}: {} foto con GPS, {} con un luogo, {} senza ({} in {})", nome, fine.stato(), fine.trovate(),
                    fine.importate(), fine.duplicate(), fine.fatte(), Duration.between(fine.iniziatoIl(), fine.finitoIl()));
            return;
        }
        if (lavoro.origine == Origine.EVENTI) {
            log.info("{} {}: {} foto, {} in un evento, {} fuori ({} in {})", nome, fine.stato(), fine.trovate(),
                    fine.importate(), fine.duplicate(), fine.fatte(), Duration.between(fine.iniziatoIl(), fine.finitoIl()));
            return;
        }
        log.info("{} {} di {}: {} nuove, {} già presenti, {} errori, {} tolte dall'origine ({} in {})",
                nome, fine.stato(), fine.cartella(), fine.importate(), fine.duplicate(), fine.errori(), fine.rimossi(),
                fine.fatte(), Duration.between(fine.iniziatoIl(), fine.finitoIl()));
        if (fine.saltate() + fine.inArrivo() > 0) {
            log.info("{} di {}: {} file saltati perché già falliti uguali, {} lasciati al giro dopo perché in copia",
                    nome, fine.cartella(), fine.saltate(), fine.inArrivo());
        }
    }

    @PreDestroy
    void chiudi() {
        annulla();
        guardiano.shutdownNow();
    }

    /**
     * Stato mutabile di un lavoro; lo legge l'API mentre i thread
     * dell'importazione (più d'uno: un file ciascuno) lo aggiornano.
     */
    private final class Lavoro implements Avanzamento, LuoghiService.Avanzamento, EventiService.Avanzamento {
        private final String id = UUID.randomUUID().toString();
        private final String cartella;
        private final Path base;
        private final Origine origine;
        private final Instant iniziatoIl;
        private volatile boolean annullata;
        /** Scatta con Annulla: sveglia chi aspetta la riprova. */
        private final CountDownLatch annullamento = new CountDownLatch(1);
        /** Scatta quando lo stato non è più IN_CORSO: lo aspetta il lavoro dopo. */
        private final CountDownLatch chiuso = new CountDownLatch(1);
        /** Il thread del lavoro, per il log del guardiano e per accorgersi se muore. */
        private volatile Thread thread;
        /** I file in lavorazione adesso ({@link ImportazioneCartelle.Avanzamento#sorveglia}). */
        private volatile Supplier<List<FileInLavorazione.Vista>> inLavorazione;
        /** In coda dietro l'importazione automatica, che sta finendo i suoi file. */
        private boolean inAttesa;
        private boolean ricerca;
        private String cercaIn;
        private Stato stato = Stato.IN_CORSO;
        private Instant finitoIl;
        private int trovate;
        private int fatte;
        private int importate;
        private int duplicate;
        private int errori;
        private int rimossi;
        private int saltate;
        private int inArrivo;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(String cartella, Origine origine, Instant iniziatoIl) {
            this.cartella = cartella;
            this.base = Path.of(cartella).toAbsolutePath().normalize();
            this.origine = origine;
            this.iniziatoIl = iniziatoIl;
            this.ricerca = origine != Origine.LUOGHI && origine != Origine.EVENTI;
        }

        synchronized boolean inCorso() {
            return stato == Stato.IN_CORSO;
        }

        /** True se l'ha annullato adesso (non lo era già). */
        synchronized boolean annulla() {
            if (annullata || stato != Stato.IN_CORSO) {
                return false;
            }
            annullata = true;
            annullamento.countDown();
            return true;
        }

        /** Aspetta che si chiuda: annullato, si chiude da sé o lo chiude il guardiano. */
        void aspettaChiusura() {
            try {
                chiuso.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        /**
         * Stato "in corso" ma il thread non c'è più: non dovrebbe succedere
         * (esegui chiude sempre), ma se succede il lavoro non resta appeso.
         */
        void controllaThread(Instant adesso) {
            Thread t = thread;
            if (t != null && !t.isAlive() && inCorso()) {
                log.error("Lavoro {} di {} ancora in corso ma senza thread: lo chiudo come fallito", origine, cartella);
                finisci(Stato.FALLITA, "Il lavoro si è fermato senza dire perché: guarda i log del server", adesso);
            }
        }

        @Override
        public void sorveglia(Supplier<List<FileInLavorazione.Vista>> elenco) {
            inLavorazione = elenco;
        }

        synchronized void comincia() {
            inAttesa = false;
        }

        @Override
        public synchronized void cercando(int trovati, Path dove) {
            trovate = trovati;
            Path c = dove.toAbsolutePath().normalize();
            cercaIn = (c.startsWith(base) ? base.relativize(c) : c).toString().replace('\\', '/');
        }

        @Override
        public synchronized void inizio(int totale) {
            trovate = totale;
            ricerca = false;
            cercaIn = null;
        }

        @Override
        public synchronized void saltate(int giaFallite, int inArrivo) {
            saltate = giaFallite;
            this.inArrivo = inArrivo;
        }

        @Override
        public synchronized void fatta(Path file, Caricamento esito, boolean rimossa) {
            fatte++;
            switch (esito.esito()) {
                case CARICATA -> importate++;
                case DUPLICATA -> duplicate++;
                case ERRORE -> {
                    errori++;
                    if (messaggi.size() < MAX_MESSAGGI) {
                        messaggi.add(importazione.relativo(file) + ": " + esito.messaggio());
                    }
                }
            }
            if (rimossa) {
                rimossi++;
            }
            if (origine == Origine.AUTOMATICA && fatte % 250 == 0) {
                log.info("Importazione automatica: {} di {} ({} nuove, {} già presenti, {} errori)", fatte, trovate,
                        importate, duplicate, errori);
            }
            if (origine == Origine.INDICIZZAZIONE && fatte % 1000 == 0) {
                log.info("Indicizzazione: {} di {} ({} nuove, {} già presenti, {} errori)", fatte, trovate,
                        importate, duplicate, errori);
            }
        }

        @Override
        public synchronized void sparita(Path file) {
            fatte++;
            rimossi++;
            log.info("Importazione automatica: {} tolto da altri prima di leggerlo", importazione.relativo(file));
        }

        @Override
        public synchronized void calcolate(int quante, int conLuogo) {
            fatte += quante;
            importate += conLuogo;
            duplicate += quante - conLuogo;
        }

        @Override
        public synchronized void raggruppate(int quante, int inEventi) {
            fatte += quante;
            importate += inEventi;
            duplicate += quante - inEventi;
        }

        @Override
        public boolean annullata() {
            return annullata;
        }

        @Override
        public boolean aspetta(Duration quanto) throws InterruptedException {
            return annullamento.await(quanto.toNanos(), TimeUnit.NANOSECONDS);
        }

        /** Solo la prima volta: chi chiude dopo (il thread tornato dopo il guardiano) non cambia niente. */
        synchronized void finisci(Stato fine, String motivo, Instant quando) {
            if (stato != Stato.IN_CORSO) {
                return;
            }
            stato = fine;
            errore = motivo;
            finitoIl = quando;
            ricerca = false;
            cercaIn = null;
            inAttesa = false;
            chiuso.countDown();
        }

        synchronized StatoLavoro stato() {
            Supplier<List<FileInLavorazione.Vista>> elenco = inLavorazione;
            List<FileInLavorazione.Vista> file = stato == Stato.IN_CORSO && elenco != null ? elenco.get() : List.of();
            return new StatoLavoro(id, importazione.relativo(Path.of(cartella)), origine, stato, iniziatoIl, finitoIl,
                    trovate, fatte, importate, duplicate, errori, rimossi, List.copyOf(messaggi), errore, saltate,
                    inArrivo, ricerca, cercaIn, annullata && stato == Stato.IN_CORSO, inAttesa, file);
        }
    }
}
