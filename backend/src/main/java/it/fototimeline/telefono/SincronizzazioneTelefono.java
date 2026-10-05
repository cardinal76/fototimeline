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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutionException;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import it.fototimeline.telefono.ImpostazioniTelefono.Esito;
import it.fototimeline.telefono.ImpostazioniTelefono.Giro;

/**
 * Porta le foto nuove del telefono (pCloud, "Automatic Upload") nella
 * cartella automatica del cloud, da dove l'importazione automatica le sposta
 * nell'archivio. Dopo {@code giorniPrimaDiCancellare} giorni toglie dalla
 * sorgente i file che l'importazione ha davvero preso.
 *
 * <p>Tutto passa dall'API rc di rclone, non dal montaggio: la copia va anche
 * col cloud smontato. Un giro alla volta, su un thread suo.
 */
@Service
public class SincronizzazioneTelefono {

    private static final Logger log = LoggerFactory.getLogger(SincronizzazioneTelefono.class);
    static final String ID = "sincronizzazione-telefono";
    private static final int MAX_MESSAGGI = 20;
    private static final int MAX_ORE = 168;
    /** Copie insieme: con tante foto piccole il tempo va nell'attesa per file, non nella banda. */
    static final int COPIE_IN_PARALLELO = 6;

    private final Rclone rclone;
    private final CloudProperties cloud;
    private final ImportazioneAutomaticaProperties automatica;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "telefono");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inCorso = new AtomicBoolean();
    /** Il giro che sta girando, per mostrarne i contatori prima che finisca. */
    private volatile Contatori corrente;
    private volatile Instant inizioCorrente;

    public SincronizzazioneTelefono(Rclone rclone, CloudProperties cloud, ImportazioneAutomaticaProperties automatica,
            MongoTemplate mongo, Optional<Clock> clock) {
        this.rclone = rclone;
        this.cloud = cloud;
        this.automatica = automatica;
        this.mongo = mongo;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Impostazioni e stato, per l'API. {@code motivo} dice perché non si può attivare. */
    public record Stato(boolean attiva, String sorgente, int intervalloOre, int giorniPrimaDiCancellare,
            boolean disponibile, String motivo, String destinazione, boolean inCorso, Giro ultimoGiro,
            Instant prossimoGiroIl, Giro giroInCorso) {
    }

    /** Quello che si cambia dalla pagina; un campo null resta com'è. */
    public record Modifica(Boolean attiva, String sorgente, Integer intervalloOre, Integer giorniPrimaDiCancellare) {
    }

    public ImpostazioniTelefono impostazioni() {
        ImpostazioniTelefono i = mongo.findById(ID, ImpostazioniTelefono.class);
        return i != null ? i : ImpostazioniTelefono.predefinite();
    }

    public Stato stato() {
        ImpostazioniTelefono i = impostazioni();
        String motivo = motivo();
        Instant prossimo = null;
        if (i.attiva() && motivo == null) {
            prossimo = i.ultimoGiro() == null ? clock.instant()
                    : i.ultimoGiro().iniziatoIl().plus(Duration.ofHours(i.intervalloOre()));
        }
        Contatori c = corrente;
        Instant inizio = inizioCorrente;
        Giro giroInCorso = c != null && inizio != null ? c.giro(inizio, null, null, "In corso") : null;
        return new Stato(i.attiva(), i.sorgente(), i.intervalloOre(), i.giorniPrimaDiCancellare(), motivo == null,
                motivo, motivo == null ? nelCloud(destinazione()) : null, inCorso.get(), i.ultimoGiro(), prossimo,
                giroInCorso);
    }

    /** Salva le impostazioni; valori fuori misura → IllegalArgumentException (400). */
    public Stato salva(Modifica m) {
        ImpostazioniTelefono ora = impostazioni();
        boolean attiva = m.attiva() != null ? m.attiva() : ora.attiva();
        String sorgente = m.sorgente() != null ? m.sorgente().trim() : ora.sorgente();
        int ore = m.intervalloOre() != null ? m.intervalloOre() : ora.intervalloOre();
        int giorni = m.giorniPrimaDiCancellare() != null ? m.giorniPrimaDiCancellare() : ora.giorniPrimaDiCancellare();
        Sorgente.da(sorgente);
        if (ore < 1 || ore > MAX_ORE) {
            throw new IllegalArgumentException("Ogni quante ore: da 1 a " + MAX_ORE);
        }
        if (giorni < 0) {
            throw new IllegalArgumentException("Giorni prima di togliere da pCloud: 0 (mai) o di più");
        }
        String motivo = motivo();
        if (attiva && motivo != null) {
            throw new IllegalArgumentException("La sincronizzazione non si può attivare: " + motivo);
        }
        mongo.upsert(Query.query(Criteria.where("_id").is(ID)), new Update()
                .set("attiva", attiva)
                .set("sorgente", sorgente)
                .set("intervalloOre", ore)
                .set("giorniPrimaDiCancellare", giorni), ImpostazioniTelefono.class);
        log.info("Sincronizzazione del telefono: attiva={}, sorgente={}, ogni {} ore, cancella dopo {} giorni",
                attiva, sorgente, ore, giorni);
        return stato();
    }

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

    public boolean inCorso() {
        return inCorso.get();
    }

    /** "Sincronizza ora": parte in sottofondo. Con un giro in corso o senza rclone, IllegalStateException (409). */
    public Stato avvia() {
        String motivo = motivo();
        if (motivo != null) {
            throw new IllegalStateException("La sincronizzazione non può partire: " + motivo);
        }
        prendi();
        try {
            esecutore.submit(() -> {
                try {
                    esegui();
                } finally {
                    inCorso.set(false);
                }
            });
        } catch (RuntimeException e) {
            inCorso.set(false);
            throw e;
        }
        return stato();
    }

    /** Un giro adesso, su questo thread (per i test). */
    Giro sincronizza() {
        prendi();
        try {
            return esegui();
        } finally {
            inCorso.set(false);
        }
    }

    private void prendi() {
        if (!inCorso.compareAndSet(false, true)) {
            throw new IllegalStateException("C'è già una sincronizzazione del telefono in corso");
        }
    }

    /** Ogni pochi minuti: se è attiva e sono passate le ore dall'ultimo giro, ne lancia uno. */
    @Scheduled(fixedDelayString = "${fototimeline.telefono.controllo:PT5M}",
            initialDelayString = "${fototimeline.telefono.ritardo-iniziale:PT3M}")
    public void seNecessario() {
        ImpostazioniTelefono i = impostazioni();
        if (!i.attiva() || inCorso.get() || motivo() != null) {
            return;
        }
        Giro ultimo = i.ultimoGiro();
        if (ultimo != null && ultimo.iniziatoIl().plus(Duration.ofHours(i.intervalloOre())).isAfter(clock.instant())) {
            return;
        }
        try {
            avvia();
        } catch (IllegalStateException e) {
            log.debug("Sincronizzazione del telefono rimandata: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------ il giro

    private Giro esegui() {
        Instant inizio = clock.instant();
        ImpostazioniTelefono i = impostazioni();
        Contatori c = new Contatori();
        inizioCorrente = inizio;
        corrente = c;
        Esito esito;
        String messaggio;
        try {
            Sorgente sorgente = Sorgente.da(i.sorgente());
            copia(sorgente, destinazione(), inizio, c);
            if (i.giorniPrimaDiCancellare() > 0) {
                cancella(inizio.minus(Duration.ofDays(i.giorniPrimaDiCancellare())), inizio, c);
            }
            int errori = c.errori();
            esito = errori == 0 ? Esito.OK : Esito.ERRORE;
            messaggio = errori == 0 ? "Fatto" : errori + " file non riusciti";
        } catch (RuntimeException e) {
            log.warn("Sincronizzazione del telefono non riuscita: {}", e.getMessage());
            esito = Esito.ERRORE;
            messaggio = e.getMessage();
        }
        Giro giro = c.giro(inizio, clock.instant(), esito, messaggio);
        corrente = null;
        inizioCorrente = null;
        ImpostazioniTelefono p = ImpostazioniTelefono.predefinite();
        mongo.upsert(Query.query(Criteria.where("_id").is(ID)), new Update()
                .set("ultimoGiro", giro)
                .setOnInsert("attiva", p.attiva())
                .setOnInsert("sorgente", p.sorgente())
                .setOnInsert("intervalloOre", p.intervalloOre())
                .setOnInsert("giorniPrimaDiCancellare", p.giorniPrimaDiCancellare()), ImpostazioniTelefono.class);
        log.info("Sincronizzazione del telefono {}: {} trovati, {} copiati, {} già copiati, {} tolti dalla sorgente, {} errori",
                esito, giro.trovati(), giro.copiati(), giro.giaCopiati(), giro.cancellati(), giro.errori());
        return giro;
    }

    /** Copia nella cartella automatica i file della sorgente che non sono nel registro. */
    private void copia(Sorgente sorgente, String cartella, Instant adesso, Contatori c) {
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
        ExecutorService copie = Executors.newFixedThreadPool(COPIE_IN_PARALLELO, r -> {
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
                        mongo.insert(new CopiaTelefono(new ObjectId().toHexString(), sorgente.testo(), relativo,
                                dimensione, data(voce.get("ModTime")), destinazione, adesso, null));
                        registrate.add(chiave(relativo, dimensione));
                        if (c.copiato() % 100 == 0) {
                            log.info("Sincronizzazione del telefono: {} copiati finora", c.copiati());
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
     */
    private void cancella(Instant limite, Instant adesso, Contatori c) {
        Query vecchie = Query.query(Criteria.where("cancellatoIl").is(null).and("copiatoIl").lt(limite));
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
