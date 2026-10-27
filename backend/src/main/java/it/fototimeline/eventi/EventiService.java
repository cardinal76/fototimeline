package it.fototimeline.eventi;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.eventi.RilevamentoEventi.FotoPerEvento;
import it.fototimeline.eventi.RilevamentoEventi.Gruppo;
import it.fototimeline.eventi.RilevamentoEventi.Regole;
import it.fototimeline.service.EventiFoto.FotoAggiunta;
import it.fototimeline.service.EventiFoto.FotoDataCambiata;
import it.fototimeline.service.EventiFoto.FotoNelCestino;
import it.fototimeline.service.EventiFoto.FotoRipristinata;
import it.fototimeline.service.FotoService;

/**
 * Gli eventi: il calcolo ("Calcola eventi", tutto l'archivio, in sottofondo
 * con {@code LavoriImportazione}), il ricalcolo leggero dei giorni toccati
 * (foto nuove, nel cestino, ripristinate) e le modifiche a mano.
 *
 * <p><b>Calcolo</b>: le foto con una data, fuori dal cestino e senza
 * {@code eventoFisso}, una alla volta in ordine di scatto da un cursore di
 * Mongo ({@link RilevamentoEventi}); ogni gruppo chiuso si scrive subito: un
 * evento "a-" + la prima foto se ha almeno {@code minimoFoto} foto, e le sue
 * foto (per intervallo di date) lo prendono; altrimenti le foto escono
 * dall'evento che avevano. Gli automatici vecchi che cominciavano in quel
 * tratto si tolgono. Il punto a cui si è arrivati (la fine dell'ultimo gruppo
 * scritto) si salva in {@code impostazioni/eventi-calcolo}: annullato o
 * fermato da un riavvio, il calcolo dopo riparte da lì.
 *
 * <p><b>A mano</b>: un evento toccato diventa {@code manuale} e le sue foto
 * {@code eventoFisso}; una foto tolta resta {@code eventoFisso} senza
 * evento. Il calcolo salta le foto fisse e non toglie mai un evento
 * manuale: le modifiche a mano non si perdono. Una foto nuova che cade dentro
 * un evento manuale (caricata dopo l'ultima modifica) entra in lui.
 *
 * <p><b>Per date</b>: un evento creato a mano con nome, primo e ultimo giorno
 * ({@link #creaPerDate}) prende tutte le foto di quei giorni, anche da altri
 * eventi (automatici o manuali): vince lui. È manuale, quindi il calcolo non
 * lo tocca; le foto nuove dei suoi giorni (e quelle a cui si cambia la data
 * a mano) entrano in lui, anche se caricate prima dell'ultima modifica.
 * Due eventi per date non si sovrappongono; unire e dividere non valgono per
 * loro (si cambiano le date).
 */
@Service
public class EventiService {

    private static final Logger log = LoggerFactory.getLogger(EventiService.class);

    /** I giorni da rifare col ricalcolo leggero: un documento per giorno ("2024-05-17"). */
    static final String DA_RIFARE = "eventi_da_rifare";
    static final String ID_STATO = "eventi-calcolo";
    /** Ogni quanto, al più, il calcolo salva il punto a cui è arrivato. */
    private static final Duration SALVATAGGIO_PUNTO = Duration.ofSeconds(2);
    /** Quanto a lungo vale il posto di casa calcolato. */
    private static final Duration DURATA_CASA = Duration.ofMinutes(10);
    private static final Duration UN_MILLISECONDO = Duration.ofMillis(1);
    private static final Sort PER_DATA = Sort.by(Sort.Order.asc("scattataIl"), Sort.Order.asc("_id"));

    /** Come va il calcolo: lo segue la barra dei lavori in sottofondo. */
    public interface Avanzamento {
        Avanzamento NESSUNO = new Avanzamento() { };

        default void inizio(int totale) {
        }

        /** Altre {@code quante} foto raggruppate, di cui {@code inEventi} in un evento. */
        default void raggruppate(int quante, int inEventi) {
        }

        default boolean annullata() {
            return false;
        }
    }

    /**
     * Dove è arrivato il calcolo completo.
     *
     * @param fattoFinoA la fine dell'ultimo gruppo scritto, se il calcolo si è fermato a metà
     * @param interrotto true se non è arrivato in fondo: "Calcola eventi" riprende da {@code fattoFinoA}
     */
    @org.springframework.data.mongodb.core.mapping.Document("impostazioni")
    public record StatoCalcolo(@Id String id, LocalDateTime fattoFinoA, boolean interrotto, Instant iniziatoIl,
            Instant finitoIl) {
    }

    /** Com'è finito un calcolo: foto viste, di cui in un evento, eventi scritti. */
    public record Calcolo(int foto, int inEventi, int eventi, boolean ripreso, boolean completo) {
    }

    public record VoceEvento(String id, String nome, LocalDateTime inizio, LocalDateTime fine, LocalDate primoGiorno,
            LocalDate ultimoGiorno, int conteggio, String copertina, String luogo, boolean manuale,
            boolean nascosto, LocalDate dal, LocalDate al) {

        static VoceEvento di(Evento e) {
            return new VoceEvento(e.getId(), e.getNome(), e.getInizio(), e.getFine(), e.getPrimoGiorno(),
                    e.getUltimoGiorno(), e.getConteggio(), e.getCopertina(), e.getLuogo(), e.isManuale(),
                    e.isNascosto(), e.getDal(), e.getAl());
        }
    }

    /**
     * @param calcolo       null se "Calcola eventi" non è mai partito
     * @param giorniDaRifare giorni toccati che aspettano il ricalcolo leggero
     */
    public record ElencoEventi(List<VoceEvento> eventi, StatoCalcolo calcolo, long giorniDaRifare, int minimoFoto) {
    }

