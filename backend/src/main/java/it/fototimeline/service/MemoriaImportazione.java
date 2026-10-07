package it.fototimeline.service;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Quello che l'importazione automatica ricorda dei file che non è riuscita a
 * prendere ({@link FileScartato}): così un file cifrato o rotto si legge una
 * volta sola, non a ogni giro (dal cloud vuol dire scaricarlo ogni volta).
 */
@Service
public class MemoriaImportazione {

    private final MongoTemplate mongo;
    private final Clock clock;

    public MemoriaImportazione(MongoTemplate mongo, Optional<Clock> clock) {
        this.mongo = mongo;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Gli scartati che stanno sotto {@code cartella}, per percorso. */
    public Map<String, FileScartato> sotto(Path cartella) {
        return mongo.find(Query.query(Criteria.where("_id").regex("^" + Pattern.quote(
                cartella.toAbsolutePath().normalize() + "/"))), FileScartato.class)
                .stream().collect(Collectors.toMap(FileScartato::percorso, Function.identity()));
    }

    public void ricorda(Path file, long dimensione, Instant modificatoIl, String motivo) {
        String percorso = file.toAbsolutePath().normalize().toString();
        FileScartato prima = mongo.findById(percorso, FileScartato.class);
        mongo.save(new FileScartato(percorso, dimensione, modificatoIl, motivo,
                prima != null && motivo.equals(prima.motivo()) ? prima.quando() : clock.instant()));
    }

    public void dimentica(Path file) {
        mongo.remove(Query.query(Criteria.where("_id").is(file.toAbsolutePath().normalize().toString())),
                FileScartato.class);
    }

    /** Toglie i ricordi dei file che non ci sono più (spostati a mano, cancellati). */
    public void dimenticaTranne(Path cartella, Collection<String> presenti) {
        List<String> via = sotto(cartella).keySet().stream().filter(p -> !presenti.contains(p)).toList();
        if (!via.isEmpty()) {
            mongo.remove(Query.query(Criteria.where("_id").in(via)), FileScartato.class);
        }
    }

    /** Tutti, dal più recente: l'elenco che l'app mostra. */
    public List<FileScartato> elenco() {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "quando")), FileScartato.class);
    }

    /** "Riprova": al prossimo giro si rileggono tutti. */
    public long dimenticaTutti() {
        return mongo.remove(new Query(), FileScartato.class).getDeletedCount();
    }
}
