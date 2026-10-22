package it.fototimeline.service;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * Un file che un lavoro di importazione sta leggendo, su un thread suo
 * ({@link ImportazioneCartelle#inParallelo}): a che fase è, da quando, e
 * quando scade il suo tempo massimo ({@link IndicizzazioneProperties#tempoMassimo}).
 *
 * <p>Finisce in uno di due modi, e uno solo: lo {@link #concludi conclude} il
 * suo thread (che allora lo conta), o lo {@link #abbandona abbandona} chi
 * sorveglia il lavoro (che lo conta come errore di lettura). Abbandonato, il
 * file è {@link #fermato}: se la lettura appesa prima o poi torna, il thread
 * smette alla prima occasione senza scrivere niente ({@link FotoService.Annullata}),
 * e il turno per le parti pesanti che teneva è già tornato agli altri.
 */
public final class FileInLavorazione implements ControlloFile {

    /** Per lo stato del lavoro: il file, la fase e da quanti secondi (in tutto e nella fase). */
    public record Vista(String file, String fase, long secondi, long secondiFase) {
    }

    private enum Stato { IN_CORSO, CONCLUSO, ABBANDONATO }

    private final Path file;
    private final String nome;
    private final BooleanSupplier lavoroAnnullato;
    private final IndicizzazioneProperties tempi;
    private final long inizio = System.nanoTime();
    private final Instant iniziatoIl = Instant.now();
    private volatile Fase fase = Fase.INIZIO;
    private volatile long faseDal = inizio;
    private volatile long scadenza;
    private volatile Stato stato = Stato.IN_CORSO;
    private volatile Thread thread;
    /** Le risorse prese ({@link #tieni}) e non ancora restituite. */
    private final List<Runnable> tenute = new ArrayList<>();

    FileInLavorazione(Path file, String nome, BooleanSupplier lavoroAnnullato, IndicizzazioneProperties tempi) {
        this.file = file;
        this.nome = nome;
        this.lavoroAnnullato = lavoroAnnullato;
        this.tempi = tempi;
        this.scadenza = inizio + tempi.tempoMassimo(0).toNanos();
    }

    public Path file() {
        return file;
    }

    /** Il percorso da mostrare (relativo alla radice delle importazioni). */
    public String nome() {
        return nome;
    }

    public Instant iniziatoIl() {
        return iniziatoIl;
    }

    public Fase faseCorrente() {
        return fase;
    }

    void thread(Thread t) {
        this.thread = t;
    }

    /** Il thread che lo lavora: per il log quando lo si abbandona. */
    Thread thread() {
        return thread;
    }

    /** Appena se ne sa la dimensione: il tempo massimo cresce coi GB. */
    void dimensione(long dimensione) {
        scadenza = inizio + tempi.tempoMassimo(dimensione).toNanos();
    }

    boolean scaduto(long adesso) {
        return stato == Stato.IN_CORSO && adesso - scadenza >= 0;
    }

    /** Nanosecondi alla scadenza (negativi se passata). */
    long mancano(long adesso) {
        return scadenza - adesso;
    }

    Duration durata() {
        return Duration.ofNanos(System.nanoTime() - inizio);
    }

    boolean abbandonato() {
        return stato == Stato.ABBANDONATO;
    }

    @Override
    public boolean fermato() {
        return stato == Stato.ABBANDONATO || lavoroAnnullato.getAsBoolean();
    }

    @Override
    public void fase(Fase fase) {
        this.fase = fase;
        this.faseDal = System.nanoTime();
    }

    @Override
    public synchronized boolean tieni(Runnable rilascio) {
        if (stato == Stato.ABBANDONATO) {
            return false;
        }
        tenute.add(rilascio);
        return true;
    }

    @Override
    public synchronized void lascia(Runnable rilascio) {
        tenute.remove(rilascio);
    }

    /**
     * Il thread del file ha finito e conta l'esito: true se tocca a lui,
     * false se il file era già stato abbandonato (l'ha contato chi l'ha
     * abbandonato; il thread non deve fare né contare niente) o già concluso.
     */
    synchronized boolean concludi() {
        if (stato != Stato.IN_CORSO) {
            return false;
        }
        stato = Stato.CONCLUSO;
        return true;
    }

    /**
     * Lo abbandona chi sorveglia il lavoro: tempo massimo superato, o Annulla
     * che ha aspettato abbastanza. Restituisce subito le risorse che teneva.
     * True se era ancora in corso (va contato come errore), false se il suo
     * thread l'aveva già concluso.
     */
    boolean abbandona() {
        List<Runnable> daRilasciare;
        synchronized (this) {
            if (stato != Stato.IN_CORSO) {
                return false;
            }
            stato = Stato.ABBANDONATO;
            daRilasciare = List.copyOf(tenute);
            tenute.clear();
        }
        daRilasciare.forEach(Runnable::run);
        return true;
    }

    Vista vista() {
        long adesso = System.nanoTime();
        return new Vista(nome, fase.etichetta(), Duration.ofNanos(adesso - inizio).toSeconds(),
                Duration.ofNanos(adesso - faseDal).toSeconds());
    }

    @Override
    public String toString() {
        return nome + " · " + fase.etichetta() + " · " + durata().toSeconds() + " s";
    }
}
