package it.fototimeline.google;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.google.ZipTakeout.StatoZip;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.MetadatiEsterni;
import it.fototimeline.service.Risultati.Caricamento;

/**
 * "Importa da Google Takeout": tutta la libreria di Google Foto, dagli zip che
 * Takeout mette su Google Drive (da marzo 2025 l'API di Google Foto vede solo
 * le foto caricate dall'app stessa, quindi la libreria intera si porta via
 * così).
 *
 * <p>Uno zip alla volta, su un thread suo: si elenca la sorgente con l'API rc
 * di rclone ({@code operations/list}), si scarica lo zip con
 * {@code operations/copyfile} (asincrono, {@code job/status}) in una cartella
 * del disco del server che vedono sia rclone sia l'app, lo si apre con
 * {@link ZipFile} (accesso diretto, ZIP64 compreso: niente estrazione di tutto)
 * e si importa un file alla volta con {@link FotoService}, coi metadati del
 * JSON di Google dove l'EXIF non c'è. Poi lo zip locale si cancella.
 *
 * <p>Registro in {@code takeout_zip} ({@link ZipTakeout}): uno zip fatto non
 * si rifà; uno a metà riparte dal file a cui era arrivato, anche dopo un
 * riavvio ({@link #riprendi()}). Col cloud smontato aspetta. Prima di ogni
 * zip controlla lo spazio libero e, se non basta, si ferma con un messaggio.
 */
@Service
public class ImportazioneTakeout {

    private static final Logger log = LoggerFactory.getLogger(ImportazioneTakeout.class);
    private static final int MAX_MESSAGGI = 30;
    private static final int MAX_MESSAGGI_ZIP = 20;
    /** Ogni quanti file si salva a che punto si è (per ripartire dopo un riavvio). */
    private static final int SALVA_OGNI = 20;
    private static final int MAX_REGISTRO = 200;
    /** Le cartelle per anno ("Photos from 2019", in italiano "Foto dal 2019"): non sono album. */
    private static final Pattern CARTELLA_ANNO = Pattern.compile(
            "(?iu)^(photos from|foto dal|foto del|fotos de|fotos del|fotos von|photos de|fotos van|zdjęcia z) (19|20)\\d{2}$");
    /** Cartelle di Takeout che non sono album. */
    private static final Set<String> NON_ALBUM = Set.of("takeout", "google photos", "google foto", "archive",
            "archivio", "trash", "cestino", "bin", "failed videos", "video non riusciti", "untitled", "senza titolo");