    private final MongoTemplate mongo;
    private final EventiProperties proprieta;
    private final Clock clock;
    /** Ricalcolo leggero e modifiche a mano, uno alla volta. */
    private final Object lock = new Object();
    private volatile boolean calcoloInCorso;
    private volatile LuogoFoto casa;
    private volatile Instant casaCalcolataIl;

    public EventiService(MongoTemplate mongo, EventiProperties proprieta, Optional<Clock> clock) {
        this.mongo = mongo;
        this.proprieta = proprieta;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ---------------------------------------------------------------- lettura

    /** Gli eventi dal più recente, senza quelli vuoti (salvo i per date); i nascosti solo se chiesti. */
    public ElencoEventi elenco(boolean nascosti) {
        Criteria c = new Criteria().orOperator(where("conteggio").gt(0), where("dal").ne(null));
        if (!nascosti) {
            c = c.and("nascosto").ne(true);
        }
        List<VoceEvento> eventi = mongo.find(Query.query(c).with(Sort.by(Sort.Direction.DESC, "inizio")), Evento.class)
                .stream().map(VoceEvento::di).toList();
        return new ElencoEventi(eventi, stato(), mongo.count(new Query(), DA_RIFARE), proprieta.minimoFoto());
    }

    public Optional<VoceEvento> evento(String id) {
        return Optional.ofNullable(mongo.findById(id, Evento.class)).map(VoceEvento::di);
    }

    /** I nomi degli eventi non nascosti tra questi (per "Accadde oggi"). */
    public Map<String, String> nomi(Collection<String> ids) {
        Map<String, String> nomi = new LinkedHashMap<>();
        if (ids.isEmpty()) {
            return nomi;
        }
        Query q = Query.query(where("_id").in(ids).and("nascosto").ne(true).and("conteggio").gt(0));
        q.fields().include("nome");
        mongo.find(q, Evento.class).forEach(e -> nomi.put(e.getId(), e.getNome()));
        return nomi;
    }

    public StatoCalcolo stato() {
        return mongo.findById(ID_STATO, StatoCalcolo.class);
    }

    public boolean calcoloInCorso() {
        return calcoloInCorso;
    }

    /**
     * Il posto di casa: quello di {@code fototimeline.eventi.casa} o, se non
     * c'è, il luogo con più foto. Lì i giorni di fila non fanno un viaggio.
     */
    LuogoFoto casa() {
        Instant adesso = clock.instant();
        if (casaCalcolataIl != null && casaCalcolataIl.plus(DURATA_CASA).isAfter(adesso)) {
            return casa;
        }
        Criteria c = proprieta.casa() != null ? where("luogo").is(proprieta.casa()) : where("luogo").ne(null);
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(c.and(FotoService.CESTINO).is(null)),
                Aggregation.group(LuogoFoto.CAMPI.toArray(String[]::new)).count().as("conteggio"),
                Aggregation.sort(Sort.Direction.DESC, "conteggio"),
                Aggregation.limit(1));
        Document d = mongo.aggregate(aggregazione, Foto.class, Document.class).getUniqueMappedResult();
        LuogoFoto trovata = null;
        if (d != null && d.get("_id") instanceof Document id) {
            trovata = new LuogoFoto(id.getString("luogo"), id.getString("provincia"), id.getString("regione"), id.getString("nazione"),
                    id.getString("codiceNazione"));
        } else if (proprieta.casa() != null) {
            trovata = new LuogoFoto(proprieta.casa(), null, null, null, null);
        }
        casa = trovata;
        casaCalcolataIl = adesso;
        return trovata;
    }

    /** La casa da rifare subito (test, dopo un'importazione grossa). */
    void dimenticaCasa() {
        casaCalcolataIl = null;
    }

    // ---------------------------------------------------------------- calcolo

    /**
     * "Calcola eventi": tutto l'archivio, o da dove si era fermato quello di
     * prima ({@code daCapo} false e {@link StatoCalcolo#interrotto}). Un
     * calcolo alla volta (IllegalStateException se ce n'è già uno).
     */
    public Calcolo calcola(boolean daCapo, Avanzamento avanzamento) {
        synchronized (lock) {
            if (calcoloInCorso) {
                throw new IllegalStateException("Il calcolo degli eventi è già in corso");
            }
            calcoloInCorso = true;
        }
        try {
            dimenticaCasa();
            StatoCalcolo prima = stato();
            boolean ripreso = !daCapo && prima != null && prima.interrotto() && prima.fattoFinoA() != null;
            LocalDateTime da = ripreso ? prima.fattoFinoA().plus(UN_MILLISECONDO) : null;
            Instant iniziatoIl = ripreso && prima.iniziatoIl() != null ? prima.iniziatoIl() : clock.instant();
            mongo.save(new StatoCalcolo(ID_STATO, ripreso ? prima.fattoFinoA() : null, true, iniziatoIl, null));
            Punto punto = new Punto(iniziatoIl, ripreso ? prima.fattoFinoA() : null);
            Esito esito;
            try {
                esito = ricalcola(da, null, avanzamento, punto::arrivato);
            } finally {
                punto.salva(true);
            }
            if (esito.completo()) {
                mongo.save(new StatoCalcolo(ID_STATO, null, false, iniziatoIl, clock.instant()));
            }
            log.info("Calcolo degli eventi {}{}: {} foto, {} in {} eventi", esito.completo() ? "finito" : "fermato",
                    ripreso ? " (ripreso)" : "", esito.foto(), esito.inEventi(), esito.eventi());
            return new Calcolo(esito.foto(), esito.inEventi(), esito.eventi(), ripreso, esito.completo());
        } finally {
            calcoloInCorso = false;
        }
    }

