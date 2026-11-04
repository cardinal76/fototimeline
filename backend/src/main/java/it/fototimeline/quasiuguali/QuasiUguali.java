package it.fototimeline.quasiuguali;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumMap;
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
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import it.fototimeline.contenuto.Visione;
import it.fototimeline.contenuto.VisioneProperties;
import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.quasiuguali.Confronto.Finestre;
import it.fototimeline.quasiuguali.Confronto.Sensibilita;
import it.fototimeline.quasiuguali.Raggruppamento.Coppia;
import it.fototimeline.quasiuguali.Raggruppamento.Voce;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.EventiFoto.FotoNelCestino;
import it.fototimeline.service.EventiFoto.FotoResaPrivata;
import it.fototimeline.service.EventiFoto.FotoRipristinata;
import it.fototimeline.service.EventiFoto.FotoTornataDiTutti;
import it.fototimeline.service.EventiFoto.FotoRuotata;
import it.fototimeline.service.FotoService;

/**
 * "Foto quasi uguali": raffiche, scatti ripetuti, la stessa foto ricompressa o
 * ridimensionata. I gruppi si calcolano in memoria dalle sole impronte e firme
 * (con 150.000 foto una decina di MB), per ogni {@link Sensibilita}, e restano
 * in cache finché non cambia il numero di foto, per al più
 * {@link #DURATA_CACHE}; togliere e ignorare li aggiornano subito senza rifare
 * il calcolo. Quando due foto stanno insieme lo decide {@link Confronto}; i
 * gruppi li fa {@link Raggruppamento}, a legame completo.
 */
@Service
public class QuasiUguali {

    private static final Logger log = LoggerFactory.getLogger(QuasiUguali.class);

