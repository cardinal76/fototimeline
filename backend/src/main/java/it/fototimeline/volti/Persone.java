package it.fototimeline.volti;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOptions;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import it.fototimeline.dominio.Foto;
import it.fototimeline.volti.VisioneVolti.Vicino;
import it.fototimeline.volti.Volto.Riquadro;

/**
 * Persone e gruppi di volti: l'assegnazione dei volti nuovi (raggruppamento
 * incrementale) e quello che si fa a mano (dare un nome, unire, nascondere,
 * togliere un volto sbagliato, confermare le proposte).
 *
 * <p><b>Raggruppamento.</b> Niente confronto di tutti i volti con tutti
 * (con 300.000 volti sarebbero 45 miliardi di coppie) né un clustering
 * periodico che rimescola i gruppi già nominati: ogni volto nuovo chiede a
 * visione i suoi {@code vicini} più simili (indice dei volti, coseno) e
 * <ol>
 * <li>se tra loro c'è una persona con nome sopra {@code sogliaSicura}, è sua;</li>
 * <li>altrimenti, se c'è un gruppo senza nome (o nascosto) sopra {@code sogliaGruppo}, entra lì;</li>
 * <li>una persona con nome fra {@code sogliaProposta} e {@code sogliaSicura} resta una proposta;</li>
 * <li>se no, con i vicini ancora senza gruppo sopra {@code sogliaGruppo} fa un gruppo nuovo.</li>
 * </ol>
 * Fra più candidati vince quello con più somiglianza in tutto (somma sui
 * vicini), non il singolo vicino più simile: un volto ambiguo non trascina
 * un gruppo intero dalla parte sbagliata. I volti della stessa foto non si
 * contano (in una foto la stessa persona c'è una volta sola).
 *
 * <p><b>Una persona al massimo una volta per foto</b>, sempre: dopo ogni
 * assegnazione, spostamento, conferma o unione {@link #risolviDoppioni}
 * lascia la persona a un volto solo della foto e libera gli altri. Vince chi
 * l'ha appena decisa a mano, poi un volto deciso a mano, poi uno automatico,
 * poi una persona {@code aggiunta} senza volto (un volto vero la sostituisce);
 * a parità il più somigliante al centro degli altri volti della persona. Due
 * volti veri decisi a mano per la stessa persona nella stessa foto non si
 * fanno: {@link PersonaGiaNellaFoto}, 409. Chi è già su un volto della foto
 * non si propone ("Forse è…?") agli altri volti. I dati di prima li corregge
 * {@link #correggiDoppioni} all'avvio.
 *
 * <p>Ogni modifica passa da qui ed è {@code synchronized}: il lavoro che
 * assegna e chi unisce due persone non si pestano i piedi. Le foto tengono
 * gli id delle loro persone in {@code Foto.persone} ({@link #aggiornaFoto}).
 */
@Service
public class Persone {

    private static final Logger log = LoggerFactory.getLogger(Persone.class);

    static final String FOTO_PERSONE = "persone";
    private static final int NOME_MASSIMO = 80;
    private static final int VOLTI_PER_SIMILI = 16;
    /** Quanti volti al più per il centro di una persona, quando due volti di una foto se la contendono. */
    private static final int VOLTI_PER_CENTRO = 2000;
    private static final int MASSIMO_FOTO_IN_ERRORE = 20;

    public enum Tipo { NOMINATE, GRUPPI, NASCOSTE }

    /** Per la griglia: una persona o un gruppo, quanti volti e quante proposte, il volto da mostrare. */
    public record VocePersona(String id, String nome, boolean nascosta, long volti, long proposte, String copertina) {
    }

    public record ElencoPersone(List<VocePersona> persone, long totale) {
    }

    /** Un volto di una persona; {@code somiglianza} con la persona (media dei suoi volti) o con la proposta. */
    public record VoceVolto(String id, String fotoId, double punteggio, Double somiglianza, boolean manuale) {
    }

    /** Un altro gruppo o persona che somiglia: da unire, forse. */
    public record PersonaSimile(VocePersona persona, double somiglianza, int vicini) {
    }

    /**
     * Un volto nel visore: dove sta e di chi è (il nome solo per chi è entrato
     * nell'app). I volti delle persone nascoste non ci sono, e nemmeno le
     * proposte verso di loro.
     */
    public record VoltoInFoto(String id, Riquadro riquadro, String personaId, String nome,
            String proposta, String nomeProposta, boolean aggiunto) {
    }

    /** Quello che ha fatto {@link #correggiDoppioni}: volti liberati e proposte tolte. */
    public record Correzione(int liberati, int proposte) {
    }

    /**
     * La persona è già in questa foto, su un altro volto deciso a mano (o, per
     * una persona aggiunta senza volto, su un volto qualsiasi): una persona c'è
     * una volta sola per foto. 409, come le altre {@link IllegalStateException}.
     */
    public static class PersonaGiaNellaFoto extends IllegalStateException {
        private final transient Persona persona;
        private final transient List<String> foto;

        PersonaGiaNellaFoto(String messaggio, Persona persona, List<String> foto) {
            super(messaggio);
            this.persona = persona;
            this.foto = foto;
        }

        public Persona persona() {
            return persona;
        }

        public List<String> foto() {
            return foto;
        }
    }

    /** C'è già una persona con questo nome: si uniscono, non si fanno due "Anna". */
    public static class NomeGiaUsato extends RuntimeException {
        private final transient Persona altra;

        NomeGiaUsato(Persona altra) {
            super("C'è già una persona che si chiama " + altra.getNome() + ": uniscile");
            this.altra = altra;
        }

        public Persona altra() {
            return altra;
        }
    }

    private final VoltiProperties properties;
    private final MongoTemplate mongo;
    private final VisioneVolti visione;
    private final Clock clock;

