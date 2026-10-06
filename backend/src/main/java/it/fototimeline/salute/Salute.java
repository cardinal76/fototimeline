package it.fototimeline.salute;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import jakarta.annotation.PreDestroy;
import it.fototimeline.backup.BackupMetadati;
import it.fototimeline.backup.BackupMetadati.Copia;
import it.fototimeline.backup.BackupMetadati.Esito;
import it.fototimeline.backup.BackupProperties;
import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.dominio.Foto;
import it.fototimeline.google.ImportazioneTakeout;
import it.fototimeline.google.ZipTakeout;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.telefono.SincronizzazioneTelefono;
import it.fototimeline.telefono.SorgenteTelefono;
import it.fototimeline.telefono.SorgenteTelefono.Giro;

/**
 * La pagina "Salute": rclone, cloud, backup, telefoni, importazioni, spazio
 * e numeri dell'archivio, ognuno con un pallino verde, giallo o rosso.
 *
 * <p>Ogni controllo gira per conto suo, con un tempo massimo: se uno lancia o
 * non risponde, la sua voce è rossa e le altre si vedono lo stesso. Verso
 * rclone i timeout sono brevi (non quelli del montaggio).
 */
@Service
public class Salute {

    private static final Logger log = LoggerFactory.getLogger(Salute.class);
    /** Oltre questo la voce che manca diventa rossa: la pagina non resta appesa. */
    static final Duration TEMPO_MASSIMO = Duration.ofSeconds(10);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("d MMM y, HH:mm", Locale.ITALIAN);
    /** Sotto queste percentuali di spazio libero: rosso e giallo. */
    static final double LIBERO_ERRORE = 0.05;
    static final double LIBERO_ATTENZIONE = 0.10;

    private final CloudProperties cloud;
    private final Rclone rclone;
    private final ArchivioFile archivio;
    private final BackupMetadati backup;
    private final BackupProperties backupProperties;
    private final SincronizzazioneTelefono telefono;
    private final LavoriImportazione lavori;
    private final ImportazioneAutomaticaProperties automatica;
    private final ImportazioneTakeout takeout;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final ExecutorService esecutore;