    private final Rclone rclone;
    private final CloudProperties cloud;
    private final TakeoutProperties properties;
    private final FotoService fotoService;
    private final MongoTemplate mongo;
    private final BooleanSupplier cloudDisponibile;
    private final Clock clock;
    private final Duration attesaScarico;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "takeout");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<Lavoro> corrente = new AtomicReference<>();

    @Autowired
    public ImportazioneTakeout(Rclone rclone, CloudProperties cloud, TakeoutProperties properties,
            ArchivioFile archivio, FotoService fotoService, MongoTemplate mongo, Optional<Clock> clock) {
        this(rclone, cloud, properties, archivio::disponibile, fotoService, mongo,
                clock.orElse(Clock.systemDefaultZone()), Duration.ofSeconds(2));
    }

    /** Per i test: un cloud che si monta e smonta a comando, un orologio fisso, attese brevi. */
    ImportazioneTakeout(Rclone rclone, CloudProperties cloud, TakeoutProperties properties,
            BooleanSupplier cloudDisponibile, FotoService fotoService, MongoTemplate mongo, Clock clock,
            Duration attesaScarico) {
        this.rclone = rclone;
        this.cloud = cloud;
        this.properties = properties;
        this.cloudDisponibile = cloudDisponibile;
        this.fotoService = fotoService;
        this.mongo = mongo;
        this.clock = clock;
        this.attesaScarico = attesaScarico;
    }

    // ------------------------------------------------------------ per l'API

    public enum Fase { ELENCO, SCARICO, IMPORTO, ATTESA_CLOUD, PULIZIA, FINITO, ANNULLATO, FALLITO }

    /**
     * Il giro in corso o l'ultimo, per la barra.
     *
     * @param zipIndice   lo zip in lavorazione, da 1 a {@code zipTotali} (quelli da fare in questo giro)
     * @param scaricati   byte dello zip già arrivati (mentre si scarica)
     */
    public record Avanzamento(Fase fase, boolean inCorso, Instant iniziatoIl, Instant finitoIl, String zip,
            int zipIndice, int zipTotali, long scaricati, long daScaricare, int fileFatti, int fileTotali, int nuove,
            int giaPresenti, int senzaJson, int saltati, int errori, String errore, List<String> messaggi) {
    }

    /**
     * Tutto per il pannello. {@code motivo} dice perché qui non può girare
     * (null se può); {@code registro} gli zip visti, dal più recente.
     */
    public record Stato(boolean disponibile, String motivo, String sorgente, String proprietario,
            int giorniPrimaDiCancellare, String cartella, Avanzamento lavoro, List<ZipTakeout> registro) {
    }

    /** Dal pannello; un campo null resta com'è. Proprietario vuoto = chi lancia. */
    public record Modifica(String sorgente, String proprietario, Integer giorniPrimaDiCancellare) {
    }

    /** Per la pagina "Salute". */
    public record Riepilogo(long zip, long fatti, long conErrori, long falliti, long inCorso, Avanzamento lavoro,
            List<ZipTakeout> problemi) {
    }

    /** Perché qui l'importazione non può girare; null se può. */
    public String motivo() {
        if (!cloud.gestito()) {
            return "qui l'archivio è un disco locale, senza rclone: l'importazione da Google Takeout c'è solo sul server";
        }
        return null;
    }

    ImpostazioniTakeout impostazioni() {
        ImpostazioniTakeout i = mongo.findById(ImpostazioniTakeout.ID, ImpostazioniTakeout.class);
        return i != null ? i : new ImpostazioniTakeout(ImpostazioniTakeout.ID, properties.sorgente(), null, 0, false, null);
    }

    public Stato stato() {
        ImpostazioniTakeout i = impostazioni();
        String motivo = motivo();
        return new Stato(motivo == null, motivo, i.sorgente(), i.proprietario(), i.giorniPrimaDiCancellare(),
                properties.cartella().toString(), avanzamento().orElse(null), registro());
    }

    public Optional<Avanzamento> avanzamento() {
        return Optional.ofNullable(corrente.get()).map(Lavoro::avanzamento);
    }

    public List<ZipTakeout> registro() {
        return mongo.find(new Query().with(Sort.by(Sort.Order.desc("iniziatoIl"), Sort.Order.desc("nome")))
                .limit(MAX_REGISTRO), ZipTakeout.class);
    }

    public Riepilogo riepilogo() {
        long zip = mongo.count(new Query(), ZipTakeout.class);
        List<ZipTakeout> problemi = mongo.find(Query.query(Criteria.where("stato")
                .in(StatoZip.CON_ERRORI, StatoZip.FALLITO)).with(Sort.by(Sort.Order.desc("iniziatoIl"))).limit(10),
                ZipTakeout.class);
        return new Riepilogo(zip, conta(StatoZip.FATTO), conta(StatoZip.CON_ERRORI), conta(StatoZip.FALLITO),
                conta(StatoZip.IN_CORSO), avanzamento().orElse(null), problemi);
    }

    private long conta(StatoZip stato) {
        return mongo.count(Query.query(Criteria.where("stato").is(stato)), ZipTakeout.class);
    }

    /** Salva le impostazioni; valori sbagliati → IllegalArgumentException (400). */
    public Stato salva(Modifica m) {
        ImpostazioniTakeout ora = impostazioni();
        String sorgente = m.sorgente() != null ? m.sorgente().trim() : ora.sorgente();
        Sorgente.da(sorgente);
        String proprietario = m.proprietario() != null ? m.proprietario().trim() : ora.proprietario();
        int giorni = m.giorniPrimaDiCancellare() != null ? m.giorniPrimaDiCancellare() : ora.giorniPrimaDiCancellare();
        if (giorni < 0) {
            throw new IllegalArgumentException("Giorni prima di togliere gli zip da Drive: 0 (mai) o di più");
        }
        mongo.upsert(Query.query(Criteria.where("_id").is(ImpostazioniTakeout.ID)), new Update()
                .set("sorgente", sorgente)
                .set("proprietario", proprietario == null || proprietario.isEmpty() ? null : proprietario)
                .set("giorniPrimaDiCancellare", giorni), ImpostazioniTakeout.class);
        log.info("Google Takeout: sorgente {}, proprietario {}, togli da Drive dopo {} giorni", sorgente,
                proprietario, giorni);
        return stato();
    }

    /** "Riprova" uno zip: lo toglie dal registro, così al prossimo giro si rifà (i doppioni si saltano). */
    public boolean dimentica(String id) {
        if (inCorso()) {
            throw new IllegalStateException("L'importazione da Takeout è in corso: aspetta che finisca o annullala");
        }
        return mongo.remove(Query.query(Criteria.where("_id").is(id)), ZipTakeout.class).getDeletedCount() > 0;
    }

    public boolean inCorso() {
        Lavoro l = corrente.get();
        return l != null && l.inCorso();
    }

    // ------------------------------------------------------------ avvio, annullamento, ripresa

    /**
     * Parte in sottofondo. Le foto saranno "caricate da" il proprietario
     * scelto o, se non c'è, da {@code chi}. Già in corso o senza rclone:
     * IllegalStateException (409).
     */
    public synchronized Avanzamento avvia(String chi) {
        String motivo = motivo();
        if (motivo != null) {
            throw new IllegalStateException("L'importazione da Google Takeout non può partire: " + motivo);
        }
        if (inCorso()) {
            throw new IllegalStateException("L'importazione da Google Takeout è già in corso");
        }
        mongo.upsert(Query.query(Criteria.where("_id").is(ImpostazioniTakeout.ID)), new Update()
                .set("richiesto", true)
                .set("lanciatoDa", chi)
                .setOnInsert("sorgente", properties.sorgente())
                .setOnInsert("giorniPrimaDiCancellare", 0), ImpostazioniTakeout.class);
        return parti();
    }

    private Avanzamento parti() {
        Lavoro lavoro = new Lavoro(clock.instant());
        corrente.set(lavoro);
        esecutore.submit(() -> esegui(lavoro));
        return lavoro.avanzamento();
    }

    /** Si ferma dopo il file che sta facendo; lo zip a metà riparte da lì al prossimo "Avvia". */
    public boolean annulla() {
        Lavoro l = corrente.get();
        if (l == null || !l.inCorso()) {
            return false;
        }
        l.annullato = true;
        richiesto(false);
        return true;
    }

    /** Dopo un riavvio: se era in corso (né finito né annullato), riparte da dov'era. */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void riprendi() {
        if (motivo() == null && impostazioni().richiesto() && !inCorso()) {
            log.info("Google Takeout: l'importazione era in corso prima del riavvio, riparte");
            parti();
        }
    }

    /** Un giro adesso, su questo thread (per i test). */
    Avanzamento esegui(String chi) {
        mongo.upsert(Query.query(Criteria.where("_id").is(ImpostazioniTakeout.ID)), new Update()
                .set("richiesto", true).set("lanciatoDa", chi)
                .setOnInsert("sorgente", properties.sorgente()), ImpostazioniTakeout.class);
        Lavoro lavoro = new Lavoro(clock.instant());
        corrente.set(lavoro);
        esegui(lavoro);
        return lavoro.avanzamento();
    }

    /** Per i test: annulla il giro che parte su questo thread appena tocca il file numero {@code n}. */
    void annullaAlFile(int n) {
        annullaAlFile = n;
    }

    private volatile int annullaAlFile = -1;

    private void richiesto(boolean richiesto) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(ImpostazioniTakeout.ID)),
                Update.update("richiesto", richiesto), ImpostazioniTakeout.class);
    }

    /** Ogni tanto: toglie da Drive gli zip importati da più dei giorni scelti. */
    @Scheduled(fixedDelayString = "${fototimeline.takeout.controllo:PT6H}", initialDelayString = "PT10M")
    public void controllaDaTogliere() {
        if (motivo() != null || inCorso()) {
            return;
        }
        try {
            togliDaDrive(impostazioni(), null);
        } catch (RuntimeException e) {
            log.warn("Google Takeout: zip da togliere da Drive non controllati: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ il giro

    /** Il giro è stato annullato (o l'app si sta fermando). */
    private static final class Fermato extends RuntimeException {
        Fermato() {
            super("annullato", null, false, false);
        }
    }

    /** Non c'è spazio per lo zip: il giro si ferma, non solo lo zip. */
    private static final class SpazioInsufficiente extends RuntimeException {
        SpazioInsufficiente(String messaggio) {
            super(messaggio);
        }
    }

    private void esegui(Lavoro l) {
        ImpostazioniTakeout imp = impostazioni();
        String caricataDa = imp.proprietario() != null && !imp.proprietario().isBlank() ? imp.proprietario()
                : imp.lanciatoDa();
        try {
            Sorgente sorgente = Sorgente.da(imp.sorgente());
            l.fase(Fase.ELENCO);
            List<VoceDrive> daFare = new ArrayList<>();
            for (VoceDrive v : elenca(sorgente)) {
                ZipTakeout z = registrato(v);
                if (z == null || z.stato() == StatoZip.IN_CORSO || z.stato() == StatoZip.FALLITO) {
                    daFare.add(v);
                }
            }
            l.zipTotali(daFare.size());
            log.info("Google Takeout: {} zip da importare da {}", daFare.size(), sorgente.testo());
            for (int i = 0; i < daFare.size(); i++) {
                fermaSeAnnullato(l);
                importaZip(sorgente, daFare.get(i), i + 1, l, caricataDa);
            }
            fermaSeAnnullato(l);
            l.fase(Fase.PULIZIA);
            togliDaDrive(imp, l);
            richiesto(false);
            l.finisci(Fase.FINITO, null, clock.instant());
        } catch (Fermato e) {
            l.finisci(Fase.ANNULLATO, null, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Google Takeout: importazione non riuscita: {}", e.getMessage());
            if (!Thread.currentThread().isInterrupted()) {
                richiesto(false);
            }
            l.finisci(Fase.FALLITO, e.getMessage(), clock.instant());
        }
        Avanzamento fine = l.avanzamento();
        log.info("Google Takeout {}: {} nuove, {} già presenti, {} senza JSON, {} saltate, {} errori", fine.fase(),
                fine.nuove(), fine.giaPresenti(), fine.senzaJson(), fine.saltati(), fine.errori());
    }

    private void fermaSeAnnullato(Lavoro l) {
        if (l.annullato || Thread.currentThread().isInterrupted()) {
            throw new Fermato();
        }
    }

    /** Uno zip su Drive: percorso relativo alla sorgente, dimensione, data. */
    record VoceDrive(String nome, long dimensione, Instant modificatoIl) {
    }

    /** Gli zip della sorgente (anche in sottocartelle), in ordine di nome. */
    private List<VoceDrive> elenca(Sorgente s) {
        Map<String, Object> risposta = rclone.chiama("operations/list", Map.of(
                "fs", s.fs(),
                "remote", s.percorso(),
                "opt", Map.of("recurse", true, "filesOnly", true)));
        if (!(risposta.get("list") instanceof List<?> lista)) {
            throw new IllegalStateException("operations/list: risposta di rclone senza elenco");
        }
        List<VoceDrive> zip = new ArrayList<>();
        for (Object o : lista) {
            if (o instanceof Map<?, ?> voce && !Boolean.TRUE.equals(voce.get("IsDir"))
                    && voce.get("Path") instanceof String path && path.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                zip.add(new VoceDrive(s.relativo(path), numero(voce.get("Size")), data(voce.get("ModTime"))));
            }
        }
        zip.sort(Comparator.comparing(VoceDrive::nome));
        return zip;
    }

    private ZipTakeout registrato(VoceDrive v) {
        Criteria c = Criteria.where("nome").is(v.nome()).and("dimensione").is(v.dimensione());
        c = v.modificatoIl() == null ? c.and("modificatoIl").is(null) : c.and("modificatoIl").is(v.modificatoIl());
        return mongo.findOne(Query.query(c), ZipTakeout.class);
    }

    private void importaZip(Sorgente sorgente, VoceDrive v, int indice, Lavoro l, String caricataDa) {
        ZipTakeout z = registrato(v);
        if (z == null) {
            z = new ZipTakeout(new ObjectId().toHexString(), sorgente.testo(), v.nome(), v.dimensione(),
                    v.modificatoIl(), StatoZip.IN_CORSO, clock.instant(), null, 0, 0, 0, 0, 0, 0, 0, List.of(), null,
                    null);
            try {
                mongo.insert(z);
            } catch (DuplicateKeyException e) {
                z = registrato(v);
            }
        } else {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(z.id())), new Update()
                    .set("stato", StatoZip.IN_CORSO).set("errore", null).set("finitoIl", null)
                    .set("sorgente", sorgente.testo()), ZipTakeout.class);
        }
        l.zip(v.nome(), indice, v.dimensione());
        Path locale = properties.cartella().resolve(z.id() + ".zip");
        boolean tieni = false;
        try {
            Files.createDirectories(properties.cartella());
            if (!(Files.isRegularFile(locale) && Files.size(locale) == v.dimensione())) {
                Files.deleteIfExists(locale);
                controllaSpazio(v);
                aspettaCloud(l);
                l.fase(Fase.SCARICO);
                scarica(sorgente, v, locale.getFileName().toString(), l);
            } else {
                log.info("Google Takeout: {} è già scaricato, riparto da lì", v.nome());
            }
            if (!Files.isRegularFile(locale) || Files.size(locale) != v.dimensione()) {
                throw new IllegalStateException("scaricato a metà: " + v.dimensione() + " byte attesi, "
                        + (Files.isRegularFile(locale) ? Files.size(locale) : 0) + " arrivati");
            }
            l.fase(Fase.IMPORTO);
            apri(locale, z, l, caricataDa);
        } catch (Fermato e) {
            // L'app si ferma: lo zip resta per ripartire senza riscaricarlo. Annullato a mano: si libera il disco.
            tieni = !l.annullato;
            throw e;
        } catch (SpazioInsufficiente e) {
            fallito(z, e.getMessage());
            throw e;
        } catch (IOException | RuntimeException e) {
            log.warn("Google Takeout: {} non importato: {}", v.nome(), e.getMessage());
            fallito(z, e.getMessage());
            l.messaggio(v.nome() + ": " + e.getMessage());
        } finally {
            if (!tieni) {
                eliminaLocale(locale);
            }
        }
    }

    private void fallito(ZipTakeout z, String motivo) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(z.id())), new Update()
                .set("stato", StatoZip.FALLITO).set("errore", motivo).set("finitoIl", clock.instant()),
                ZipTakeout.class);
    }

    /** Lo zip e quello che serve per aprirlo devono stare sul disco; altrimenti ci si ferma. */
    private void controllaSpazio(VoceDrive v) throws IOException {
        long libero = Files.getFileStore(properties.cartella()).getUsableSpace();
        long serve = v.dimensione() + properties.riserva().toBytes();
        if (libero < serve) {
            throw new SpazioInsufficiente("Spazio insufficiente in " + properties.cartella() + " per " + v.nome()
                    + ": servono " + byte_(serve) + " (lo zip più " + byte_(properties.riserva().toBytes())
                    + " di margine), liberi " + byte_(libero) + ". Libera spazio sul server o rifai Takeout"
                    + " con zip più piccoli (per esempio 10 GB), poi premi di nuovo Avvia");
        }
    }

    /**
     * Copia lo zip da Drive alla cartella sul disco con rclone, in modo
     * asincrono ({@code _async}): un file da 50 GB richiede ore, la chiamata
     * HTTP no. Si segue con {@code job/status}; annullando, {@code job/stop}.
     */
    private void scarica(Sorgente sorgente, VoceDrive v, String nomeLocale, Lavoro l) {
        log.info("Google Takeout: scarico {} ({})", v.nome(), byte_(v.dimensione()));
        Map<String, Object> risposta = rclone.chiama("operations/copyfile", Map.of(
                "srcFs", sorgente.fs(),
                "srcRemote", sorgente.dentro(v.nome()),
                "dstFs", properties.cartellaRclone(),
                "dstRemote", nomeLocale,
                "_async", true));
        if (!(risposta.get("jobid") instanceof Number id)) {
            // Senza job: rclone ha già finito.
            return;
        }
        long jobid = id.longValue();
        while (true) {
            if (l.annullato || Thread.currentThread().isInterrupted()) {
                try {
                    rclone.chiama("job/stop", Map.of("jobid", jobid));
                } catch (RuntimeException e) {
                    log.debug("job/stop {}: {}", jobid, e.getMessage());
                }
                throw new Fermato();
            }
            Map<String, Object> stato = rclone.chiama("job/status", Map.of("jobid", jobid));
            if (Boolean.TRUE.equals(stato.get("finished"))) {
                if (!Boolean.TRUE.equals(stato.get("success"))) {
                    Object errore = stato.get("error");
                    throw new IllegalStateException("download non riuscito: "
                            + (errore instanceof String s && !s.isBlank() ? s : "errore di rclone"));
                }
                l.scaricati(v.dimensione());
                return;
            }
            try {
                if (rclone.chiama("core/stats", Map.of("group", "job/" + jobid)).get("bytes") instanceof Number b) {
                    l.scaricati(b.longValue());
                }
            } catch (RuntimeException e) {
                // Solo per la barra.
            }
            dormi(attesaScarico);
        }
    }

    /** Apre lo zip scaricato e importa i file da dove era arrivato. */
    private void apri(Path locale, ZipTakeout z, Lavoro l, String caricataDa) throws IOException {
        try (ZipFile zip = new ZipFile(locale.toFile(), StandardCharsets.UTF_8)) {
            Indice indice = indice(zip);
            ZipTakeout ora = mongo.findById(z.id(), ZipTakeout.class);
            int fatti = ora != null ? Math.min(ora.fatti(), indice.media().size()) : 0;
            Contatori c = ora != null && fatti > 0 ? Contatori.da(ora) : new Contatori();
            if (fatti == 0) {
                c.saltati = indice.saltati().size();
                indice.saltati().stream().limit(5).forEach(n -> c.messaggio(n + ": formato non supportato"));
            }
            mongo.updateFirst(Query.query(Criteria.where("_id").is(z.id())), Update.update("file", indice.media().size()),
                    ZipTakeout.class);
            l.file(fatti, indice.media().size(), c);
            Map<String, String> album = albumEsistenti();
            for (int i = fatti; i < indice.media().size(); i++) {
                if (i == annullaAlFile) {
                    l.annullato = true;
                }
                if (l.annullato || Thread.currentThread().isInterrupted()) {
                    salvaAvanzamento(z.id(), i, c, null);
                    throw new Fermato();
                }
                aspettaCloud(l);
                Media m = indice.media().get(i);
                try {
                    importaUno(zip, m, album, caricataDa, c, l);
                } catch (ArchivioNonDisponibile e) {
                    // Smontato a metà: si aspetta e si rifà lo stesso file.
                    i--;
                    continue;
                }
                l.fatto(c);
                if ((i + 1) % SALVA_OGNI == 0) {
                    salvaAvanzamento(z.id(), i + 1, c, null);
                }
            }
            StatoZip fine = c.errori > 0 ? StatoZip.CON_ERRORI : StatoZip.FATTO;
            salvaAvanzamento(z.id(), indice.media().size(), c, fine);
            log.info("Google Takeout: {} {}: {} file, {} nuove, {} già presenti, {} senza JSON, {} saltati, {} errori",
                    z.nome(), fine, indice.media().size(), c.nuove, c.giaPresenti, c.senzaJson, c.saltati, c.errori);
        } catch (ZipException e) {
            throw new IllegalStateException("zip illeggibile (" + e.getMessage() + ")", e);
        }
    }

    private void importaUno(ZipFile zip, Media m, Map<String, String> albumEsistenti, String caricataDa,
            Contatori c, Lavoro l) throws IOException {
        MetadatiTakeout meta = null;
        if (m.json() != null) {
            try (InputStream in = zip.getInputStream(m.json())) {
                meta = MetadatiTakeout.leggi(in);
            } catch (IOException | RuntimeException e) {
                log.debug("JSON illeggibile {}: {}", m.json().getName(), e.getMessage());
            }
        }
        String album = m.album() == null ? null
                : albumEsistenti.computeIfAbsent(m.album().toLowerCase(Locale.ROOT), k -> m.album());
        Path estratto = Files.createTempFile(properties.cartella(), "estratto-", "." + estensione(m.nome()));
        try {
            try (InputStream in = zip.getInputStream(m.voce())) {
                Files.copy(in, estratto, StandardCopyOption.REPLACE_EXISTING);
            }
            MetadatiEsterni esterni = meta != null ? meta.esterni(clock.getZone()) : null;
            Caricamento esito = fotoService.importa(m.nome(), estratto, dataNelloZip(m.voce()), album, caricataDa,
                    esterni);
            switch (esito.esito()) {
                case CARICATA -> {
                    c.nuove++;
                    if (meta == null) {
                        c.senzaJson++;
                    }
                }
                case DUPLICATA -> c.giaPresenti++;
                case ERRORE -> {
                    c.errori++;
                    String testo = m.percorso() + ": " + esito.messaggio();
                    c.messaggio(testo);
                    l.messaggio(testo);
                }
            }
        } finally {
            Files.deleteIfExists(estratto);
        }
    }

    /** La data del file nello zip, se sensata: vale solo se mancano EXIF e JSON. */
    private static Instant dataNelloZip(ZipEntry e) {
        if (e.getLastModifiedTime() == null) {
            return null;
        }
        Instant t = e.getLastModifiedTime().toInstant();
        return t.isBefore(Instant.parse("1990-01-01T00:00:00Z")) ? null : t;
    }

    private void salvaAvanzamento(String id, int fatti, Contatori c, StatoZip fine) {
        Update u = new Update()
                .set("fatti", fatti)
                .set("nuove", c.nuove)
                .set("giaPresenti", c.giaPresenti)
                .set("senzaJson", c.senzaJson)
                .set("saltati", c.saltati)
                .set("errori", c.errori)
                .set("messaggi", List.copyOf(c.messaggi));
        if (fine != null) {
            u.set("stato", fine).set("finitoIl", clock.instant());
        }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), u, ZipTakeout.class);
    }

    /** Gli album che l'app ha già, per nome senza maiuscole: "vacanze" → "Vacanze". */
    private Map<String, String> albumEsistenti() {
        Map<String, String> m = new HashMap<>();
        for (String a : fotoService.album()) {
            m.putIfAbsent(a.toLowerCase(Locale.ROOT), a);
        }
        return m;
    }

    /** Col cloud smontato si aspetta (e si può annullare intanto). */
    private void aspettaCloud(Lavoro l) {
        if (cloudDisponibile.getAsBoolean()) {
            return;
        }
        Fase prima = l.fase();
        l.fase(Fase.ATTESA_CLOUD);
        log.info("Google Takeout: il cloud è smontato, aspetto");
        while (!cloudDisponibile.getAsBoolean()) {
            fermaSeAnnullato(l);
            dormi(properties.attesaCloud());
        }
        l.fase(prima);
    }

    private static void dormi(Duration d) {
        try {
            Thread.sleep(d);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fermato();
        }
    }

    private static void eliminaLocale(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Google Takeout: non riesco a togliere {}: {}", p, e.getMessage());
        }
    }

    // ------------------------------------------------------------ toglierli da Drive

    /**
     * Toglie da Drive gli zip importati senza errori da più di
     * {@code giorniPrimaDiCancellare} giorni (0 = mai), solo se su Drive c'è
     * ancora lo stesso file (stessa dimensione).
     */
    private void togliDaDrive(ImpostazioniTakeout imp, Lavoro l) {
        if (imp.giorniPrimaDiCancellare() <= 0) {
            return;
        }
        Instant limite = clock.instant().minus(Duration.ofDays(imp.giorniPrimaDiCancellare()));
        Query vecchi = Query.query(Criteria.where("stato").is(StatoZip.FATTO).and("cancellatoIl").is(null)
                .and("finitoIl").lt(limite));
        for (ZipTakeout z : mongo.find(vecchi, ZipTakeout.class)) {
            try {
                Sorgente s = Sorgente.da(z.sorgente());
                Object voce = rclone.chiama("operations/stat", Map.of("fs", s.fs(), "remote", s.dentro(z.nome())))
                        .get("item");
                if (voce instanceof Map<?, ?> m && numero(m.get("Size")) != z.dimensione()) {
                    // Un altro file con lo stesso nome: resta.
                    continue;
                }
                if (voce != null) {
                    rclone.chiama("operations/deletefile", Map.of("fs", s.fs(), "remote", s.dentro(z.nome())));
                    log.info("Google Takeout: {} tolto da Drive", z.nome());
                }
                mongo.updateFirst(Query.query(Criteria.where("_id").is(z.id())),
                        Update.update("cancellatoIl", clock.instant()), ZipTakeout.class);
            } catch (RuntimeException e) {
                log.warn("Google Takeout: {} non tolto da Drive: {}", z.nome(), e.getMessage());
                if (l != null) {
                    l.messaggio(z.nome() + " non tolto da Drive: " + e.getMessage());
                }
            }
        }
    }

    // ------------------------------------------------------------ dentro lo zip

    /**
     * Una foto o un video dello zip.
     *
     * @param nome  quello con cui entra nell'archivio ({@code PXL_1.MP} → {@code PXL_1.mp4})
     * @param album null per le cartelle per anno
     * @param json  il suo JSON di Google, se c'è
     */
    record Media(ZipEntry voce, String percorso, String nome, String album, ZipEntry json) {
    }

    /** Le foto e i video da importare, in ordine fisso; i file che l'archivio non accetta. */
    record Indice(List<Media> media, List<String> saltati) {
    }

    static Indice indice(ZipFile zip) throws IOException {
        Map<String, Map<String, ZipEntry>> perCartella = new TreeMap<>();
        for (ZipEntry e : Collections.list(zip.entries())) {
            if (e.isDirectory()) {
                continue;
            }
            String n = e.getName();
            int barra = n.lastIndexOf('/');
            perCartella.computeIfAbsent(barra < 0 ? "" : n.substring(0, barra), k -> new LinkedHashMap<>())
                    .put(barra < 0 ? n : n.substring(barra + 1), e);
        }
        List<Media> media = new ArrayList<>();
        List<String> saltati = new ArrayList<>();
        for (var cartella : perCartella.entrySet()) {
            Map<String, ZipEntry> file = cartella.getValue();
            List<String> nomiMedia = new ArrayList<>();
            List<String> nomiJson = new ArrayList<>();
            String titolo = null;
            for (var f : file.entrySet()) {
                String nome = f.getKey();
                String minuscolo = nome.toLowerCase(Locale.ROOT);
                if (SidecarTakeout.eMetadatiAlbum(nome)) {
                    try (InputStream in = zip.getInputStream(f.getValue())) {
                        titolo = MetadatiTakeout.titoloAlbum(in);
                    } catch (IOException | RuntimeException e) {
                        log.debug("metadata.json illeggibile in {}: {}", cartella.getKey(), e.getMessage());
                    }
                } else if (minuscolo.endsWith(".json")) {
                    nomiJson.add(nome);
                } else if (nomeInArchivio(nome) != null) {
                    nomiMedia.add(nome);
                } else if (!minuscolo.endsWith(".html") && !nome.startsWith(".")) {
                    saltati.add(cartella.getKey() + "/" + nome);
                }
            }
            Map<String, String> abbinati = SidecarTakeout.abbina(nomiMedia, nomiJson);
            String album = album(cartella.getKey(), titolo);
            for (String nome : nomiMedia) {
                String json = abbinati.get(nome);
                media.add(new Media(file.get(nome), cartella.getKey() + "/" + nome, nomeInArchivio(nome), album,
                        json == null ? null : file.get(json)));
            }
        }
        return new Indice(List.copyOf(media), List.copyOf(saltati));
    }

    /**
     * Il nome con cui un file entra nell'archivio, o null se l'archivio non lo
     * accetta. I video delle foto in movimento dei Pixel ({@code .MP}) sono
     * MP4: entrano come {@code .mp4}.
     */
    static String nomeInArchivio(String nome) {
        if (FotoService.eMedia(nome)) {
            return nome;
        }
        int punto = nome.lastIndexOf('.');
        if (punto > 0 && nome.substring(punto + 1).equalsIgnoreCase("mp")) {
            return nome.substring(0, punto) + ".mp4";
        }
        return null;
    }

    /**
     * L'album di una cartella dello zip: il titolo del suo {@code metadata.json}
     * se c'è, altrimenti il nome della cartella; nessuno per le cartelle per
     * anno ("Photos from 2019", "Foto dal 2019") e quelle di servizio
     * (Cestino, Archivio).
     */
    static String album(String cartella, String titolo) {
        String nome = cartella.substring(cartella.lastIndexOf('/') + 1).trim();
        if (CARTELLA_ANNO.matcher(nome).matches()) {
            return null;
        }
        if (titolo != null && !titolo.isBlank()) {
            return titolo.trim();
        }
        if (nome.isEmpty() || NON_ALBUM.contains(nome.toLowerCase(Locale.ROOT))) {
            return null;
        }
        return nome;
    }

    private static String estensione(String nome) {
        int punto = nome.lastIndexOf('.');
        return punto < 0 ? "bin" : nome.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------ utilità

    /** {@code gdrive:Takeout}: il remote ({@code gdrive:}) e il percorso dentro ({@code Takeout}). */
    record Sorgente(String testo, String fs, String percorso) {

        static Sorgente da(String testo) {
            int duePunti = testo == null ? -1 : testo.indexOf(':');
            if (duePunti <= 0) {
                throw new IllegalArgumentException(
                        "Sorgente: remote di rclone e cartella, per esempio \"gdrive:Takeout\"");
            }
            String percorso = testo.substring(duePunti + 1).replaceAll("^/+|/+$", "");
            return new Sorgente(testo, testo.substring(0, duePunti + 1), percorso);
        }

        String dentro(String relativo) {
            return percorso.isEmpty() ? relativo : percorso + "/" + relativo;
        }

        String relativo(String path) {
            return !percorso.isEmpty() && path.startsWith(percorso + "/") ? path.substring(percorso.length() + 1) : path;
        }
    }

    private static long numero(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    private static Instant data(Object o) {
        try {
            return o instanceof String s ? OffsetDateTime.parse(s).toInstant() : null;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    static String byte_(long b) {
        double gb = b / 1e9;
        if (gb >= 1) {
            return String.format(Locale.ITALIAN, "%.1f GB", gb);
        }
        return String.format(Locale.ITALIAN, "%.0f MB", b / 1e6);
    }

    @PreDestroy
    void chiudi() {
        // Senza toccare "richiesto": dopo il riavvio riparte da dov'era.
        esecutore.shutdownNow();
    }

    /** I contatori di uno zip (si salvano nel registro). */
    private static final class Contatori {
        int nuove;
        int giaPresenti;
        int senzaJson;
        int saltati;
        int errori;
        final List<String> messaggi = new ArrayList<>();

        static Contatori da(ZipTakeout z) {
            Contatori c = new Contatori();
            c.nuove = z.nuove();
            c.giaPresenti = z.giaPresenti();
            c.senzaJson = z.senzaJson();
            c.saltati = z.saltati();
            c.errori = z.errori();
            c.messaggi.addAll(z.messaggi());
            return c;
        }

        void messaggio(String m) {
            if (messaggi.size() < MAX_MESSAGGI_ZIP) {
                messaggi.add(m);
            }
        }
    }

    /** Il giro per l'API: lo aggiorna il thread "takeout", lo legge il pannello. */
    private static final class Lavoro {
        private final Instant iniziatoIl;
        private volatile boolean annullato;
        private Fase fase = Fase.ELENCO;
        private Instant finitoIl;
        private String zip;
        private int zipIndice;
        private int zipTotali;
        private long scaricati;
        private long daScaricare;
        private int fileFatti;
        private int fileTotali;
        /** I totali degli zip già finiti in questo giro; quelli dello zip in corso sono in {@code zipCorrente}. */
        private int nuove;
        private int giaPresenti;
        private int senzaJson;
        private int saltati;
        private int errori;
        private Contatori zipCorrente;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(Instant iniziatoIl) {
            this.iniziatoIl = iniziatoIl;
        }

        synchronized boolean inCorso() {
            return finitoIl == null;
        }

        synchronized Fase fase() {
            return fase;
        }

        synchronized void fase(Fase f) {
            fase = f;
        }

        synchronized void zipTotali(int n) {
            zipTotali = n;
        }

        synchronized void zip(String nome, int indice, long dimensione) {
            chiudiZip();
            zip = nome;
            zipIndice = indice;
            daScaricare = dimensione;
            scaricati = 0;
            fileFatti = 0;
            fileTotali = 0;
        }

        /** Somma ai totali i contatori dello zip appena chiuso. */
        private void chiudiZip() {
            if (zipCorrente != null) {
                nuove += zipCorrente.nuove;
                giaPresenti += zipCorrente.giaPresenti;
                senzaJson += zipCorrente.senzaJson;
                saltati += zipCorrente.saltati;
                errori += zipCorrente.errori;
                zipCorrente = null;
            }
        }

        synchronized void scaricati(long b) {
            scaricati = b;
        }

        synchronized void file(int fatti, int totali, Contatori c) {
            fileFatti = fatti;
            fileTotali = totali;
            zipCorrente = c;
        }

        synchronized void fatto(Contatori c) {
            fileFatti++;
            zipCorrente = c;
        }

        synchronized void messaggio(String m) {
            if (messaggi.size() < MAX_MESSAGGI) {
                messaggi.add(m);
            }
        }

        synchronized void finisci(Fase f, String motivo, Instant quando) {
            chiudiZip();
            fase = f;
            errore = motivo;
            finitoIl = quando;
        }

        synchronized Avanzamento avanzamento() {
            Contatori c = zipCorrente;
            return new Avanzamento(fase, finitoIl == null, iniziatoIl, finitoIl, zip, zipIndice, zipTotali, scaricati,
                    daScaricare, fileFatti, fileTotali, nuove + (c != null ? c.nuove : 0),
                    giaPresenti + (c != null ? c.giaPresenti : 0), senzaJson + (c != null ? c.senzaJson : 0),
                    saltati + (c != null ? c.saltati : 0), errori + (c != null ? c.errori : 0), errore,
                    List.copyOf(messaggi));
        }
    }
}
