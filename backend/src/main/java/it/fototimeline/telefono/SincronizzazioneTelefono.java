package it.fototimeline.telefono;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.ProvenienzaFile;
import it.fototimeline.sicurezza.Utente;
import it.fototimeline.sicurezza.UtenteRegistrato;
import it.fototimeline.telefono.SorgenteTelefono.Esito;
import it.fototimeline.telefono.SorgenteTelefono.Giro;

/**
 * Porta le foto nuove dei telefoni della famiglia (pCloud, "Automatic
 * Upload", uno o più account) nella cartella automatica del cloud, da dove
 * l'importazione automatica le sposta nell'archivio. Dopo
 * {@code giorniPrimaDiCancellare} giorni toglie dalla sorgente i file che
 * l'importazione ha davvero preso.
 *
 * <p>Tutto passa dall'API rc di rclone, non dal montaggio: la copia va anche
 * col cloud smontato. Un giro alla volta, di un telefono alla volta, su un
 * thread suo: gli altri aspettano in coda.
 *
 * <p>La cartella automatica resta piatta per tutti i telefoni (le
 * sottocartelle diventerebbero album): di chi è un file lo dice il registro
 * delle copie, cercando la sua destinazione ({@link #caricataDa(Path)}).
 */
@Service
public class SincronizzazioneTelefono implements ProvenienzaFile {

    private static final Logger log = LoggerFactory.getLogger(SincronizzazioneTelefono.class);
    /** L'id del telefono nato dalla migrazione del documento unico. */
    static final String ID_MIGRATO = "telefono";
    private static final int MAX_MESSAGGI = 20;
    private static final int MAX_ORE = 168;