    /**
     * Gruppi segnati "non sono doppioni" (e coppie "non è uguale", documenti
     * di due id): le foto di uno stesso documento non si uniscono più.
     */
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
            boolean tieni, Integer somiglianza, List<String> motivi) {
    }

    public record Gruppo(List<FotoSimile> foto) {
    }

    /**
     * {@code somiglianza} (0..100) e {@code motivi} di ogni foto sono rispetto
     * alla suggerita, che non li ha.
     */
    public record PaginaGruppi(List<Gruppo> gruppi, long totale, int pagina, boolean altre, Sensibilita sensibilita) {
    }

    public record Esito(int eliminate) {
    }

    /** {@code contenuto}: il coseno CLIP delle coppie che l'hanno, per chiave {@link #coppia}. */
    private record Cache(List<List<String>> gruppi, Map<String, Double> contenuto, long foto, Instant fattaIl) {
    }

    private final MongoTemplate mongo;
    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final QuasiUgualiProperties properties;
    private final Visione visione;
    private final boolean conContenuto;
    private final Clock clock;
    private final Map<Sensibilita, Cache> cache = new EnumMap<>(Sensibilita.class);

    public QuasiUguali(MongoTemplate mongo, FotoService fotoService, ArchivioFile archivio,
            QuasiUgualiProperties properties, Visione visione, VisioneProperties visioneProperties,
            Optional<Clock> clock) {
        this.mongo = mongo;
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.properties = properties;
        this.visione = visione;
        this.conContenuto = properties.contenuto() && visioneProperties.attiva();
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ---------------------------------------------------------------- gruppi

    /** Una pagina di gruppi, dal più numeroso; a parità, dal più recente. Sensibilità severa. */
    public PaginaGruppi gruppi(int pagina, int dimensione) {
        return gruppi(Sensibilita.SEVERA, pagina, dimensione);
    }

    /** Una pagina di gruppi con questa sensibilità, dal più numeroso; a parità, dal più recente. */
    public PaginaGruppi gruppi(Sensibilita sensibilita, int pagina, int dimensione) {
        Cache c = gruppiInCache(sensibilita);
        List<List<String>> tutti = c.gruppi();
        int da = (int) Math.min((long) pagina * dimensione, tutti.size());
        int a = Math.min(da + dimensione, tutti.size());
        List<List<String>> scelti = tutti.subList(da, a);

        Set<String> ids = scelti.stream().flatMap(List::stream).collect(Collectors.toSet());
        // Le private (di chiunque) stanno fuori, come quelle nel cestino: i gruppi sono gli stessi per tutti.
        Map<String, Foto> foto = mongo.find(Query.query(FotoService.diFamiglia().and("_id").in(ids)),
                        Foto.class).stream()
                .collect(Collectors.toMap(Foto::getId, Function.identity()));
        List<Gruppo> gruppi = new ArrayList<>();
        for (List<String> g : scelti) {
            // Una foto tolta nel frattempo da un'altra parte (nel cestino) non c'è più.
            List<Foto> presenti = g.stream().map(foto::get).filter(f -> f != null).sorted(DA_TENERE).toList();
            if (presenti.size() > 1) {
                Foto prima = presenti.getFirst();
                gruppi.add(new Gruppo(presenti.stream()
                        .map(f -> simile(f, prima, sensibilita, c.contenuto()))
                        .toList()));
            }
        }
        return new PaginaGruppi(gruppi, tutti.size(), pagina, a < tutti.size(), sensibilita);
    }

    /** La foto del gruppo, con quanto somiglia alla suggerita e perché. */
    private FotoSimile simile(Foto f, Foto suggerita, Sensibilita sensibilita, Map<String, Double> contenuto) {
        boolean eLei = f == suggerita;
        Integer somiglianza = null;
        List<String> motivi = List.of();
        if (!eLei && f.getImpronta() != null && suggerita.getImpronta() != null) {
            Confronto.Esito e = Confronto.valuta(voce(f), voce(suggerita), sensibilita, finestre());
            Double clip = contenuto.get(coppia(f.getId(), suggerita.getId()));
            Duration distanza = f.getScattataIl() == null || suggerita.getScattataIl() == null ? null
                    : Duration.between(f.getScattataIl(), suggerita.getScattataIl()).abs();
            somiglianza = e.percentuale(clip);
            motivi = Confronto.motivi(e, clip, distanza);
        }
        return new FotoSimile(f.getId(), FotoService.indirizzoMiniatura(f), f.getNomeOriginale(),
                f.getLarghezza(), f.getAltezza(), f.getDimensione(), f.getScattataIl(), f.getFotocamera(),
                f.isPreferita(), eLei, eLei || f.isPreferita(), somiglianza, motivi);
    }

    /** I gruppi come liste di id, rifatti se il numero di foto è cambiato o la cache è vecchia. */
    private synchronized Cache gruppiInCache(Sensibilita sensibilita) {
        long foto = mongo.estimatedCount(Foto.class);
        Cache c = cache.get(sensibilita);
        if (c == null || c.foto() != foto || c.fattaIl().plus(DURATA_CACHE).isBefore(clock.instant())) {
            c = calcolaGruppi(sensibilita, foto);
            cache.put(sensibilita, c);
        }
        return c;
    }

    /**
     * Da rifare al prossimo giro, per tutte le sensibilità (dopo "Calcola
     * impronte", o se cambia il calcolo: la cache sta solo in memoria, un
     * riavvio con un algoritmo nuovo la rifà comunque).
     */
    public synchronized void invalida() {
        cache.clear();
    }

    /**
     * Una foto che va nel cestino (da qui con {@link #risolvi}, o dal visore e
     * dalla selezione) o che ne torna cambia i gruppi, ma il numero di schede
     * no (nel cestino la scheda resta): la cache non se ne accorgerebbe da
     * sola. Una tolta esce dai gruppi in cache senza ricalcolare; una
     * ripristinata può rientrare in un gruppo, e i gruppi si rifanno.
     */
    @EventListener
    public void nelCestino(FotoNelCestino evento) {
        aggiornaCache(Set.of(evento.id()), Set.of());
    }

    @EventListener
    public void ripristinata(FotoRipristinata evento) {
        invalida();
    }

    /** Nel "Privato" di qualcuno: per le quasi uguali è come nel cestino. */
    @EventListener
    public void resaPrivata(FotoResaPrivata evento) {
        aggiornaCache(Set.of(evento.id()), Set.of());
    }

    @EventListener
    public void tornataDiTutti(FotoTornataDiTutti evento) {
        invalida();
    }

    /** Girata a mano: l'impronta è cambiata (si calcola sulla miniatura come si vede). */
    @EventListener
    public void ruotata(FotoRuotata evento) {
        invalida();
    }

    private Cache calcolaGruppi(Sensibilita sensibilita, long quanteFoto) {
        long inizio = System.nanoTime();
        Query query = Query.query(FotoService.diFamiglia().and("impronta").ne(null));
        query.fields().include("impronta", "firma", "scattataIl", "origineData", "fotocamera", "larghezza",
                "altezza");
        List<Voce> voci = mongo.find(query, Foto.class).stream().map(QuasiUguali::voce).toList();

        // Per ogni foto, i gruppi "non sono doppioni" di cui fa parte.
        Map<String, List<Integer>> ignorati = new HashMap<>();
        int n = 0;
        for (Document d : mongo.findAll(Document.class, IGNORATI)) {
            for (String id : d.getList("ids", String.class)) {
                ignorati.computeIfAbsent(id, k -> new ArrayList<>()).add(n);
            }
            n++;
        }
        var risultato = Raggruppamento.gruppi(voci, sensibilita, finestre(),
                (a, b) -> insieme(ignorati.get(a.id()), ignorati.get(b.id())),
                conContenuto ? this::contenuto : null);
        List<List<Integer>> gruppi = risultato.gruppi();

        Comparator<List<Integer>> ordine = Comparator.<List<Integer>>comparingInt(List::size).reversed()
                .thenComparing(g -> g.stream().map(i -> voci.get(i).scattataIl()).filter(d -> d != null)
                        .max(Comparator.naturalOrder()).orElse(LocalDateTime.MIN), Comparator.reverseOrder());
        List<List<String>> ordinati = gruppi.stream()
                .sorted(ordine)
                .map(g -> g.stream().map(i -> voci.get(i).id()).toList())
                .toList();
        // Il coseno solo delle coppie nei gruppi: serve per mostrarlo.
        Map<String, Double> contenuto = new HashMap<>();
        for (List<Integer> g : gruppi) {
            for (int x : g) {
                for (int y : g) {
                    Double coseno = risultato.contenuto().get(Raggruppamento.chiave(x, y, voci.size()));
                    if (x < y && coseno != null) {
                        contenuto.put(coppia(voci.get(x).id(), voci.get(y).id()), coseno);
                    }
                }
            }
        }
        log.info("Quasi uguali ({}): {} gruppi da {} impronte in {} ms", sensibilita, ordinati.size(), voci.size(),
                (System.nanoTime() - inizio) / 1_000_000);
        return new Cache(ordinati, Map.copyOf(contenuto), quanteFoto, clock.instant());
    }

    static Voce voce(Foto f) {
        return new Voce(f.getId(), f.getImpronta(), f.getFirma(), f.getScattataIl(), haExif(f),
                f.getLarghezza() == null ? 0 : f.getLarghezza(), f.getAltezza() == null ? 0 : f.getAltezza());
    }

    private Finestre finestre() {
        return new Finestre(properties.finestraRaffica().isZero() ? null : properties.finestraRaffica(),
                properties.finestraVicine().isZero() ? null : properties.finestraVicine());
    }

    /**
     * Il coseno CLIP delle coppie, da visione. Se visione non risponde i gruppi
     * si fanno lo stesso, senza contenuto (null: Raggruppamento lascia stare).
     */
    private double[] contenuto(List<Voce> voci, List<Coppia> coppie) {
        try {
            List<Double> punteggi = visione.somiglianze(coppie.stream()
                    .map(c -> List.of(voci.get(c.a()).id(), voci.get(c.b()).id()))
                    .toList());
            if (punteggi == null || punteggi.size() != coppie.size()) {
                return null;
            }
            return punteggi.stream().mapToDouble(p -> p == null ? Double.NaN : p).toArray();
        } catch (RuntimeException e) {
            log.warn("Quasi uguali senza il contenuto (visione): {}", e.getMessage());
            return null;
        }
    }

    /** Chiave di una coppia di id, nello stesso ordine comunque le si passi. */
    private static String coppia(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static boolean insieme(List<Integer> a, List<Integer> b) {
        return a != null && b != null && a.stream().anyMatch(b::contains);
    }

    // ---------------------------------------------------------------- decisioni

    /**
     * Toglie le foto {@code togli} come l'eliminazione normale: nel cestino
     * (col cloud smontato 503). Se se ne tengono più d'una,
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

    /**
     * "Non è uguale": questa foto non si propone più insieme a nessuna di
     * {@code altre} (le altre tra loro restano libere di stare insieme).
     */
    public void separa(String id, Collection<String> altre) {
        Set<String> da = altre == null ? new HashSet<>() : new HashSet<>(altre);
        da.remove(id);
        if (id == null || id.isBlank() || da.isEmpty()) {
            throw new IllegalArgumentException("Servono la foto e almeno un'altra");
        }
        Date il = Date.from(clock.instant());
        mongo.insert(da.stream()
                .map(altra -> new Document("ids", List.copyOf(new TreeSet<>(Set.of(id, altra)))).append("il", il))
                .toList(), IGNORATI);
        separaInCache(id, da);
    }

    private void salvaIgnorati(Set<String> ids) {
        mongo.insert(new Document("ids", List.copyOf(new TreeSet<>(ids))).append("il", Date.from(clock.instant())),
                IGNORATI);
    }

    /** Toglie dalla cache le foto eliminate e i gruppi ormai tutti "non sono doppioni", senza ricalcolare. */
    private synchronized void aggiornaCache(Set<String> eliminate, Set<String> ignorate) {
        long foto = mongo.estimatedCount(Foto.class);
        cache.replaceAll((s, c) -> {
            List<List<String>> gruppi = new ArrayList<>();
            for (List<String> g : c.gruppi()) {
                List<String> resto = g.stream().filter(id -> !eliminate.contains(id)).toList();
                if (resto.size() > 1 && !ignorate.containsAll(resto)) {
                    gruppi.add(resto);
                }
            }
            return new Cache(List.copyOf(gruppi), c.contenuto(), foto, c.fattaIl());
        });
    }

    /** "Non è uguale": la foto esce dai gruppi in cache dove c'è una delle altre. */
    private synchronized void separaInCache(String id, Set<String> altre) {
        cache.replaceAll((s, c) -> {
            List<List<String>> gruppi = new ArrayList<>();
            for (List<String> g : c.gruppi()) {
                List<String> resto = g.contains(id) && g.stream().anyMatch(altre::contains)
                        ? g.stream().filter(x -> !x.equals(id)).toList()
                        : g;
                if (resto.size() > 1) {
                    gruppi.add(resto);
                }
            }
            return new Cache(List.copyOf(gruppi), c.contenuto(), c.foto(), c.fattaIl());
        });
    }

    // ---------------------------------------------------------------- utilità

    private static long area(Foto f) {
        return f.getLarghezza() == null || f.getAltezza() == null ? 0 : (long) f.getLarghezza() * f.getAltezza();
    }

    private static boolean haExif(Foto f) {
        return f.getFotocamera() != null || f.getOrigineData() == OrigineData.EXIF;
    }
}
