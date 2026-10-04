package it.fototimeline.backup;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;

/**
 * Copia dei metadati (date corrette, titoli, tag, album): gli originali sono
 * nel cloud, ma questi stanno solo in MongoDB su server2.
 *
 * <p>Un file {@code fototimeline-AAAA-MM-GG-HHmm.json.gz} con un documento per
 * riga in Extended JSON, che {@code mongoimport} ripristina così com'è
 * (DEPLOY.md, "Backup"). Finisce nel cloud, accanto alle foto, quindi si fa
 * solo col cloud montato: ogni ora si controlla se l'ultimo è più vecchio
 * dell'intervallo, così una notte da smontato si recupera appena lo si monta.
 */
@Service
public class BackupMetadati {

    private static final Logger log = LoggerFactory.getLogger(BackupMetadati.class);
    static final String PREFISSO = "fototimeline-";
    static final String SUFFISSO = ".json.gz";
    private static final DateTimeFormatter NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss");
    private static final JsonWriterSettings JSON = JsonWriterSettings.builder().outputMode(JsonMode.RELAXED).build();

    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final BackupProperties properties;
    private final Clock clock;

    public BackupMetadati(MongoTemplate mongo, ArchivioFile archivio, BackupProperties properties,
            Optional<Clock> clock) {
        this.mongo = mongo;
        this.archivio = archivio;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    public record Copia(String nome, long dimensione, Instant fattoIl) {
    }

    public Path cartella() {
        return properties.cartella() != null ? properties.cartella() : archivio.radice().resolve(".backup");
    }

    /** Ogni ora: se l'ultimo backup è vecchio e il cloud è montato, ne fa uno. */
    @Scheduled(fixedDelayString = "${fototimeline.backup.controllo:PT1H}",
            initialDelayString = "${fototimeline.backup.ritardo-iniziale:PT10M}")
    public void seNecessario() {
        if (!archivio.disponibile()) {
            return;
        }
        try {
            Optional<Copia> ultima = elenco().stream().findFirst();
            if (ultima.isEmpty() || ultima.get().fattoIl().plus(properties.intervallo()).isBefore(clock.instant())) {
                esegui();
            }
        } catch (Exception e) {
            log.warn("Backup dei metadati non riuscito: {}", e.getMessage());
        }
    }

    /** Fa subito un backup e toglie quelli oltre {@code tenere}. */
    public synchronized Copia esegui() throws IOException {
        archivio.verificaDisponibile();
        Path cartella = cartella();
        Files.createDirectories(cartella);
        String nome = PREFISSO + LocalDateTime.ofInstant(clock.instant(), ZoneId.systemDefault()).format(NOME) + SUFFISSO;
        Path temporaneo = cartella.resolve(nome + ".parziale");
        long documenti = 0;
        try (var uscita = new BufferedWriter(new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(temporaneo)), StandardCharsets.UTF_8));
                var cursore = mongo.getCollection(mongo.getCollectionName(Foto.class)).find().iterator()) {
            while (cursore.hasNext()) {
                Document d = cursore.next();
                uscita.write(d.toJson(JSON));
                uscita.newLine();
                documenti++;
            }
        }
        Path finale = cartella.resolve(nome);
        // Mai un backup a metà con il nome buono.
        Files.move(temporaneo, finale, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.info("Backup dei metadati: {} foto in {}", documenti, finale);
        pulisci();
        return copia(finale);
    }

    /** I backup presenti, dal più recente. */
    public List<Copia> elenco() throws IOException {
        Path cartella = cartella();
        if (!Files.isDirectory(cartella)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(cartella)) {
            return s.filter(BackupMetadati::eBackup)
                    .map(BackupMetadati::copia)
                    .sorted(Comparator.comparing(Copia::nome).reversed())
                    .toList();
        }
    }

    private void pulisci() throws IOException {
        List<Copia> copie = elenco();
        for (Copia vecchia : copie.subList(Math.min(properties.tenere(), copie.size()), copie.size())) {
            Files.deleteIfExists(cartella().resolve(vecchia.nome()));
        }
    }

    private static boolean eBackup(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith(PREFISSO) && n.endsWith(SUFFISSO);
    }

    private static Copia copia(Path p) {
        try {
            return new Copia(p.getFileName().toString(), Files.size(p), Files.getLastModifiedTime(p).toInstant());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
