package it.fototimeline.contenuto;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.contenuto.Visione.EsitoBlocco;
import it.fototimeline.contenuto.Visione.Somiglianza;
import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.EventiFoto.FotoAggiunta;
import it.fototimeline.service.EventiFoto.FotoEliminata;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Risultati.PaginaFoto;
import jakarta.annotation.PreDestroy;

/**
 * Ricerca per contenuto ("spiaggia", "torta di compleanno"): le miniature
 * vanno al servizio visione, che tiene l'indice dei vettori CLIP; qui resta
 * solo il segno {@code contenutoIndicizzato} sulla foto.
 *
 * <ul>
 * <li>Ogni foto nuova (importazione, caricamento, "Indicizza archivio") si
 *     manda da sola, in coda su un thread suo: l'importazione non aspetta.</li>
 * <li>"Indicizza contenuto" (admin) manda a blocchi tutte quelle senza segno.
 *     Con 150.000 foto su CPU ci vogliono ore: se l'app riparte, riparte anche
 *     lui (documento {@code impostazioni/indice-contenuto}), e siccome prende
 *     solo le foto senza segno ricomincia da dove era arrivato.</li>
 * <li>Una foto eliminata si toglie anche dall'indice.</li>
 * </ul>
 *
 * Senza {@code fototimeline.visione.url} non fa niente: la funzione non c'è.
 */
@Service
public class IndiceContenuto {

    private static final Logger log = LoggerFactory.getLogger(IndiceContenuto.class);
    static final String ID = "indice-contenuto";
    static final String CAMPO = "contenutoIndicizzato";
    private static final String IMPOSTAZIONI = "impostazioni";
    private static final int MAX_MESSAGGI = 20;
    private static final int TENTATIVI = 5;

    public enum Stato { IN_CORSO, FINITO, ANNULLATO, FALLITO }

    /** Il lavoro "Indicizza contenuto", per l'API. {@code daFare}: le foto senza segno quando è partito. */
    public record StatoLavoro(String id, Stato stato, Instant iniziatoIl, Instant finitoIl, long daFare, int fatte,
            int errori, List<String> messaggi, String errore) {
    }

    /**
     * @param attiva     se la ricerca per contenuto c'è (visione configurata)
     * @param foto       foto e video in archivio
     * @param mancanti   quelli non ancora nell'indice
     * @param nellIndice i vettori che ha visione; null se non risponde
     */
    public record StatoContenuto(boolean attiva, long foto, long indicizzate, long mancanti, Long nellIndice,
            StatoLavoro lavoro) {
    }

