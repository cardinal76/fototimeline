package it.fototimeline.volti;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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

import it.fototimeline.contenuto.VisioneNonRisponde;
import it.fototimeline.contenuto.VisioneProperties;
import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.EventiFoto.FotoAggiunta;
import it.fototimeline.service.EventiFoto.FotoEliminata;
import it.fototimeline.service.EventiFoto.FotoNelCestino;
import it.fototimeline.service.EventiFoto.FotoRipristinata;
import it.fototimeline.service.FotoService;
import it.fototimeline.volti.VisioneVolti.ImmagineVolti;
import it.fototimeline.volti.VisioneVolti.Rilevamento;
import it.fototimeline.volti.VisioneVolti.Vicino;
import it.fototimeline.volti.VisioneVolti.VoltoRilevato;
import jakarta.annotation.PreDestroy;

/**
 * "Riconosci volti": le miniature vanno a visione, che trova i volti e ne
 * dà i vettori; qui i volti si salvano ({@code volti}), entrano nell'indice
 * dei vicini di visione e si raggruppano ({@link Persone#assegnaNuovi}).
 *
 * <ul>
 * <li>Solo foto e HEIC, dalla <b>miniatura</b> (480 px, sul disco di
 *     server2): niente video e niente originali dal cloud. Se la miniatura
 *     manca la rifà dall'originale, e lì serve il cloud montato.</li>
 * <li>Ogni foto nuova si fa da sola ({@link FotoAggiunta}), su un thread suo:
 *     l'importazione non aspetta.</li>
 * <li>Il lavoro prende le foto senza {@code voltiCercati} a blocchi, in
 *     ordine di id: annullabile, e se l'app riparte riprende da dove era
 *     ({@code impostazioni/riconosci-volti}).</li>
 * <li>Cestino: i volti di una foto nel cestino non contano e lasciano
 *     l'indice dei vicini; ripristinata tornano; eliminata per sempre se ne
 *     vanno.</li>
 * </ul>
 *
 * Spento senza visione o con {@code fototimeline.volti.attiva=false}.
 */
@Service
public class RiconoscimentoVolti {

    private static final Logger log = LoggerFactory.getLogger(RiconoscimentoVolti.class);
    static final String ID = "riconosci-volti";
    static final String CAMPO = "voltiCercati";
    private static final String IMPOSTAZIONI = "impostazioni";
    private static final int MAX_MESSAGGI = 20;
    private static final int TENTATIVI = 5;
    private static final int BLOCCO_INDICE = 500;

    public enum Stato { IN_CORSO, FINITO, ANNULLATO, FALLITO }

    /** Il lavoro, per l'API: {@code daFare} le foto senza volti cercati quando è partito. */
    public record StatoLavoro(String id, Stato stato, Instant iniziatoIl, Instant finitoIl, long daFare, int fatte,
            int volti, int errori, List<String> messaggi, String errore, boolean ricostruisci) {
    }

    /**
     * @param attiva     se il riconoscimento c'è (visione configurata e volti accesi)
     * @param foto       le foto (non i video) fuori dal cestino
     * @param mancanti   quelle non ancora guardate
     * @param volti      i volti trovati, fuori dal cestino
     * @param nellIndice i volti nell'indice di visione; null se non risponde
     * @param persone    le persone con un nome
     */
    public record StatoVolti(boolean attiva, long foto, long cercate, long mancanti, long volti, Long nellIndice,
            long persone, StatoLavoro lavoro) {
    }