    /** Il punto a cui è arrivato il calcolo completo, salvato al più ogni {@link #SALVATAGGIO_PUNTO}. */
    private final class Punto {
        private final Instant iniziatoIl;
        private LocalDateTime fattoFinoA;
        private boolean daSalvare;
        private Instant salvatoIl = Instant.MIN;

        Punto(Instant iniziatoIl, LocalDateTime fattoFinoA) {
            this.iniziatoIl = iniziatoIl;
            this.fattoFinoA = fattoFinoA;
        }

        void arrivato(LocalDateTime fine) {
            fattoFinoA = fine;
            daSalvare = true;
            salva(false);
        }

        void salva(boolean comunque) {
            Instant adesso = clock.instant();
            if (daSalvare && (comunque || Duration.between(salvatoIl, adesso).compareTo(SALVATAGGIO_PUNTO) >= 0)) {
                mongo.save(new StatoCalcolo(ID_STATO, fattoFinoA, true, iniziatoIl, null));
                salvatoIl = adesso;
                daSalvare = false;
            }
        }
    }

    private record Esito(int foto, int inEventi, int eventi, boolean completo) {
    }

    /** Le foto che il calcolo raggruppa: con una data, fuori dal cestino, non fissate a mano. */
    private static Criteria daRaggruppare(LocalDateTime da, LocalDateTime finoA) {
        Criteria data = where("scattataIl").ne(null);
        if (da != null) {
            data = data.gte(da);
        }
        if (finoA != null) {
            data = data.lte(finoA);
        }
        return data.and(FotoService.CESTINO).is(null).and("eventoFisso").ne(true);
    }

    private static Query perGruppo(Criteria c) {
        Query q = Query.query(c).with(PER_DATA);
        q.fields().include("scattataIl", "preferita", "video", "caricataIl").include(LuogoFoto.CAMPI.toArray(String[]::new));
        return q;
    }

    private static FotoPerEvento perEvento(Foto f) {
        return new FotoPerEvento(f.getId(), f.getScattataIl(), LuogoFoto.di(f), f.isPreferita(), f.isVideo());
    }

    /**
     * Rifà gli eventi automatici delle foto scattate tra {@code da} e
     * {@code finoA} (compresi; null = senza limite). Gli automatici che
     * cominciano lì dentro e non ci sono più si tolgono.
     */
    private Esito ricalcola(LocalDateTime da, LocalDateTime finoA, Avanzamento avanzamento,
            Consumer<LocalDateTime> arrivato) {
        Regole regole = proprieta.regole(casa());
        Criteria criteri = daRaggruppare(da, finoA);
        avanzamento.inizio((int) mongo.count(Query.query(criteri), Foto.class));

        Query manuali = Query.query(where("manuale").is(true));
        manuali.with(Sort.by("inizio"));
        manuali.fields().include("inizio", "fine", "modificatoIl", "dal", "al");
        List<Evento> fissi = mongo.find(manuali, Evento.class).stream()
                .filter(e -> e.getInizio() != null && e.getFine() != null)
                .filter(e -> (da == null || !e.getFine().isBefore(da)) && (finoA == null || !e.getInizio().isAfter(finoA)))
                .toList();
        Set<String> manualiToccati = new LinkedHashSet<>();

        Scrittura scrittura = new Scrittura(regole, da != null ? da.minus(UN_MILLISECONDO) : null, avanzamento, arrivato);
        RilevamentoEventi rilevamento = new RilevamentoEventi(regole, scrittura);
        boolean annullato = false;
        int primoFisso = 0;
        try (Stream<Foto> foto = mongo.stream(perGruppo(criteri), Foto.class)) {
            for (var it = foto.iterator(); it.hasNext();) {
                if (avanzamento.annullata()) {
                    annullato = true;
                    break;
                }
                Foto f = it.next();
                while (primoFisso < fissi.size() && fissi.get(primoFisso).getFine().isBefore(f.getScattataIl())) {
                    primoFisso++;
                }
                Evento dentro = manualeChePrende(fissi, primoFisso, f);
                if (dentro != null) {
                    mongo.updateFirst(Query.query(where("_id").is(f.getId())),
                            new Update().set("evento", dentro.getId()).set("eventoFisso", true), Foto.class);
                    manualiToccati.add(dentro.getId());
                    avanzamento.raggruppate(1, 1);
                    continue;
                }
                rilevamento.aggiungi(perEvento(f));
            }
        }
        if (!annullato) {
            rilevamento.finisci();
            scrittura.togliVecchiFinoA(finoA, null);
        }
        manualiToccati.forEach(this::aggiornaRiepilogo);
        return new Esito(scrittura.foto, scrittura.inEventi, scrittura.eventi, !annullato);
    }

    /**
     * L'evento manuale in cui cade una foto non fissa: prima un evento per
     * date che ha il suo giorno (sempre); altrimenti un manuale che la
     * comprende nelle sue date, se la foto è stata caricata dopo la sua ultima
     * modifica (le foto che c'erano già, fuori, le ha lasciate fuori qualcuno).
     */
    private static Evento manualeChePrende(List<Evento> fissi, int da, Foto f) {
        LocalDate giorno = f.getScattataIl().toLocalDate();
        Evento altro = null;
        for (int i = da; i < fissi.size() && !fissi.get(i).getInizio().isAfter(f.getScattataIl()); i++) {
            Evento e = fissi.get(i);
            if (e.comprende(giorno)) {
                return e;
            }
            if (altro == null && !e.perDate() && f.getCaricataIl() != null && !e.getFine().isBefore(f.getScattataIl())
                    && e.getModificatoIl() != null && f.getCaricataIl().isAfter(e.getModificatoIl())) {
                altro = e;
            }
        }
        return altro;
    }

