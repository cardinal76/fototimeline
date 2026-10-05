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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.cloud.ArchivioNonDisponibile;
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
    private static final int MAX_MESSAGGI = 50;

    /** MANUALE e AUTOMATICA importano una cartella; INDICIZZAZIONE cerca nell'archivio i file senza scheda. */
    public enum Origine { MANUALE, AUTOMATICA, INDICIZZAZIONE }

    public enum Stato { IN_CORSO, FINITA, ANNULLATA, FALLITA }

    /** Fotografia dello stato di un lavoro, per l'API. */
    public record StatoLavoro(String id, String cartella, Origine origine, Stato stato,
            Instant iniziatoIl, Instant finitoIl, int trovate, int fatte, int importate, int duplicate,
            int errori, int rimossi, List<String> messaggi, String errore) {
    }

    private final ImportazioneCartelle importazione;
    private final ImportazioneAutomaticaProperties automatica;
    private final ArchivioFile archivio;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "importazione");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Lavoro> ultimo = new AtomicReference<>();

    public LavoriImportazione(ImportazioneCartelle importazione, ImportazioneAutomaticaProperties automatica,
            ArchivioFile archivio, Optional<Clock> clock) {
        this.importazione = importazione;
        this.automatica = automatica;
        this.archivio = archivio;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Lancia l'importazione in sottofondo. Una alla volta: con un'altra in corso, IllegalStateException. */
    public StatoLavoro avvia(Path cartella, boolean albumDaCartella, boolean sposta, Origine origine) {
        return avvia(cartella, origine, lavoro -> importazione.importa(cartella, albumDaCartella, sposta, lavoro));
    }

    /**
     * "Indicizza archivio" in sottofondo, sullo stesso thread delle
     * importazioni: non gira mai insieme a una di loro.
     */
    public StatoLavoro avviaIndicizzazione() {
        return avvia(archivio.radice(), Origine.INDICIZZAZIONE, importazione::indicizza);
    }

    /** Quello che fa un lavoro, riferendo a {@code Avanzamento}. */
    private interface Compito {
        void esegui(Avanzamento avanzamento) throws Exception;
    }

    private synchronized StatoLavoro avvia(Path cartella, Origine origine, Compito compito) {
        Lavoro attuale = ultimo.get();
        if (attuale != null && attuale.inCorso()) {
            throw new IllegalStateException(attuale.origine == Origine.INDICIZZAZIONE
                    ? "C'è un'indicizzazione dell'archivio in corso: aspetta che finisca o annullala"
                    : "C'è già un'importazione in corso: aspetta che finisca o annullala");
        }
        archivio.verificaDisponibile();
        Lavoro lavoro = new Lavoro(cartella.toString(), origine, clock.instant());
        ultimo.set(lavoro);
        esecutore.submit(() -> esegui(lavoro, compito));
        return lavoro.stato();
    }

    /** L'importazione in corso o l'ultima finita, se c'è. */
    public Optional<StatoLavoro> corrente() {
        return Optional.ofNullable(ultimo.get()).map(Lavoro::stato);
    }

    /** Ferma l'importazione in corso dopo la foto che sta facendo. */
    public boolean annulla() {
        Lavoro lavoro = ultimo.get();
        if (lavoro == null || !lavoro.inCorso()) {
            return false;
        }
        lavoro.annullata = true;
        return true;
    }

    /**
     * Ogni {@code fototimeline.importazione-automatica.intervallo}: se la
     * cartella automatica ha foto e il cloud è montato, le importa spostandole.
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
            if (!Files.isDirectory(cartella) || !contieneImmagini(cartella)) {
                return;
            }
            log.info("Foto nuove in {}: le importo", cartella);
            avvia(cartella, true, true, Origine.AUTOMATICA);
        } catch (IllegalStateException | ArchivioNonDisponibile e) {
            log.debug("Importazione automatica rimandata: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Controllo della cartella automatica non riuscito: {}", e.getMessage());
        }
    }

    private boolean contieneImmagini(Path cartella) throws java.io.IOException {
        try (Stream<Path> s = Files.walk(cartella)) {
            return s.filter(Files::isRegularFile)
                    .anyMatch(p -> FotoService.TIPI.containsKey(FotoService.estensione(p.getFileName().toString())));
        }
    }

    private void esegui(Lavoro lavoro, Compito compito) {
        String nome = lavoro.origine == Origine.INDICIZZAZIONE ? "Indicizzazione" : "Importazione";
        try {
            compito.esegui(lavoro);
            lavoro.finisci(lavoro.annullata ? Stato.ANNULLATA : Stato.FINITA, null, clock.instant());
        } catch (Exception e) {
            log.warn("{} di {} fallita", nome, lavoro.cartella, e);
            lavoro.finisci(Stato.FALLITA, e.getMessage(), clock.instant());
        }
        StatoLavoro fine = lavoro.stato();
        log.info("{} {} di {}: {} nuove, {} già presenti, {} errori, {} tolte dall'origine ({} in {})",
                nome, fine.stato(), fine.cartella(), fine.importate(), fine.duplicate(), fine.errori(), fine.rimossi(),
                fine.fatte(), Duration.between(fine.iniziatoIl(), fine.finitoIl()));
    }

    @PreDestroy
    void chiudi() {
        annulla();
        esecutore.shutdown();
    }

    /** Stato mutabile di un lavoro; lo legge l'API mentre il thread di importazione lo aggiorna. */
    private final class Lavoro implements Avanzamento {
        private final String id = UUID.randomUUID().toString();
        private final String cartella;
        private final Origine origine;
        private final Instant iniziatoIl;
        private volatile boolean annullata;
        private Stato stato = Stato.IN_CORSO;
        private Instant finitoIl;
        private int trovate;
        private int fatte;
        private int importate;
        private int duplicate;
        private int errori;
        private int rimossi;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(String cartella, Origine origine, Instant iniziatoIl) {
            this.cartella = cartella;
            this.origine = origine;
            this.iniziatoIl = iniziatoIl;
        }

        synchronized boolean inCorso() {
            return stato == Stato.IN_CORSO;
        }

        @Override
        public synchronized void inizio(int totale) {
            trovate = totale;
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
        }

        @Override
        public boolean annullata() {
            return annullata;
        }

        synchronized void finisci(Stato fine, String motivo, Instant quando) {
            stato = fine;
            errore = motivo;
            finitoIl = quando;
        }

        synchronized StatoLavoro stato() {
            return new StatoLavoro(id, importazione.relativo(Path.of(cartella)), origine, stato, iniziatoIl, finitoIl,
                    trovate, fatte, importate, duplicate, errori, rimossi, List.copyOf(messaggi), errore);
        }
    }
}