    public Persone(VoltiProperties properties, MongoTemplate mongo, VisioneVolti visione, Optional<Clock> clock) {
        this.properties = properties;
        this.mongo = mongo;
        this.visione = visione;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    // ------------------------------------------------------------ volti nuovi

    /**
     * Dà un gruppo o una persona ai volti appena trovati, in ordine: un volto
     * può finire nel gruppo di uno arrivato poco prima nello stesso blocco.
     *
     * @param vicini per ogni volto (stesso ordine) i suoi vicini nell'indice
     * @return le foto i cui volti hanno cambiato persona
     */
    public synchronized Set<String> assegnaNuovi(List<Volto> nuovi, List<List<Vicino>> vicini) {
        Set<String> foto = new HashSet<>();
        for (int i = 0; i < nuovi.size(); i++) {
            Volto v = nuovi.get(i);
            List<Vicino> suoi = i < vicini.size() && vicini.get(i) != null ? vicini.get(i) : List.of();
            if (assegna(v, suoi, foto)) {
                foto.add(v.getFotoId());
            }
        }
        aggiornaFoto(foto);
        // Due volti della stessa foto finiti alla stessa persona: resta il più somigliante.
        Set<String> toccate = new HashSet<>(foto);
        nuovi.forEach(v -> toccate.add(v.getFotoId()));
        foto.addAll(sistemaFoto(toccate, Set.of()));
        return foto;
    }

    private boolean assegna(Volto v, List<Vicino> vicini, Set<String> foto) {
        // Le persone già sugli altri volti di questa foto: chi è su un volto deciso a mano non si
        // prende (vince la mano), e nessuna di loro si propone. Una aggiunta senza volto non conta:
        // un volto vero la sostituisce.
        Query stessaFoto = Query.query(Criteria.where("fotoId").is(v.getFotoId()).and("_id").ne(v.getId())
                .and("personaId").ne(null).and("aggiunto").ne(true));
        stessaFoto.fields().include("personaId", "manuale");
        Set<String> aMano = new HashSet<>();
        Set<String> presenti = new HashSet<>();
        for (Volto altro : mongo.find(stessaFoto, Volto.class)) {
            presenti.add(altro.getPersonaId());
            if (altro.isManuale()) {
                aMano.add(altro.getPersonaId());
            }
        }
        Map<String, Double> somiglianze = new LinkedHashMap<>();
        for (Vicino x : vicini) {
            if (!x.id().equals(v.getId())) {
                somiglianze.putIfAbsent(x.id(), x.punteggio());
            }
        }
        if (somiglianze.isEmpty()) {
            return false;
        }
        Query query = Query.query(Criteria.where("_id").in(somiglianze.keySet()).and("fotoId").ne(v.getFotoId())
                .and("nelCestino").is(false));
        query.fields().include("personaId", "fotoId", "manuale");
        Map<String, double[]> perPersona = new HashMap<>();
        // Un libero per foto, il più simile: due volti della stessa foto non fanno lo stesso gruppo.
        Map<String, Volto> liberi = new LinkedHashMap<>();
        for (Volto altro : mongo.find(query, Volto.class)) {
            double s = somiglianze.get(altro.getId());
            if (altro.getPersonaId() != null) {
                double[] stat = perPersona.computeIfAbsent(altro.getPersonaId(), k -> new double[2]);
                stat[0] = Math.max(stat[0], s);
                stat[1] += s;
            } else if (s >= properties.sogliaGruppo() && !altro.isManuale()) {
                // Uno tolto a mano ("non è nessuno") resta di nessuno: un gruppo nuovo non se lo prende.
                liberi.merge(altro.getFotoId(), altro,
                        (a, b) -> somiglianze.get(a.getId()) >= somiglianze.get(b.getId()) ? a : b);
            }
        }
        Map<String, Persona> persone = new HashMap<>();
        if (!perPersona.isEmpty()) {
            mongo.find(Query.query(Criteria.where("_id").in(perPersona.keySet())), Persona.class)
                    .forEach(p -> persone.put(p.getId(), p));
        }
        // Una persona con nome (e non nascosta) sopra la soglia sicura; se no un gruppo senza nome o nascosto.
        String scelta = migliore(perPersona, persone, v, aMano, true, properties.sogliaSicura(),
                Comparator.comparingDouble(s -> s[1]));
        if (scelta == null) {
            scelta = migliore(perPersona, persone, v, aMano, false, properties.sogliaGruppo(),
                    Comparator.comparingDouble(s -> s[1]));
        }
        String proposta = null;
        if (scelta == null || !persone.get(scelta).conNome() || persone.get(scelta).isNascosta()) {
            proposta = migliore(perPersona, persone, v, presenti, true, properties.sogliaProposta(),
                    Comparator.comparingDouble(s -> s[0]));
        }
        Update update = new Update();
        boolean cambiata = false;
        if (scelta != null) {
            update.set("personaId", scelta);
            v.setPersonaId(scelta);
            cambiata = true;
            Persona p = persone.get(scelta);
            if (p.getCopertina() == null) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(scelta)), new Update().set("copertina", v.getId()),
                        Persona.class);
            }
        }
        if (proposta != null) {
            update.set("proposta", proposta).set("somiglianzaProposta", perPersona.get(proposta)[0]);
            v.setProposta(proposta);
        }
        if (scelta == null && proposta == null && !liberi.isEmpty()) {
            // Un gruppo nuovo: lui e i vicini simili che non sono ancora di nessuno.
            Persona gruppo = new Persona();
            gruppo.setCopertina(v.getId());
            gruppo.setCreataIl(clock.instant());
            gruppo = mongo.insert(gruppo);
            update.set("personaId", gruppo.getId());
            v.setPersonaId(gruppo.getId());
            cambiata = true;
            List<String> ids = liberi.values().stream().map(Volto::getId).toList();
            mongo.updateMulti(Query.query(Criteria.where("_id").in(ids).and("personaId").is(null).and("manuale").ne(true)),
                    new Update().set("personaId", gruppo.getId()), Volto.class);
            liberi.values().forEach(l -> foto.add(l.getFotoId()));
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(v.getId())), update, Volto.class);
        }
        return cambiata;
    }

    /**
     * Fra le persone dei vicini sopra la soglia, quella che vince secondo {@code ordine}; null se
     * nessuna. Mai una degli esclusi del volto né una di {@code vietate} (già sulla foto).
     */
    private static String migliore(Map<String, double[]> perPersona, Map<String, Persona> persone, Volto v,
            Set<String> vietate, boolean conNome, double soglia, Comparator<double[]> ordine) {
        return perPersona.entrySet().stream()
                .filter(e -> persone.containsKey(e.getKey()) && !v.getEsclusi().contains(e.getKey())
                        && !vietate.contains(e.getKey()))
                .filter(e -> {
                    Persona p = persone.get(e.getKey());
                    return conNome == (p.conNome() && !p.isNascosta());
                })
                .filter(e -> e.getValue()[0] >= soglia)
                .max(Map.Entry.comparingByValue(ordine))
                .map(Map.Entry::getKey)
                .orElse(null);
    }

    /** Rimette in {@code Foto.persone} le persone dei volti di queste foto. */
    void aggiornaFoto(Collection<String> fotoIds) {
        List<String> tutte = new ArrayList<>(new LinkedHashSet<>(fotoIds));
        for (int da = 0; da < tutte.size(); da += 1000) {
            List<String> pezzo = tutte.subList(da, Math.min(tutte.size(), da + 1000));
            Query query = Query.query(Criteria.where("fotoId").in(pezzo).and("personaId").ne(null));
            query.fields().include("fotoId", "personaId");
            Map<String, Set<String>> perFoto = new HashMap<>();
            mongo.find(query, Volto.class)
                    .forEach(v -> perFoto.computeIfAbsent(v.getFotoId(), k -> new TreeSet<>()).add(v.getPersonaId()));
            BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Foto.class);
            for (String id : pezzo) {
                Set<String> persone = perFoto.get(id);
                bulk.updateOne(Query.query(Criteria.where("_id").is(id)),
                        persone == null ? new Update().unset(FOTO_PERSONE) : new Update().set(FOTO_PERSONE, persone));
            }
            bulk.execute();
        }
    }

    // ------------------------------------------------------------ una persona per foto

    /**
     * Dopo una modifica a queste foto: una persona su un volto solo per foto
     * ({@link #risolviDoppioni}) e niente proposte verso chi c'è già.
     *
     * @param decisi i volti appena decisi a mano: tengono la persona
     * @return le foto in cui un volto è stato liberato
     */
    private Set<String> sistemaFoto(Collection<String> fotoIds, Set<String> decisi) {
        Set<String> cambiate = new HashSet<>();
        List<String> tutte = new ArrayList<>(new LinkedHashSet<>(fotoIds));
        for (int da = 0; da < tutte.size(); da += 1000) {
            List<String> pezzo = tutte.subList(da, Math.min(tutte.size(), da + 1000));
            risolviDoppioni(Criteria.where("fotoId").in(pezzo), decisi).forEach(v -> cambiate.add(v.getFotoId()));
            togliProposteDeiPresenti(Criteria.where("fotoId").in(pezzo));
        }
        return cambiate;
    }

    /**
     * Le foto (fra quelle dei volti che rispondono a {@code criteri}) con la
     * stessa persona su più volti: ne resta uno, gli altri tornano senza
     * persona. Vince, nell'ordine: un volto di {@code decisi} (appena deciso a
     * mano), un volto vero deciso a mano, uno automatico, una persona aggiunta
     * a mano senza volto (che se ne va: c'è il volto vero). A parità il più
     * somigliante al centro degli altri volti della persona (quelli fuori da
     * questa foto), poi il più sicuro per il rilevatore. Un volto deciso a mano
     * che perde resta "tolto a mano", con la persona negli esclusi.
     *
     * @return i volti liberati (com'erano prima)
     */
    private List<Volto> risolviDoppioni(Criteria criteri, Set<String> decisi) {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(new Criteria().andOperator(criteri, Criteria.where("personaId").ne(null))),
                Aggregation.group("fotoId", "personaId").count().as("n").push("_id").as("volti"),
                Aggregation.match(Criteria.where("n").gt(1)))
                .withOptions(AggregationOptions.builder().allowDiskUse(true).build());
        List<Document> doppi = mongo.aggregate(aggregazione, Volto.class, Document.class).getMappedResults();
        if (doppi.isEmpty()) {
            return List.of();
        }
        Set<String> cambiate = new HashSet<>();
        List<Volto> liberati = new ArrayList<>();
        for (Document d : doppi) {
            Document chiave = d.get("_id", Document.class);
            String fotoId = chiave.getString("fotoId");
            String personaId = chiave.getString("personaId");
            List<String> ids = d.getList("volti", Object.class).stream().map(Object::toString).toList();
            List<Volto> volti = mongo.find(Query.query(Criteria.where("_id").in(ids).and("personaId").is(personaId)),
                    Volto.class);
            if (volti.size() < 2) {
                continue;
            }
            float[] centro = media(centro(personaId, fotoId));
            Comparator<Volto> ordine = Comparator.comparingInt((Volto v) -> livello(v, decisi))
                    .thenComparingDouble(v -> {
                        Double c = coseno(centro, v.vettoreFloat());
                        return c == null ? -2 : c;
                    })
                    .thenComparingDouble(Volto::getPunteggio)
                    .thenComparing(Volto::getId, Comparator.reverseOrder());
            Volto vince = volti.stream().max(ordine).orElseThrow();
            for (Volto v : volti) {
                if (v != vince) {
                    libera(v, personaId);
                    liberati.add(v);
                    cambiate.add(fotoId);
                }
            }
            log.debug("Foto {}: {} su {} volti, resta {}, liberati {}", fotoId, personaId, volti.size(), vince.getId(),
                    volti.stream().filter(v -> v != vince).map(Volto::getId).toList());
        }
        aggiornaFoto(cambiate);
        liberati.forEach(v -> dopoTolto(v.getPersonaId(), v.getId()));
        return liberati;
    }

    /** Chi tiene la persona quando due volti di una foto se la contendono: più alto vince. */
    private static int livello(Volto v, Set<String> decisi) {
        if (decisi.contains(v.getId())) {
            return 3;
        }
        if (v.isAggiunto()) {
            return 0;
        }
        return v.isManuale() ? 2 : 1;
    }

    /** I volti della persona fuori da questa foto, per il suo centro. */
    private List<Volto> centro(String personaId, String fotoId) {
        Query query = Query.query(Criteria.where("personaId").is(personaId).and("fotoId").ne(fotoId)
                .and("nelCestino").is(false).and("aggiunto").ne(true)).limit(VOLTI_PER_CENTRO);
        query.fields().include("vettore");
        return mongo.find(query, Volto.class);
    }

    /** Il volto perde la persona: un aggiunto se ne va, uno deciso a mano resta "tolto", uno automatico libero. */
    private void libera(Volto v, String personaId) {
        if (v.isAggiunto()) {
            mongo.remove(Query.query(Criteria.where("_id").is(v.getId())), Volto.class);
            return;
        }
        Update update = new Update().unset("personaId");
        if (v.isManuale()) {
            update.addToSet("esclusi", personaId);
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(v.getId()).and("personaId").is(personaId)), update,
                Volto.class);
    }

    /**
     * Le proposte (fra i volti che rispondono a {@code criteri}) verso una
     * persona che è già su un altro volto vero della stessa foto: si tolgono.
     * Una persona aggiunta senza volto non conta: la proposta può essere
     * proprio il suo volto. @return quante
     */
    private int togliProposteDeiPresenti(Criteria criteri) {
        Query query = Query.query(new Criteria().andOperator(criteri, Criteria.where("proposta").ne(null)));
        query.fields().include("fotoId", "proposta");
        List<Volto> proposti = mongo.find(query, Volto.class);
        int tolte = 0;
        for (int da = 0; da < proposti.size(); da += 1000) {
            List<Volto> pezzo = proposti.subList(da, Math.min(proposti.size(), da + 1000));
            Query presenti = Query.query(Criteria.where("fotoId").in(pezzo.stream().map(Volto::getFotoId).toList())
                    .and("personaId").ne(null).and("aggiunto").ne(true));
            presenti.fields().include("fotoId", "personaId");
            Map<String, List<Volto>> perFoto = new HashMap<>();
            mongo.find(presenti, Volto.class).forEach(v -> perFoto.computeIfAbsent(v.getFotoId(), k -> new ArrayList<>()).add(v));
            List<String> daTogliere = pezzo.stream()
                    .filter(p -> perFoto.getOrDefault(p.getFotoId(), List.of()).stream()
                            .anyMatch(x -> !x.getId().equals(p.getId()) && p.getProposta().equals(x.getPersonaId())))
                    .map(Volto::getId).toList();
            if (!daTogliere.isEmpty()) {
                mongo.updateMulti(Query.query(Criteria.where("_id").in(daTogliere)),
                        new Update().unset("proposta").unset("somiglianzaProposta"), Volto.class);
                tolte += daTogliere.size();
            }
        }
        return tolte;
    }

    /**
     * Corregge i dati di prima della regola "una persona al massimo una volta
     * per foto": in ogni foto resta il volto migliore ({@link #risolviDoppioni})
     * e le proposte verso chi c'è già se ne vanno. Idempotente: un secondo giro
     * non trova niente. Parte da solo all'avvio ({@link #allAvvio}).
     */
    public synchronized Correzione correggiDoppioni() {
        int liberati = risolviDoppioni(new Criteria(), Set.of()).size();
        return new Correzione(liberati, togliProposteDeiPresenti(new Criteria()));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void allAvvio() {
        try {
            Correzione c = correggiDoppioni();
            if (c.liberati() > 0 || c.proposte() > 0) {
                log.info("Una persona per foto: {} volti liberati, {} proposte tolte", c.liberati(), c.proposte());
            }
        } catch (RuntimeException e) {
            // Non ferma l'avvio: la regola vale comunque per le modifiche nuove, e al prossimo avvio si riprova.
            log.warn("Correzione \"una persona per foto\" non riuscita: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ cestino ed eliminazione

    /** La foto è nel cestino: i suoi volti non contano più. @return gli id da togliere dall'indice */
    synchronized List<String> nelCestino(String fotoId) {
        // Anche le persone aggiunte a mano (non sono nell'indice: non fra gli id).
        mongo.updateMulti(Query.query(Criteria.where("fotoId").is(fotoId)), new Update().set("nelCestino", true),
                Volto.class);
        return idVolti(fotoId);
    }

    /** Tornata dal cestino: i volti tornano com'erano. @return i loro vettori, da rimettere nell'indice */
    synchronized Map<String, float[]> ripristinata(String fotoId) {
        mongo.updateMulti(Query.query(Criteria.where("fotoId").is(fotoId)), new Update().set("nelCestino", false),
                Volto.class);
        Map<String, float[]> vettori = new LinkedHashMap<>();
        // Le persone aggiunte a mano non hanno vettore: nell'indice non vanno.
        mongo.find(Query.query(Criteria.where("fotoId").is(fotoId).and("aggiunto").ne(true)), Volto.class)
                .forEach(v -> vettori.put(v.getId(), v.vettoreFloat()));
        return vettori;
    }

    /** Eliminata per sempre: i volti se ne vanno, e i gruppi senza nome rimasti vuoti pure. @return gli id da togliere dall'indice */
    synchronized List<String> eliminata(String fotoId) {
        return togli(Criteria.where("fotoId").is(fotoId));
    }

    /**
     * I volti trovati da visione, da rifare (un giro interrotto a metà): le
     * persone aggiunte a mano restano. @return gli id da togliere dall'indice
     */
    synchronized List<String> togliRilevati(String fotoId) {
        return togli(Criteria.where("fotoId").is(fotoId).and("aggiunto").ne(true));
    }

    private List<String> togli(Criteria criteri) {
        Query query = Query.query(criteri);
        query.fields().include("personaId", "aggiunto");
        List<Volto> volti = mongo.find(query, Volto.class);
        if (volti.isEmpty()) {
            return List.of();
        }
        mongo.remove(Query.query(Criteria.where("_id").in(volti.stream().map(Volto::getId).toList())), Volto.class);
        volti.stream().filter(v -> v.getPersonaId() != null)
                .forEach(v -> dopoTolto(v.getPersonaId(), v.getId()));
        return volti.stream().filter(v -> !v.isAggiunto()).map(Volto::getId).toList();
    }

    private List<String> idVolti(String fotoId) {
        Query query = Query.query(Criteria.where("fotoId").is(fotoId).and("aggiunto").ne(true));
        query.fields().include("_id");
        return mongo.find(query, Volto.class).stream().map(Volto::getId).toList();
    }

    /** Un volto è uscito da una persona: la copertina si rifà, un gruppo senza nome vuoto si toglie. */
    private void dopoTolto(String personaId, String voltoId) {
        Persona p = mongo.findById(personaId, Persona.class);
        if (p == null) {
            return;
        }
        if (!mongo.exists(Query.query(Criteria.where("personaId").is(personaId)), Volto.class) && !p.conNome()) {
            mongo.remove(p);
            mongo.updateMulti(Query.query(Criteria.where("proposta").is(personaId)),
                    new Update().unset("proposta").unset("somiglianzaProposta"), Volto.class);
        } else if (voltoId.equals(p.getCopertina())) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(personaId)), new Update().unset("copertina"),
                    Persona.class);
        }
    }

    // ------------------------------------------------------------ lettura

    /** Le persone con nome (per nome), i gruppi senza nome (dal più grande) o quelli nascosti. */
    public ElencoPersone elenco(Tipo tipo, int limite) {
        List<Persona> persone = mongo.find(new Query(switch (tipo) {
            case NOMINATE -> Criteria.where("nome").ne(null).and("nascosta").is(false);
            case GRUPPI -> Criteria.where("nome").is(null).and("nascosta").is(false);
            case NASCOSTE -> Criteria.where("nascosta").is(true);
        }), Persona.class);
        Map<String, Long> volti = conteggi("personaId", persone.stream().map(Persona::getId).toList());
        Map<String, Long> proposte = tipo == Tipo.NOMINATE ? conteggiProposte(volti.keySet()) : Map.of();
        long minimo = tipo == Tipo.GRUPPI ? properties.minimoGruppo() : 0;
        Comparator<VocePersona> ordine = tipo == Tipo.GRUPPI
                ? Comparator.comparingLong(VocePersona::volti).reversed().thenComparing(VocePersona::id)
                : Comparator.comparing((VocePersona p) -> p.nome() == null ? "" : p.nome(), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Comparator.comparingLong(VocePersona::volti).reversed());
        List<VocePersona> tutte = persone.stream()
                .map(p -> voce(p, volti.getOrDefault(p.getId(), 0L), proposte.getOrDefault(p.getId(), 0L)))
                .filter(p -> p.volti() >= minimo && (p.volti() > 0 || p.nome() != null))
                .sorted(ordine)
                .toList();
        List<VocePersona> pagina = tutte.subList(0, Math.min(tutte.size(), Math.max(limite, 1)));
        return new ElencoPersone(conCopertine(pagina), tutte.size());
    }

    public VocePersona persona(String id) {
        Persona p = trova(id);
        long volti = conteggi("personaId", List.of(id)).getOrDefault(id, 0L);
        long proposte = conteggiProposte(List.of(id)).getOrDefault(id, 0L);
        return conCopertine(List.of(voce(p, volti, proposte))).getFirst();
    }

    /** I volti di una persona: i meno somiglianti alla persona prima (lì stanno quelli sbagliati). */
    public List<VoceVolto> volti(String personaId, int pagina, int dimensione) {
        trova(personaId);
        List<Volto> volti = mongo.find(Query.query(Criteria.where("personaId").is(personaId).and("nelCestino").is(false))
                .limit(5000), Volto.class);
        float[] media = media(volti);
        return volti.stream()
                .map(v -> new VoceVolto(v.getId(), v.getFotoId(), v.getPunteggio(), coseno(media, v.vettoreFloat()),
                        v.isManuale()))
                .sorted(Comparator.comparingDouble((VoceVolto v) -> v.somiglianza() == null ? 1 : v.somiglianza())
                        .thenComparing(VoceVolto::id))
                .skip((long) pagina * dimensione)
                .limit(dimensione)
                .toList();
    }

    /** I volti proposti per una persona, dal più somigliante. */
    public List<VoceVolto> proposte(String personaId, int limite) {
        trova(personaId);
        Query query = Query.query(proposteDi(personaId))
                .with(Sort.by(Sort.Order.desc("somiglianzaProposta"))).limit(limite);
        query.fields().exclude("vettore");
        return mongo.find(query, Volto.class).stream()
                .map(v -> new VoceVolto(v.getId(), v.getFotoId(), v.getPunteggio(), v.getSomiglianzaProposta(), v.isManuale()))
                .toList();
    }

    /**
     * Gli altri gruppi (o persone) che somigliano a questa: i vicini dei suoi
     * volti migliori, contati per persona. Serve a unire i pezzi di una
     * stessa persona finiti in gruppi diversi (da piccola e da grande, con e
     * senza occhiali). Le nascoste non si propongono, salvo a un'altra
     * nascosta (due gruppi di sconosciuti da unire).
     */
    public List<PersonaSimile> simili(String personaId, int limite) {
        Persona aperta = trova(personaId);
        List<Volto> campione = mongo.find(Query.query(Criteria.where("personaId").is(personaId).and("nelCestino").is(false)
                .and("aggiunto").ne(true)).with(Sort.by(Sort.Order.desc("punteggio"))).limit(VOLTI_PER_SIMILI), Volto.class);
        if (campione.isEmpty()) {
            return List.of();
        }
        List<List<Vicino>> vicini = visione.vicini(campione.stream().map(Volto::vettoreFloat).toList(),
                properties.vicini() * 2, properties.sogliaProposta());
        Map<String, Double> migliori = new HashMap<>();
        vicini.forEach(l -> l.forEach(x -> migliori.merge(x.id(), x.punteggio(), Math::max)));
        if (migliori.isEmpty()) {
            return List.of();
        }
        Query query = Query.query(Criteria.where("_id").in(migliori.keySet()).and("personaId").nin(personaId, null)
                .and("nelCestino").is(false));
        query.fields().include("personaId");
        Map<String, double[]> perPersona = new HashMap<>();
        for (Volto v : mongo.find(query, Volto.class)) {
            double[] s = perPersona.computeIfAbsent(v.getPersonaId(), k -> new double[2]);
            s[0] = Math.max(s[0], migliori.get(v.getId()));
            s[1]++;
        }
        Map<String, Persona> persone = new HashMap<>();
        mongo.find(Query.query(Criteria.where("_id").in(perPersona.keySet())), Persona.class)
                .forEach(p -> persone.put(p.getId(), p));
        Map<String, Long> volti = conteggi("personaId", persone.keySet());
        List<PersonaSimile> simili = perPersona.entrySet().stream()
                .filter(e -> persone.containsKey(e.getKey()))
                .filter(e -> aperta.isNascosta() || !persone.get(e.getKey()).isNascosta())
                .sorted(Comparator.comparingDouble((Map.Entry<String, double[]> e) -> e.getValue()[1]).reversed()
                        .thenComparing(e -> -e.getValue()[0]))
                .limit(limite)
                .map(e -> new PersonaSimile(voce(persone.get(e.getKey()), volti.getOrDefault(e.getKey(), 0L), 0),
                        e.getValue()[0], (int) e.getValue()[1]))
                .toList();
        List<VocePersona> conCopertina = conCopertine(simili.stream().map(PersonaSimile::persona).toList());
        List<PersonaSimile> risultato = new ArrayList<>();
        for (int i = 0; i < simili.size(); i++) {
            risultato.add(new PersonaSimile(conCopertina.get(i), simili.get(i).somiglianza(), simili.get(i).vicini()));
        }
        return risultato;
    }

    /**
     * I volti di una foto, con il nome della persona: da sinistra a destra, poi
     * le persone aggiunte a mano (senza riquadro). Quelli delle persone
     * nascoste non ci sono (né riquadro né "Chi è?"), e una proposta verso una
     * nascosta non si mostra: nascondere vale anche nel visore. Nemmeno una
     * proposta verso chi è già su un altro volto della foto.
     */
    public List<VoltoInFoto> dellaFoto(String fotoId) {
        Query query = Query.query(Criteria.where("fotoId").is(fotoId));
        query.fields().exclude("vettore");
        List<Volto> volti = new ArrayList<>(mongo.find(query, Volto.class));
        volti.sort(Comparator.comparing((Volto v) -> v.getRiquadro() == null)
                .thenComparingDouble(v -> v.getRiquadro() == null ? 0 : v.getRiquadro().x())
                .thenComparing(Volto::getId));
        Set<String> ids = new HashSet<>();
        volti.forEach(v -> {
            if (v.getPersonaId() != null) {
                ids.add(v.getPersonaId());
            }
            if (v.getProposta() != null) {
                ids.add(v.getProposta());
            }
        });
        Map<String, Persona> persone = new HashMap<>();
        if (!ids.isEmpty()) {
            mongo.find(Query.query(Criteria.where("_id").in(ids)), Persona.class).forEach(p -> persone.put(p.getId(), p));
        }
        return volti.stream().filter(v -> {
            Persona p = v.getPersonaId() == null ? null : persone.get(v.getPersonaId());
            return p == null || !p.isNascosta();
        }).map(v -> {
            Persona p = v.getPersonaId() == null ? null : persone.get(v.getPersonaId());
            Persona proposta = v.getProposta() == null ? null : persone.get(v.getProposta());
            if (proposta != null && (proposta.isNascosta() || suUnAltroVolto(volti, v, proposta.getId()))) {
                proposta = null;
            }
            return new VoltoInFoto(v.getId(), v.getRiquadro(), p == null ? null : p.getId(),
                    p == null ? null : p.getNome(),
                    proposta == null ? null : proposta.getId(), proposta == null ? null : proposta.getNome(),
                    v.isAggiunto());
        }).toList();
    }

    /** Questa persona è su un altro volto vero (non aggiunto) di questi? */
    private static boolean suUnAltroVolto(List<Volto> volti, Volto v, String personaId) {
        return volti.stream().anyMatch(x -> !x.getId().equals(v.getId()) && !x.isAggiunto()
                && personaId.equals(x.getPersonaId()));
    }

    public Volto volto(String id) {
        Volto v = mongo.findById(id, Volto.class);
        if (v == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Volto non trovato");
        }
        return v;
    }

    // ------------------------------------------------------------ a mano

    /** Dà (o cambia, o toglie con un nome vuoto) il nome: un gruppo diventa una persona. */
    public synchronized VocePersona nomina(String id, String nome) {
        Persona p = trova(id);
        String pulito = pulisciNome(nome);
        if (pulito != null) {
            Persona altra = conNome(pulito);
            if (altra != null && !altra.getId().equals(id)) {
                throw new NomeGiaUsato(altra);
            }
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                pulito == null ? new Update().unset("nome").unset("nominataIl")
                        : new Update().set("nome", pulito).set("nominataIl", clock.instant()),
                Persona.class);
        if (pulito == null) {
            // Tornata gruppo: le proposte per lei non hanno più senso.
            mongo.updateMulti(Query.query(Criteria.where("proposta").is(p.getId())),
                    new Update().unset("proposta").unset("somiglianzaProposta"), Volto.class);
        }
        return persona(id);
    }

    /**
     * "Nascondi" / "Mostra di nuovo": vale per tutta la famiglia, come il nome.
     * Resta un gruppo: i volti simili che arrivano dopo ci finiscono dentro e
     * restano nascosti con lei ({@link #migliore} tratta una nascosta come un
     * gruppo senza nome). Le foto restano dove sono, si vedono come prima.
     */
    public synchronized VocePersona nascondi(String id, boolean nascosta) {
        trova(id);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("nascosta", nascosta), Persona.class);
        return persona(id);
    }

    /**
     * Unisce le persone {@code da} in {@code in}: volti, proposte e foto
     * passano a lei, le altre spariscono. Il nome resta quello di {@code in};
     * se non ce l'ha, prende il primo delle altre.
     *
     * <p>Due persone che stanno insieme in una foto: dopo l'unione la foto ne
     * ha una sola, quindi un volto solo la tiene ({@link #risolviDoppioni}: un
     * volto deciso a mano su uno automatico, se no il più somigliante) e gli
     * altri tornano senza persona. Se in una foto sono su due volti veri
     * decisi entrambi a mano, l'unione non si fa (409): qualcuno ha detto che
     * sono due persone, prima si corregge uno dei due volti.
     */
    public synchronized VocePersona unisci(String in, Collection<String> da) {
        Persona destinazione = trova(in);
        String nome = destinazione.getNome();
        String copertina = destinazione.getCopertina();
        List<Persona> altre = new ArrayList<>();
        for (String id : new LinkedHashSet<>(da)) {
            if (!id.equals(in)) {
                altre.add(trova(id));
            }
        }
        controllaUnione(destinazione, altre);
        for (Persona altra : altre) {
            String id = altra.getId();
            mongo.updateMulti(Query.query(Criteria.where("personaId").is(id)), new Update().set("personaId", in), Volto.class);
            mongo.updateMulti(Query.query(Criteria.where("proposta").is(id)), new Update().set("proposta", in), Volto.class);
            mongo.updateMulti(Query.query(Criteria.where("esclusi").is(id)), new Update().addToSet("esclusi", in), Volto.class);
            mongo.updateMulti(Query.query(Criteria.where("esclusi").is(id)), new Update().pull("esclusi", id), Volto.class);
            // Le foto: prima la persona nuova, poi via la vecchia (Mongo non fa le due cose sullo stesso campo insieme).
            mongo.updateMulti(Query.query(Criteria.where(FOTO_PERSONE).is(id)), new Update().addToSet(FOTO_PERSONE, in),
                    Foto.class);
            mongo.updateMulti(Query.query(Criteria.where(FOTO_PERSONE).is(id)), new Update().pull(FOTO_PERSONE, id),
                    Foto.class);
            if (nome == null && altra.getNome() != null) {
                nome = altra.getNome();
            }
            if (copertina == null) {
                copertina = altra.getCopertina();
            }
            mongo.remove(altra);
        }
        // I volti che ora sono suoi non le si propongono più.
        mongo.updateMulti(Query.query(Criteria.where("personaId").is(in).and("proposta").is(in)),
                new Update().unset("proposta").unset("somiglianzaProposta"), Volto.class);
        Update update = new Update();
        if (nome != null && !nome.equals(destinazione.getNome())) {
            update.set("nome", nome).set("nominataIl", clock.instant());
        }
        if (copertina != null) {
            update.set("copertina", copertina);
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(in)), update, Persona.class);
        }
        // Le foto dove erano insieme: ora una volta sola. E non la si propone dove c'è già.
        risolviDoppioni(Criteria.where("personaId").is(in), Set.of());
        togliProposteDeiPresenti(Criteria.where("proposta").is(in));
        return persona(in);
    }

    /**
     * "Non è lei": il volto esce dalla persona e non ci torna da solo. Una
     * persona aggiunta a mano senza volto se ne va e basta: un volto vuoto
     * senza riquadro non serve a niente.
     */
    public synchronized void togliVolto(String voltoId) {
        Volto v = volto(voltoId);
        if (v.isAggiunto()) {
            scartaVolto(voltoId);
            return;
        }
        String persona = v.getPersonaId();
        if (persona == null) {
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(voltoId)), new Update().unset("personaId")
                .unset("proposta").unset("somiglianzaProposta").set("manuale", true).addToSet("esclusi", persona),
                Volto.class);
        aggiornaFoto(List.of(v.getFotoId()));
        dopoTolto(persona, voltoId);
    }

    /** Il volto è di questa persona (una proposta confermata, o spostato a mano). */
    public synchronized void assegnaVolto(String voltoId, String personaId) {
        spostaVolto(volto(voltoId), trova(personaId));
    }

    /**
     * Un volto solo, a mano, a un'altra persona: il suo gruppo o la persona di
     * prima restano come sono, e quella di prima finisce fra gli
     * {@code esclusi} ("non è lei"), così non ci torna da sola. Il volto è
     * {@code manuale}: il raggruppamento non lo tocca più.
     *
     * <p>Se la persona è già su un altro volto automatico della foto, la mano
     * vince: quello torna senza persona. Su un altro volto deciso a mano, 409
     * ({@link #controllaPosto}).
     */
    private void spostaVolto(Volto v, Persona p) {
        controllaPosto(v, p);
        String prima = v.getPersonaId();
        assegnaVolti(List.of(v), p);
        if (prima != null && !prima.equals(p.getId())) {
            // Un altro update: Mongo non fa pull (in assegnaVolti) e addToSet sullo stesso campo insieme.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(v.getId())), new Update().addToSet("esclusi", prima),
                    Volto.class);
        }
        sistemaFoto(List.of(v.getFotoId()), Set.of(v.getId()));
    }

    /**
     * La persona può andare a questo volto? No se è già su un altro volto vero
     * della foto deciso a mano: due decisioni a mano che si contraddicono non
     * si sciolgono da sole. Una persona aggiunta senza volto non prende chi è
     * già nella foto in nessun modo.
     */
    private void controllaPosto(Volto v, Persona p) {
        Criteria altri = Criteria.where("fotoId").is(v.getFotoId()).and("_id").ne(v.getId()).and("personaId").is(p.getId());
        if (v.isAggiunto()) {
            if (mongo.exists(Query.query(altri), Volto.class)) {
                throw new PersonaGiaNellaFoto(chi(p) + " è già in questa foto", p, List.of(v.getFotoId()));
            }
            return;
        }
        if (mongo.exists(Query.query(altri.and("manuale").is(true).and("aggiunto").ne(true)), Volto.class)) {
            throw new PersonaGiaNellaFoto(chi(p) + " è già in questa foto, su un altro volto: una persona c'è una volta"
                    + " sola per foto. Correggi prima quel volto (✎ → Non è nessuno).", p, List.of(v.getFotoId()));
        }
    }

    /**
     * Prima di unire: in nessuna foto queste persone sono su due volti veri
     * decisi entrambi a mano, se no 409 con le foto da correggere.
     */
    private void controllaUnione(Persona in, List<Persona> altre) {
        if (altre.isEmpty()) {
            return;
        }
        List<String> ids = new ArrayList<>();
        ids.add(in.getId());
        altre.forEach(p -> ids.add(p.getId()));
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("personaId").in(ids).and("manuale").is(true).and("aggiunto").ne(true)),
                Aggregation.group("fotoId").addToSet("personaId").as("persone"),
                Aggregation.match(Criteria.where("persone.1").exists(true)),
                Aggregation.limit(MASSIMO_FOTO_IN_ERRORE));
        List<String> foto = mongo.aggregate(aggregazione, Volto.class, Document.class).getMappedResults().stream()
                .map(d -> d.getString("_id")).toList();
        if (!foto.isEmpty()) {
            throw new PersonaGiaNellaFoto("Non si possono unire: " + (foto.size() == 1 ? "in una foto sono" : "in "
                    + (foto.size() == MASSIMO_FOTO_IN_ERRORE ? "almeno " : "") + foto.size() + " foto sono")
                    + " su due volti diversi decisi a mano, e una persona c'è una volta sola per foto."
                    + " Correggi prima quei volti.", in, foto);
        }
    }

    private static String chi(Persona p) {
        return p.getNome() != null ? p.getNome() : "Questa persona";
    }

    /**
     * "Non è un volto" (un disegno, un animale, una macchia): il volto se ne va
     * da Mongo e dall'indice dei vicini, la sua persona ricalcola foto e
     * copertina. La foto resta segnata come fatta ({@code voltiCercati}),
     * quindi il lavoro non lo ritrova.
     */
    public synchronized void scartaVolto(String voltoId) {
        Volto v = volto(voltoId);
        mongo.remove(Query.query(Criteria.where("_id").is(voltoId)), Volto.class);
        aggiornaFoto(List.of(v.getFotoId()));
        if (v.getPersonaId() != null) {
            dopoTolto(v.getPersonaId(), voltoId);
        }
        if (v.isAggiunto()) {
            // Nell'indice dei vicini non c'è mai stato.
            return;
        }
        try {
            visione.togli(List.of(voltoId));
        } catch (RuntimeException e) {
            // Resta un vettore in più nell'indice: un vicino che in Mongo non c'è si scarta (assegna).
            log.warn("Volto {} non tolto dall'indice: {}", voltoId, e.getMessage());
        }
    }

    private void assegnaVolti(List<Volto> volti, Persona p) {
        if (volti.isEmpty()) {
            return;
        }
        List<String> ids = volti.stream().map(Volto::getId).toList();
        mongo.updateMulti(Query.query(Criteria.where("_id").in(ids)), new Update().set("personaId", p.getId())
                .unset("proposta").unset("somiglianzaProposta").set("manuale", true).pull("esclusi", p.getId()),
                Volto.class);
        aggiornaFoto(volti.stream().map(Volto::getFotoId).toList());
        volti.stream().filter(v -> v.getPersonaId() != null && !v.getPersonaId().equals(p.getId()))
                .forEach(v -> dopoTolto(v.getPersonaId(), v.getId()));
        if (p.getCopertina() == null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(p.getId())), new Update().set("copertina", ids.getFirst()),
                    Persona.class);
        }
    }

    /**
     * "Chi è?" dal visore, su un volto: se c'è già una persona con quel nome
     * il volto va a lei; se il volto sta in un gruppo senza nome, il gruppo
     * prende il nome; altrimenti nasce una persona nuova con questo volto.
     * Da una persona con nome ("È un'altra persona…") si sposta solo questo
     * volto ({@link #spostaVolto}). Il nome di una nascosta vale: il volto va
     * a lei e resta nascosto con lei.
     */
    public synchronized VocePersona nominaVolto(String voltoId, String nome) {
        Volto v = volto(voltoId);
        String pulito = pulisciNome(nome);
        if (pulito == null) {
            throw new IllegalArgumentException("Manca il nome");
        }
        Persona esistente = conNome(pulito);
        if (esistente != null) {
            spostaVolto(v, esistente);
            return persona(esistente.getId());
        }
        Persona attuale = v.getPersonaId() == null ? null : mongo.findById(v.getPersonaId(), Persona.class);
        if (attuale != null && !attuale.conNome()) {
            return nomina(attuale.getId(), pulito);
        }
        Persona nuova = new Persona();
        nuova.setNome(pulito);
        nuova.setCopertina(voltoId);
        nuova.setCreataIl(clock.instant());
        nuova.setNominataIl(clock.instant());
        nuova = mongo.insert(nuova);
        spostaVolto(v, nuova);
        return persona(nuova.getId());
    }

    /** "Non è lei" su una proposta: resta senza, e quella persona non gliela si ripropone. */
    public synchronized void rifiutaProposta(String voltoId) {
        Volto v = volto(voltoId);
        if (v.getProposta() != null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(voltoId)), new Update().addToSet("esclusi", v.getProposta())
                    .unset("proposta").unset("somiglianzaProposta"), Volto.class);
        }
    }

    /**
     * Le proposte di una persona diventano sue, una per foto (la più
     * somigliante) e non dove lei è già su un volto deciso a mano: una persona
     * c'è una volta sola per foto. Le altre proposte di quelle foto se ne
     * vanno ({@link #sistemaFoto}). @return quante
     */
    public synchronized int confermaProposte(String personaId) {
        Persona p = trova(personaId);
        Query query = Query.query(proposteDi(personaId));
        query.fields().include("fotoId", "personaId", "somiglianzaProposta");
        List<Volto> volti = mongo.find(query, Volto.class);
        if (volti.isEmpty()) {
            return 0;
        }
        Set<String> foto = new HashSet<>();
        volti.forEach(v -> foto.add(v.getFotoId()));
        Query giaSua = Query.query(Criteria.where("fotoId").in(foto).and("personaId").is(personaId)
                .and("manuale").is(true).and("aggiunto").ne(true));
        giaSua.fields().include("fotoId");
        Set<String> conLei = new HashSet<>();
        mongo.find(giaSua, Volto.class).forEach(v -> conLei.add(v.getFotoId()));
        Map<String, Volto> perFoto = new LinkedHashMap<>();
        for (Volto v : volti) {
            if (!conLei.contains(v.getFotoId())) {
                perFoto.merge(v.getFotoId(), v, (a, b) -> somiglianza(a) >= somiglianza(b) ? a : b);
            }
        }
        List<Volto> scelti = List.copyOf(perFoto.values());
        assegnaVolti(scelti, p);
        sistemaFoto(foto, scelti.stream().map(Volto::getId).collect(Collectors.toSet()));
        return scelti.size();
    }

    private static double somiglianza(Volto v) {
        return v.getSomiglianzaProposta() == null ? 0 : v.getSomiglianzaProposta();
    }

    /**
     * "+ Aggiungi persona" dal visore: la persona è nella foto anche se visione
     * non ha trovato il suo volto (di spalle, piccola, tagliata). Diventa un
     * {@link Volto} {@code aggiunto}: niente riquadro né vettore, deciso a mano.
     * Un nome che c'è già va a quella persona (anche nascosta, come "Chi è?"),
     * uno nuovo fa una persona nuova. Se è già nella foto, 409.
     *
     * @param nelCestino la foto non conta per le persone (cestino o privata): il volto nemmeno
     */
    public synchronized VocePersona aggiungiAllaFoto(String fotoId, boolean nelCestino, String personaId, String nome) {
        Persona p;
        if (personaId != null && !personaId.isBlank()) {
            p = trova(personaId);
        } else {
            String pulito = pulisciNome(nome);
            if (pulito == null) {
                throw new IllegalArgumentException("Manca il nome");
            }
            p = conNome(pulito);
            if (p == null) {
                p = new Persona();
                p.setNome(pulito);
                p.setCreataIl(clock.instant());
                p.setNominataIl(clock.instant());
            }
        }
        if (p.getId() != null && mongo.exists(Query.query(Criteria.where("fotoId").is(fotoId).and("personaId").is(p.getId())),
                Volto.class)) {
            throw new PersonaGiaNellaFoto(chi(p) + " è già in questa foto", p, List.of(fotoId));
        }
        if (p.getId() == null) {
            p = mongo.insert(p);
        }
        Volto v = new Volto();
        v.setFotoId(fotoId);
        v.setPersonaId(p.getId());
        v.setManuale(true);
        v.setAggiunto(true);
        v.setNelCestino(nelCestino);
        v.setTrovatoIl(clock.instant());
        v = mongo.insert(v);
        if (p.getCopertina() == null) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(p.getId())), new Update().set("copertina", v.getId()),
                    Persona.class);
        }
        aggiornaFoto(List.of(fotoId));
        return persona(p.getId());
    }

    /** Il volto da mostrare nella griglia per questa persona (deve essere suo). */
    public synchronized void copertina(String personaId, String voltoId) {
        trova(personaId);
        if (!personaId.equals(volto(voltoId).getPersonaId())) {
            throw new IllegalArgumentException("Il volto non è di questa persona");
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(personaId)), new Update().set("copertina", voltoId),
                Persona.class);
    }

    /** True se c'è ed è nascosta. */
    public boolean nascosta(String id) {
        Persona p = id == null ? null : mongo.findById(id, Persona.class);
        return p != null && p.isNascosta();
    }

    // ------------------------------------------------------------ aiuti

    /** Gli id delle persone nascoste (poche: sconosciuti, folla). */
    private List<String> idNascoste() {
        Query query = Query.query(Criteria.where("nascosta").is(true));
        query.fields().include("_id");
        return mongo.find(query, Persona.class).stream().map(Persona::getId).toList();
    }

    /** I volti proposti a questa persona, fuori dal cestino e non di una nascosta: lo sconosciuto resta nascosto. */
    private Criteria proposteDi(String personaId) {
        return Criteria.where("proposta").is(personaId).and("nelCestino").is(false).and("personaId").nin(idNascoste());
    }

    private Map<String, Long> conteggiProposte(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return conteggi(Criteria.where("proposta").in(ids).and("nelCestino").is(false).and("personaId").nin(idNascoste()),
                "proposta");
    }

    private Persona trova(String id) {
        Persona p = id == null ? null : mongo.findById(id, Persona.class);
        if (p == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Persona non trovata");
        }
        return p;
    }

    private Persona conNome(String nome) {
        return mongo.findOne(Query.query(Criteria.where("nome")
                .regex("^" + Pattern.quote(nome) + "$", "i")), Persona.class);
    }

    private static String pulisciNome(String nome) {
        if (nome == null || nome.isBlank()) {
            return null;
        }
        String pulito = nome.strip().replaceAll("\\s+", " ");
        if (pulito.length() > NOME_MASSIMO) {
            throw new IllegalArgumentException("Nome troppo lungo: al massimo " + NOME_MASSIMO + " caratteri");
        }
        return pulito;
    }

    private static VocePersona voce(Persona p, long volti, long proposte) {
        return new VocePersona(p.getId(), p.getNome(), p.isNascosta(), volti, proposte, p.getCopertina());
    }

    /** Quanti volti (fuori dal cestino) per ciascuno di questi id, contati su {@code campo}. */
    private Map<String, Long> conteggi(String campo, Collection<String> ids) {
        return ids.isEmpty() ? Map.of() : conteggi(Criteria.where(campo).in(ids).and("nelCestino").is(false), campo);
    }

    private Map<String, Long> conteggi(Criteria criteri, String campo) {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(criteri),
                Aggregation.group(campo).count().as("n"));
        Map<String, Long> conteggi = new HashMap<>();
        mongo.aggregate(aggregazione, Volto.class, Document.class).getMappedResults()
                .forEach(d -> conteggi.put(d.getString("_id"), ((Number) d.get("n")).longValue()));
        return conteggi;
    }

    /**
     * La copertina di ognuna: quella salvata se è ancora un suo volto fuori dal
     * cestino, se no il suo volto migliore (e la si salva).
     */
    private List<VocePersona> conCopertine(List<VocePersona> voci) {
        List<String> copertine = voci.stream().map(VocePersona::copertina).filter(c -> c != null).toList();
        Map<String, Volto> valide = new HashMap<>();
        if (!copertine.isEmpty()) {
            Query query = Query.query(Criteria.where("_id").in(copertine).and("nelCestino").is(false));
            query.fields().include("personaId");
            mongo.find(query, Volto.class).forEach(v -> valide.put(v.getId(), v));
        }
        List<VocePersona> risultato = new ArrayList<>(voci.size());
        for (VocePersona p : voci) {
            Volto c = p.copertina() == null ? null : valide.get(p.copertina());
            if (c != null && p.id().equals(c.getPersonaId())) {
                risultato.add(p);
                continue;
            }
            Query migliore = Query.query(Criteria.where("personaId").is(p.id()).and("nelCestino").is(false))
                    .with(Sort.by(Sort.Order.desc("punteggio"))).limit(1);
            migliore.fields().include("_id");
            Volto v = mongo.findOne(migliore, Volto.class);
            String nuova = v == null ? null : v.getId();
            if (nuova != null) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(p.id())), new Update().set("copertina", nuova),
                        Persona.class);
            }
            risultato.add(new VocePersona(p.id(), p.nome(), p.nascosta(), p.volti(), p.proposte(), nuova));
        }
        return risultato;
    }

    private static float[] media(List<Volto> volti) {
        float[] somma = null;
        for (Volto v : volti) {
            float[] x = v.vettoreFloat();
            if (x.length == 0) {
                // Una persona aggiunta a mano: niente vettore, non sposta il centro.
                continue;
            }
            if (somma == null) {
                somma = new float[x.length];
            }
            if (x.length != somma.length) {
                continue;
            }
            for (int i = 0; i < x.length; i++) {
                somma[i] += x[i];
            }
        }
        return somma;
    }

    private static Double coseno(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length || a.length == 0) {
            return null;
        }
        double ab = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) {
            ab += a[i] * b[i];
            aa += a[i] * a[i];
            bb += b[i] * b[i];
        }
        return aa == 0 || bb == 0 ? null : ab / Math.sqrt(aa * bb);
    }

    Instant adesso() {
        return clock.instant();
    }
}