    /** Scrive i gruppi chiusi, uno alla volta, appena il rilevamento li consegna. */
    private final class Scrittura implements RilevamentoEventi.Uscita {
        private final Regole regole;
        private final Avanzamento avanzamento;
        private final Consumer<LocalDateTime> arrivato;
        /** La fine dell'ultimo gruppo scritto: gli automatici che cominciano dopo sono da rifare. */
        private LocalDateTime ultimaFine;
        int foto;
        int inEventi;
        int eventi;

        Scrittura(Regole regole, LocalDateTime prima, Avanzamento avanzamento, Consumer<LocalDateTime> arrivato) {
            this.regole = regole;
            this.ultimaFine = prima;
            this.avanzamento = avanzamento;
            this.arrivato = arrivato;
        }

        @Override
        public void chiuso(Gruppo g) {
            Criteria delGruppo = where("scattataIl").gte(g.inizio()).lte(g.fine())
                    .and(FotoService.CESTINO).is(null).and("eventoFisso").ne(true);
            String id = null;
            if (g.conteggio() >= regole.minimoFoto()) {
                id = idAutomatico(g);
                Evento e = new Evento();
                e.setId(id);
                riempi(e, g, regole.casa());
                e.setCreatoIl(clock.instant());
                e.setModificatoIl(clock.instant());
                mongo.save(e);
                mongo.updateMulti(Query.query(delGruppo.and("evento").ne(id)), new Update().set("evento", id), Foto.class);
                inEventi += g.conteggio();
                eventi++;
            } else {
                mongo.updateMulti(Query.query(delGruppo.and("evento").ne(null)), new Update().unset("evento"), Foto.class);
            }
            togliVecchiFinoA(g.fine(), id);
            foto += g.conteggio();
            avanzamento.raggruppate(g.conteggio(), id != null ? g.conteggio() : 0);
            if (arrivato != null) {
                arrivato.accept(g.fine());
            }
        }

        /** Via gli automatici che cominciano dopo l'ultimo gruppo scritto e fino a {@code fine} (null = in fondo). */
        void togliVecchiFinoA(LocalDateTime fine, String tranne) {
            Criteria inizio = where("inizio");
            if (ultimaFine != null) {
                inizio = inizio.gt(ultimaFine);
            }
            if (fine != null) {
                inizio = inizio.lte(fine);
            }
            if (ultimaFine == null && fine == null) {
                inizio = where("inizio").exists(true);
            }
            Criteria c = new Criteria().andOperator(where("manuale").ne(true), inizio);
            if (tranne != null) {
                c = new Criteria().andOperator(c, where("_id").ne(tranne));
            }
            mongo.remove(Query.query(c), Evento.class);
            if (fine != null) {
                ultimaFine = fine;
            }
        }
    }

    /** "a-" + la prima foto; se un manuale ha già quell'id (era automatico), anche l'inizio. */
    private String idAutomatico(Gruppo g) {
        String id = "a-" + g.primaFoto();
        if (mongo.exists(Query.query(where("_id").is(id).and("manuale").is(true)), Evento.class)) {
            id = id + "-" + g.inizio().toString().replaceAll("\\D", "");
        }
        return id;
    }

    /** Date, conteggio, copertina, luogo e, se non l'ha scritto qualcuno, il nome. */
    private static void riempi(Evento e, Gruppo g, LuogoFoto casa) {
        e.setInizio(g.inizio());
        e.setFine(g.fine());
        e.setPrimoGiorno(g.primoGiorno());
        e.setUltimoGiorno(g.ultimoGiorno());
        if (e.perDate()) {
            giorniDelPeriodo(e);
        }
        e.setConteggio(g.conteggio());
        e.setCopertina(g.copertina());
        e.setLuogo(NomiEventi.nomeLuogo(g.luoghi(), casa));
        if (!e.isNomeAMano()) {
            e.setNome(NomiEventi.nome(g, casa));
        }
    }

    /**
     * Un evento per date copre sempre i suoi giorni (anche con foto fuori,
     * aggiunte a mano): così il calcolo lo trova per le foto nuove di quei giorni.
     */
    private static void giorniDelPeriodo(Evento e) {
        LocalDateTime inizio = e.getDal().atStartOfDay();
        LocalDateTime fine = e.getAl().plusDays(1).atStartOfDay().minus(UN_MILLISECONDO);
        if (e.getInizio() == null || inizio.isBefore(e.getInizio())) {
            e.setInizio(inizio);
        }
        if (e.getFine() == null || fine.isAfter(e.getFine())) {
            e.setFine(fine);
        }
        if (e.getPrimoGiorno() == null || e.getDal().isBefore(e.getPrimoGiorno())) {
            e.setPrimoGiorno(e.getDal());
        }
        if (e.getUltimoGiorno() == null || e.getAl().isAfter(e.getUltimoGiorno())) {
            e.setUltimoGiorno(e.getAl());
        }
    }

    // ---------------------------------------------------------------- ricalcolo leggero

    /**
     * Ogni {@code fototimeline.eventi.ricalcolo}: i giorni toccati (foto
     * nuove, nel cestino, ripristinate) si rifanno, coi giorni vicini e gli
     * eventi automatici che li toccano. Mai mentre gira "Calcola eventi".
     */
    @Scheduled(fixedDelayString = "${fototimeline.eventi.ricalcolo:PT10M}",
            initialDelayString = "${fototimeline.eventi.ritardo-iniziale:PT3M}")
    public void ricalcoloPeriodico() {
        try {
            int giorni = ricalcolaGiorniToccati();
            if (giorni > 0) {
                log.info("Eventi rifatti per {} giorni toccati", giorni);
            }
        } catch (RuntimeException e) {
            log.warn("Ricalcolo leggero degli eventi non riuscito: {}", e.toString());
        }
    }

