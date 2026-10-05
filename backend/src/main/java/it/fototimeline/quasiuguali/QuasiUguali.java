package it.fototimeline.quasiuguali;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.quasiuguali.Raggruppamento.Voce;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;

/**
 * "Foto quasi uguali": raffiche, scatti ripetuti, la stessa foto ricompressa o
 * ridimensionata. I gruppi si calcolano in memoria dalle sole impronte (con
 * 150.000 foto sono pochi MB) e restano in cache finché non cambia il numero
 * di foto, per al più {@link #DURATA_CACHE}; togliere e ignorare li aggiornano
 * subito senza rifare il calcolo.
 */
@Service
public class QuasiUguali {

    private static final Logger log = LoggerFactory.getLogger(QuasiUguali.class);

    /** Gruppi segnati "non sono doppioni": le foto di uno stesso documento non si uniscono più. */
    static final String IGNORATI = "quasi_uguali_ignorati";
    private static final Duration DURATA_CACHE = Duration.ofMinutes(10);

    /**
     * Ordine per scegliere quale tenere: risoluzione più alta, poi file più
     * grande, poi chi ha l'EXIF, poi la data di scatto più vecchia.
     */
    static final Comparator<Foto> DA_TENERE = Comparator.comparingLong(QuasiUguali::area).reversed()
            .thenComparing(Comparator.comparingLong(Foto::getDimensione).reversed())
            .thenComparing(f -> !haExif(f))
            .thenComparing(Foto::getScattataIl, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Foto::getId);

    /**
     * Una foto del gruppo. {@code suggerita} è quella da tenere secondo
     * {@link #DA_TENERE}; {@code tieni} vale anche per le preferite, che non
     * si propongono mai da togliere.
     */
    public record FotoSimile(String id, String miniatura, String nomeOriginale, Integer larghezza, Integer altezza,
            long dimensione, LocalDateTime scattataIl, String fotocamera, boolean preferita, boolean suggerita,
            boolean tieni) {
    }

    public record Gruppo(List<FotoSimile> foto) {
    }

    public record PaginaGruppi(List<Gruppo> gruppi, long totale, int pagina, boolean altre) {
    }

    public record Esito(int eliminate) {
    }

    private record Cache(List<List<String>> gruppi, long foto, Instant fattaIl) {
    }

    private final MongoTemplate mongo;
    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final QuasiUgualiProperties properties;
    private final Clock clock;
    private Cache cache;

    public QuasiUguali(MongoTemplate mongo, FotoService fotoService, ArchivioFile archivio,
            QuasiUgualiProperties properties, Optional<Clock> clock) {
        this.mongo = mongo;
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ---------------------------------------------------------------- gruppi

    /** Una pagina di gruppi, dal più numeroso; a parità, dal più recente. */
    public PaginaGruppi gruppi(int pagina, int dimensione) {
        List<List<String>> tutti = gruppiInCache();
        int da = (int) Math.min((long) pagina * dimensione, tutti.size());
        int a = Math.min(da + dimensione, tutti.size());
        List<List<String>> scelti = tutti.subList(da, a);

        Set<String> ids = scelti.stream().flatMap(List::stream).collect(Collectors.toSet());
        Map<String, Foto> foto = mongo.find(Query.query(Criteria.where("_id").in(ids)), Foto.class).stream()
                .collect(Collectors.toMap(Foto::getId, Function.identity()));
        List<Gruppo> gruppi = new ArrayList<>();
        for (List<String> g : scelti) {
            // Una foto tolta nel frattempo da un'altra parte non c'è più.
            List<Foto> presenti = g.stream().map(foto::get).filter(f -> f != null).sorted(DA_TENERE).toList();
            if (presenti.size() > 1) {
                gruppi.add(new Gruppo(presenti.stream()
                        .map(f -> simile(f, f == presenti.getFirst()))
                        .toList()));
            }
        }
        return new PaginaGruppi(gruppi, tutti.size(), pagina, a < tutti.size());
    }

    private static FotoSimile simile(Foto f, boolean suggerita) {
        return new FotoSimile(f.getId(), "/api/foto/" + f.getId() + "/miniatura", f.getNomeOriginale(),
                f.getLarghezza(), f.getAltezza(), f.getDimensione(), f.getScattataIl(), f.getFotocamera(),
                f.isPreferita(), suggerita, suggerita || f.isPreferita());
    }

    /** I gruppi come liste di id, rifatti se il numero di foto è cambiato o la cache è vecchia. */
    private synchronized List<List<String>> gruppiInCache() {
        long foto = mongo.estimatedCount(Foto.class);
        if (cache == null || cache.foto() != foto
                || cache.fattaIl().plus(DURATA_CACHE).isBefore(clock.instant())) {
            cache = new Cache(calcolaGruppi(), foto, clock.instant());
        }
        return cache.gruppi();
    }

    /** Da rifare al prossimo giro (per esempio dopo "Calcola impronte"). */
    public synchronized void invalida() {
        cache = null;
    }

    private List<List<String>> calcolaGruppi() {
        long inizio = System.nanoTime();
        Query query = Query.query(Criteria.where("impronta").ne(null));
        query.fields().include("impronta", "scattataIl", "origineData", "fotocamera");
        List<Voce> voci = mongo.find(query, Foto.class).stream()
                .map(f -> new Voce(f.getId(), f.getImpronta(), f.getScattataIl(), haExif(f)))
                .toList();

        // Per ogni foto, i gruppi "non sono doppioni" di cui fa parte.
        Map<String, List<Integer>> ignorati = new HashMap<>();
        int n = 0;
        for (Document d : mongo.findAll(Document.class, IGNORATI)) {
            for (String id : d.getList("ids", String.class)) {
                ignorati.computeIfAbsent(id, k -> new ArrayList<>()).add(n);
            }
            n++;
        }
        Duration finestra = properties.finestraRaffica().isZero() ? null : properties.finestraRaffica();
        List<List<Integer>> gruppi = Raggruppamento.gruppi(voci, properties.soglia(), finestra,
                properties.sogliaRaffica(), (a, b) -> insieme(ignorati.get(a.id()), ignorati.get(b.id())));

        Comparator<List<Integer>> ordine = Comparator.<List<Integer>>comparingInt(List::size).reversed()
                .thenComparing(g -> g.stream().map(i -> voci.get(i).scattataIl()).filter(d -> d != null)
                        .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN), Comparator.reverseOrder());
        List<List<String>> risultato = gruppi.stream()
                .sorted(ordine)
                .map(g -> g.stream().map(i -> voci.get(i).id()).toList())
                .toList();
        log.info("Quasi uguali: {} gruppi da {} impronte in {} ms", risultato.size(), voci.size(),
                (System.nanoTime() - inizio) / 1_000_000);
        return risultato;
    }