    @Autowired
    public Salute(CloudProperties cloud, RestClient.Builder builder, ArchivioFile archivio, BackupMetadati backup,
            BackupProperties backupProperties, SincronizzazioneTelefono telefono, LavoriImportazione lavori,
            ImportazioneAutomaticaProperties automatica, ImportazioneTakeout takeout, MongoTemplate mongo,
            Optional<Clock> clock) {
        this(cloud, new Rclone(cloud, builder.requestFactory(richiesteBrevi())), archivio, backup, backupProperties,
                telefono, lavori, automatica, takeout, mongo, clock.orElse(Clock.systemUTC()),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    /** Per i test: un rclone finto e un esecutore qualunque (anche sullo stesso thread). */
    Salute(CloudProperties cloud, Rclone rclone, ArchivioFile archivio, BackupMetadati backup,
            BackupProperties backupProperties, SincronizzazioneTelefono telefono, LavoriImportazione lavori,
            ImportazioneAutomaticaProperties automatica, ImportazioneTakeout takeout, MongoTemplate mongo, Clock clock,
            ExecutorService esecutore) {
        this.cloud = cloud;
        this.rclone = rclone;
        this.archivio = archivio;
        this.backup = backup;
        this.backupProperties = backupProperties;
        this.telefono = telefono;
        this.lavori = lavori;
        this.automatica = automatica;
        this.takeout = takeout;
        this.mongo = mongo;
        this.clock = clock;
        this.esecutore = esecutore;
    }

    /** Pochi secondi: rclone che non risponde non deve fermare la pagina. */
    private static SimpleClientHttpRequestFactory richiesteBrevi() {
        var f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(2));
        f.setReadTimeout(Duration.ofSeconds(6));
        return f;
    }

    /** Il risultato: lo stato peggiore fra le voci, le voci, quando. */
    public record Rapporto(StatoSalute stato, List<VoceSalute> voci, Instant controllatoIl) {
    }

    /** Un controllo: la voce (o le voci) che produce; chiave e titolo servono se lancia. */
    private record Controllo(String chiave, String titolo, Callable<List<VoceSalute>> esegui) {
    }

    public Rapporto controlla() {
        List<Controllo> controlli = new ArrayList<>();
        if (cloud.gestito()) {
            controlli.add(new Controllo("rclone", "rclone", () -> List.of(rclone())));
        }
        controlli.add(new Controllo("cloud", "Cloud", () -> List.of(montaggio())));
        controlli.add(new Controllo("backup", "Backup dei metadati", () -> List.of(backup())));
        controlli.add(new Controllo("telefono", TITOLO_TELEFONI, this::telefono));
        controlli.add(new Controllo("importazione", "Importazioni", () -> List.of(importazione())));
        controlli.add(new Controllo("google-takeout", TITOLO_TAKEOUT, this::takeout));
        controlli.add(new Controllo("disco", "Disco delle miniature", () -> List.of(disco())));
        if (cloud.gestito()) {
            controlli.add(new Controllo("spazio-cloud", "Spazio nel cloud", this::spazioRemote));
        }
        controlli.add(new Controllo("archivio", "Archivio e MongoDB", () -> List.of(archivio())));

        List<CompletableFuture<List<VoceSalute>>> futuri = controlli.stream()
                .map(c -> CompletableFuture.supplyAsync(() -> esegui(c), esecutore))
                .toList();
        long scadenza = System.nanoTime() + TEMPO_MASSIMO.toNanos();
        List<VoceSalute> voci = new ArrayList<>();
        for (int i = 0; i < controlli.size(); i++) {
            Controllo c = controlli.get(i);
            try {
                voci.addAll(futuri.get(i).get(Math.max(0, scadenza - System.nanoTime()), TimeUnit.NANOSECONDS));
            } catch (TimeoutException e) {
                futuri.get(i).cancel(true);
                voci.add(VoceSalute.errore(c.chiave(), c.titolo(),
                        "Nessuna risposta entro " + TEMPO_MASSIMO.toSeconds() + " secondi", null));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                voci.add(VoceSalute.errore(c.chiave(), c.titolo(), "Controllo interrotto", null));
            } catch (ExecutionException e) {
                voci.add(VoceSalute.errore(c.chiave(), c.titolo(), messaggio(e.getCause()), null));
            }
        }
        StatoSalute stato = voci.stream().map(VoceSalute::stato).reduce(StatoSalute.OK, StatoSalute::peggiore);
        return new Rapporto(stato, List.copyOf(voci), clock.instant());
    }

    private static List<VoceSalute> esegui(Controllo c) {
        try {
            return c.esegui().call();
        } catch (Exception e) {
            log.debug("Salute, {}: {}", c.chiave(), e.getMessage());
            return List.of(VoceSalute.errore(c.chiave(), c.titolo(), messaggio(e), null));
        }
    }

    // ------------------------------------------------------------ rclone e cloud

    private VoceSalute rclone() {
        Object versione = rclone.chiama("core/version", Map.of()).get("version");
        return VoceSalute.ok("rclone", "rclone", "Risponde" + (versione != null ? " (" + versione + ")" : ""), null);
    }

    private VoceSalute montaggio() {
        if (!cloud.gestito()) {
            return VoceSalute.ok("cloud", "Cloud", "Archivio su disco locale: sempre disponibile", null);
        }
        Map<String, Object> risposta = rclone.chiama("mount/listmounts", Map.of());
        boolean montato = risposta.get("mountPoints") instanceof List<?> punti
                && punti.stream().anyMatch(p -> p instanceof Map<?, ?> m
                        && cloud.puntoMontaggio().equals(m.get("MountPoint")));
        return montato
                ? VoceSalute.ok("cloud", "Cloud", "Montato: " + cloud.remoto() + " su " + cloud.puntoMontaggio(), null)
                : VoceSalute.attenzione("cloud", "Cloud",
                        "Smontato: la timeline si vede, originali, backup e importazioni aspettano", null);
    }

    // ------------------------------------------------------------ backup

    private VoceSalute backup() throws IOException {
        String chiave = "backup";
        String titolo = "Backup dei metadati";
        Duration intervallo = backupProperties.intervallo();
        Esito esito = backup.esito().orElse(null);
        Instant ultimo = esito != null ? esito.ultimoIl() : null;
        String nome = esito != null ? esito.ultimoNome() : null;
        // I file si vedono solo col cloud montato; il registro in Mongo sempre.
        if (archivio.disponibile()) {
            Optional<Copia> copia = backup.elenco().stream().findFirst();
            if (copia.isPresent() && (ultimo == null || copia.get().fattoIl().isAfter(ultimo))) {
                ultimo = copia.get().fattoIl();
                nome = copia.get().nome();
            }
        }
        List<String> dettagli = new ArrayList<>();
        dettagli.add("Uno ogni " + durata(intervallo) + ", in " + backup.cartella());
        if (nome != null) {
            dettagli.add("Ultimo file: " + nome);
        }
        boolean tentativoFallito = esito != null && esito.errore() != null
                && (ultimo == null || esito.tentativoIl() == null || esito.tentativoIl().isAfter(ultimo));
        if (tentativoFallito) {
            dettagli.add("Ultimo tentativo (" + data(esito.tentativoIl()) + "): " + esito.errore());
        }
        if (ultimo == null) {
            return tentativoFallito
                    ? VoceSalute.errore(chiave, titolo, "Mai riuscito: " + esito.errore(), dettagli)
                    : VoceSalute.attenzione(chiave, titolo, "Mai fatto", dettagli);
        }
        String quando = "Ultimo " + data(ultimo) + " (" + fa(ultimo) + ")";
        if (ultimo.plus(intervallo.multipliedBy(2)).isBefore(clock.instant())) {
            return VoceSalute.errore(chiave, titolo, quando + ": troppo vecchio", dettagli);
        }
        if (tentativoFallito) {
            return VoceSalute.attenzione(chiave, titolo, quando + "; l'ultimo tentativo non è riuscito", dettagli);
        }
        return VoceSalute.ok(chiave, titolo, quando, dettagli);
    }

    // ------------------------------------------------------------ telefono

    static final String TITOLO_TELEFONI = "Telefoni (pCloud)";

    /**
     * Una voce sola per tutti i telefoni: il pallino è quello del telefono
     * messo peggio, e nel messaggio c'è il suo nome. Contano quelli attivi e
     * quelli che hanno girato almeno una volta (anche a mano).
     */
    private List<VoceSalute> telefono() {
        String chiave = "telefono";
        SincronizzazioneTelefono.Elenco elenco = telefono.elenco(t -> true);
        List<SincronizzazioneTelefono.Stato> usati = elenco.telefoni().stream()
                .filter(t -> t.attiva() || t.ultimoGiro() != null || t.inCorso())
                .toList();
        if (usati.isEmpty()) {
            // Sul PC non c'è; sul server nessun telefono è mai stato usato: niente da dire.
            return elenco.disponibile()
                    ? List.of(VoceSalute.ok(chiave, TITOLO_TELEFONI,
                            elenco.telefoni().isEmpty() ? "Nessun telefono" : "Tutti spenti", null))
                    : List.of();
        }
        List<VoceSalute> singole = usati.stream().map(this::telefono).toList();
        StatoSalute peggiore = StatoSalute.OK;
        for (VoceSalute v : singole) {
            peggiore = peggiore.peggiore(v.stato());
        }
        List<String> dettagli = new ArrayList<>();
        List<String> messaggi = new ArrayList<>();
        for (VoceSalute v : singole) {
            if (singole.size() > 1) {
                dettagli.add(v.titolo() + ": " + v.messaggio());
            }
            v.dettagli().forEach(d -> dettagli.add(singole.size() > 1 ? v.titolo() + " · " + d : d));
            if (v.stato() == peggiore) {
                messaggi.add(v.titolo() + ": " + v.messaggio());
            }
        }
        String messaggio = peggiore == StatoSalute.OK && singole.size() > 1
                ? singole.size() + " telefoni a posto"
                : String.join("; ", messaggi);
        return List.of(new VoceSalute(chiave, TITOLO_TELEFONI, peggiore, messaggio, dettagli));
    }

    /** Un telefono: titolo il suo nome, il resto come la voce di quando il telefono era uno. */
    private VoceSalute telefono(SincronizzazioneTelefono.Stato s) {
        String chiave = "telefono-" + s.id();
        String titolo = s.nome() != null && !s.nome().isBlank() ? s.nome() : SorgenteTelefono.NOME;
        Giro ultimo = s.ultimoGiro();
        List<String> dettagli = new ArrayList<>();
        dettagli.add((s.attiva() ? "Attiva, ogni " + s.intervalloOre() + " ore" : "Spenta (solo a mano)")
                + ", da " + s.sorgente());
        if (s.inCorso()) {
            dettagli.add(s.inCoda() ? "Un giro è in coda" : "Un giro è in corso adesso");
        }
        if (ultimo != null) {
            dettagli.add(ultimo.trovati() + " su pCloud · " + ultimo.copiati() + " copiati · "
                    + ultimo.cancellati() + " tolti da pCloud · " + ultimo.errori() + " errori");
            ultimo.messaggiErrori().stream().limit(5).forEach(dettagli::add);
        }
        if (s.prossimoGiroIl() != null) {
            dettagli.add("Prossimo giro: " + data(s.prossimoGiroIl()));
        }
        if (ultimo == null) {
            return s.inCorso()
                    ? VoceSalute.ok(chiave, titolo, "Primo giro in corso", dettagli)
                    : VoceSalute.attenzione(chiave, titolo, "Attiva, ma nessun giro finora", dettagli);
        }
        String quando = "Ultimo giro " + data(ultimo.iniziatoIl()) + " (" + fa(ultimo.iniziatoIl()) + ")";
        if (ultimo.esito() == SorgenteTelefono.Esito.ERRORE) {
            String motivo = ultimo.messaggio() != null ? ultimo.messaggio() : "errore";
            String testo = erroreDiToken(ultimo)
                    ? "pCloud rifiuta il token: va rifatta la configurazione di rclone (GUIDA.md, 3.1). " + motivo
                    : quando + " con errori: " + motivo;
            return VoceSalute.errore(chiave, titolo, testo, dettagli);
        }
        Duration limite = Duration.ofHours(2L * s.intervalloOre());
        if (s.attiva() && !s.inCorso() && ultimo.iniziatoIl().plus(limite).isBefore(clock.instant())) {
            return VoceSalute.errore(chiave, titolo,
                    "Non gira da più di " + durata(limite) + ": " + quando.toLowerCase(Locale.ITALIAN), dettagli);
        }
        return VoceSalute.ok(chiave, titolo, quando + ": " + ultimo.copiati() + " copiati", dettagli);
    }

    /** "Invalid 'access_token' (2094)", "empty token found": pCloud non accetta più le credenziali. */
    static boolean erroreDiToken(Giro giro) {
        return Stream.concat(Stream.ofNullable(giro.messaggio()), giro.messaggiErrori().stream())
                .map(m -> m.toLowerCase(Locale.ROOT))
                .anyMatch(m -> m.contains("token") || m.contains("2094"));
    }

    // ------------------------------------------------------------ importazioni

    private VoceSalute importazione() {
        String chiave = "importazione";
        String titolo = "Importazioni";
        List<String> dettagli = new ArrayList<>();
        Path cartella = automatica.cartella();
        if (cartella == null) {
            dettagli.add("Importazione automatica spenta");
        } else {
            try {
                Integer attesa = inAttesa(cartella);
                if (attesa != null) {
                    dettagli.add(attesa + " file in attesa in " + cartella);
                }
            } catch (RuntimeException | IOException e) {
                dettagli.add("File in attesa in " + cartella + " non contati: " + messaggio(e));
            }
        }
        Optional<StatoLavoro> corrente = lavori.corrente();
        if (corrente.isEmpty()) {
            return VoceSalute.ok(chiave, titolo, "Nessuna da quando l'app è partita", dettagli);
        }
        StatoLavoro l = corrente.get();
        String cosa = switch (l.origine()) {
            case AUTOMATICA -> "Importazione automatica";
            case INDICIZZAZIONE -> "Indicizzazione";
            case LUOGHI -> "Calcolo dei luoghi";
            case MANUALE -> "Importazione";
        };
        dettagli.add(0, cosa + " di " + l.cartella() + ": " + l.importate() + " nuove · " + l.duplicate()
                + " già presenti · " + l.errori() + " errori");
        l.messaggi().stream().limit(5).forEach(dettagli::add);
        return switch (l.stato()) {
            case IN_CORSO -> VoceSalute.ok(chiave, titolo,
                    cosa + " in corso: " + l.fatte() + " di " + l.trovate(), dettagli);
            case FALLITA -> VoceSalute.errore(chiave, titolo,
                    cosa + " non riuscita " + data(l.finitoIl()) + ": " + l.errore(), dettagli);
            case FINITA, ANNULLATA -> {
                String fine = cosa + (l.stato() == LavoriImportazione.Stato.ANNULLATA ? " annullata " : " finita ")
                        + data(l.finitoIl());
                yield l.errori() > 0
                        ? VoceSalute.attenzione(chiave, titolo, fine + " con " + l.errori() + " errori", dettagli)
                        : VoceSalute.ok(chiave, titolo, fine + ": " + l.importate() + " nuove", dettagli);
            }
        };
    }

    // ------------------------------------------------------------ Google Takeout

    static final String TITOLO_TAKEOUT = "Google Takeout";

    /**
     * Gli zip di Google Takeout: rosso se uno non si è potuto importare (o il
     * giro è fallito), giallo se in uno qualche file non è entrato. Niente
     * voce finché non se n'è mai importato uno.
     */
    private List<VoceSalute> takeout() {
        String chiave = "google-takeout";
        ImportazioneTakeout.Riepilogo r = takeout.riepilogo();
        ImportazioneTakeout.Avanzamento l = r.lavoro();
        if (r.zip() == 0 && l == null) {
            return List.of();
        }
        List<String> dettagli = new ArrayList<>();
        dettagli.add(r.zip() + " zip visti · " + r.fatti() + " fatti · " + r.conErrori() + " con errori · "
                + r.falliti() + " non importati");
        for (ZipTakeout z : r.problemi()) {
            String perche = z.errore() != null ? z.errore() : z.errori() + " file non entrati";
            dettagli.add(z.nome() + ": " + perche);
            z.messaggi().stream().limit(3).forEach(m -> dettagli.add("  " + m));
        }
        if (l != null && l.inCorso()) {
            String dove = l.fase() == ImportazioneTakeout.Fase.ATTESA_CLOUD ? "aspetta il cloud"
                    : l.zipTotali() > 0 ? "zip " + l.zipIndice() + " di " + l.zipTotali() + ", file " + l.fileFatti()
                            + " di " + l.fileTotali() : "cerco gli zip";
            return List.of(VoceSalute.ok(chiave, TITOLO_TAKEOUT, "In corso: " + dove, dettagli));
        }
        if (l != null && l.fase() == ImportazioneTakeout.Fase.FALLITO) {
            return List.of(VoceSalute.errore(chiave, TITOLO_TAKEOUT, "Ultimo giro non riuscito: " + l.errore(), dettagli));
        }
        if (r.falliti() > 0) {
            ZipTakeout primo = r.problemi().stream().filter(z -> z.stato() == ZipTakeout.StatoZip.FALLITO).findFirst()
                    .orElse(null);
            return List.of(VoceSalute.errore(chiave, TITOLO_TAKEOUT, r.falliti() + " zip non importati"
                    + (primo != null && primo.errore() != null ? ": " + primo.errore() : ""), dettagli));
        }
        if (r.conErrori() > 0) {
            return List.of(VoceSalute.attenzione(chiave, TITOLO_TAKEOUT,
                    r.conErrori() + " zip con file non entrati (\"Riprova\" nel pannello Google Foto)", dettagli));
        }
        return List.of(VoceSalute.ok(chiave, TITOLO_TAKEOUT, r.fatti() + " zip importati", dettagli));
    }

    /**
     * Foto e video nella cartella automatica, senza scendere nelle
     * sottocartelle. Sul server con l'API di rclone (va anche da smontato),
     * sul PC dal disco; null se non si può sapere senza fatica.
     */
    private Integer inAttesa(Path cartella) throws IOException {
        if (cloud.gestito()) {
            Path punto = Path.of(cloud.puntoMontaggio()).normalize();
            Path c = cartella.normalize();
            if (!c.startsWith(punto) || c.equals(punto)) {
                return null;
            }
            String relativa = punto.relativize(c).toString().replace('\\', '/');
            Map<String, Object> risposta = rclone.chiama("operations/list", Map.of(
                    "fs", cloud.remoto(),
                    "remote", relativa,
                    "opt", Map.of("filesOnly", true)));
            if (!(risposta.get("list") instanceof List<?> lista)) {
                return null;
            }
            return (int) lista.stream()
                    .filter(o -> o instanceof Map<?, ?> m && m.get("Name") instanceof String n && FotoService.eMedia(n))
                    .count();
        }
        if (!Files.isDirectory(cartella)) {
            return null;
        }
        try (Stream<Path> s = Files.list(cartella)) {
            return (int) s.filter(Files::isRegularFile).filter(p -> FotoService.eMedia(p.getFileName().toString()))
                    .count();
        }
    }

    // ------------------------------------------------------------ spazio

    private VoceSalute disco() throws IOException {
        Path p = archivio.cartellaMiniature();
        while (p != null && !Files.exists(p)) {
            p = p.getParent();
        }
        if (p == null) {
            throw new IOException("la cartella " + archivio.cartellaMiniature() + " non esiste");
        }
        FileStore store = Files.getFileStore(p);
        return spazio("disco", "Disco delle miniature", store.getUsableSpace(), store.getTotalSpace(),
                List.of(archivio.cartellaMiniature().toString()));
    }

    /**
     * Lo spazio dei remote che usiamo: quello del cloud e, se c'è nel
     * rclone.conf, quelli dei telefoni. Alcuni remote non lo dicono.
     */
    private List<VoceSalute> spazioRemote() {
        Set<String> configurati = new HashSet<>();
        if (rclone.chiama("config/listremotes", Map.of()).get("remotes") instanceof List<?> remotes) {
            remotes.forEach(r -> configurati.add(r + ":"));
        }
        List<String> da = new ArrayList<>();
        da.add(nomeRemote(cloud.remoto()));
        for (SorgenteTelefono t : telefono.sorgenti()) {
            String sorgente = t.sorgente();
            if (sorgente != null && sorgente.contains(":")) {
                da.add(nomeRemote(sorgente));
            }
        }
        List<VoceSalute> voci = new ArrayList<>();
        for (String remote : da.stream().distinct().toList()) {
            if (configurati.contains(remote)) {
                voci.add(spazioRemote(remote));
            }
        }
        return voci;
    }

    private VoceSalute spazioRemote(String remote) {
        String chiave = "spazio-" + remote.substring(0, remote.length() - 1);
        String titolo = "Spazio su " + remote;
        Map<String, Object> about;
        try {
            about = rclone.chiama("operations/about", Map.of("fs", remote));
        } catch (RuntimeException e) {
            String m = messaggio(e);
            String minuscolo = m.toLowerCase(Locale.ROOT);
            if (minuscolo.contains("doesn't support about") || minuscolo.contains("not supported")) {
                return VoceSalute.ok(chiave, titolo, "Il remote non dice quanto spazio c'è", null);
            }
            return VoceSalute.attenzione(chiave, titolo, "Spazio non letto: " + m, null);
        }
        long totale = numero(about.get("total"));
        long usato = numero(about.get("used"));
        long libero = numero(about.get("free"));
        if (libero < 0 && totale >= 0 && usato >= 0) {
            libero = totale - usato;
        }
        if (totale <= 0 || libero < 0) {
            return VoceSalute.ok(chiave, titolo,
                    usato >= 0 ? "Usati " + byte_(usato) + " (il totale non si sa)" : "Il remote non dice quanto spazio c'è",
                    null);
        }
        return spazio(chiave, titolo, libero, totale, null);
    }

    private static VoceSalute spazio(String chiave, String titolo, long libero, long totale, List<String> dettagli) {
        double quota = totale > 0 ? (double) libero / totale : 0;
        String testo = "Liberi " + byte_(libero) + " su " + byte_(totale) + " (" + Math.round(quota * 100) + "%)";
        if (quota < LIBERO_ERRORE) {
            return VoceSalute.errore(chiave, titolo, testo, dettagli);
        }
        if (quota < LIBERO_ATTENZIONE) {
            return VoceSalute.attenzione(chiave, titolo, testo, dettagli);
        }
        return VoceSalute.ok(chiave, titolo, testo, dettagli);
    }

    private static String nomeRemote(String testo) {
        return testo.substring(0, testo.indexOf(':') + 1);
    }

    // ------------------------------------------------------------ numeri

    private VoceSalute archivio() throws IOException {
        long totale = mongo.count(new Query(), Foto.class);
        long video = mongo.count(Query.query(Criteria.where("video").is(true)), Foto.class);
        List<String> dettagli = new ArrayList<>();
        long senza = senzaMiniatura();
        if (senza > 0) {
            dettagli.add(senza + " senza miniatura (si rifanno quando servono)");
        }
        return VoceSalute.ok("archivio", "Archivio e MongoDB",
                numero(totale - video) + " foto, " + numero(video) + " video, " + numero(totale) + " in tutto", dettagli);
    }

    /** Un elenco della cartella e gli id da Mongo: niente controlli file per file. */
    private long senzaMiniatura() throws IOException {
        Set<String> miniature = new HashSet<>();
        if (Files.isDirectory(archivio.cartellaMiniature())) {
            try (Stream<Path> s = Files.list(archivio.cartellaMiniature())) {
                s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jpg")).forEach(miniature::add);
            }
        }
        long senza = 0;
        for (Document d : mongo.getCollection(mongo.getCollectionName(Foto.class)).find()
                .projection(new Document("_id", 1))) {
            if (!miniature.contains(d.get("_id") + ".jpg")) {
                senza++;
            }
        }
        return senza;
    }

    // ------------------------------------------------------------ testi

    private static String messaggio(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private static String data(Instant quando) {
        return quando == null ? "?" : DATA.format(quando.atZone(ZoneId.systemDefault()));
    }

    /** "3 ore fa", "2 giorni fa". */
    private String fa(Instant quando) {
        Duration d = Duration.between(quando, clock.instant());
        if (d.isNegative() || d.toMinutes() < 1) {
            return "adesso";
        }
        return durata(d) + " fa";
    }

    static String durata(Duration d) {
        if (d.toDays() >= 2) {
            return d.toDays() + " giorni";
        }
        if (d.equals(Duration.ofDays(1))) {
            return "1 giorno";
        }
        if (d.toHours() >= 2) {
            return d.toHours() + " ore";
        }
        if (d.toHours() == 1) {
            return "1 ora";
        }
        return Math.max(1, d.toMinutes()) + " minuti";
    }

    private static long numero(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    private static String numero(long n) {
        return String.format(Locale.ITALIAN, "%,d", n);
    }

    static String byte_(long b) {
        double gb = b / 1e9;
        if (gb >= 1000) {
            return String.format(Locale.ITALIAN, "%.1f TB", gb / 1000);
        }
        if (gb >= 1) {
            return String.format(Locale.ITALIAN, "%.1f GB", gb);
        }
        return String.format(Locale.ITALIAN, "%.0f MB", b / 1e6);
    }

    @PreDestroy
    void chiudi() {
        esecutore.shutdownNow();
    }
}