    private final VoltiProperties properties;
    private final VisioneProperties visioneProperties;
    private final VisioneVolti visione;
    private final Persone persone;
    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final FotoService fotoService;
    private final Clock clock;
    /** Foto nuove ed eventi del cestino, uno alla volta. Coda piena: si saltano, le prende il lavoro. */
    private final ThreadPoolExecutor singole = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(10_000), demone("volti"), new ThreadPoolExecutor.DiscardPolicy());
    private final ExecutorService lavori = Executors.newSingleThreadExecutor(demone("riconosci-volti"));
    private final AtomicReference<Lavoro> ultimo = new AtomicReference<>();
    /** Un blocco di foto alla volta, fra il lavoro e le foto nuove: la stessa foto non si fa due volte. */
    private final ReentrantLock elaborazione = new ReentrantLock();

    public RiconoscimentoVolti(VoltiProperties properties, VisioneProperties visioneProperties, VisioneVolti visione,
            Persone persone, MongoTemplate mongo, ArchivioFile archivio, FotoService fotoService, Optional<Clock> clock) {
        this.properties = properties;
        this.visioneProperties = visioneProperties;
        this.visione = visione;
        this.persone = persone;
        this.mongo = mongo;
        this.archivio = archivio;
        this.fotoService = fotoService;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    public boolean attiva() {
        return visioneProperties.attiva() && properties.attiva();
    }

    /** Per i controller: con la funzione spenta, 409. */
    public void richiediAttiva() {
        if (!attiva()) {
            throw new IllegalStateException("Il riconoscimento dei volti è spento (FOTOTIMELINE_VOLTI o servizio visione)");
        }
    }

    // ------------------------------------------------------------ foto nuove e cestino

    @EventListener
    public void aggiunta(FotoAggiunta evento) {
        if (attiva()) {
            singole.execute(() -> unaFoto(evento.id()));
        }
    }

    @EventListener
    public void nelCestino(FotoNelCestino evento) {
        if (attiva()) {
            singole.execute(() -> {
                List<String> ids = persone.nelCestino(evento.id());
                togliDallIndice(evento.id(), ids);
            });
        }
    }

    @EventListener
    public void ripristinata(FotoRipristinata evento) {
        if (attiva()) {
            singole.execute(() -> {
                Map<String, float[]> vettori = persone.ripristinata(evento.id());
                try {
                    visione.metti(vettori);
                } catch (RuntimeException e) {
                    // Contano già (sono in Mongo); mancano solo come vicini dei volti nuovi, finché non si ricostruisce.
                    log.warn("Volti di {} non rimessi nell'indice: {}", evento.id(), e.getMessage());
                }
                Foto foto = mongo.findById(evento.id(), Foto.class);
                if (foto != null && foto.getVoltiCercati() == null) {
                    unaFoto(evento.id());
                }
            });
        }
    }

    @EventListener
    public void eliminata(FotoEliminata evento) {
        if (attiva()) {
            singole.execute(() -> togliDallIndice(evento.id(), persone.eliminata(evento.id())));
        }
    }

    private void togliDallIndice(String fotoId, List<String> ids) {
        try {
            visione.togli(ids);
        } catch (RuntimeException e) {
            // Resta un vettore in più nell'indice: i vicini nel cestino o spariti si scartano (Persone.assegna).
            log.warn("Volti di {} non tolti dall'indice: {}", fotoId, e.getMessage());
        }
    }

    private void unaFoto(String id) {
        Foto foto = mongo.findById(id, Foto.class);
        if (foto == null || foto.isVideo() || foto.getEliminataIl() != null || foto.getVoltiCercati() != null
                || !Files.exists(archivio.miniatura(id))) {
            return;
        }
        try {
            elabora(List.of(foto), null);
        } catch (RuntimeException e) {
            log.warn("Volti di {} non cercati (li riprende \"Riconosci volti\"): {}", id, e.getMessage());
        }
    }

    // ------------------------------------------------------------ un blocco di foto

    private record Esito(int fatte, int volti) {
    }

    /**
     * Trova i volti delle foto, li salva, li mette nell'indice e li assegna.
     * Le foto che non riescono restano senza {@code voltiCercati}.
     */
    private Esito elabora(List<Foto> blocco, Lavoro lavoro) {
        elaborazione.lock();
        try {
            // Rilette qui dentro: una foto nuova può averla già fatta il thread delle singole.
            List<String> ids = blocco.stream().map(Foto::getId).toList();
            Map<String, Foto> daFare = new LinkedHashMap<>();
            mongo.find(Query.query(Criteria.where("_id").in(ids).and(CAMPO).is(null).and(FotoService.CESTINO).is(null)),
                    Foto.class).forEach(f -> daFare.put(f.getId(), f));
            Map<String, Path> miniature = new LinkedHashMap<>();
            for (Foto foto : daFare.values()) {
                try {
                    miniature.put(foto.getId(), miniatura(foto));
                } catch (RuntimeException e) {
                    errore(lavoro, foto.getNomeOriginale() + ": miniatura non disponibile (" + e.getMessage() + ")");
                }
            }
            if (miniature.isEmpty()) {
                return new Esito(0, 0);
            }
            Rilevamento rilevamento = conTentativi(lavoro,
                    () -> visione.rileva(miniature, properties.latoMinimo(), properties.punteggioMinimo()));
            rilevamento.errori().forEach((id, motivo) -> {
                Foto f = daFare.get(id);
                errore(lavoro, (f == null ? id : f.getNomeOriginale()) + ": " + motivo);
            });

            List<Volto> nuovi = new ArrayList<>();
            for (var e : rilevamento.risultati().entrySet()) {
                if (!daFare.containsKey(e.getKey())) {
                    continue;
                }
                // Un giro interrotto a metà può aver lasciato dei volti: si rifanno.
                List<String> vecchi = persone.eliminata(e.getKey());
                if (!vecchi.isEmpty()) {
                    visione.togli(vecchi);
                }
                ImmagineVolti immagine = e.getValue();
                for (VoltoRilevato r : immagine.volti() == null ? List.<VoltoRilevato>of() : immagine.volti()) {
                    Volto v = new Volto();
                    v.setFotoId(e.getKey());
                    v.setRiquadro(r.riquadro());
                    v.setPunteggio(r.punteggio());
                    v.setVettore(Volto.inByte(r.vettore()));
                    v.setModello(rilevamento.modello());
                    v.setTrovatoIl(clock.instant());
                    nuovi.add(v);
                }
            }
            if (!nuovi.isEmpty()) {
                nuovi = new ArrayList<>(mongo.insertAll(nuovi));
                Map<String, float[]> vettori = new LinkedHashMap<>();
                nuovi.forEach(v -> vettori.put(v.getId(), v.vettoreFloat()));
                visione.metti(vettori);
                // I vicini di tutti i volti del blocco con una richiesta sola: l'indice si scorre una volta.
                List<List<Vicino>> vicini = visione.vicini(new ArrayList<>(vettori.values()), properties.vicini() + 4,
                        properties.sogliaProposta());
                persone.assegnaNuovi(nuovi, vicini);
            }
            List<String> fatte = rilevamento.risultati().keySet().stream().filter(daFare::containsKey).toList();
            if (!fatte.isEmpty()) {
                mongo.updateMulti(Query.query(Criteria.where("_id").in(fatte)), new Update().set(CAMPO, clock.instant()),
                        Foto.class);
            }
            return new Esito(fatte.size(), nuovi.size());
        } finally {
            elaborazione.unlock();
        }
    }

    /**
     * La miniatura dritta (senza la rotazione a mano: i riquadri dei volti
     * valgono su di lei, RitaglioVolto ritaglia da lì); se manca la rifà
     * dall'originale (serve il cloud montato).
     */
    private Path miniatura(Foto foto) {
        Path miniatura = archivio.miniatura(foto.getId());
        return Files.exists(miniatura) ? miniatura : fotoService.fileMiniaturaDritta(foto);
    }

    // ------------------------------------------------------------ "Riconosci volti"

    /**
     * Cerca i volti di tutte le foto che non li hanno ancora, in sottofondo.
     * Con {@code ricostruisci} prima rimette nell'indice di visione i vettori
     * dei volti già trovati (indice perso). Uno alla volta.
     */
    public synchronized StatoLavoro avvia(boolean ricostruisci) {
        richiediAttiva();
        Lavoro attuale = ultimo.get();
        if (attuale != null && attuale.inCorso()) {
            throw new IllegalStateException("\"Riconosci volti\" è già in corso");
        }
        ricordaAttivo(true, ricostruisci);
        return lancia(ricostruisci);
    }

    /** Si ferma dopo il blocco in corso, e non riparte al prossimo avvio. */
    public boolean annulla() {
        Lavoro lavoro = ultimo.get();
        if (lavoro == null || !lavoro.inCorso()) {
            return false;
        }
        lavoro.annullato = true;
        ricordaAttivo(false, false);
        return true;
    }

    /** Se l'app si era fermata a metà del lavoro, lo riprende. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void riprendi() {
        Document d = mongo.findById(ID, Document.class, IMPOSTAZIONI);
        boolean inCorso = corrente().map(l -> l.stato() == Stato.IN_CORSO).orElse(false);
        if (attiva() && d != null && Boolean.TRUE.equals(d.getBoolean("attivo")) && !inCorso) {
            log.info("Riprendo \"Riconosci volti\" da dove era arrivato");
            lancia(Boolean.TRUE.equals(d.getBoolean("ricostruisci")));
        }
    }

    public Optional<StatoLavoro> corrente() {
        return Optional.ofNullable(ultimo.get()).map(Lavoro::stato);
    }

    private StatoLavoro lancia(boolean ricostruisci) {
        Lavoro lavoro = new Lavoro(clock.instant(), mongo.count(daCercare(), Foto.class), ricostruisci);
        ultimo.set(lavoro);
        lavori.submit(() -> esegui(lavoro));
        return lavoro.stato();
    }

    private void ricordaAttivo(boolean attivo, boolean ricostruisci) {
        mongo.upsert(Query.query(Criteria.where("_id").is(ID)),
                new Update().set("attivo", attivo).set("ricostruisci", ricostruisci), IMPOSTAZIONI);
    }

    /** Le foto da guardare: senza volti cercati, non video, non nel cestino. */
    private static Query daCercare() {
        return Query.query(Criteria.where(CAMPO).is(null).and("video").is(false).and(FotoService.CESTINO).is(null));
    }

    private void esegui(Lavoro lavoro) {
        String ultimoId = "";
        try {
            if (lavoro.ricostruisci) {
                ricostruisciIndice(lavoro);
                // Fatto: se riparte, non lo rifà.
                if (!lavoro.annullato) {
                    ricordaAttivo(true, false);
                }
            }
            while (!lavoro.annullato) {
                Query query = Query.query(Criteria.where(CAMPO).is(null).and("video").is(false)
                                .and(FotoService.CESTINO).is(null).and("_id").gt(ultimoId))
                        .with(Sort.by("_id")).limit(properties.blocco());
                List<Foto> blocco = mongo.find(query, Foto.class);
                if (blocco.isEmpty()) {
                    break;
                }
                ultimoId = blocco.getLast().getId();
                Esito esito = elabora(blocco, lavoro);
                lavoro.fatte(esito.fatte(), esito.volti());
            }
            lavoro.finisci(lavoro.annullato ? Stato.ANNULLATO : Stato.FINITO, null, clock.instant());
            if (!lavoro.annullato) {
                ricordaAttivo(false, false);
            }
        } catch (Exception e) {
            // Resta "attivo": riparte al prossimo avvio dell'app, o dal pulsante.
            log.warn("\"Riconosci volti\" fermo: {}", e.getMessage());
            lavoro.finisci(Stato.FALLITO, e.getMessage(), clock.instant());
        }
        StatoLavoro fine = lavoro.stato();
        log.info("Riconosci volti {}: {} foto guardate, {} volti, {} errori, {} ancora da fare", fine.stato(), fine.fatte(),
                fine.volti(), fine.errori(), mongo.count(daCercare(), Foto.class));
    }

    /** Rimette nell'indice di visione i vettori di tutti i volti fuori dal cestino, a blocchi. */
    private void ricostruisciIndice(Lavoro lavoro) {
        String ultimoId = null;
        int messi = 0;
        while (!lavoro.annullato) {
            // Gli id dei volti sono ObjectId: "" non è confrontabile con loro, quindi il primo giro senza.
            Criteria criteri = Criteria.where("nelCestino").is(false);
            if (ultimoId != null) {
                criteri = criteri.and("_id").gt(ultimoId);
            }
            Query query = Query.query(criteri).with(Sort.by("_id")).limit(BLOCCO_INDICE);
            query.fields().include("vettore");
            List<Volto> volti = mongo.find(query, Volto.class);
            if (volti.isEmpty()) {
                break;
            }
            ultimoId = volti.getLast().getId();
            Map<String, float[]> vettori = new LinkedHashMap<>();
            volti.forEach(v -> vettori.put(v.getId(), v.vettoreFloat()));
            conTentativi(lavoro, () -> {
                visione.metti(vettori);
                return null;
            });
            messi += vettori.size();
        }
        log.info("Indice dei volti ricostruito: {} vettori", messi);
    }

    /** Visione può essere in riavvio (carica i modelli): qualche tentativo prima di arrendersi. */
    private <T> T conTentativi(Lavoro lavoro, Supplier<T> chiamata) {
        for (int tentativo = 1;; tentativo++) {
            try {
                return chiamata.get();
            } catch (VisioneNonRisponde e) {
                if (lavoro == null || tentativo >= TENTATIVI || lavoro.annullato) {
                    throw e;
                }
                log.info("{}: riprovo tra {}", e.getMessage(), properties.attesa());
                try {
                    Thread.sleep(properties.attesa().toMillis());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void errore(Lavoro lavoro, String messaggio) {
        if (lavoro != null) {
            lavoro.errore(messaggio);
        } else {
            log.warn("Volti: {}", messaggio);
        }
    }

    // ------------------------------------------------------------ stato

    public StatoVolti stato() {
        if (!attiva()) {
            return new StatoVolti(false, 0, 0, 0, 0, null, 0, null);
        }
        long foto = mongo.count(Query.query(Criteria.where("video").is(false).and(FotoService.CESTINO).is(null)), Foto.class);
        long mancanti = mongo.count(daCercare(), Foto.class);
        long volti = mongo.count(Query.query(Criteria.where("nelCestino").is(false)), Volto.class);
        long nominate = mongo.count(Query.query(Criteria.where("nome").ne(null)), Persona.class);
        Long nellIndice;
        try {
            nellIndice = visione.totale();
        } catch (RuntimeException e) {
            nellIndice = null;
        }
        return new StatoVolti(true, foto, foto - mancanti, mancanti, volti, nellIndice, nominate, corrente().orElse(null));
    }

    /**
     * Per i test: aspetta che le foto nuove e gli eventi in coda siano passati.
     * Il thread è uno solo e prende la coda in ordine: quando tocca a questo
     * segnaposto, i compiti messi prima sono finiti. (Guardare "coda vuota e
     * nessuno attivo" non basta: tra il prelievo di un compito e il suo inizio
     * il worker non risulta attivo, e la coda sembrava già vuota.)
     */
    void aspettaCoda(Duration massimo) throws InterruptedException, ExecutionException, TimeoutException {
        singole.submit(() -> { }).get(massimo.toMillis(), TimeUnit.MILLISECONDS);
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
        private final boolean ricostruisci;
        private volatile boolean annullato;
        private Stato stato = Stato.IN_CORSO;
        private Instant finitoIl;
        private int fatte;
        private int volti;
        private int errori;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(Instant iniziatoIl, long daFare, boolean ricostruisci) {
            this.iniziatoIl = iniziatoIl;
            this.daFare = daFare;
            this.ricostruisci = ricostruisci;
        }

        synchronized boolean inCorso() {
            return stato == Stato.IN_CORSO;
        }

        synchronized void fatte(int foto, int trovati) {
            fatte += foto;
            volti += trovati;
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
            return new StatoLavoro(id, stato, iniziatoIl, finitoIl, daFare, fatte, volti, errori, List.copyOf(messaggi),
                    errore, ricostruisci);
        }
    }
}
