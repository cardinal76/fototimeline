package it.fototimeline.luoghi;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.bson.Document;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineLuogo;
import it.fototimeline.luoghi.IndiceLuoghi.Trovato;
import it.fototimeline.service.FotoService;

/**
 * L'elenco dei luoghi per il filtro, "Calcola luoghi" per le foto che non ce
 * l'hanno e il luogo scelto a mano (una foto o una selezione), che il calcolo
 * non tocca più finché non si torna al GPS.
 */
@Service
public class LuoghiService {

    public static final String SENZA_DATASET =
            "Dataset dei luoghi (GeoNames) non disponibile: vedi FOTOTIMELINE_LUOGHI";

    /** Aggiornamenti per ogni giro a Mongo. */
    static final int GRUPPO = 500;

    /** I campi del luogo di una foto: si scrivono e si tolgono insieme. */
    private static final List<String> CAMPI = List.of("luogo", "provincia", "regione", "nazione", "codiceNazione",
            "latitudineLuogo", "longitudineLuogo", "origineLuogo");

    private final MongoTemplate mongo;
    private final GeocodificaInversa geocodifica;

    public LuoghiService(MongoTemplate mongo, GeocodificaInversa geocodifica) {
        this.mongo = mongo;
        this.geocodifica = geocodifica;
    }

    /** Una città: {@code provincia} c'è solo in Italia. */
    public record VoceLuogo(String nome, String provincia, long conteggio) {
    }

    public record VoceProvincia(String nome, long conteggio) {
    }

    /**
     * @param province in Italia le province della regione, con quante foto;
     *                 vuote all'estero
     * @param luoghi   le città della regione (anche quelle dentro le province)
     */
    public record VoceRegione(String nome, long conteggio, List<VoceProvincia> province, List<VoceLuogo> luoghi) {
    }

    /** @param altriNomi altri nomi in italiano, per la ricerca: "Olanda" per i Paesi Bassi */
    public record VoceNazione(String codice, String nome, long conteggio, List<VoceRegione> regioni,
            List<String> altriNomi) {
    }

    /**
     * @param disponibile il dataset di GeoNames c'è
     * @param daCalcolare foto con GPS a cui il luogo non è ancora stato cercato
     *                    (o cercato con un calcolo di prima, senza la provincia)
     */
    public record ElencoLuoghi(boolean disponibile, long daCalcolare, List<VoceNazione> nazioni) {
    }

    public boolean disponibile() {
        return geocodifica.disponibile();
    }