    /** Il ricalcolo leggero adesso; restituisce quanti giorni ha rifatto. */
    public int ricalcolaGiorniToccati() {
        synchronized (lock) {
            if (calcoloInCorso) {
                return 0;
            }
            List<String> segnati = mongo.findAll(Document.class, DA_RIFARE).stream()
                    .map(d -> d.getString("_id"))
                    .filter(s -> s != null)
                    .sorted()
                    .toList();
            if (segnati.isEmpty()) {
                return 0;
            }
            List<LocalDate> giorni = new ArrayList<>();
            for (String s : segnati) {
                try {
                    giorni.add(LocalDate.parse(s));
                } catch (RuntimeException e) {
                    log.debug("Giorno da rifare non valido: {}", s);
                }
            }
            long distanza = 2L * (proprieta.giorniVuoti() + 1);
            LocalDate primo = null;
            LocalDate ultimo = null;
            for (LocalDate g : giorni) {
                if (primo != null && g.isAfter(ultimo.plusDays(distanza))) {
                    rifai(primo, ultimo);
                    primo = null;
                }
                if (primo == null) {
                    primo = g;
                }
                ultimo = g;
            }
            if (primo != null) {
                rifai(primo, ultimo);
            }
            mongo.remove(Query.query(where("_id").in(segnati)), DA_RIFARE);
            return segnati.size();
        }
    }

    /**
     * Rifà i giorni da {@code primo} a {@code ultimo}, allargati di quanto può
     * unire un viaggio e fino a comprendere gli automatici che toccano: un
     * evento non si taglia a metà.
     */
    private void rifai(LocalDate primo, LocalDate ultimo) {
        Duration margine = Duration.ofDays(proprieta.giorniVuoti() + 1L).plus(proprieta.pausa());
        LocalDateTime da = primo.atStartOfDay().minus(margine);
        LocalDateTime finoA = ultimo.plusDays(1).atStartOfDay().plus(margine);
        for (int giro = 0; giro < 20; giro++) {
            Query q = Query.query(where("manuale").ne(true).and("inizio").lte(finoA).and("fine").gte(da));
            q.fields().include("inizio", "fine");
            LocalDateTime nuovoDa = da;
            LocalDateTime nuovoFinoA = finoA;
            for (Evento e : mongo.find(q, Evento.class)) {
                if (e.getInizio().isBefore(nuovoDa)) {
                    nuovoDa = e.getInizio();
                }
                if (e.getFine().isAfter(nuovoFinoA)) {
                    nuovoFinoA = e.getFine();
                }
            }
            if (nuovoDa.equals(da) && nuovoFinoA.equals(finoA)) {
                break;
            }
            da = nuovoDa;
            finoA = nuovoFinoA;
        }
        ricalcola(da, finoA, Avanzamento.NESSUNO, null);
    }

    /** Un giorno da rifare al prossimo ricalcolo leggero. */
    void segnaGiorno(String giorno) {
        mongo.upsert(Query.query(where("_id").is(giorno)), new Update().setOnInsert("segnatoIl", clock.instant()),
                DA_RIFARE);
    }

    @EventListener
    public void aggiunta(FotoAggiunta evento) {
        toccata(evento.id());
    }

    @EventListener
    public void nelCestino(FotoNelCestino evento) {
        toccata(evento.id());
    }

    @EventListener
    public void ripristinata(FotoRipristinata evento) {
        toccata(evento.id());
    }

    /**
     * Una foto entrata, finita nel cestino o tornata: il suo giorno si rifà
     * al prossimo ricalcolo leggero, e il suo evento (se ne ha uno) aggiorna
     * subito conteggio e copertina. Non lancia: chi pubblica non deve fallire.
     */
    private void toccata(String id) {
        try {
            Query q = Query.query(where("_id").is(id));
            q.fields().include("giorno", "evento");
            Foto f = mongo.findOne(q, Foto.class);
            if (f == null) {
                return;
            }
            if (f.getGiorno() != null) {
                segnaGiorno(f.getGiorno());
            }
            if (f.getEvento() != null) {
                aggiornaRiepilogo(f.getEvento());
            }
        } catch (RuntimeException e) {
            log.warn("Eventi: non riesco a segnare la foto {}: {}", id, e.toString());
        }
    }

    // ---------------------------------------------------------------- a mano

    /** Date, conteggio, copertina e nome (se non è a mano) dalle foto che ha; senza più foto, via. */
    void aggiornaRiepilogo(String id) {
        Evento e = mongo.findById(id, Evento.class);
        if (e == null) {
            return;
        }
        Gruppo g = null;
        try (Stream<Foto> foto = mongo.stream(perGruppo(where("evento").is(id).and(FotoService.CESTINO).is(null)
                .and("scattataIl").ne(null)), Foto.class)) {
            for (var it = foto.iterator(); it.hasNext();) {
                FotoPerEvento f = perEvento(it.next());
                if (g == null) {
                    g = new Gruppo(f);
                } else {
                    g.aggiungi(f);
                }
            }
        }
        if (g == null && e.perDate()) {
            // Per date: resta anche senza foto, coi suoi giorni.
            e.setInizio(null);
            e.setFine(null);
            e.setPrimoGiorno(null);
            e.setUltimoGiorno(null);
            e.setConteggio(0);
            e.setCopertina(null);
            e.setLuogo(null);
            giorniDelPeriodo(e);
            mongo.save(e);
            return;
        }
        if (g == null) {
            if (!mongo.exists(Query.query(where("evento").is(id)), Foto.class)) {
                mongo.remove(e);
            } else {
                // Tutte nel cestino: torna se le ripristinano.
                e.setConteggio(0);
                mongo.save(e);
            }
            return;
        }
        riempi(e, g, casa());
        mongo.save(e);
    }