    private static boolean insieme(List<Integer> a, List<Integer> b) {
        return a != null && b != null && a.stream().anyMatch(b::contains);
    }

    // ---------------------------------------------------------------- decisioni

    /**
     * Toglie le foto {@code togli} come l'eliminazione normale (originale,
     * scheda, miniatura: col cloud smontato 503). Se se ne tengono più d'una,
     * per quelle vale "non sono doppioni" e il gruppo non ricompare.
     */
    public Esito risolvi(Collection<String> tieni, Collection<String> togli) {
        if (togli == null || togli.isEmpty()) {
            throw new IllegalArgumentException("Nessuna foto da togliere");
        }
        if (tieni == null || tieni.isEmpty()) {
            throw new IllegalArgumentException("Tieni almeno una foto del gruppo");
        }
        if (togli.stream().anyMatch(tieni::contains)) {
            throw new IllegalArgumentException("Una foto non può essere sia da tenere sia da togliere");
        }
        archivio.verificaDisponibile();
        mongo.find(Query.query(Criteria.where("_id").in(togli).and("preferita").is(true)), Foto.class).stream()
                .findFirst()
                .ifPresent(f -> {
                    throw new IllegalArgumentException(
                            "\"" + f.getNomeOriginale() + "\" è tra le preferite: toglila dalle preferite prima");
                });
        Set<String> eliminate = new HashSet<>();
        for (String id : new TreeSet<>(togli)) {
            if (fotoService.elimina(id)) {
                eliminate.add(id);
            }
        }
        Set<String> tenute = new HashSet<>(tieni);
        if (tenute.size() > 1) {
            salvaIgnorati(tenute);
        }
        aggiornaCache(eliminate, tenute.size() > 1 ? tenute : Set.of());
        return new Esito(eliminate.size());
    }

    /** "Non sono doppioni": queste foto non si propongono più insieme. */
    public void ignora(Collection<String> ids) {
        Set<String> insieme = ids == null ? Set.of() : new HashSet<>(ids);
        if (insieme.size() < 2) {
            throw new IllegalArgumentException("Servono almeno due foto");
        }
        salvaIgnorati(insieme);
        aggiornaCache(Set.of(), insieme);
    }

    private void salvaIgnorati(Set<String> ids) {
        mongo.insert(new Document("ids", List.copyOf(new TreeSet<>(ids))).append("il", Date.from(clock.instant())),
                IGNORATI);
    }

    /** Toglie dalla cache le foto eliminate e i gruppi ormai tutti "non sono doppioni", senza ricalcolare. */
    private synchronized void aggiornaCache(Set<String> eliminate, Set<String> ignorate) {
        if (cache == null) {
            return;
        }
        List<List<String>> gruppi = new ArrayList<>();
        for (List<String> g : cache.gruppi()) {
            List<String> resto = g.stream().filter(id -> !eliminate.contains(id)).toList();
            if (resto.size() > 1 && !ignorate.containsAll(resto)) {
                gruppi.add(resto);
            }
        }
        cache = new Cache(List.copyOf(gruppi), mongo.estimatedCount(Foto.class), cache.fattaIl());
    }

    // ---------------------------------------------------------------- utilità

    private static long area(Foto f) {
        return f.getLarghezza() == null || f.getAltezza() == null ? 0 : (long) f.getLarghezza() * f.getAltezza();
    }

    private static boolean haExif(Foto f) {
        return f.getFotocamera() != null || f.getOrigineData() == OrigineData.EXIF;
    }
}
