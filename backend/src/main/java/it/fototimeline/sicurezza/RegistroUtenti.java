package it.fototimeline.sicurezza;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/** La collezione {@code utenti}: chi è entrato almeno una volta. */
@Service
public class RegistroUtenti {

    private final MongoTemplate mongo;
    private final ApplicationEventPublisher eventi;
    private final Clock clock;

    public RegistroUtenti(MongoTemplate mongo, ApplicationEventPublisher eventi, Optional<Clock> clock) {
        this.mongo = mongo;
        this.eventi = eventi;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Aggiunge l'utente o ne aggiorna nome, email, ruolo e ultimo accesso. */
    public void registra(String username, String nome, String email, boolean admin) {
        if (username == null || username.isBlank()) {
            return;
        }
        var adesso = clock.instant();
        mongo.upsert(Query.query(Criteria.where("_id").is(username)), new Update()
                .set("nome", nome == null || nome.isBlank() ? username : nome)
                .set("email", email)
                .set("admin", admin)
                .set("ultimoAccesso", adesso)
                .setOnInsert("primoAccesso", adesso), Utente.class);
        eventi.publishEvent(new UtenteRegistrato(username, admin));
    }

    /** Tutti, per nome. */
    public List<Utente> elenco() {
        return mongo.find(new Query().with(Sort.by("nome", "_id")), Utente.class);
    }

    /** username → nome, per mostrare "Caricata da Anna". */
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