    /**
     * Nazioni, regioni (con le province in Italia) e città con quante foto, le
     * più fotografate prima. Contano anche le foto con solo una regione o una
     * nazione scelta a mano; quelle nel cestino no.
     */
    public ElencoLuoghi elenco() {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(where("codiceNazione").ne(null).and(FotoService.CESTINO).is(null)),
                Aggregation.group("codiceNazione", "nazione", "regione", "provincia", "luogo").count().as("conteggio"));
        Map<String, Map<String, List<Document>>> albero = new LinkedHashMap<>();
        Map<String, String> nomiNazioni = new LinkedHashMap<>();
        for (Document d : mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults()) {
            Document id = d.get("_id", Document.class);
            String codice = Objects.requireNonNullElse(id.getString("codiceNazione"), "");
            nomiNazioni.putIfAbsent(codice, Objects.requireNonNullElse(id.getString("nazione"), codice));
            albero.computeIfAbsent(codice, c -> new LinkedHashMap<>())
                    .computeIfAbsent(Objects.requireNonNullElse(id.getString("regione"), ""), r -> new ArrayList<>())
                    .add(new Document(id).append("conteggio", ((Number) d.get("conteggio")).longValue()));
        }
        Comparator<VoceLuogo> perLuogo = Comparator.comparingLong(VoceLuogo::conteggio).reversed()
                .thenComparing(VoceLuogo::nome, String.CASE_INSENSITIVE_ORDER);
        List<VoceNazione> nazioni = new ArrayList<>();
        albero.forEach((codice, regioni) -> {
            List<VoceRegione> voci = new ArrayList<>();
            regioni.forEach((regione, gruppi) -> {
                Map<String, Long> province = new LinkedHashMap<>();
                Map<List<String>, Long> luoghi = new LinkedHashMap<>();
                long totale = 0;
                for (Document g : gruppi) {
                    long n = g.getLong("conteggio");
                    totale += n;
                    String provincia = g.getString("provincia");
                    if (provincia != null) {
                        province.merge(provincia, n, Long::sum);
                    }
                    String luogo = g.getString("luogo");
                    if (luogo != null) {
                        luoghi.merge(java.util.Arrays.asList(luogo, provincia), n, Long::sum);
                    }
                }
                List<VoceLuogo> citta = new ArrayList<>();
                luoghi.forEach((l, n) -> citta.add(new VoceLuogo(l.get(0), l.get(1), n)));
                citta.sort(perLuogo);
                List<VoceProvincia> elencoProvince = new ArrayList<>();
                province.forEach((p, n) -> elencoProvince.add(new VoceProvincia(p, n)));
                elencoProvince.sort(Comparator.comparingLong(VoceProvincia::conteggio).reversed()
                        .thenComparing(VoceProvincia::nome, String.CASE_INSENSITIVE_ORDER));
                voci.add(new VoceRegione(regione.isEmpty() ? null : regione, totale, List.copyOf(elencoProvince),
                        List.copyOf(citta)));
            });
            voci.sort(Comparator.comparingLong(VoceRegione::conteggio).reversed());
            nazioni.add(new VoceNazione(codice, nomiNazioni.get(codice),
                    voci.stream().mapToLong(VoceRegione::conteggio).sum(), voci, geocodifica.altriNomi(codice)));
        });
        nazioni.sort(Comparator.comparingLong(VoceNazione::conteggio).reversed());
        return new ElencoLuoghi(geocodifica.disponibile(), mongo.count(new Query(daCalcolare(false)), Foto.class),
                nazioni);
    }

    /** Come va il calcolo: lo segue la barra dei lavori in sottofondo. */
    public interface Avanzamento {
        Avanzamento NESSUNO = new Avanzamento() { };

        default void inizio(int totale) {
        }

        /** Altre {@code quante} foto fatte, di cui {@code conLuogo} con un luogo trovato. */
        default void calcolate(int quante, int conLuogo) {
        }

        default boolean annullata() {
            return false;
        }
    }

    public record Calcolo(int foto, int conLuogo) {
    }

    /**
     * "Calcola luoghi": cerca il luogo delle foto con GPS che non l'hanno
     * ancora cercato, o l'hanno cercato con una versione di prima del calcolo
     * ({@code tutte}: di tutte, per esempio dopo aver aggiornato il dataset).
     * I luoghi scelti a mano restano. Solo Mongo e memoria, nessun accesso al
     * cloud: 150 mila foto in pochi secondi. Ogni gruppo di {@link #GRUPPO}
     * scrive la versione: annullato o interrotto, il giro dopo riparte da
     * dove era rimasto. Rifarlo non cambia niente.
     */
    public Calcolo calcola(boolean tutte, Avanzamento avanzamento) {
        if (!geocodifica.disponibile()) {
            throw new IllegalStateException(SENZA_DATASET);
        }
        Query query = new Query(daCalcolare(tutte));
        query.fields().include("latitudine", "longitudine");
        avanzamento.inizio((int) mongo.count(query, Foto.class));
        int fatte = 0;
        int conLuogo = 0;
        BulkOperations gruppo = null;
        int nelGruppo = 0;
        int trovatiNelGruppo = 0;
        try (Stream<Foto> foto = mongo.stream(query, Foto.class)) {
            for (var it = foto.iterator(); it.hasNext() && !avanzamento.annullata();) {
                Foto f = it.next();
                Trovato t = geocodifica.trova(f.getLatitudine(), f.getLongitudine()).orElse(null);
                if (t != null) {
                    trovatiNelGruppo++;
                }
                if (gruppo == null) {
                    gruppo = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Foto.class);
                }
                // Il controllo su MANUALE anche qui: un luogo scelto a mano mentre il calcolo gira resta.
                gruppo.updateOne(Query.query(where("_id").is(f.getId()).and("origineLuogo").ne(OrigineLuogo.MANUALE)),
                        daGps(t));
                if (++nelGruppo == GRUPPO) {
                    gruppo.execute();
                    avanzamento.calcolate(nelGruppo, trovatiNelGruppo);
                    fatte += nelGruppo;
                    conLuogo += trovatiNelGruppo;
                    gruppo = null;
                    nelGruppo = 0;
                    trovatiNelGruppo = 0;
                }
            }
        }
        if (gruppo != null) {
            gruppo.execute();
            avanzamento.calcolate(nelGruppo, trovatiNelGruppo);
            fatte += nelGruppo;
            conLuogo += trovatiNelGruppo;
        }
        return new Calcolo(fatte, conLuogo);
    }

    /** Il luogo dal GPS ({@code t}, o null: mare aperto), con la versione del calcolo. */
    private static Update daGps(Trovato t) {
        return t == null ? campi(null, null, null, null, null, null, null, null)
                : campi(t.nome(), t.provincia(), t.regione(), t.nazione(), t.codiceNazione(), null, null,
                        OrigineLuogo.GPS);
    }

    /** Ogni campo del luogo ({@link #CAMPI}, nello stesso ordine): scritto se c'è, tolto se no. */
    private static Update campi(String luogo, String provincia, String regione, String nazione, String codiceNazione,
            Double latitudine, Double longitudine, OrigineLuogo origine) {
        Update u = new Update().set("luogoCalcolato", true).set("versioneLuoghi", GeocodificaInversa.VERSIONE);
        Object[] valori = {luogo, provincia, regione, nazione, codiceNazione, latitudine, longitudine, origine};
        for (int i = 0; i < CAMPI.size(); i++) {
            if (valori[i] == null) {
                u.unset(CAMPI.get(i));
            } else {
                u.set(CAMPI.get(i), valori[i]);
            }
        }
        return u;
    }

    // ------------------------------------------------------------ a mano

    /** I luoghi di GeoNames che si chiamano così, per sceglierne uno ("Manch" → Manchester). */
    public List<Luogo> cerca(String testo) {
        return geocodifica.cerca(testo, 15);
    }

    /**
     * Il luogo scelto a mano per le foto {@code ids} (non quelle nel
     * cestino), anche senza GPS: una città, una provincia, una regione o una
     * nazione ({@link GeocodificaInversa#luogo}); {@code chiave} vuota = "nessun
     * luogo". In tutti e due i casi è MANUALE: "Calcola luoghi" non lo tocca.
     * Una città porta le sue coordinate, per la mappa.
     *
     * @return quante foto sono cambiate
     * @throws IllegalArgumentException se la chiave non è un luogo
     */
    public long imposta(Collection<String> ids, String chiave) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Update u;
        if (chiave == null || chiave.isBlank()) {
            u = campi(null, null, null, null, null, null, null, OrigineLuogo.MANUALE);
        } else {
            if (!geocodifica.disponibile()) {
                throw new IllegalStateException(SENZA_DATASET);
            }
            Luogo l = geocodifica.luogo(chiave.trim())
                    .orElseThrow(() -> new IllegalArgumentException("Luogo sconosciuto: " + chiave));
            u = campi(l.luogo(), l.provincia(), l.regione(), l.nazione(), l.codiceNazione(), l.latitudine(),
                    l.longitudine(), OrigineLuogo.MANUALE);
        }
        return mongo.updateMulti(Query.query(where("_id").in(ids).and(FotoService.CESTINO).is(null)), u, Foto.class)
                .getModifiedCount();
    }

    /**
     * "Usa il GPS": le foto {@code ids} tornano al luogo automatico, subito
     * ricalcolato dalle coordinate (senza GPS, nessun luogo). Senza dataset
     * il luogo si toglie e lo rifarà "Calcola luoghi".
     *
     * @return quante foto sono cambiate
     */
    public long usaGps(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Query query = Query.query(where("_id").in(ids).and(FotoService.CESTINO).is(null));
        query.fields().include("latitudine", "longitudine");
        BulkOperations gruppo = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Foto.class);
        int quante = 0;
        for (Foto f : mongo.find(query, Foto.class)) {
            Update u;
            if (geocodifica.disponibile()) {
                u = daGps(geocodifica.trova(f.getLatitudine(), f.getLongitudine()).orElse(null));
            } else {
                u = new Update().set("luogoCalcolato", false).unset("versioneLuoghi");
                CAMPI.forEach(u::unset);
            }
            if (f.getLatitudine() == null || f.getLongitudine() == null) {
                // Senza GPS non c'è niente da cercare: il calcolo non la prende comunque.
                u.set("luogoCalcolato", false);
            }
            gruppo.updateOne(Query.query(where("_id").is(f.getId())), u);
            quante++;
        }
        return quante == 0 ? 0 : gruppo.execute().getModifiedCount();
    }

    /** Le foto col GPS da calcolare; quelle nel cestino no (ripristinate, le prende il giro dopo), quelle a mano mai. */
    private static Criteria daCalcolare(boolean tutte) {
        Criteria c = where("latitudine").ne(null).and("longitudine").ne(null).and(FotoService.CESTINO).is(null)
                .and("origineLuogo").ne(OrigineLuogo.MANUALE);
        return tutte ? c : c.and("versioneLuoghi").ne(GeocodificaInversa.VERSIONE);
    }
}
