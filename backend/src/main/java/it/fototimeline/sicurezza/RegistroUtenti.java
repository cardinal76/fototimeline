package it.fototimeline.sicurezza;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** La collezione {@code utenti}: chi è entrato almeno una volta, o è stato creato dalla pagina Utenti. */
@Service
public class RegistroUtenti {

    private final MongoTemplate mongo;
    private final ApplicationEventPublisher eventi;
    private final Clock clock;
    /** username → da quando le sessioni di prima non valgono: letto a ogni richiesta, quindi in memoria. */
    private final Map<String, Instant> sessioniChiuse = new ConcurrentHashMap<>();
    private volatile boolean sessioniLette;

    public RegistroUtenti(MongoTemplate mongo, ApplicationEventPublisher eventi, Optional<Clock> clock) {
        this.mongo = mongo;
        this.eventi = eventi;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Aggiunge l'utente o ne aggiorna nome, email, ruolo e ultimo accesso. */
    public void registra(String username, String nome, String email, Ruolo ruolo) {
        if (username == null || username.isBlank()) {
            return;
        }
        var adesso = clock.instant();
        boolean admin = ruolo != null && ruolo.admin();
        mongo.upsert(Query.query(Criteria.where("_id").is(username)), new Update()
                .set("nome", nome == null || nome.isBlank() ? username : nome)
                .set("email", email)
                .set("admin", admin)
                .set("ruolo", ruolo)
                .set("ultimoAccesso", adesso)
                .unset("eliminatoIl")
                .setOnInsert("primoAccesso", adesso), Utente.class);
        eventi.publishEvent(new UtenteRegistrato(username, admin));
    }

    /**
     * Un utente creato o cambiato dalla pagina Utenti: nome, email e ruolo,
     * senza toccare gli accessi (non è entrato lui).
     */
    public void aggiorna(String username, String nome, String email, Ruolo ruolo) {
        mongo.upsert(Query.query(Criteria.where("_id").is(username)), new Update()
                .set("nome", nome == null || nome.isBlank() ? username : nome)
                .set("email", email)
                .set("admin", ruolo != null && ruolo.admin())
                .set("ruolo", ruolo)
                .unset("eliminatoIl"), Utente.class);
    }

    /** Tolto da Keycloak: la scheda resta (il nome in "Caricata da"), senza ruolo. */
    public void eliminato(String username) {
        mongo.upsert(Query.query(Criteria.where("_id").is(username)), new Update()
                .set("eliminatoIl", clock.instant())
                .set("admin", false)
                .set("ruolo", null), Utente.class);
    }

    /**
     * Da adesso le sessioni già aperte di questo utente non valgono più
     * ({@link SessioniChiuse}): rientrando, Keycloak dà il ruolo nuovo, o
     * nessun accesso se è disattivato.
     */
    public void chiudiSessioni(String username) {
        Instant adesso = clock.instant();
        sessioniChiuse.put(username, adesso);
        mongo.upsert(Query.query(Criteria.where("_id").is(username)),
                new Update().set("sessioniChiuseIl", adesso), Utente.class);
    }

    /** Da quando le sessioni di questo utente non valgono; null se valgono tutte. */
    public Instant sessioniChiuseIl(String username) {
        if (!sessioniLette) {
            leggiSessioniChiuse();
        }
        return sessioniChiuse.get(username);
    }

    private synchronized void leggiSessioniChiuse() {
        if (sessioniLette) {
            return;
        }
        for (Utente u : mongo.find(Query.query(Criteria.where("sessioniChiuseIl").ne(null)), Utente.class)) {
            sessioniChiuse.merge(u.username(), u.sessioniChiuseIl(), (a, b) -> a.isAfter(b) ? a : b);
        }
        sessioniLette = true;
    }

    /** Tutti, per nome. */
    public List<Utente> elenco() {
        return mongo.find(new Query().with(Sort.by("nome", "_id")), Utente.class);
    }

    public Optional<Utente> trova(String username) {
        return Optional.ofNullable(mongo.findById(username, Utente.class));
    }

    /** username → nome, per mostrare "Caricata da Anna" (anche degli utenti eliminati). */
    public Map<String, String> nomi() {
        return elenco().stream().collect(Collectors.toMap(Utente::username,
                u -> u.nome() != null ? u.nome() : u.username()));
    }

    /** Il primo amministratore entrato, se c'è. */
    public Optional<Utente> primoAdmin() {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("admin").is(true))
                .with(Sort.by("primoAccesso", "_id")).limit(1), Utente.class));
    }
}
