package it.fototimeline.quasiuguali;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.FotoService;
import jakarta.annotation.PreDestroy;

/**
 * "Calcola impronte": dà impronta (pHash) e {@link Firma} alle foto che sono
 * in archivio da prima (le nuove le prendono all'importazione). In
 * sottofondo, a blocchi in ordine di id, una foto alla volta dalla sua
 * miniatura; si può annullare e rilanciare: tocca solo le foto senza impronta
 * o senza la firma della {@link Impronta#VERSIONE} di adesso. Un pHash che c'è
 * già non si riscrive (la firma nuova non lo cambia): si legge la miniatura
 * una volta sola per foto, qualche millisecondo, niente cloud.
 * Su un thread suo: non ferma le importazioni.
 */
@Service
public class CalcoloImpronte {

    private static final Logger log = LoggerFactory.getLogger(CalcoloImpronte.class);
    private static final int BLOCCO = 200;

    public enum Stato { IN_CORSO, FINITO, ANNULLATO, FALLITO }

    /**
     * @param stato         null se dall'avvio non è mai partito
     * @param senzaImpronta foto (non video) ancora senza impronta, adesso
     */
    public record StatoCalcolo(Stato stato, Instant iniziatoIl, Instant finitoIl, int totale, int fatte,
            int calcolate, int errori, String errore, long senzaImpronta) {
    }

    private final MongoTemplate mongo;
    private final FotoService fotoService;
    private final QuasiUguali quasiUguali;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "impronte");
        t.setDaemon(true);
        return t;
    });

    private Stato stato;
    private Instant iniziatoIl;
    private Instant finitoIl;
    private int totale;
    private int fatte;
    private int calcolate;
    private int errori;
    private String errore;
    private volatile boolean annullato;

    public CalcoloImpronte(MongoTemplate mongo, FotoService fotoService, QuasiUguali quasiUguali,
            Optional<Clock> clock) {
        this.mongo = mongo;
        this.fotoService = fotoService;
        this.quasiUguali = quasiUguali;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * Le foto senza impronta o con la firma mancante o di un'altra versione:
     * niente video (quelli che non hanno il campo sono foto vecchie) né foto
     * nel cestino. Anche le private: il calcolo non mostra niente.
     */
    private static Criteria senzaImpronta() {
        return new Criteria().andOperator(
                Criteria.where("video").ne(true).and(FotoService.CESTINO).is(null),
                new Criteria().orOperator(Criteria.where("impronta").exists(false),
                        Criteria.where("firma.versione").ne(Impronta.VERSIONE)));
    }

    /**
     * Prima lo stato, poi il conteggio: se dice FINITO, le impronte del giro
     * sono già tutte su Mongo e {@code senzaImpronta} le vede. Al contrario
     * (contare e poi leggere lo stato) il giro poteva finire in mezzo e
     * arrivava "FINITO" con le ultime foto ancora contate come mancanti.
     */
    public StatoCalcolo stato() {
        StatoCalcolo s;
        synchronized (this) {
            s = new StatoCalcolo(stato, iniziatoIl, finitoIl, totale, fatte, calcolate, errori, errore, 0);
        }
        long mancanti = mongo.count(Query.query(senzaImpronta()), Foto.class);
        return new StatoCalcolo(s.stato(), s.iniziatoIl(), s.finitoIl(), s.totale(), s.fatte(), s.calcolate(),
                s.errori(), s.errore(), mancanti);
    }

    /** Lo fa partire in sottofondo; con uno già in corso, IllegalStateException (409). */
    public StatoCalcolo avvia() {
        synchronized (this) {
            if (stato == Stato.IN_CORSO) {
                throw new IllegalStateException("Il calcolo delle impronte è già in corso");
            }
            stato = Stato.IN_CORSO;
            iniziatoIl = clock.instant();
            finitoIl = null;
            totale = (int) mongo.count(Query.query(senzaImpronta()), Foto.class);
            fatte = 0;
            calcolate = 0;
            errori = 0;
            errore = null;
            annullato = false;
        }
        esecutore.submit(this::esegui);
        return stato();
    }

    public synchronized boolean annulla() {
        if (stato != Stato.IN_CORSO) {
            return false;
        }
        annullato = true;
        return true;
    }

    private void esegui() {
        Stato fine = Stato.FINITO;
        String motivo = null;
        try {
            String ultimo = "";
            while (!annullato) {
                // Per id crescente dopo l'ultimo visto: una foto che non riesce non si ripesca all'infinito.
                Query blocco = Query.query(new Criteria().andOperator(senzaImpronta(), Criteria.where("_id").gt(ultimo)))
                        .with(Sort.by("_id"))
                        .limit(BLOCCO);
                List<Foto> foto = mongo.find(blocco, Foto.class);
                if (foto.isEmpty()) {
                    break;
                }
                for (Foto f : foto) {
                    if (annullato) {
                        break;
                    }
                    boolean riuscita = calcola(f);
                    synchronized (this) {
                        fatte++;
                        if (riuscita) {
                            calcolate++;
                        } else {
                            errori++;
                        }
                    }
                    ultimo = f.getId();
                }
            }
            if (annullato) {
                fine = Stato.ANNULLATO;
            }
        } catch (RuntimeException e) {
            log.warn("Calcolo delle impronte fallito", e);
            fine = Stato.FALLITO;
            motivo = e.getMessage();
        }
        // Prima la cache, poi lo stato: se dice FINITO, i gruppi si rifanno con le impronte nuove.
        quasiUguali.invalida();
        synchronized (this) {
            stato = fine;
            errore = motivo;
            finitoIl = clock.instant();
            log.info("Calcolo delle impronte {}: {} calcolate, {} errori ({} in {})", fine, calcolate, errori,
                    fatte, Duration.between(iniziatoIl, finitoIl));
        }
    }

    private boolean calcola(Foto f) {
        try {
            // La miniatura sta sul disco del server; se manca si rifà dall'originale (serve il cloud).
            Impronta.Analisi analisi = Impronta.analizza(fotoService.fileMiniatura(f));
            Update update = new Update().set("firma", analisi.firma());
            if (f.getImpronta() == null) {
                update.set("impronta", analisi.impronta());
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(f.getId())), update, Foto.class);
            return true;
        } catch (Exception e) {
            log.debug("Impronta di {} non calcolata: {}", f.getPercorso(), e.toString());
            return false;
        }
    }

    @PreDestroy
    void chiudi() {
        annulla();
        esecutore.shutdown();
    }
}
