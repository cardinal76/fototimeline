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

import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
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
 * <p>Ogni modifica passa da qui ed è {@code synchronized}: il lavoro che
 * assegna e chi unisce due persone non si pestano i piedi. Le foto tengono
 * gli id delle loro persone in {@code Foto.persone} ({@link #aggiornaFoto}).
 */
@Service
public class Persone {

    static final String FOTO_PERSONE = "persone";
    private static final int NOME_MASSIMO = 80;
    private static final int VOLTI_PER_SIMILI = 16;

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

    /** Un volto nel visore: dove sta e di chi è (il nome solo per chi è entrato nell'app). */
    public record VoltoInFoto(String id, Riquadro riquadro, String personaId, String nome, boolean nascosta,
            String proposta, String nomeProposta) {
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
        return foto;
    }

    private boolean assegna(Volto v, List<Vicino> vicini, Set<String> foto) {
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
        query.fields().include("personaId", "fotoId");
        Map<String, double[]> perPersona = new HashMap<>();
        List<Volto> liberi = new ArrayList<>();
        for (Volto altro : mongo.find(query, Volto.class)) {
            double s = somiglianze.get(altro.getId());
            if (altro.getPersonaId() != null) {
                double[] stat = perPersona.computeIfAbsent(altro.getPersonaId(), k -> new double[2]);
                stat[0] = Math.max(stat[0], s);
                stat[1] += s;
            } else if (s >= properties.sogliaGruppo()) {
                liberi.add(altro);
            }
        }
        Map<String, Persona> persone = new HashMap<>();
        if (!perPersona.isEmpty()) {
            mongo.find(Query.query(Criteria.where("_id").in(perPersona.keySet())), Persona.class)
                    .forEach(p -> persone.put(p.getId(), p));
        }
        // Una persona con nome (e non nascosta) sopra la soglia sicura; se no un gruppo senza nome o nascosto.
        String scelta = migliore(perPersona, persone, v, true, properties.sogliaSicura(), Comparator.comparingDouble(s -> s[1]));
        if (scelta == null) {
            scelta = migliore(perPersona, persone, v, false, properties.sogliaGruppo(), Comparator.comparingDouble(s -> s[1]));
        }
        String proposta = null;
        if (scelta == null || !persone.get(scelta).conNome() || persone.get(scelta).isNascosta()) {
            proposta = migliore(perPersona, persone, v, true, properties.sogliaProposta(),
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
            List<String> ids = liberi.stream().map(Volto::getId).toList();
            mongo.updateMulti(Query.query(Criteria.where("_id").in(ids).and("personaId").is(null)),
                    new Update().set("personaId", gruppo.getId()), Volto.class);
            liberi.forEach(l -> foto.add(l.getFotoId()));
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(v.getId())), update, Volto.class);
        }
        return cambiata;
    }

    /** Fra le persone dei vicini sopra la soglia, quella che vince secondo {@code ordine}; null se nessuna. */
    private static String migliore(Map<String, double[]> perPersona, Map<String, Persona> persone, Volto v,
            boolean conNome, double soglia, Comparator<double[]> ordine) {
        return perPersona.entrySet().stream()
                .filter(e -> persone.containsKey(e.getKey()) && !v.getEsclusi().contains(e.getKey()))
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

    // ------------------------------------------------------------ cestino ed eliminazione

    /** La foto è nel cestino: i suoi volti non contano più. @return gli id da togliere dall'indice */
    synchronized List<String> nelCestino(String fotoId) {
        List<String> ids = idVolti(fotoId);
        if (!ids.isEmpty()) {
            mongo.updateMulti(Query.query(Criteria.where("fotoId").is(fotoId)), new Update().set("nelCestino", true),
                    Volto.class);
        }
        return ids;
    }

    /** Tornata dal cestino: i volti tornano com'erano. @return i loro vettori, da rimettere nell'indice */
    synchronized Map<String, float[]> ripristinata(String fotoId) {
        mongo.updateMulti(Query.query(Criteria.where("fotoId").is(fotoId)), new Update().set("nelCestino", false),
                Volto.class);
        Map<String, float[]> vettori = new LinkedHashMap<>();
        mongo.find(Query.query(Criteria.where("fotoId").is(fotoId)), Volto.class)
                .forEach(v -> vettori.put(v.getId(), v.vettoreFloat()));
        return vettori;
    }

    /** Eliminata per sempre: i volti se ne vanno, e i gruppi senza nome rimasti vuoti pure. @return gli id tolti */
    synchronized List<String> eliminata(String fotoId) {
        Query query = Query.query(Criteria.where("fotoId").is(fotoId));
        query.fields().include("personaId");
        List<Volto> volti = mongo.find(query, Volto.class);
        if (volti.isEmpty()) {
            return List.of();
        }
        mongo.remove(Query.query(Criteria.where("fotoId").is(fotoId)), Volto.class);
        volti.stream().filter(v -> v.getPersonaId() != null)
                .forEach(v -> dopoTolto(v.getPersonaId(), v.getId()));
        return volti.stream().map(Volto::getId).toList();
    }

    private List<String> idVolti(String fotoId) {
        Query query = Query.query(Criteria.where("fotoId").is(fotoId));
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
        Map<String, Long> proposte = tipo == Tipo.NOMINATE ? conteggi("proposta", volti.keySet()) : Map.of();
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
        long proposte = conteggi("proposta", List.of(id)).getOrDefault(id, 0L);
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
        Query query = Query.query(Criteria.where("proposta").is(personaId).and("nelCestino").is(false))
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
     * senza occhiali).
     */
    public List<PersonaSimile> simili(String personaId, int limite) {
        trova(personaId);
        List<Volto> campione = mongo.find(Query.query(Criteria.where("personaId").is(personaId).and("nelCestino").is(false))
                .with(Sort.by(Sort.Order.desc("punteggio"))).limit(VOLTI_PER_SIMILI), Volto.class);
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

    /** I volti di una foto, con il nome della persona (le nascoste senza nome). */
    public List<VoltoInFoto> dellaFoto(String fotoId) {
        Query query = Query.query(Criteria.where("fotoId").is(fotoId)).with(Sort.by("riquadro.x"));
        query.fields().exclude("vettore");
        List<Volto> volti = mongo.find(query, Volto.class);
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
        return volti.stream().map(v -> {
            Persona p = v.getPersonaId() == null ? null : persone.get(v.getPersonaId());
            Persona proposta = v.getProposta() == null ? null : persone.get(v.getProposta());
            return new VoltoInFoto(v.getId(), v.getRiquadro(), p == null ? null : p.getId(),
                    p == null || p.isNascosta() ? null : p.getNome(), p != null && p.isNascosta(),
                    proposta == null ? null : proposta.getId(), proposta == null ? null : proposta.getNome());
        }).toList();
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

    public synchronized VocePersona nascondi(String id, boolean nascosta) {
        trova(id);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().set("nascosta", nascosta), Persona.class);
        return persona(id);
    }

    /**
     * Unisce le persone {@code da} in {@code in}: volti, proposte e foto
     * passano a lei, le altre spariscono. Il nome resta quello di {@code in};
     * se non ce l'ha, prende il primo delle altre.
     */
    public synchronized VocePersona unisci(String in, Collection<String> da) {
        Persona destinazione = trova(in);
        String nome = destinazione.getNome();
        String copertina = destinazione.getCopertina();
        for (String id : new LinkedHashSet<>(da)) {
            if (id.equals(in)) {
                continue;
            }
            Persona altra = trova(id);
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
        return persona(in);
    }

    /** "Non è lei": il volto esce dalla persona e non ci torna da solo. */
    public synchronized void togliVolto(String voltoId) {
        Volto v = volto(voltoId);
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
        assegnaVolti(List.of(volto(voltoId)), trova(personaId));
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
     */
    public synchronized VocePersona nominaVolto(String voltoId, String nome) {
        Volto v = volto(voltoId);
        String pulito = pulisciNome(nome);
        if (pulito == null) {
            throw new IllegalArgumentException("Manca il nome");
        }
        Persona esistente = conNome(pulito);
        if (esistente != null) {
            assegnaVolti(List.of(v), esistente);
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
        assegnaVolti(List.of(v), nuova);
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

    /** Tutte le proposte di una persona diventano sue. @return quante */
    public synchronized int confermaProposte(String personaId) {
        Persona p = trova(personaId);
        Query query = Query.query(Criteria.where("proposta").is(personaId).and("nelCestino").is(false));
        query.fields().include("fotoId", "personaId");
        List<Volto> volti = mongo.find(query, Volto.class);
        assegnaVolti(volti, p);
        return volti.size();
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

    // ------------------------------------------------------------ aiuti

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
        if (ids.isEmpty()) {
            return Map.of();
        }
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(Criteria.where(campo).in(ids).and("nelCestino").is(false)),
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
