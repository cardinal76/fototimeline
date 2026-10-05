package it.fototimeline.luoghi;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.util.ArrayList;
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
import it.fototimeline.luoghi.IndiceLuoghi.Trovato;

/** L'elenco dei luoghi per il filtro e "Calcola luoghi" per le foto che non ce l'hanno. */
@Service
public class LuoghiService {

    public static final String SENZA_DATASET =
            "Dataset dei luoghi (GeoNames) non disponibile: vedi FOTOTIMELINE_LUOGHI";

    /** Aggiornamenti per ogni giro a Mongo. */
    static final int GRUPPO = 500;

    private final MongoTemplate mongo;
    private final GeocodificaInversa geocodifica;

    public LuoghiService(MongoTemplate mongo, GeocodificaInversa geocodifica) {
        this.mongo = mongo;
        this.geocodifica = geocodifica;
    }

    public record VoceLuogo(String nome, long conteggio) {
    }

    public record VoceRegione(String nome, long conteggio, List<VoceLuogo> luoghi) {
    }

    public record VoceNazione(String codice, String nome, long conteggio, List<VoceRegione> regioni) {
    }

    /**
     * @param disponibile il dataset di GeoNames c'è
     * @param daCalcolare foto con GPS a cui il luogo non è ancora stato cercato
     */
    public record ElencoLuoghi(boolean disponibile, long daCalcolare, List<VoceNazione> nazioni) {
    }

    public boolean disponibile() {
        return geocodifica.disponibile();
    }

    /** Nazioni, regioni e luoghi con quante foto, i più fotografati prima. */
    public ElencoLuoghi elenco() {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(where("luogo").ne(null)),
                Aggregation.group("codiceNazione", "nazione", "regione", "luogo").count().as("conteggio"));
        Map<String, Map<String, List<VoceLuogo>>> albero = new LinkedHashMap<>();
        Map<String, String> nomiNazioni = new LinkedHashMap<>();
        for (Document d : mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults()) {
            Document id = d.get("_id", Document.class);
            String codice = Objects.requireNonNullElse(id.getString("codiceNazione"), "");
            nomiNazioni.putIfAbsent(codice, Objects.requireNonNullElse(id.getString("nazione"), codice));
            albero.computeIfAbsent(codice, c -> new LinkedHashMap<>())
                    .computeIfAbsent(Objects.requireNonNullElse(id.getString("regione"), ""), r -> new ArrayList<>())
                    .add(new VoceLuogo(id.getString("luogo"), ((Number) d.get("conteggio")).longValue()));
        }
        Comparator<VoceLuogo> perLuogo = Comparator.comparingLong(VoceLuogo::conteggio).reversed()
                .thenComparing(VoceLuogo::nome, String.CASE_INSENSITIVE_ORDER);
        List<VoceNazione> nazioni = new ArrayList<>();
        albero.forEach((codice, regioni) -> {
            List<VoceRegione> voci = new ArrayList<>();
            regioni.forEach((regione, luoghi) -> {
                luoghi.sort(perLuogo);
                voci.add(new VoceRegione(regione.isEmpty() ? null : regione,
                        luoghi.stream().mapToLong(VoceLuogo::conteggio).sum(), List.copyOf(luoghi)));
            });
            voci.sort(Comparator.comparingLong(VoceRegione::conteggio).reversed());
            nazioni.add(new VoceNazione(codice, nomiNazioni.get(codice),
                    voci.stream().mapToLong(VoceRegione::conteggio).sum(), voci));
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
     * ancora cercato ({@code tutte}: di tutte, per esempio dopo aver
     * aggiornato il dataset). Solo Mongo e memoria, nessun accesso al cloud:
     * 150 mila foto in pochi secondi. Rifarlo non cambia niente.
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
                Update u = new Update().set("luogoCalcolato", true);
                if (t == null) {
                    u.unset("luogo").unset("regione").unset("nazione").unset("codiceNazione");
                } else {
                    u.set("luogo", t.nome()).set("regione", t.regione()).set("nazione", t.nazione())
                            .set("codiceNazione", t.codiceNazione());
                    trovatiNelGruppo++;
                }
                if (gruppo == null) {
                    gruppo = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Foto.class);
                }
                gruppo.updateOne(Query.query(where("_id").is(f.getId())), u);
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

    private static Criteria daCalcolare(boolean tutte) {
        Criteria c = where("latitudine").ne(null).and("longitudine").ne(null);
        return tutte ? c : c.and("luogoCalcolato").ne(true);
    }
}