    private final VisioneProperties properties;
    private final Visione visione;
    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final FotoService fotoService;
    private final Clock clock;
    /** Le foto appena entrate, una alla volta. Coda piena: si saltano, le prende il lavoro. */
    private final ThreadPoolExecutor singole = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(10_000), demone("contenuto"), new ThreadPoolExecutor.DiscardPolicy());
    private final ExecutorService lavori = Executors.newSingleThreadExecutor(demone("indice-contenuto"));
    private final AtomicReference<Lavoro> ultimo = new AtomicReference<>();

    public IndiceContenuto(VisioneProperties properties, Visione visione, MongoTemplate mongo, ArchivioFile archivio,
            FotoService fotoService, Optional<Clock> clock) {
        this.properties = properties;
        this.visione = visione;
        this.mongo = mongo;
        this.archivio = archivio;
        this.fotoService = fotoService;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public boolean attiva() {
        return properties.attiva();
    }

    // ------------------------------------------------------------ foto nuove ed eliminate

    @EventListener
    public void aggiunta(FotoAggiunta evento) {
        if (attiva()) {
            singole.execute(() -> indicizzaUna(evento.id()));
        }
    }

    @EventListener
    public void eliminata(FotoEliminata evento) {
        if (attiva()) {
            singole.execute(() -> {
                try {
                    visione.togli(evento.id());
                } catch (RuntimeException e) {
                    // Resta un vettore senza foto: la ricerca lo scarta (cerca solo tra le foto che ci sono).
                    log.warn("Non riesco a togliere {} dall'indice del contenuto: {}", evento.id(), e.getMessage());
                }
            });
        }
    }

    private void indicizzaUna(String id) {
        Path miniatura = archivio.miniatura(id);
        if (!Files.exists(miniatura)) {
            return;
        }
        try {
            visione.metti(id, miniatura);
            segna(List.of(id));
        } catch (RuntimeException e) {
            log.warn("Contenuto di {} non indicizzato (lo riprende \"Indicizza contenuto\"): {}", id, e.getMessage());
        }
    }

    private void segna(Collection<String> ids) {
        if (!ids.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("_id").in(ids)), new Update().set(CAMPO, clock.instant()),
                    Foto.class);
        }
    }

    // ------------------------------------------------------------ "Indicizza contenuto"

    /**
     * Manda a visione tutte le foto senza segno, in sottofondo. Con
     * {@code daCapo} prima toglie tutti i segni (indice di visione perso o
     * modello cambiato). Uno alla volta: con un altro in corso, IllegalStateException.
     */
    public synchronized StatoLavoro avvia(boolean daCapo) {
        if (!attiva()) {
            throw new IllegalStateException("La ricerca per contenuto è spenta: manca il servizio visione");
        }
        Lavoro attuale = ultimo.get();
        if (attuale != null && attuale.inCorso()) {
            throw new IllegalStateException("\"Indicizza contenuto\" è già in corso");
        }
        if (daCapo) {
            mongo.updateMulti(Query.query(Criteria.where(CAMPO).ne(null)), new Update().unset(CAMPO), Foto.class);
        }
        ricordaAttivo(true);
        return lancia();
    }

    /** Si ferma dopo il blocco che sta mandando, e non riparte al prossimo avvio. */
    public boolean annulla() {
        Lavoro lavoro = ultimo.get();
        if (lavoro == null || !lavoro.inCorso()) {
            return false;
        }
        lavoro.annullato = true;
        ricordaAttivo(false);
        return true;
    }

    /** Se l'app si era fermata a metà del lavoro, lo riprende. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void riprendi() {
        Document d = mongo.findById(ID, Document.class, IMPOSTAZIONI);
        boolean inCorso = corrente().map(l -> l.stato() == Stato.IN_CORSO).orElse(false);
        if (attiva() && d != null && Boolean.TRUE.equals(d.getBoolean("attivo")) && !inCorso) {
            log.info("Riprendo \"Indicizza contenuto\" da dove era arrivato");
            lancia();
        }
    }

    public Optional<StatoLavoro> corrente() {
        return Optional.ofNullable(ultimo.get()).map(Lavoro::stato);
    }

    private StatoLavoro lancia() {
        Lavoro lavoro = new Lavoro(clock.instant(), mongo.count(senzaSegno(), Foto.class));
        ultimo.set(lavoro);
        lavori.submit(() -> esegui(lavoro));
        return lavoro.stato();
    }

    private void ricordaAttivo(boolean attivo) {
        mongo.upsert(Query.query(Criteria.where("_id").is(ID)), new Update().set("attivo", attivo), IMPOSTAZIONI);
    }

    private static Query senzaSegno() {
        return Query.query(Criteria.where(CAMPO).is(null));
    }

    private void esegui(Lavoro lavoro) {
        // In ordine di id: le foto che non riescono si saltano, senza ripassarci in questo giro.
        String ultimoId = "";
        try {
            while (!lavoro.annullato) {
                Query query = Query.query(Criteria.where(CAMPO).is(null).and("_id").gt(ultimoId))
                        .with(Sort.by("_id")).limit(properties.blocco());
                List<Foto> blocco = mongo.find(query, Foto.class);
                if (blocco.isEmpty()) {
                    break;
                }
                ultimoId = blocco.getLast().getId();
                Map<String, Path> miniature = new LinkedHashMap<>();
                for (Foto foto : blocco) {
                    try {
                        miniature.put(foto.getId(), miniatura(foto));
                    } catch (RuntimeException e) {
                        lavoro.errore(foto.getNomeOriginale() + ": miniatura non disponibile (" + e.getMessage() + ")");
                    }
                }
                if (miniature.isEmpty()) {
                    continue;
                }
                EsitoBlocco esito = conTentativi(lavoro, () -> visione.mettiBlocco(miniature));
                segna(esito.aggiunte());
                lavoro.fatte(esito.aggiunte().size());
                Map<String, String> nomi = new HashMap<>();
                blocco.forEach(f -> nomi.put(f.getId(), f.getNomeOriginale()));
                esito.errori().forEach((id, motivo) -> lavoro.errore(nomi.getOrDefault(id, id) + ": " + motivo));
            }
            lavoro.finisci(lavoro.annullato ? Stato.ANNULLATO : Stato.FINITO, null, clock.instant());
            if (!lavoro.annullato) {
                ricordaAttivo(false);
            }
        } catch (Exception e) {
            // Resta "attivo": riparte al prossimo avvio dell'app, o dal pulsante.
            log.warn("\"Indicizza contenuto\" fermo: {}", e.getMessage());
            lavoro.finisci(Stato.FALLITO, e.getMessage(), clock.instant());
        }
        StatoLavoro fine = lavoro.stato();
        log.info("Indicizza contenuto {}: {} foto nell'indice, {} errori, {} ancora da fare", fine.stato(), fine.fatte(),
                fine.errori(), mongo.count(senzaSegno(), Foto.class));
    }

    /** La miniatura su disco; se manca la rifà dall'originale (serve il cloud montato). */
    private Path miniatura(Foto foto) {
        Path miniatura = archivio.miniatura(foto.getId());
        return Files.exists(miniatura) ? miniatura : fotoService.fileMiniatura(foto);
    }

    /** Visione può essere in riavvio (carica il modello): qualche tentativo prima di arrendersi. */
    private <T> T conTentativi(Lavoro lavoro, Supplier<T> chiamata) throws InterruptedException {
        for (int tentativo = 1;; tentativo++) {
            try {
                return chiamata.get();
            } catch (VisioneNonRisponde e) {
                if (tentativo >= TENTATIVI || lavoro.annullato) {
                    throw e;
                }
                log.info("{}: riprovo tra {}", e.getMessage(), properties.attesa());
                Thread.sleep(properties.attesa().toMillis());
            }
        }
    }

    // ------------------------------------------------------------ stato e ricerca

    public StatoContenuto stato() {
        if (!attiva()) {
            return new StatoContenuto(false, 0, 0, 0, null, null);
        }
        long foto = mongo.count(new Query(), Foto.class);
        long mancanti = mongo.count(senzaSegno(), Foto.class);
        Long nellIndice;
        try {
            nellIndice = visione.totale();
        } catch (RuntimeException e) {
            nellIndice = null;
        }
        return new StatoContenuto(true, foto, foto - mancanti, mancanti, nellIndice, corrente().orElse(null));
    }

    /**
     * Le foto più simili al testo, dalla più simile, con gli altri filtri
     * della timeline (tag, album, preferite, date; {@code q} non conta). Solo
     * quelle sopra la soglia e vicine alla più simile: per "cane" i cani, non
     * tutte le foto in ordine di somiglianza.
     */
    public PaginaFoto cerca(String testo, FiltroFoto filtro, int pagina, int dimensione) {
        if (!attiva()) {
            throw new IllegalStateException("La ricerca per contenuto è spenta: manca il servizio visione");
        }
        if (testo == null || testo.isBlank()) {
            throw new IllegalArgumentException("Cosa cercare?");
        }
        List<Somiglianza> simili = visione.cerca(testo.trim(), properties.massimo(), properties.soglia());
        if (simili.isEmpty()) {
            return new PaginaFoto(List.of(), 0, pagina, false);
        }
        Map<String, Double> punteggi = new HashMap<>();
        simili.forEach(x -> punteggi.putIfAbsent(x.id(), x.punteggio()));
        FiltroFoto altri = new FiltroFoto(null, filtro.tag(), filtro.album(), filtro.preferite(), filtro.dal(), filtro.al());
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").in(punteggi.keySet()), FotoService.criteri(altri)));
        List<Foto> trovate = new ArrayList<>(mongo.find(query, Foto.class));
        trovate.sort(Comparator.comparing((Foto f) -> punteggi.get(f.getId())).reversed());
        // Il distacco si misura dalla più simile tra quelle che ci sono (l'indice può avere foto già eliminate).
        if (!trovate.isEmpty()) {
            double minimo = punteggi.get(trovate.getFirst().getId()) - properties.margine();
            trovate.removeIf(f -> punteggi.get(f.getId()) < minimo);
        }
        int da = (int) Math.min((long) pagina * dimensione, trovate.size());
        int a = Math.min(da + dimensione, trovate.size());
        return new PaginaFoto(List.copyOf(trovate.subList(da, a)), trovate.size(), pagina, a < trovate.size());
    }

    @PreDestroy
    void chiudi() {
        Lavoro lavoro = ultimo.get();
        if (lavoro != null) {
            // Non annulla(): il segno "attivo" resta, e al prossimo avvio si riprende.
            lavoro.annullato = true;
        }
        singole.shutdown();
        lavori.shutdown();
    }

    private static ThreadFactory demone(String nome) {
        return r -> {
            Thread t = new Thread(r, nome);
            t.setDaemon(true);
            return t;
        };
    }

    /** Stato mutabile del lavoro; lo legge l'API mentre il thread lo aggiorna. */
    private static final class Lavoro {
        private final String id = UUID.randomUUID().toString();
        private final Instant iniziatoIl;
        private final long daFare;
        private volatile boolean annullato;
        private Stato stato = Stato.IN_CORSO;
        private Instant finitoIl;
        private int fatte;
        private int errori;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(Instant iniziatoIl, long daFare) {
            this.iniziatoIl = iniziatoIl;
            this.daFare = daFare;
        }

        synchronized boolean inCorso() {
            return stato == Stato.IN_CORSO;
        }

        synchronized void fatte(int n) {
            fatte += n;
        }

        synchronized void errore(String messaggio) {
            errori++;
            if (messaggi.size() < MAX_MESSAGGI) {
                messaggi.add(messaggio);
            }
        }

        synchronized void finisci(Stato fine, String motivo, Instant quando) {
            stato = fine;
            errore = motivo;
            finitoIl = quando;
        }

        synchronized StatoLavoro stato() {
            return new StatoLavoro(id, stato, iniziatoIl, finitoIl, daFare, fatte, errori, List.copyOf(messaggi), errore);
        }
    }
}