    private final Rclone rclone;
    private final CloudProperties cloud;
    private final ImportazioneAutomaticaProperties automatica;
    private final MongoTemplate mongo;
    private final String proprietarioMigrato;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telefono");
        t.setDaemon(true);
        return t;
    });
    /** I telefoni con un giro in coda o in corso. */
    private final Set<String> prenotati = ConcurrentHashMap.newKeySet();
    /** I giri che stanno girando, per mostrarne i contatori prima che finiscano. */
    private final Map<String, Corrente> correnti = new ConcurrentHashMap<>();

    private record Corrente(Instant inizio, Contatori contatori) {
    }

    /**
     * @param proprietarioMigrato a chi dare il telefono migrato dal documento
     *                            unico ({@code fototimeline.telefono.proprietario});
     *                            vuoto = al primo amministratore
     */
    public SincronizzazioneTelefono(Rclone rclone, CloudProperties cloud, ImportazioneAutomaticaProperties automatica,
            MongoTemplate mongo, @Value("${fototimeline.telefono.proprietario:}") String proprietarioMigrato,
            Optional<Clock> clock) {
        this.rclone = rclone;
        this.cloud = cloud;
        this.automatica = automatica;
        this.mongo = mongo;
        this.proprietarioMigrato = proprietarioMigrato == null || proprietarioMigrato.isBlank() ? null
                : proprietarioMigrato.trim();
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Un telefono e il suo stato, per l'API. */
    public record Stato(String id, String nome, String proprietario, boolean attiva, String sorgente,
            int intervalloOre, int giorniPrimaDiCancellare, int copieInParallelo, boolean inCorso, boolean inCoda,
            Giro ultimoGiro, Instant prossimoGiroIl, Giro giroInCorso) {
    }

    /**
     * I telefoni e quello che vale per tutti: {@code motivo} dice perché qui
     * la sincronizzazione non può girare, {@code destinazione} dove finiscono le copie.
     */
    public record Elenco(boolean disponibile, String motivo, String destinazione, List<Stato> telefoni) {
    }

    /** Quello che si cambia dalla pagina; un campo null resta com'è (alla creazione, il predefinito). */
    public record Modifica(String nome, String proprietario, Boolean attiva, String sorgente, Integer intervalloOre,
            Integer giorniPrimaDiCancellare, Integer copieInParallelo) {
    }

    // ------------------------------------------------------------ i telefoni

    /** Tutti, nell'ordine in cui sono stati aggiunti. */
    public List<SorgenteTelefono> sorgenti() {
        return mongo.find(new Query().with(Sort.by("creataIl", "_id")), SorgenteTelefono.class);
    }

    public Optional<SorgenteTelefono> sorgente(String id) {
        return Optional.ofNullable(mongo.findById(id, SorgenteTelefono.class));
    }

    /** I telefoni che {@code visibile} lascia vedere (l'admin tutti, gli altri il proprio). */
    public Elenco elenco(Predicate<SorgenteTelefono> visibile) {
        String motivo = motivo();
        return new Elenco(motivo == null, motivo, motivo == null ? nelCloud(destinazione()) : null,
                sorgenti().stream().filter(visibile).map(s -> stato(s, motivo)).toList());
    }

    public Stato stato(SorgenteTelefono s) {
        return stato(s, motivo());
    }

    private Stato stato(SorgenteTelefono s, String motivo) {
        Instant prossimo = null;
        if (s.attiva() && motivo == null) {
            prossimo = s.ultimoGiro() == null ? clock.instant()
                    : s.ultimoGiro().iniziatoIl().plus(Duration.ofHours(s.intervalloOre()));
        }
        Corrente c = correnti.get(s.id());
        Giro giroInCorso = c != null ? c.contatori().giro(c.inizio(), null, null, "In corso") : null;
        boolean prenotato = prenotati.contains(s.id());
        return new Stato(s.id(), s.nome(), s.proprietario(), s.attiva(), s.sorgente(), s.intervalloOre(),
                s.giorniPrimaDiCancellare(), s.copie(), prenotato, prenotato && c == null, s.ultimoGiro(), prossimo,
                giroInCorso);
    }

    /** Aggiunge un telefono; nome, proprietario e cartella servono. Valori fuori misura → IllegalArgumentException (400). */
    public Stato crea(Modifica m) {
        var modello = new SorgenteTelefono(new ObjectId().toHexString(), null, null, false, SorgenteTelefono.SORGENTE,
                SorgenteTelefono.INTERVALLO_ORE, SorgenteTelefono.GIORNI_PRIMA_DI_CANCELLARE,
                SorgenteTelefono.COPIE_IN_PARALLELO, clock.instant(), null);
        SorgenteTelefono nuova = unisci(modello, m);
        mongo.insert(nuova);
        log.info("Telefono aggiunto: {} di {}, sorgente {}", nuova.nome(), nuova.proprietario(), nuova.sorgente());
        return stato(nuova);
    }

    /** Salva le impostazioni di un telefono; NoSuchElementException se non c'è. */
    public Stato salva(String id, Modifica m) {
        SorgenteTelefono s = unisci(sorgente(id).orElseThrow(() -> nonTrovato(id)), m);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update()
                .set("nome", s.nome())
                .set("proprietario", s.proprietario())
                .set("attiva", s.attiva())
                .set("sorgente", s.sorgente())
                .set("intervalloOre", s.intervalloOre())
                .set("giorniPrimaDiCancellare", s.giorniPrimaDiCancellare())
                .set("copieInParallelo", s.copieInParallelo()), SorgenteTelefono.class);
        log.info("Telefono {}: di {}, attiva={}, sorgente={}, ogni {} ore, cancella dopo {} giorni, {} copie insieme",
                s.nome(), s.proprietario(), s.attiva(), s.sorgente(), s.intervalloOre(), s.giorniPrimaDiCancellare(),
                s.copieInParallelo());
        return stato(sorgente(id).orElseThrow(() -> nonTrovato(id)));
    }

    /**
     * Toglie un telefono. Il registro delle copie resta: rimesso con la stessa
     * cartella, non ricopia niente. Con un giro in coda o in corso, IllegalStateException (409).
     */
    public boolean elimina(String id) {
        if (prenotati.contains(id)) {
            throw new IllegalStateException("Questo telefono si sta sincronizzando: aspetta che finisca");
        }
        boolean tolto = mongo.remove(Query.query(Criteria.where("_id").is(id)), SorgenteTelefono.class)
                .getDeletedCount() > 0;
        if (tolto) {
            log.info("Telefono {} tolto (il registro delle copie resta)", id);
        }
        return tolto;
    }

    /** I valori di {@code m} sopra quelli di {@code ora}, controllati. */
    private SorgenteTelefono unisci(SorgenteTelefono ora, Modifica m) {
        String nome = m.nome() != null ? m.nome().trim() : ora.nome();
        String proprietario = m.proprietario() != null ? m.proprietario().trim() : ora.proprietario();
        boolean attiva = m.attiva() != null ? m.attiva() : ora.attiva();
        String sorgente = m.sorgente() != null ? m.sorgente().trim() : ora.sorgente();
        int ore = m.intervalloOre() != null ? m.intervalloOre() : ora.intervalloOre();
        int giorni = m.giorniPrimaDiCancellare() != null ? m.giorniPrimaDiCancellare() : ora.giorniPrimaDiCancellare();
        int copie = m.copieInParallelo() != null ? m.copieInParallelo() : ora.copie();
        if (nome == null || nome.isEmpty()) {
            throw new IllegalArgumentException("Il nome serve, per esempio \"Telefono di Anna\"");
        }
        // Null resta possibile solo per il telefono migrato, finché nessuno lo sceglie.
        boolean nuovo = ora.nome() == null;
        if (proprietario != null && proprietario.isEmpty() || proprietario == null && nuovo) {
            throw new IllegalArgumentException("Il proprietario serve: lo username di chi ha il telefono");
        }
        Sorgente nuova = Sorgente.da(sorgente);
        for (SorgenteTelefono altra : sorgenti()) {
            if (!altra.id().equals(ora.id()) && Sorgente.da(altra.sorgente()).sovrapposta(nuova)) {
                throw new IllegalArgumentException("La cartella " + sorgente + " è già di \"" + altra.nome()
                        + "\" (" + altra.sorgente() + "): due telefoni non possono copiare gli stessi file");
            }
        }
        if (ore < 1 || ore > MAX_ORE) {
            throw new IllegalArgumentException("Ogni quante ore: da 1 a " + MAX_ORE);
        }
        if (copie < 1 || copie > SorgenteTelefono.MAX_COPIE_IN_PARALLELO) {
            throw new IllegalArgumentException("Copie in parallelo: da 1 a " + SorgenteTelefono.MAX_COPIE_IN_PARALLELO);
        }
        if (giorni < 0) {
            throw new IllegalArgumentException("Giorni prima di togliere da pCloud: 0 (mai) o di più");
        }
        String motivo = motivo();
        if (attiva && motivo != null) {
            throw new IllegalArgumentException("La sincronizzazione non si può attivare: " + motivo);
        }
        return new SorgenteTelefono(ora.id(), nome, proprietario, attiva, sorgente, ore, giorni, copie, ora.creataIl(),
                ora.ultimoGiro());
    }

    private static NoSuchElementException nonTrovato(String id) {
        return new NoSuchElementException("Nessun telefono " + id);
    }

    // ------------------------------------------------------------ migrazione e proprietari

    /**
     * Il documento unico di prima ({@code impostazioni/sincronizzazione-telefono})
     * diventa il primo telefono, "Telefono", con le stesse impostazioni e
     * l'ultimo giro. Il registro resta com'è (la chiave è la cartella: niente
     * si ricopia); le sue voci prendono l'id del telefono. Il proprietario è
     * {@code fototimeline.telefono.proprietario} o il primo amministratore
     * entrato; se non c'è ancora nessuno, il primo che entra
     * ({@link #utenteRegistrato}). Gira all'avvio e non fa niente la seconda volta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void migra() {
        ImpostazioniTelefono vecchie = mongo.findById(ImpostazioniTelefono.ID, ImpostazioniTelefono.class);
        if (vecchie == null) {
            return;
        }
        if (mongo.findById(ID_MIGRATO, SorgenteTelefono.class) == null) {
            String proprietario = proprietarioMigrato;
            if (proprietario == null) {
                Utente admin = mongo.findOne(Query.query(Criteria.where("admin").is(true))
                        .with(Sort.by("primoAccesso", "_id")).limit(1), Utente.class);
                proprietario = admin != null ? admin.username() : null;
            }
            mongo.insert(new SorgenteTelefono(ID_MIGRATO, SorgenteTelefono.NOME, proprietario, vecchie.attiva(),
                    vecchie.sorgente() != null ? vecchie.sorgente() : SorgenteTelefono.SORGENTE,
                    vecchie.intervalloOre() > 0 ? vecchie.intervalloOre() : SorgenteTelefono.INTERVALLO_ORE,
                    vecchie.giorniPrimaDiCancellare(), vecchie.copieInParallelo(), clock.instant(),
                    vecchie.ultimoGiro()));
        }
        long voci = mongo.updateMulti(Query.query(Criteria.where("idSorgente").exists(false)),
                Update.update("idSorgente", ID_MIGRATO), CopiaTelefono.class).getModifiedCount();
        mongo.remove(Query.query(Criteria.where("_id").is(ImpostazioniTelefono.ID)), ImpostazioniTelefono.class);
        log.info("Sincronizzazione del telefono migrata al primo telefono dell'elenco ({} voci del registro)", voci);
    }

    /** Un amministratore è entrato: se il telefono migrato non ha ancora un proprietario, è suo. */
    @EventListener
    public void utenteRegistrato(UtenteRegistrato e) {
        if (!e.admin()) {
            return;
        }
        long dati = mongo.updateMulti(Query.query(Criteria.where("proprietario").is(null)),
                Update.update("proprietario", e.username()), SorgenteTelefono.class).getModifiedCount();
        if (dati > 0) {
            log.info("{} telefoni senza proprietario dati a {}", dati, e.username());
        }
    }

    /**
     * Di chi è un file della cartella automatica: si cerca nel registro la
     * copia con quella destinazione (la più recente: un nome si libera quando
     * l'importazione sposta il file) e si prende il proprietario del suo
     * telefono, o quello di allora se il telefono non c'è più.
     */
    @Override
    public String caricataDa(Path file) {
        if (!cloud.gestito() || cloud.puntoMontaggio() == null || cloud.puntoMontaggio().isBlank()) {
            return null;
        }
        Path punto = Path.of(cloud.puntoMontaggio()).toAbsolutePath().normalize();
        Path f = file.toAbsolutePath().normalize();
        if (!f.startsWith(punto) || f.equals(punto)) {
            return null;
        }
        String destinazione = punto.relativize(f).toString().replace('\\', '/');
        CopiaTelefono copia = mongo.findOne(Query.query(Criteria.where("destinazione").is(destinazione))
                .with(Sort.by(Sort.Direction.DESC, "copiatoIl")).limit(1), CopiaTelefono.class);
        if (copia == null) {
            return null;
        }
        String proprietario = copia.idSorgente() == null ? null
                : sorgente(copia.idSorgente()).map(SorgenteTelefono::proprietario).orElse(null);
        return proprietario != null ? proprietario : copia.proprietario();
    }

    // ------------------------------------------------------------ dove

    /**
     * Perché la sincronizzazione qui non può girare; null se può. Serve
     * rclone (il server) e una cartella automatica dentro il punto di
     * montaggio, che diventa la destinazione nel remote del cloud.
     */
    public String motivo() {
        if (!cloud.gestito()) {
            return "qui l'archivio è un disco locale, senza rclone: la sincronizzazione del telefono c'è solo sul server";
        }
        Path cartella = automatica.cartella();
        if (cartella == null) {
            return "la cartella automatica (fototimeline.importazione-automatica.cartella) non è impostata";
        }
        if (cloud.puntoMontaggio() == null || cloud.puntoMontaggio().isBlank()) {
            return "il punto di montaggio del cloud non è impostato";
        }
        Path punto = Path.of(cloud.puntoMontaggio()).normalize();
        Path c = cartella.normalize();
        if (!c.startsWith(punto) || c.equals(punto)) {
            return "la cartella automatica " + cartella + " non sta dentro il punto di montaggio " + punto;
        }
        return null;
    }

    /** La cartella automatica relativa al remote del cloud: {@code /cloud/telefono} → {@code telefono}. */
    private String destinazione() {
        Path punto = Path.of(cloud.puntoMontaggio()).normalize();
        return punto.relativize(automatica.cartella().normalize()).toString().replace('\\', '/');
    }

    /** Per mostrarla: {@code lifetime:} + {@code telefono} → {@code lifetime:telefono}. */
    private String nelCloud(String percorso) {
        String remoto = cloud.remoto();
        return remoto + (remoto.endsWith(":") || remoto.endsWith("/") ? "" : "/") + percorso;
    }

    // ------------------------------------------------------------ quando

    /** True se un giro è in coda o in corso, di qualunque telefono. */
    public boolean inCorso() {
        return !prenotati.isEmpty();
    }

    /**
     * "Sincronizza ora": va in coda e parte appena tocca a lui. Con un giro
     * di questo telefono già in coda o senza rclone, IllegalStateException (409).
     */
    public Stato avvia(String id) {
        String motivo = motivo();
        if (motivo != null) {
            throw new IllegalStateException("La sincronizzazione non può partire: " + motivo);
        }
        SorgenteTelefono s = sorgente(id).orElseThrow(() -> nonTrovato(id));
        prendi(id);
        try {
            esecutore.submit(() -> {
                try {
                    esegui(id);
                } finally {
                    prenotati.remove(id);
                }
            });
        } catch (RuntimeException e) {
            prenotati.remove(id);
            throw e;
        }
        return stato(s);
    }

    /** Un giro adesso, su questo thread (per i test). */
    Giro sincronizza(String id) {
        prendi(id);
        try {
            return esegui(id);
        } finally {
            prenotati.remove(id);
        }
    }

    private void prendi(String id) {
        if (!prenotati.add(id)) {
            throw new IllegalStateException("Questo telefono si sta già sincronizzando");
        }
    }

    /**
     * Ogni pochi minuti: mette in coda i telefoni attivi per cui sono passate
     * le ore dall'ultimo giro. Girano uno alla volta, nell'ordine dell'elenco.
     */
    @Scheduled(fixedDelayString = "${fototimeline.telefono.controllo:PT5M}",
            initialDelayString = "${fototimeline.telefono.ritardo-iniziale:PT3M}")
    public void seNecessario() {
        if (motivo() != null) {
            return;
        }
        for (SorgenteTelefono s : sorgenti()) {
            Giro ultimo = s.ultimoGiro();
            if (!s.attiva() || prenotati.contains(s.id()) || ultimo != null
                    && ultimo.iniziatoIl().plus(Duration.ofHours(s.intervalloOre())).isAfter(clock.instant())) {
                continue;
            }
            try {
                avvia(s.id());
            } catch (IllegalStateException | NoSuchElementException e) {
                log.debug("Sincronizzazione di {} rimandata: {}", s.nome(), e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ il giro

    private Giro esegui(String id) {
        SorgenteTelefono s = sorgente(id).orElse(null);
        if (s == null) {
            // Tolto mentre aspettava in coda.
            return null;
        }
        Instant inizio = clock.instant();
        Contatori c = new Contatori();
        correnti.put(id, new Corrente(inizio, c));
        Esito esito;
        String messaggio;
        try {
            Sorgente sorgente = Sorgente.da(s.sorgente());
            copia(s, sorgente, destinazione(), inizio, c);
            if (s.giorniPrimaDiCancellare() > 0) {
                cancella(sorgente, inizio.minus(Duration.ofDays(s.giorniPrimaDiCancellare())), inizio, c);
            }
            int errori = c.errori();
            esito = errori == 0 ? Esito.OK : Esito.ERRORE;
            messaggio = errori == 0 ? "Fatto" : errori + " file non riusciti";
        } catch (RuntimeException e) {
            log.warn("Sincronizzazione di {} non riuscita: {}", s.nome(), e.getMessage());
            esito = Esito.ERRORE;
            messaggio = e.getMessage();
        }
        Giro giro = c.giro(inizio, clock.instant(), esito, messaggio);
        correnti.remove(id);
        // updateFirst e non upsert: un telefono tolto durante il giro non torna.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), Update.update("ultimoGiro", giro),
                SorgenteTelefono.class);
        log.info("Sincronizzazione di {} {}: {} trovati, {} copiati, {} già copiati, {} tolti dalla sorgente, {} errori",
                s.nome(), esito, giro.trovati(), giro.copiati(), giro.giaCopiati(), giro.cancellati(), giro.errori());
        return giro;
    }

    /** Copia nella cartella automatica i file della sorgente che non sono nel registro. */
    private void copia(SorgenteTelefono telefono, Sorgente sorgente, String cartella, Instant adesso, Contatori c) {
        int inParallelo = telefono.copie();
        Map<String, Object> risposta = rclone.chiama("operations/list", Map.of(
                "fs", sorgente.fs(),
                "remote", sorgente.percorso(),
                "opt", Map.of("recurse", true, "filesOnly", true)));
        Set<String> registrate = ConcurrentHashMap.newKeySet();
        for (CopiaTelefono copia : mongo.find(Query.query(Criteria.where("sorgente").is(sorgente.testo())),
                CopiaTelefono.class)) {
            registrate.add(chiave(copia.percorso(), copia.dimensione()));
        }
        if (!(risposta.get("list") instanceof List<?> lista)) {
            throw new IllegalStateException("operations/list: risposta di rclone senza elenco");
        }
        // I nomi presi in questo giro: due copie in parallelo non devono scegliere lo stesso.
        Set<String> prenotati = ConcurrentHashMap.newKeySet();
        ExecutorService copie = Executors.newFixedThreadPool(inParallelo, r -> {
            Thread t = new Thread(r, "telefono-copia");
            t.setDaemon(true);
            return t;
        });
        List<Future<?>> lavori = new ArrayList<>();
        try {
            for (Object o : lista) {
                if (!(o instanceof Map<?, ?> voce) || Boolean.TRUE.equals(voce.get("IsDir"))
                        || !(voce.get("Path") instanceof String path)) {
                    continue;
                }
                String relativo = sorgente.relativo(path);
                String nome = relativo.substring(relativo.lastIndexOf('/') + 1);
                if (nome.startsWith(".") || !FotoService.eMedia(nome)) {
                    continue;
                }
                c.trovato();
                long dimensione = numero(voce.get("Size"));
                if (registrate.contains(chiave(relativo, dimensione))) {
                    c.giaCopiato();
                    continue;
                }
                lavori.add(copie.submit(() -> {
                    try {
                        String destinazione = copiaFile(sorgente, relativo, dimensione, cartella, prenotati);
                        mongo.insert(new CopiaTelefono(new ObjectId().toHexString(), sorgente.testo(), telefono.id(),
                                telefono.proprietario(), relativo, dimensione, data(voce.get("ModTime")), destinazione,
                                adesso, null));
                        registrate.add(chiave(relativo, dimensione));
                        if (c.copiato() % 100 == 0) {
                            log.info("Sincronizzazione di {}: {} copiati finora", telefono.nome(), c.copiati());
                        }
                    } catch (RuntimeException e) {
                        c.errore(relativo, e);
                    }
                }));
            }
            for (Future<?> lavoro : lavori) {
                lavoro.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("sincronizzazione interrotta");
        } catch (ExecutionException e) {
            throw new IllegalStateException(e.getCause().getMessage(), e.getCause());
        } finally {
            copie.shutdownNow();
        }
    }

    /**
     * Copia un file e controlla che sia arrivato intero. Niente sottocartelle
     * in destinazione (diventerebbero album coi nomi dei dispositivi):
     * {@code Pixel 8/IMG_1.jpg} → {@code telefono/Pixel 8 - IMG_1.jpg}.
     */
    private String copiaFile(Sorgente sorgente, String relativo, long dimensione, String cartella,
            Set<String> prenotati) {
        String destinazione = libero(cartella + "/" + relativo.replace("/", " - "), prenotati);
        rclone.chiama("operations/copyfile", Map.of(
                "srcFs", sorgente.fs(),
                "srcRemote", sorgente.dentro(relativo),
                "dstFs", cloud.remoto(),
                "dstRemote", destinazione));
        Map<?, ?> copiato = stat(cloud.remoto(), destinazione);
        if (copiato == null || numero(copiato.get("Size")) != dimensione) {
            String arrivati = copiato == null ? "nessun file" : numero(copiato.get("Size")) + " byte";
            try {
                // Una copia a metà non deve finire nell'archivio: il prossimo giro la rifà.
                if (copiato != null) {
                    rclone.chiama("operations/deletefile", Map.of("fs", cloud.remoto(), "remote", destinazione));
                }
            } catch (RuntimeException e) {
                log.warn("Copia incompleta {} non tolta: {}", destinazione, e.getMessage());
            }
            throw new IllegalStateException("copia incompleta: " + dimensione + " byte attesi, " + arrivati);
        }
        return destinazione;
    }

    /** Il nome se è libero, altrimenti "nome (2).ext", "nome (3).ext"...: non si sovrascrive mai. */
    private String libero(String voluto, Set<String> prenotati) {
        int punto = voluto.lastIndexOf('.');
        String base = punto > voluto.lastIndexOf('/') ? voluto.substring(0, punto) : voluto;
        String estensione = voluto.substring(base.length());
        String nome = voluto;
        for (int n = 2; !prenotati.add(nome) || stat(cloud.remoto(), nome) != null; n++) {
            if (n > 1000) {
                throw new IllegalStateException("troppi file con il nome " + voluto);
            }
            nome = base + " (" + n + ")" + estensione;
        }
        return nome;
    }

    /**
     * Toglie dalla sorgente i file copiati prima di {@code limite} che non
     * sono più nella cartella automatica, cioè che l'importazione ha spostato
     * nell'archivio. Quelli ancora lì (importazione non riuscita) restano.
     * Solo le copie di questa sorgente: ogni telefono ha i suoi giorni.
     */
    private void cancella(Sorgente questa, Instant limite, Instant adesso, Contatori c) {
        Query vecchie = Query.query(Criteria.where("cancellatoIl").is(null).and("copiatoIl").lt(limite)
                .and("sorgente").is(questa.testo()));
        for (CopiaTelefono copia : mongo.find(vecchie, CopiaTelefono.class)) {
            try {
                if (stat(cloud.remoto(), copia.destinazione()) != null) {
                    continue;
                }
                Sorgente sorgente = Sorgente.da(copia.sorgente());
                String remoto = sorgente.dentro(copia.percorso());
                Map<?, ?> originale = stat(sorgente.fs(), remoto);
                // Un file con lo stesso nome ma un'altra dimensione è un altro file: resta.
                if (originale != null && numero(originale.get("Size")) == copia.dimensione()) {
                    rclone.chiama("operations/deletefile", Map.of("fs", sorgente.fs(), "remote", remoto));
                    c.cancellato();
                }
                mongo.updateFirst(Query.query(Criteria.where("_id").is(copia.id())),
                        Update.update("cancellatoIl", adesso), CopiaTelefono.class);
            } catch (RuntimeException e) {
                c.errore(copia.percorso(), e);
            }
        }
    }

    /** {@code operations/stat}: la voce, o null se il file non c'è. */
    private Map<?, ?> stat(String fs, String remote) {
        Object voce = rclone.chiama("operations/stat", Map.of("fs", fs, "remote", remote)).get("item");
        return voce instanceof Map<?, ?> m ? m : null;
    }

    private static String chiave(String percorso, long dimensione) {
        return dimensione + ":" + percorso;
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

    @PreDestroy
    void chiudi() {
        esecutore.shutdownNow();
    }

    /**
     * {@code pcloud:Automatic Upload}: il remote ({@code pcloud:}) e il
     * percorso dentro ({@code Automatic Upload}, senza / ai lati).
     */
    record Sorgente(String testo, String fs, String percorso) {

        static Sorgente da(String testo) {
            int duePunti = testo == null ? -1 : testo.indexOf(':');
            if (duePunti <= 0) {
                throw new IllegalArgumentException(
                        "Cartella su pCloud: remote di rclone e percorso, per esempio \"pcloud:Automatic Upload\"");
            }
            String percorso = testo.substring(duePunti + 1).replaceAll("^/+|/+$", "");
            return new Sorgente(testo, testo.substring(0, duePunti + 1), percorso);
        }

        String dentro(String relativo) {
            return percorso.isEmpty() ? relativo : percorso + "/" + relativo;
        }

        /** Il Path di operations/list, relativo alla sorgente (lo è già; per sicurezza toglie il prefisso). */
        String relativo(String path) {
            return !percorso.isEmpty() && path.startsWith(percorso + "/") ? path.substring(percorso.length() + 1) : path;
        }

        /**
         * La stessa cartella, anche scritta in un altro modo
         * ({@code pcloud:/Automatic Upload/}), o una dentro l'altra: due
         * telefoni così copierebbero gli stessi file.
         */
        boolean sovrapposta(Sorgente altra) {
            return fs.equals(altra.fs) && (percorso.equals(altra.percorso) || dentro(percorso, altra.percorso)
                    || dentro(altra.percorso, percorso));
        }

        private static boolean dentro(String figlia, String madre) {
            return madre.isEmpty() || figlia.startsWith(madre + "/");
        }
    }

    /** Si aggiornano dalle copie in parallelo e si leggono dall'API mentre il giro gira. */
    private static final class Contatori {
        private int trovati;
        private int copiati;
        private int giaCopiati;
        private int cancellati;
        private int errori;
        private final List<String> messaggi = new ArrayList<>();

        synchronized void trovato() {
            trovati++;
        }

        synchronized void giaCopiato() {
            giaCopiati++;
        }

        synchronized int copiato() {
            return ++copiati;
        }

        synchronized int copiati() {
            return copiati;
        }

        synchronized void cancellato() {
            cancellati++;
        }

        synchronized int errori() {
            return errori;
        }

        synchronized void errore(String file, RuntimeException e) {
            errori++;
            log.warn("Telefono, {}: {}", file, e.getMessage());
            if (messaggi.size() < MAX_MESSAGGI) {
                messaggi.add(file + ": " + e.getMessage());
            }
        }

        synchronized Giro giro(Instant inizio, Instant fine, Esito esito, String messaggio) {
            return new Giro(inizio, fine, esito, messaggio, trovati, copiati, giaCopiati, cancellati, errori,
                    List.copyOf(messaggi));
        }
    }
}