    private Evento trovaEvento(String id) {
        Evento e = id == null ? null : mongo.findById(id, Evento.class);
        if (e == null) {
            throw new IllegalArgumentException("Evento sconosciuto");
        }
        return e;
    }

    /** L'evento resta com'è: le sue foto diventano fisse, il ricalcolo non lo tocca più. */
    private void rendiManuale(Evento e) {
        e.setManuale(true);
        e.setModificatoIl(clock.instant());
        mongo.save(e);
        mongo.updateMulti(Query.query(where("evento").is(e.getId())), new Update().set("eventoFisso", true), Foto.class);
    }

    /** Le foto (fuori dal cestino, con una data) tra questi id. */
    private static Criteria fotoValide(Collection<String> ids) {
        return where("_id").in(ids).and(FotoService.CESTINO).is(null).and("scattataIl").ne(null);
    }

    /** Gli eventi che hanno adesso queste foto. */
    private Set<String> eventiDi(Criteria foto) {
        Set<String> eventi = new LinkedHashSet<>();
        Query q = Query.query(new Criteria().andOperator(foto, where("evento").ne(null)));
        q.fields().include("evento");
        mongo.find(q, Foto.class).forEach(f -> eventi.add(f.getEvento()));
        return eventi;
    }

    /** Un nome nuovo (vuoto: torna quello automatico). */
    public VoceEvento rinomina(String id, String nome) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            String pulito = nome == null || nome.isBlank() ? null : nome.trim();
            if (pulito != null && pulito.length() > 200) {
                throw new IllegalArgumentException("Nome troppo lungo (al massimo 200 caratteri)");
            }
            if (pulito == null && e.perDate()) {
                throw new IllegalArgumentException("Un evento per date ha bisogno di un nome");
            }
            e.setNomeAMano(pulito != null);
            if (pulito != null) {
                e.setNome(pulito);
            }
            rendiManuale(e);
            aggiornaRiepilogo(id);
            return evento(id).orElseThrow();
        }
    }

    /** Nascosto: non compare nell'elenco, nel visore né in "Accadde oggi"; le foto restano. */
    public VoceEvento nascondi(String id, boolean nascosto) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            e.setNascosto(nascosto);
            rendiManuale(e);
            return VoceEvento.di(e);
        }
    }

    /** Tutto nel primo (il più vecchio), che tiene il suo nome se era a mano. */
    public VoceEvento unisci(List<String> ids) {
        synchronized (lock) {
            List<Evento> eventi = new ArrayList<>();
            for (String id : new LinkedHashSet<>(ids == null ? List.<String>of() : ids)) {
                eventi.add(trovaEvento(id));
            }
            if (eventi.size() < 2) {
                throw new IllegalArgumentException("Per unire servono almeno due eventi");
            }
            if (eventi.stream().anyMatch(Evento::perDate)) {
                throw new IllegalArgumentException("Un evento per date non si unisce: cambia le sue date");
            }
            eventi.sort(Comparator.comparing(Evento::getInizio, Comparator.nullsLast(Comparator.naturalOrder())));
            Evento primo = eventi.getFirst();
            for (Evento altro : eventi.subList(1, eventi.size())) {
                mongo.updateMulti(Query.query(where("evento").is(altro.getId())),
                        new Update().set("evento", primo.getId()).set("eventoFisso", true), Foto.class);
                if (!primo.isNomeAMano() && altro.isNomeAMano()) {
                    primo.setNome(altro.getNome());
                    primo.setNomeAMano(true);
                }
                mongo.remove(altro);
            }
            rendiManuale(primo);
            aggiornaRiepilogo(primo.getId());
            return evento(primo.getId()).orElseThrow();
        }
    }

    /**
     * Divide l'evento prima di {@code foto}: lei e quelle dopo vanno in un
     * evento nuovo, col nome automatico. Restituisce i due eventi.
     */
    public List<VoceEvento> dividi(String id, String foto) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            if (e.perDate()) {
                throw new IllegalArgumentException("Un evento per date non si divide: cambia le sue date");
            }
            Foto punto = foto == null ? null : mongo.findById(foto, Foto.class);
            if (punto == null || !id.equals(punto.getEvento()) || punto.getScattataIl() == null) {
                throw new IllegalArgumentException("La foto non è di questo evento");
            }
            Criteria dopo = new Criteria().andOperator(where("evento").is(id), new Criteria().orOperator(
                    where("scattataIl").gt(punto.getScattataIl()),
                    where("scattataIl").is(punto.getScattataIl()).and("_id").gte(punto.getId())));
            Criteria prima = new Criteria().andOperator(where("evento").is(id), new Criteria().norOperator(dopo));
            if (!mongo.exists(Query.query(new Criteria().andOperator(prima, where(FotoService.CESTINO).is(null))),
                    Foto.class)) {
                throw new IllegalArgumentException("È la prima foto dell'evento: non c'è niente da dividere");
            }
            Evento nuovo = nuovoManuale(null);
            mongo.updateMulti(Query.query(dopo), new Update().set("evento", nuovo.getId()).set("eventoFisso", true),
                    Foto.class);
            rendiManuale(e);
            aggiornaRiepilogo(id);
            aggiornaRiepilogo(nuovo.getId());
            return List.of(evento(id).orElseThrow(), evento(nuovo.getId()).orElseThrow());
        }
    }

    /** Le foto entrano nell'evento (anche togliendole da un altro); restituisce quante. */
    public long aggiungi(String id, List<String> fotoIds) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            Criteria foto = fotoValide(fotoIds == null ? List.of() : fotoIds);
            Set<String> prima = eventiDi(foto);
            prima.remove(id);
            long n = mongo.updateMulti(Query.query(foto), new Update().set("evento", id).set("eventoFisso", true),
                    Foto.class).getModifiedCount();
            rendiManuale(e);
            aggiornaRiepilogo(id);
            prima.forEach(this::aggiornaRiepilogo);
            return n;
        }
    }

    /** Le foto escono dal loro evento e ci restano fuori, anche al ricalcolo; restituisce quante. */
    public long togli(List<String> fotoIds) {
        synchronized (lock) {
            Criteria foto = new Criteria().andOperator(where("_id").in(fotoIds == null ? List.of() : fotoIds),
                    where("evento").ne(null));
            Set<String> prima = eventiDi(foto);
            long n = mongo.updateMulti(Query.query(foto), new Update().unset("evento").set("eventoFisso", true),
                    Foto.class).getModifiedCount();
            for (String id : prima) {
                Evento e = mongo.findById(id, Evento.class);
                if (e != null) {
                    rendiManuale(e);
                    aggiornaRiepilogo(id);
                }
            }
            return n;
        }
    }

    /** Un evento nuovo dalle foto scelte; senza nome, quello automatico. */
    public VoceEvento crea(String nome, List<String> fotoIds) {
        synchronized (lock) {
            Criteria foto = fotoValide(fotoIds == null ? List.of() : fotoIds);
            if (!mongo.exists(Query.query(foto), Foto.class)) {
                throw new IllegalArgumentException("Nessuna foto con una data tra quelle scelte");
            }
            String pulito = nome == null || nome.isBlank() ? null : nome.trim();
            Set<String> prima = eventiDi(foto);
            Evento nuovo = nuovoManuale(pulito);
            mongo.updateMulti(Query.query(foto), new Update().set("evento", nuovo.getId()).set("eventoFisso", true),
                    Foto.class);
            aggiornaRiepilogo(nuovo.getId());
            prima.forEach(this::aggiornaRiepilogo);
            return evento(nuovo.getId()).orElseThrow();
        }
    }

    // ---------------------------------------------------------------- per date

    /**
     * Un evento per date: nome, primo e ultimo giorno (compresi). Prende
     * subito tutte le foto di quei giorni, anche da altri eventi.
     */
    public VoceEvento creaPerDate(String nome, LocalDate dal, LocalDate al) {
        synchronized (lock) {
            String pulito = nomeObbligatorio(nome);
            controllaDate(dal, al, null);
            Evento e = nuovoManuale(pulito);
            e.setDal(dal);
            e.setAl(al);
            giorniDelPeriodo(e);
            mongo.save(e);
            prendiGiorni(e);
            log.info("Evento per date \"{}\" dal {} al {}", pulito, dal, al);
            return evento(e.getId()).orElseThrow();
        }
    }

    /**
     * Date nuove per un evento per date: le foto fuori dai giorni nuovi
     * escono (e tornano al calcolo automatico), quelle dei giorni nuovi
     * entrano. Dopo, l'evento ha esattamente le foto dei suoi giorni.
     */
    public VoceEvento cambiaDate(String id, LocalDate dal, LocalDate al) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            if (!e.perDate()) {
                throw new IllegalArgumentException("Le date si cambiano solo a un evento creato per date");
            }
            controllaDate(dal, al, id);
            Criteria fuori = new Criteria().andOperator(where("evento").is(id),
                    new Criteria().norOperator(neiGiorni(dal, al)));
            Query giorniFuori = Query.query(fuori);
            giorniFuori.fields().include("giorno");
            Set<String> daRifare = new HashSet<>();
            mongo.find(giorniFuori, Foto.class).forEach(f -> {
                if (f.getGiorno() != null) {
                    daRifare.add(f.getGiorno());
                }
            });
            mongo.updateMulti(Query.query(fuori), new Update().unset("evento").set("eventoFisso", false), Foto.class);
            daRifare.forEach(this::segnaGiorno);
            e.setDal(dal);
            e.setAl(al);
            e.setModificatoIl(clock.instant());
            mongo.save(e);
            prendiGiorni(e);
            return evento(id).orElseThrow();
        }
    }

    /** Nome e date di un evento per date insieme (la pagina Eventi li cambia con un modulo solo). */
    public VoceEvento modificaPerDate(String id, String nome, LocalDate dal, LocalDate al) {
        synchronized (lock) {
            Evento e = trovaEvento(id);
            if (!e.perDate()) {
                throw new IllegalArgumentException("Solo un evento creato per date si cambia così");
            }
            String pulito = nomeObbligatorio(nome);
            controllaDate(dal, al, id);
            if (!dal.equals(e.getDal()) || !al.equals(e.getAl())) {
                cambiaDate(id, dal, al);
                e = trovaEvento(id);
            }
            e.setNome(pulito);
            e.setNomeAMano(true);
            e.setModificatoIl(clock.instant());
            mongo.save(e);
            return evento(id).orElseThrow();
        }
    }

    /** Elimina un evento per date: come "Torna automatico", le sue foto si raggruppano di nuovo da sole. */
    public boolean eliminaPerDate(String id) {
        synchronized (lock) {
            Evento e = mongo.findById(id, Evento.class);
            if (e == null) {
                return false;
            }
            if (!e.perDate()) {
                throw new IllegalArgumentException("Solo un evento creato per date si elimina così");
            }
            return sciogli(id);
        }
    }

    /** Le foto scattate tra {@code dal} e {@code al} (compresi), anche nel cestino: tornando, restano sue. */
    private static Criteria neiGiorni(LocalDate dal, LocalDate al) {
        return where("scattataIl").gte(dal.atStartOfDay()).lt(al.plusDays(1).atStartOfDay());
    }

    /** Tutte le foto dei giorni dell'evento passano a lui; gli eventi da cui vengono si aggiornano. */
    private void prendiGiorni(Evento e) {
        Criteria giorni = neiGiorni(e.getDal(), e.getAl());
        // distinct su un campo stringa (non un array come i tag): niente trappola dei vuoti.
        Set<String> prima = new LinkedHashSet<>(mongo.findDistinct(
                Query.query(new Criteria().andOperator(giorni, where("evento").ne(null))), "evento", Foto.class,
                String.class));
        prima.remove(e.getId());
        mongo.updateMulti(Query.query(giorni), new Update().set("evento", e.getId()).set("eventoFisso", true),
                Foto.class);
        aggiornaRiepilogo(e.getId());
        prima.forEach(this::aggiornaRiepilogo);
    }

    private static String nomeObbligatorio(String nome) {
        String pulito = nome == null ? "" : nome.trim();
        if (pulito.isEmpty()) {
            throw new IllegalArgumentException("Un evento per date ha bisogno di un nome");
        }
        if (pulito.length() > 200) {
            throw new IllegalArgumentException("Nome troppo lungo (al massimo 200 caratteri)");
        }
        return pulito;
    }

    /** Date presenti, in ordine, e nessun altro evento per date negli stessi giorni. */
    private void controllaDate(LocalDate dal, LocalDate al, String tranne) {
        if (dal == null || al == null) {
            throw new IllegalArgumentException("Servono la data di inizio e quella di fine");
        }
        if (dal.isAfter(al)) {
            throw new IllegalArgumentException("La data di inizio viene dopo quella di fine");
        }
        Criteria sovrapposto = where("dal").lte(al).and("al").gte(dal);
        if (tranne != null) {
            sovrapposto = sovrapposto.and("_id").ne(tranne);
        }
        Evento altro = mongo.findOne(Query.query(sovrapposto), Evento.class);
        if (altro != null) {
            throw new IllegalArgumentException("Quei giorni sono già dell'evento \"" + altro.getNome() + "\" (dal "
                    + altro.getDal() + " al " + altro.getAl() + ")");
        }
    }

    /**
     * La data di una foto cambiata a mano: se cade in un evento per date entra
     * in lui; se usciva da un evento per date ne esce (e torna al calcolo).
     * Il suo giorno nuovo si rifà al prossimo ricalcolo leggero. Non lancia.
     */
    @EventListener
    public void dataCambiata(FotoDataCambiata evento) {
        try {
            synchronized (lock) {
                Query q = Query.query(where("_id").is(evento.id()));
                q.fields().include("scattataIl", "giorno", "evento");
                Foto f = mongo.findOne(q, Foto.class);
                if (f == null || f.getScattataIl() == null) {
                    return;
                }
                LocalDate giorno = f.getScattataIl().toLocalDate();
                String vecchio = f.getEvento();
                Evento perDate = mongo.findOne(Query.query(where("dal").lte(giorno).and("al").gte(giorno)), Evento.class);
                if (perDate != null) {
                    if (!perDate.getId().equals(vecchio)) {
                        mongo.updateFirst(Query.query(where("_id").is(f.getId())),
                                new Update().set("evento", perDate.getId()).set("eventoFisso", true), Foto.class);
                    }
                    aggiornaRiepilogo(perDate.getId());
                } else if (vecchio != null) {
                    Evento v = mongo.findById(vecchio, Evento.class);
                    if (v != null && v.perDate()) {
                        mongo.updateFirst(Query.query(where("_id").is(f.getId())),
                                new Update().unset("evento").set("eventoFisso", false), Foto.class);
                    }
                }
                if (vecchio != null && !(perDate != null && perDate.getId().equals(vecchio))) {
                    aggiornaRiepilogo(vecchio);
                }
                segnaGiorno(giorno.toString());
            }
        } catch (RuntimeException e) {
            log.warn("Eventi: non riesco a seguire la data nuova della foto {}: {}", evento.id(), e.toString());
        }
    }

    private Evento nuovoManuale(String nome) {
        Evento e = new Evento();
        e.setId(UUID.randomUUID().toString());
        e.setNome(nome);
        e.setNomeAMano(nome != null);
        e.setManuale(true);
        e.setCreatoIl(clock.instant());
        e.setModificatoIl(clock.instant());
        mongo.save(e);
        return e;
    }

    /**
     * "Torna automatico": l'evento si scioglie, le sue foto non sono più
     * fisse e i suoi giorni si rifanno al prossimo ricalcolo leggero. Le foto
     * tolte a mano da lui restano fuori.
     */
    public boolean sciogli(String id) {
        synchronized (lock) {
            Evento e = mongo.findById(id, Evento.class);
            if (e == null) {
                return false;
            }
            Set<String> giorni = new HashSet<>();
            Query q = Query.query(where("evento").is(id).and("giorno").ne(null));
            q.fields().include("giorno");
            mongo.find(q, Foto.class).forEach(f -> giorni.add(f.getGiorno()));
            mongo.updateMulti(Query.query(where("evento").is(id)), new Update().unset("evento").set("eventoFisso", false),
                    Foto.class);
            mongo.remove(e);
            giorni.forEach(this::segnaGiorno);
            return true;
        }
    }
}
