package it.fototimeline.telefono;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.cloud.CloudProperties;
import it.fototimeline.cloud.Rclone;
import it.fototimeline.cloud.RcloneNonRiesce;
import it.fototimeline.dominio.Foto;
import it.fototimeline.media.FormatoVideo;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.media.TurnoVideo;
import it.fototimeline.telefono.SincronizzazioneTelefono.Sorgente;
import it.fototimeline.telefono.VideoGrande.Stato;
import jakarta.annotation.PreDestroy;

/**
 * I video del telefono che LifetimeCloud non prende: oltre 20480 MB risponde
 * "File too large" (403), e il suo nginx a volte rifiuta con 413 anche sotto.
 * Invece di restare errori a ogni giro si ricomprimono in H.265 sul server:
 *
 * <ol>
 * <li>il giro del telefono ({@link SincronizzazioneTelefono}) mette in coda
 * quelli oltre {@code massimo-mb} senza provare a copiarli, e quelli che il
 * cloud rifiuta per la dimensione ({@link #rifiutatoPerDimensione});</li>
 * <li>qui, uno alla volta su un thread suo: rclone scarica l'originale da
 * pCloud nella cartella del server, ffmpeg lo ricomprime
 * ({@link StrumentiMedia#ricomprimi}, aspettando il {@link TurnoVideo}),
 * si controlla che la durata sia quella dell'originale e la dimensione sotto
 * il limite (altrimenti un CRF più alto), e il ricompresso va nella cartella
 * automatica col nome dell'originale, nel registro delle copie
 * ({@code ricompresso}). L'importazione automatica lo prende come ogni altro
 * file del telefono: scheda, miniatura, data dai metadati, "caricata da";</li>
 * <li>a un giro successivo, <b>solo</b> se c'è una scheda con lo SHA-256 del
 * ricompresso, l'originale si sposta su pCloud (lato server,
 * {@code operations/movefile}) nella cartella dedicata, senza sovrascrivere.
 * L'originale non si cancella mai; la sincronizzazione salta quella
 * cartella.</li>
 * </ol>
 *
 * <p>Lo stato sta in MongoDB ({@link VideoGrande}): dopo un riavvio il video
 * a metà riparte da capo. Spazio che manca o rclone che non risponde
 * rimandano (si riprova da solo); una durata diversa o un video che resta
 * troppo grande anche col CRF più alto sono errori, e lo restano a ogni giro
 * finché non si preme "Riprova".
 */
@Service
public class VideoGrandi {

    private static final Logger log = LoggerFactory.getLogger(VideoGrandi.class);
    /** Gli errori del cloud che vogliono dire "troppo grande": quello di LifetimeCloud (403) e quelli di nginx (413). */
    private static final Pattern TROPPO_GRANDE = Pattern.compile(
            "file too large|entity too large|payload too large|\\b413\\b.{0,40}too large", Pattern.CASE_INSENSITIVE);
    private static final Set<String> VIDEO = Set.of("mp4", "m4v", "mov");
    /** La velocità più bassa che si aspetta da una copia, per il suo tempo massimo. */
    private static final long BYTE_AL_SECONDO = 256L * 1024;
    /** Il ricompresso deve stare almeno così sotto l'originale (e sotto il limite che il cloud ha rifiutato). */
    private static final double MARGINE = 0.95;
    private static final Duration FATTI_DA_MOSTRARE = Duration.ofDays(30);
    private static final int MAX_DA_MOSTRARE = 20;
    private static final String NOME_RICOMPRESSO = "ricompresso.mp4";

    /**
     * Le destinazioni nella cartella automatica che qualcuno sta scrivendo:
     * le copie del giro e i ricompressi caricati da qui. Chi ci aggiunge un
     * nome se lo prenota; l'importazione non le legge a metà
     * ({@link SincronizzazioneTelefono#inArrivo}).
     */
    final Set<String> inCopia = ConcurrentHashMap.newKeySet();

    private final Rclone rclone;
    private final CloudProperties cloud;
    private final MongoTemplate mongo;
    private final StrumentiMedia media;
    private final TurnoVideo turno;
    private final VideoGrandiProperties properties;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "video-grandi");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean inGiro = new AtomicBoolean();
    private final AtomicBoolean richiesto = new AtomicBoolean();
    private volatile boolean chiuso;
    /** False nei test: la coda gira solo con {@link #lavoraTutti()}, sul thread del test. */
    private volatile boolean inSottofondo = true;
    private volatile Lavoro corrente;
    private Duration attesaCopia = Duration.ofSeconds(2);
    private Duration limiteCopia = Duration.ofMinutes(15);
    /** Lo spazio libero sul disco della cartella; nei test si finge. */
    private ToLongFunction<Path> spazioLibero = VideoGrandi::liberoSulDisco;

    public VideoGrandi(Rclone rclone, CloudProperties cloud, MongoTemplate mongo, StrumentiMedia media,
            TurnoVideo turno, VideoGrandiProperties properties, Optional<Clock> clock) {
        this.rclone = rclone;
        this.cloud = cloud;
        this.mongo = mongo;
        this.media = media;
        this.turno = turno;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /**
     * Un video troppo grande per l'API: com'è messo, quanto pesava e quanto
     * pesa ricompresso, dove è andato l'originale. {@code fase} e
     * {@code percentuale} solo per quello in corso.
     */
    public record Vista(String id, String nome, String percorso, Stato stato, String fase, Integer percentuale,
            long dimensione, Long dimensioneRicompresso, Integer crf, String destinazione, String originaleIn,
            String motivo, String messaggio, Instant creatoIl, Instant aggiornatoIl) {
    }

    /** Quello che il giro di un telefono dice dei suoi video troppo grandi. */
    record Riepilogo(int ricompressi, int inCorso, List<String> righe, List<String[]> errori) {
    }

    // ------------------------------------------------------------ riconoscere

    /** True se la ricompressione può girare: accesa, con rclone e con ffmpeg. */
    public boolean attiva() {
        return properties.attiva() && cloud.gestito() && media.videoDisponibile();
    }

    /** True se il cloud ha rifiutato il file perché troppo grande ("File too large", 413). */
    public static boolean rifiutatoPerDimensione(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t.getMessage() != null && TROPPO_GRANDE.matcher(t.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }

    static boolean eVideo(String nome) {
        int punto = nome.lastIndexOf('.');
        return punto >= 0 && VIDEO.contains(nome.substring(punto + 1).toLowerCase(Locale.ROOT));
    }

    /** True se questo file va ricompresso senza nemmeno provare a copiarlo: un video oltre la soglia. */
    boolean oltreLaSoglia(String nome, long dimensione) {
        return attiva() && eVideo(nome) && dimensione > properties.massimo();
    }

    /** True se un errore di copia di questo file vuol dire "ricomprimilo". */
    boolean daRicomprimere(String nome, RuntimeException errore) {
        return attiva() && eVideo(nome) && rifiutatoPerDimensione(errore);
    }

    /** True se il percorso (dalla radice del remote) è nella cartella degli originali: la sincronizzazione lo salta. */
    boolean nellaCartellaOriginali(String percorso) {
        String cartella = properties.cartellaOriginali();
        return percorso.equals(cartella) || percorso.startsWith(cartella + "/");
    }

    /** True se il video ha (o sta caricando) un ricompresso nella cartella automatica. */
    static boolean haUnRicompresso(VideoGrande v) {
        return v.stato() == Stato.IN_CORSO || v.stato() == Stato.CARICATO || v.stato() == Stato.FATTO
                || v.destinazione() != null;
    }

    String soglia() {
        return "oltre i " + properties.massimoMb() + " MB";
    }

    // ------------------------------------------------------------ dal giro del telefono

    /** I video troppo grandi di questa cartella, per chiave del registro ({@code dimensione:percorso}). */
    Map<String, VideoGrande> di(Sorgente sorgente) {
        Map<String, VideoGrande> tutti = new HashMap<>();
        for (VideoGrande v : mongo.find(Query.query(Criteria.where("sorgente").is(sorgente.testo())), VideoGrande.class)) {
            tutti.put(chiave(v.percorso(), v.dimensione()), v);
        }
        return tutti;
    }

    /** Mette in coda un video; se c'è già (stessa cartella, percorso e dimensione) non fa niente. */
    void metti(SorgenteTelefono telefono, Sorgente sorgente, String cartella, String percorso, long dimensione,
            Instant modificatoIl, String motivo) {
        Instant adesso = clock.instant();
        try {
            mongo.insert(new VideoGrande(new ObjectId().toHexString(), sorgente.testo(), telefono.id(),
                    telefono.proprietario(), percorso, dimensione, modificatoIl, cartella, motivo, Stato.IN_CODA,
                    adesso, adesso, null, 0, null, null, null, null, null, null, null, null));
            log.info("Telefono {}, {} ({}): troppo grande per il cloud ({}), in coda per la ricompressione",
                    telefono.nome(), percorso, peso(dimensione), motivo);
        } catch (DuplicateKeyException e) {
            // Già in coda da un giro di prima.
        }
    }

    /**
     * Per il giro di un telefono, dopo le copie: toglie i video spariti da
     * pCloud prima di cominciare, sposta gli originali di quelli importati
     * ({@link #mettiDaParte}) e dice com'è messo ognuno. {@code presenti}
     * sono le chiavi dei file che pCloud ha appena elencato.
     */
    Riepilogo giro(Sorgente sorgente, Set<String> presenti) {
        int ricompressi = 0;
        int inCorso = 0;
        List<String> righe = new ArrayList<>();
        List<String[]> errori = new ArrayList<>();
        for (VideoGrande v : mongo.find(Query.query(Criteria.where("sorgente").is(sorgente.testo()))
                .with(Sort.by("creatoIl", "_id")), VideoGrande.class)) {
            if (!presenti.contains(chiave(v.percorso(), v.dimensione()))
                    && List.of(Stato.IN_CODA, Stato.RIMANDATO, Stato.ERRORE, Stato.ANNULLATO).contains(v.stato())) {
                // Tolto da pCloud (o cambiato) prima della ricompressione: non c'è più niente da fare.
                if (mongo.remove(Query.query(Criteria.where("_id").is(v.id()).and("stato").is(v.stato())),
                        VideoGrande.class).getDeletedCount() > 0) {
                    log.info("Telefono, {}: non è più su pCloud, tolto dalla coda dei video grandi", v.percorso());
                }
                continue;
            }
            switch (v.stato()) {
                case CARICATO -> {
                    try {
                        VideoGrande fatto = mettiDaParte(sorgente, v);
                        if (fatto == null) {
                            inCorso++;
                            righe.add(v.nome() + ": ricompresso (" + peso(v.dimensione()) + " → "
                                    + peso(v.dimensioneRicompresso()) + "), aspetta l'importazione");
                        } else if (fatto.stato() == Stato.FATTO) {
                            ricompressi++;
                            righe.add(riga(fatto));
                        } else {
                            errori.add(new String[] {v.percorso(), fatto.messaggio()});
                        }
                    } catch (RuntimeException e) {
                        errori.add(new String[] {v.percorso(), "originale non spostato: " + e.getMessage()});
                    }
                }
                case IN_CODA -> {
                    inCorso++;
                    righe.add(v.nome() + " (" + peso(v.dimensione()) + "): in coda per la ricompressione");
                }
                case IN_CORSO -> {
                    inCorso++;
                    Lavoro l = corrente;
                    righe.add(v.nome() + " (" + peso(v.dimensione()) + "): "
                            + (l != null && l.id.equals(v.id()) ? l.descrizione() : "in ricompressione"));
                }
                case RIMANDATO -> {
                    inCorso++;
                    righe.add(v.nome() + " (" + peso(v.dimensione()) + "): rimandato, " + v.messaggio());
                }
                case ANNULLATO -> righe.add(v.nome() + " (" + peso(v.dimensione()) + "): annullato a mano (Riprova per rifarlo)");
                case ERRORE -> errori.add(new String[] {v.percorso(), "ricompressione non riuscita: " + v.messaggio()});
                case FATTO -> {
                    // Fatto in un giro di prima: si vede nell'elenco del telefono, non nel giro.
                }
            }
        }
        segnala();
        return new Riepilogo(ricompressi, inCorso, righe, errori);
    }

    /**
     * Sposta l'originale di un video ricompresso nella cartella dedicata, ma
     * solo se l'importazione l'ha preso: c'è una scheda con lo SHA-256 del
     * ricompresso (anche nel cestino: l'importazione c'è stata). Lo
     * spostamento è lato server ({@code operations/movefile}), su un nome
     * libero: niente si sovrascrive, niente si cancella. Null se si aspetta
     * ancora l'importazione; altrimenti il video aggiornato (FATTO, o ERRORE
     * se il ricompresso è sparito senza scheda).
     */
    private VideoGrande mettiDaParte(Sorgente sorgente, VideoGrande v) {
        Instant adesso = clock.instant();
        if (v.hash() == null || !mongo.exists(Query.query(Criteria.where("hash").is(v.hash())), Foto.class)) {
            if (stat(cloud.remoto(), v.destinazione()) != null) {
                return null;
            }
            if (v.caricatoIl() != null && v.caricatoIl().plus(properties.attesaImportazione()).isAfter(adesso)) {
                // Forse l'importazione l'ha appena spostato e sta salvando la scheda.
                return null;
            }
            String messaggio = "il ricompresso " + v.destinazione() + " non è più nella cartella automatica e non c'è"
                    + " una foto con il suo contenuto: l'originale resta dov'è";
            aggiorna(v, Stato.CARICATO, new Update().set("stato", Stato.ERRORE).set("messaggio", messaggio));
            return conStato(v, Stato.ERRORE, messaggio);
        }
        String remoto = sorgente.dentro(v.percorso());
        Map<?, ?> originale = stat(sorgente.fs(), remoto);
        String dove = null;
        String nota = null;
        if (originale != null && numero(originale.get("Size")) == v.dimensione()) {
            String destinazione = libero(sorgente.fs(), properties.cartellaOriginali() + "/" + v.nome());
            rclone.chiama("operations/movefile", Map.of(
                    "srcFs", sorgente.fs(), "srcRemote", remoto,
                    "dstFs", sorgente.fs(), "dstRemote", destinazione));
            dove = sorgente.fs() + destinazione;
            log.info("Telefono, {}: importato ricompresso, originale spostato in {}", v.percorso(), dove);
        } else {
            // Uno spostamento di un giro di prima la cui risposta si è persa? Allora è già nella cartella.
            String giaLi = properties.cartellaOriginali() + "/" + v.nome();
            Map<?, ?> spostato = stat(sorgente.fs(), giaLi);
            if (spostato != null && numero(spostato.get("Size")) == v.dimensione()) {
                dove = sorgente.fs() + giaLi;
            } else {
                nota = "l'originale non era più su pCloud";
            }
            log.info("Telefono, {}: importato ricompresso; originale {}", v.percorso(), dove != null ? "già in " + dove
                    : "non più su pCloud");
        }
        aggiorna(v, Stato.CARICATO, new Update().set("stato", Stato.FATTO).set("originaleIn", dove)
                .set("finitoIl", adesso).set("messaggio", nota));
        mongo.updateFirst(Query.query(Criteria.where("sorgente").is(v.sorgente()).and("percorso").is(v.percorso())
                .and("dimensione").is(v.dimensione())), Update.update("cancellatoIl", adesso), CopiaTelefono.class);
        return new VideoGrande(v.id(), v.sorgente(), v.idSorgente(), v.proprietario(), v.percorso(), v.dimensione(),
                v.modificatoIl(), v.cartella(), v.motivo(), Stato.FATTO, v.creatoIl(), adesso, null, v.tentativi(),
                v.crf(), v.dimensioneRicompresso(), v.hash(), v.destinazione(), v.caricatoIl(), dove, adesso, nota);
    }

    /** Il nome se è libero nel remote, altrimenti "nome (2).ext", "nome (3).ext"...: non si sovrascrive mai. */
    private String libero(String fs, String voluto) {
        int punto = voluto.lastIndexOf('.');
        String base = punto > voluto.lastIndexOf('/') ? voluto.substring(0, punto) : voluto;
        String estensione = voluto.substring(base.length());
        String nome = voluto;
        for (int n = 2; stat(fs, nome) != null; n++) {
            if (n > 1000) {
                throw new IllegalStateException("troppi file con il nome " + voluto);
            }
            nome = base + " (" + n + ")" + estensione;
        }
        return nome;
    }

    /** Come {@link #libero}, nella cartella automatica, e prenotato in {@link #inCopia}: chi lo prende lo libera. */
    private String liberoInArrivo(String voluto) {
        int punto = voluto.lastIndexOf('.');
        String base = punto > voluto.lastIndexOf('/') ? voluto.substring(0, punto) : voluto;
        String estensione = voluto.substring(base.length());
        String nome = voluto;
        for (int n = 2;; n++) {
            if (inCopia.add(nome)) {
                boolean preso;
                try {
                    preso = stat(cloud.remoto(), nome) != null;
                } catch (RuntimeException e) {
                    inCopia.remove(nome);
                    throw e;
                }
                if (!preso) {
                    return nome;
                }
                inCopia.remove(nome);
            }
            if (n > 1000) {
                throw new IllegalStateException("troppi file con il nome " + voluto);
            }
            nome = base + " (" + n + ")" + estensione;
        }
    }

    // ------------------------------------------------------------ per la pagina

    /** I video troppo grandi di una cartella: quelli in corso o andati male, e i fatti degli ultimi 30 giorni. */
    public List<Vista> elenco(String sorgente) {
        Instant da = clock.instant().minus(FATTI_DA_MOSTRARE);
        Query q = Query.query(new Criteria().andOperator(Criteria.where("sorgente").is(sorgente),
                new Criteria().orOperator(Criteria.where("stato").ne(Stato.FATTO), Criteria.where("finitoIl").gte(da))))
                .with(Sort.by(Sort.Direction.DESC, "creatoIl")).limit(MAX_DA_MOSTRARE);
        Lavoro l = corrente;
        return mongo.find(q, VideoGrande.class).stream().map(v -> {
            boolean questo = l != null && l.id.equals(v.id()) && v.stato() == Stato.IN_CORSO;
            return new Vista(v.id(), v.nome(), v.percorso(), v.stato(), questo ? l.descrizione() : null,
                    questo ? l.percentuale : null, v.dimensione(), v.dimensioneRicompresso(), v.crf(),
                    v.destinazione(), v.originaleIn(), v.motivo(), v.messaggio(), v.creatoIl(), v.aggiornatoIl());
        }).toList();
    }

    public Optional<VideoGrande> video(String id) {
        return Optional.ofNullable(mongo.findById(id, VideoGrande.class));
    }

    /**
     * Ferma un video: in coda o rimandato non parte più, in corso si ferma
     * (rclone o ffmpeg) e i file sul disco si tolgono. L'originale resta su
     * pCloud com'era. False se non c'era niente da fermare.
     */
    public boolean annulla(String id) {
        Instant adesso = clock.instant();
        boolean fermato = mongo.updateFirst(Query.query(Criteria.where("_id").is(id)
                        .and("stato").in(Stato.IN_CODA, Stato.RIMANDATO)),
                new Update().set("stato", Stato.ANNULLATO).set("aggiornatoIl", adesso), VideoGrande.class)
                .getModifiedCount() > 0;
        Lavoro l = corrente;
        if (l != null && l.id.equals(id)) {
            l.annulla();
            fermato = true;
        }
        if (fermato) {
            log.info("Video grande {} annullato", id);
        }
        return fermato;
    }

    /** "Riprova": un video in errore, annullato o rimandato torna in coda da capo. */
    public boolean riprova(String id) {
        boolean rimesso = mongo.updateFirst(Query.query(Criteria.where("_id").is(id)
                        .and("stato").in(Stato.ERRORE, Stato.ANNULLATO, Stato.RIMANDATO)),
                new Update().set("stato", Stato.IN_CODA).set("tentativi", 0).set("riprovaIl", null)
                        .set("messaggio", null).set("aggiornatoIl", clock.instant()), VideoGrande.class)
                .getModifiedCount() > 0;
        if (rimesso) {
            segnala();
        }
        return rimesso;
    }

    // ------------------------------------------------------------ la coda

    /** Dopo un riavvio: il video che era a metà ricomincia, e i file rimasti sul disco si tolgono. */
    @EventListener(ApplicationReadyEvent.class)
    public void riprendi() {
        long ripresi = mongo.updateMulti(Query.query(Criteria.where("stato").is(Stato.IN_CORSO)),
                new Update().set("stato", Stato.IN_CODA), VideoGrande.class).getModifiedCount();
        if (ripresi > 0) {
            log.info("Video grandi ripresi dopo il riavvio: {} rimessi in coda", ripresi);
        }
        if (Files.isDirectory(properties.cartella())) {
            try (Stream<Path> dentro = Files.list(properties.cartella())) {
                // Solo le cartelle fatte da qui (l'id di un video): la cartella potrebbe essere condivisa.
                dentro.filter(p -> ObjectId.isValid(p.getFileName().toString()) && Files.isDirectory(p))
                        .forEach(VideoGrandi::togli);
            } catch (IOException e) {
                log.warn("Video grandi: non riesco a pulire {}: {}", properties.cartella(), e.getMessage());
            }
        }
        segnala();
    }

    /** Ogni {@code controllo}: riparte se c'è da fare (un rimandato da riprovare, un "Riprova"). */
    @Scheduled(fixedDelayString = "${fototimeline.telefono.video-grandi.controllo:PT5M}",
            initialDelayString = "${fototimeline.telefono.video-grandi.controllo:PT5M}")
    public void controlla() {
        segnala();
    }

    /** Fa partire la coda, se non sta già girando. Non blocca. */
    public void segnala() {
        if (!attiva() || chiuso || !inSottofondo) {
            return;
        }
        if (inGiro.compareAndSet(false, true)) {
            esecutore.submit(this::giroCoda);
        } else {
            richiesto.set(true);
        }
    }

    /** Per i test: la coda fino in fondo, su questo thread. */
    void lavoraTutti() {
        VideoGrande v;
        while ((v = prossimo()) != null) {
            lavora(v);
        }
    }

    private void giroCoda() {
        try {
            richiesto.set(false);
            VideoGrande v;
            while (!chiuso && (v = prossimo()) != null) {
                lavora(v);
            }
        } catch (RuntimeException e) {
            log.warn("Coda dei video grandi fermata", e);
        } finally {
            inGiro.set(false);
            if (richiesto.getAndSet(false)) {
                segnala();
            }
        }
    }

    /** Il prossimo da fare, dal più vecchio, segnato IN_CORSO nello stesso colpo. */
    private VideoGrande prossimo() {
        Instant adesso = clock.instant();
        Query query = Query.query(new Criteria().orOperator(Criteria.where("stato").is(Stato.IN_CODA),
                Criteria.where("stato").is(Stato.RIMANDATO).and("riprovaIl").lte(adesso)))
                .with(Sort.by("creatoIl", "_id"));
        return mongo.findAndModify(query, new Update().set("stato", Stato.IN_CORSO).set("aggiornatoIl", adesso),
                FindAndModifyOptions.options().returnNew(true), VideoGrande.class);
    }

    /** Un video, dal download al ricompresso nella cartella automatica. */
    private void lavora(VideoGrande v) {
        Lavoro l = new Lavoro(v.id());
        corrente = l;
        Path cartella = properties.cartella().resolve(v.id());
        Instant inizio = clock.instant();
        try {
            Sorgente sorgente = Sorgente.da(v.sorgente());
            togli(cartella);
            Files.createDirectories(cartella);
            controllaSpazio(v);
            String estensione = v.nome().substring(v.nome().lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            Path originale = cartella.resolve("originale." + estensione);
            l.fase("scarico l'originale da pCloud", null);
            copia(Map.of("srcFs", sorgente.fs(), "srcRemote", sorgente.dentro(v.percorso()),
                    "dstFs", properties.cartellaRclone(), "dstRemote", v.id() + "/" + originale.getFileName()),
                    v.dimensione(), l);
            if (!Files.isRegularFile(originale) || Files.size(originale) != v.dimensione()) {
                throw new Rimandato("scaricato a metà: " + v.dimensione() + " byte attesi, "
                        + (Files.isRegularFile(originale) ? Files.size(originale) : 0) + " arrivati");
            }
            FormatoVideo formato = media.formato(originale);
            Double durata = formato.durata();
            if (durata == null || durata <= 0) {
                throw new Errore("ffprobe non dice la durata dell'originale: il risultato non si potrebbe controllare");
            }
            controllaMemoria(formato);
            ricomprimiECarica(v, sorgente, originale, durata, l);
            log.info("Telefono, {}: ricompresso e caricato in {}", v.percorso(), Duration.between(inizio, clock.instant()));
        } catch (Exception e) {
            // Un'interruzione (l'app che si ferma) non deve far fallire le scritture su MongoDB che seguono.
            Thread.interrupted();
            fallito(v, l, e);
        } finally {
            corrente = null;
            Thread.interrupted();
            togli(cartella);
        }
    }

    /**
     * Ricomprime con CRF via via più alti finché il risultato sta sotto il
     * limite e ha la durata dell'originale, poi lo carica. Il limite parte
     * da {@code massimo-mb} (e sotto l'originale); se il cloud rifiuta
     * anche il ricompresso, scende sotto quello che ha rifiutato.
     */
    private void ricomprimiECarica(VideoGrande v, Sorgente sorgente, Path originale, double durata, Lavoro l)
            throws IOException, InterruptedException {
        Path ricompresso = originale.resolveSibling(NOME_RICOMPRESSO);
        long limite = Math.min(properties.massimo(), (long) (v.dimensione() * MARGINE));
        String ultimo = null;
        for (int crf = properties.crf(); crf <= properties.crfMassimo(); crf += properties.passoCrf()) {
            l.controlla();
            l.fase("ricomprimo in H.265 (CRF " + crf + ")", 0);
            if (!turno.prendi(l::annullato)) {
                throw new Annullato();
            }
            try {
                media.ricomprimi(originale, ricompresso, crf, properties.preset(), properties.thread(),
                        properties.parametriX265(), limite,
                        s -> l.percentuale = (int) Math.min(100, Math.round(s * 100 / durata)), l::annullato);
            } finally {
                turno.lascia();
            }
            l.controlla();
            long dimensione = Files.size(ricompresso);
            if (dimensione >= limite) {
                ultimo = "con CRF " + crf + " supera " + peso(limite);
                log.info("Telefono, {}: {}, riprovo più compresso", v.percorso(), ultimo);
                Files.deleteIfExists(ricompresso);
                continue;
            }
            l.fase("controllo il ricompresso", null);
            Double dopo = media.durata(ricompresso);
            double tolleranza = properties.tolleranzaDurata().toMillis() / 1000.0;
            if (dopo == null || Math.abs(dopo - durata) > tolleranza) {
                throw new Errore("la durata del ricompresso (" + secondi(dopo) + ") non è quella dell'originale ("
                        + secondi(durata) + ", tolleranza " + secondi(tolleranza) + "), CRF " + crf);
            }
            String hash = sha256(ricompresso);
            l.fase("carico il ricompresso su " + cloud.remoto(), 0);
            try {
                carica(v, ricompresso, dimensione, crf, hash, l);
                return;
            } catch (TroppoGrandePerIlCloud e) {
                ultimo = "con CRF " + crf + " (" + peso(dimensione) + ") il cloud lo rifiuta: " + e.getMessage();
                log.info("Telefono, {}: {}", v.percorso(), ultimo);
                limite = Math.min(limite, (long) (dimensione * MARGINE));
                Files.deleteIfExists(ricompresso);
            }
        }
        throw new Errore("ancora troppo grande anche con CRF " + properties.crfMassimo() + ": " + ultimo);
    }

    /**
     * Carica il ricompresso nella cartella automatica col nome
     * dell'originale (estensione {@code .mp4}), controlla che sia arrivato
     * intero e lo mette nel registro delle copie, segnato {@code ricompresso}:
     * da lì l'importazione sa di chi è e la sincronizzazione non cancella
     * mai l'originale. Finché non è nel registro il nome resta in
     * {@link #inCopia}: l'importazione non lo legge a metà.
     */
    private void carica(VideoGrande v, Path ricompresso, long dimensione, int crf, String hash, Lavoro l) {
        String nome = v.percorso().replace("/", " - ");
        int punto = nome.lastIndexOf('.');
        String voluto = v.cartella() + "/" + (punto > 0 ? nome.substring(0, punto) : nome) + ".mp4";
        String destinazione = liberoInArrivo(voluto);
        try {
            try {
                copia(Map.of("srcFs", properties.cartellaRclone(),
                        "srcRemote", v.id() + "/" + ricompresso.getFileName(),
                        "dstFs", cloud.remoto(), "dstRemote", destinazione), dimensione, l);
            } catch (RuntimeException e) {
                if (rifiutatoPerDimensione(e)) {
                    togliDallaDestinazione(destinazione);
                    throw new TroppoGrandePerIlCloud(e.getMessage());
                }
                Map<?, ?> arrivato = statSenzaErrori(cloud.remoto(), destinazione);
                if (arrivato == null || numero(arrivato.get("Size")) != dimensione) {
                    togliDallaDestinazione(destinazione);
                    throw e;
                }
                log.info("Telefono, {}: {}, ma il ricompresso è arrivato intero", v.percorso(), e.getMessage());
            }
            Map<?, ?> arrivato = stat(cloud.remoto(), destinazione);
            if (arrivato == null || numero(arrivato.get("Size")) != dimensione) {
                togliDallaDestinazione(destinazione);
                throw new Rimandato("ricompresso caricato a metà: " + dimensione + " byte attesi, "
                        + (arrivato == null ? "nessun file" : numero(arrivato.get("Size")) + " byte"));
            }
            Instant adesso = clock.instant();
            // Upsert: dopo un "Riprova" la voce di un caricamento di prima c'è già (stessa chiave).
            mongo.upsert(Query.query(Criteria.where("sorgente").is(v.sorgente()).and("percorso").is(v.percorso())
                            .and("dimensione").is(v.dimensione())),
                    new Update().setOnInsert("_id", new ObjectId().toHexString()).set("idSorgente", v.idSorgente())
                            .set("proprietario", v.proprietario()).set("modificatoIl", v.modificatoIl())
                            .set("destinazione", destinazione).set("copiatoIl", adesso).set("cancellatoIl", null)
                            .set("ricompresso", true),
                    CopiaTelefono.class);
            aggiorna(v, Stato.IN_CORSO, new Update().set("stato", Stato.CARICATO).set("crf", crf)
                    .set("dimensioneRicompresso", dimensione).set("hash", hash).set("destinazione", destinazione)
                    .set("caricatoIl", adesso).set("messaggio", null).set("tentativi", 0));
            log.info("Telefono, {}: {} → {} con CRF {}, in {}", v.percorso(), peso(v.dimensione()), peso(dimensione), crf,
                    destinazione);
        } finally {
            inCopia.remove(destinazione);
        }
    }

    /** Una copia di rclone (scaricare o caricare), con l'avanzamento in byte; Annulla la ferma. */
    private void copia(Map<String, Object> parametri, long dimensione, Lavoro l) {
        Duration limite = limiteCopia.plusSeconds(Math.max(0, dimensione) / BYTE_AL_SECONDO);
        try {
            rclone.copiaFile(parametri, attesaCopia, limite,
                    b -> l.percentuale = dimensione <= 0 ? null : (int) Math.min(100, b * 100 / dimensione),
                    l::annullato);
        } catch (RcloneNonRiesce e) {
            l.controlla();
            if (rifiutatoPerDimensione(e)) {
                throw e;
            }
            throw new Rimandato(e.getMessage());
        }
        l.controlla();
    }

    /** Una copia tolta perché rifiutata o a metà: è nostra (il nome era prenotato), non deve finire nell'archivio. */
    private void togliDallaDestinazione(String destinazione) {
        try {
            if (stat(cloud.remoto(), destinazione) != null) {
                rclone.chiama("operations/deletefile", Map.of("fs", cloud.remoto(), "remote", destinazione));
            }
        } catch (RuntimeException e) {
            log.warn("Ricompresso a metà {} non tolto: {}", destinazione, e.getMessage());
        }
    }

    /** Originale e ricompresso devono stare sul disco, più la riserva; altrimenti si rimanda. */
    private void controllaSpazio(VideoGrande v) {
        long libero = spazioLibero.applyAsLong(properties.cartella());
        long serve = v.dimensione() + Math.min(v.dimensione(), properties.massimo()) + properties.riserva().toBytes();
        if (libero < serve) {
            // Non conta fra i tentativi: non ha scaricato niente, e lo spazio si può liberare quando si vuole.
            throw new Rimandato("spazio insufficiente in " + properties.cartella() + ": servono " + peso(serve)
                    + " (originale, ricompresso e " + peso(properties.riserva().toBytes()) + " di margine), liberi "
                    + peso(libero), false);
        }
    }

    /**
     * x265 tiene in memoria i fotogrammi: un 8K o un 4K a 10 bit possono
     * superare quello che il container lascia a ffmpeg. Meglio dirlo subito
     * che farlo uccidere dopo ore (o far uccidere la JVM).
     */
    private void controllaMemoria(FormatoVideo formato) {
        Long serve = formato.memoriaX265();
        long massima = properties.memoriaMassima().toBytes();
        if (serve != null && serve > massima) {
            throw new Errore("per ricomprimere un video " + formato.larghezza() + "×" + formato.altezza()
                    + (formato.piuDi8Bit() ? " a 10 bit" : "") + " ffmpeg vuole circa " + peso(serve)
                    + " di memoria, più dei " + peso(massima) + " concessi: alza FOTOTIMELINE_MEMORIA_APP e"
                    + " FOTOTIMELINE_VIDEO_GRANDI_MEMORIA (DEPLOY.md, \"Video troppo grandi\") e premi Riprova");
        }
    }

    /** Com'è finito un video che non è arrivato al caricamento. */
    private void fallito(VideoGrande v, Lavoro l, Exception e) {
        Instant adesso = clock.instant();
        if (l.annullato() || e instanceof Annullato) {
            if (chiuso && !l.annullatoAMano) {
                // L'app si ferma: al riavvio ricomincia.
                aggiorna(v, Stato.IN_CORSO, new Update().set("stato", Stato.IN_CODA));
                return;
            }
            aggiorna(v, Stato.IN_CORSO, new Update().set("stato", Stato.ANNULLATO).set("aggiornatoIl", adesso)
                    .set("messaggio", "annullato a mano"));
            log.info("Telefono, {}: ricompressione annullata", v.percorso());
            return;
        }
        String motivo = e.getMessage() == null ? e.toString() : e.getMessage();
        if (e instanceof Rimandato r) {
            int tentativi = v.tentativi() + (r.conta ? 1 : 0);
            if (tentativi < properties.tentativi()) {
                Instant quando = adesso.plus(properties.attesaRimandati());
                aggiorna(v, Stato.IN_CORSO, new Update().set("stato", Stato.RIMANDATO).set("riprovaIl", quando)
                        .set("tentativi", tentativi).set("messaggio", motivo).set("aggiornatoIl", adesso));
                log.info("Telefono, {}: ricompressione rimandata ({}), riprovo dopo {}", v.percorso(), motivo, quando);
                return;
            }
            motivo = "rimandato " + tentativi + " volte, l'ultima: " + motivo;
        }
        log.warn("Telefono, {}: ricompressione non riuscita: {}", v.percorso(), motivo);
        aggiorna(v, Stato.IN_CORSO, new Update().set("stato", Stato.ERRORE).set("messaggio", motivo)
                .set("aggiornatoIl", adesso));
    }

    /** Aggiorna il video solo se è ancora nello stato atteso (non annullato, non tolto). */
    private boolean aggiorna(VideoGrande v, Stato atteso, Update update) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(v.id()).and("stato").is(atteso)),
                update.set("aggiornatoIl", clock.instant()), VideoGrande.class).getMatchedCount() > 0;
    }

    private static VideoGrande conStato(VideoGrande v, Stato stato, String messaggio) {
        return new VideoGrande(v.id(), v.sorgente(), v.idSorgente(), v.proprietario(), v.percorso(), v.dimensione(),
                v.modificatoIl(), v.cartella(), v.motivo(), stato, v.creatoIl(), v.aggiornatoIl(), v.riprovaIl(),
                v.tentativi(), v.crf(), v.dimensioneRicompresso(), v.hash(), v.destinazione(), v.caricatoIl(),
                v.originaleIn(), v.finitoIl(), messaggio);
    }

    /** "20260301_195506_293.mp4: ricompresso, 23 552 MB → 6 144 MB (CRF 24), originale in pcloud:...". */
    static String riga(VideoGrande v) {
        return v.nome() + ": ricompresso, " + peso(v.dimensione()) + " → " + peso(v.dimensioneRicompresso())
                + (v.crf() != null ? " (CRF " + v.crf() + ")" : "")
                + (v.originaleIn() != null ? ", originale in " + v.originaleIn()
                        : v.messaggio() != null ? ", " + v.messaggio() : "");
    }

    // ------------------------------------------------------------ attrezzi

    private Map<?, ?> stat(String fs, String remote) {
        Object voce = rclone.chiama("operations/stat", Map.of("fs", fs, "remote", remote)).get("item");
        return voce instanceof Map<?, ?> m ? m : null;
    }

    private Map<?, ?> statSenzaErrori(String fs, String remote) {
        try {
            return stat(fs, remote);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static String chiave(String percorso, long dimensione) {
        return dimensione + ":" + percorso;
    }

    private static long numero(Object o) {
        return o instanceof Number n ? n.longValue() : -1;
    }

    /** "850 MB", "23,1 GB" (in base 1024, come li conta LifetimeCloud). */
    public static String peso(Long byte_) {
        if (byte_ == null) {
            return "?";
        }
        double mb = byte_ / (1024.0 * 1024);
        return mb >= 1024 ? String.format(Locale.ITALIAN, "%.1f GB", mb / 1024)
                : String.format(Locale.ITALIAN, "%d MB", Math.round(mb));
    }

    private static String secondi(Double s) {
        return s == null ? "sconosciuta" : String.format(Locale.ITALIAN, "%.1f s", s);
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        byte[] buffer = new byte[1 << 20];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buffer)) > 0;) {
                sha.update(buffer, 0, n);
            }
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static long liberoSulDisco(Path cartella) {
        try {
            return Files.getFileStore(cartella).getUsableSpace();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Toglie una cartella di lavoro con quello che c'è dentro (originale e ricompresso: niente sottocartelle). */
    private static void togli(Path cartella) {
        if (!Files.isDirectory(cartella)) {
            return;
        }
        try (Stream<Path> dentro = Files.list(cartella)) {
            for (Path p : dentro.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(cartella);
        } catch (IOException e) {
            log.warn("Video grandi: non riesco a togliere {}: {}", cartella, e.getMessage());
        }
    }

    /** Per i test: attese brevi, spazio finto, niente coda in sottofondo. */
    void perTest(Duration attesa, Duration limite, ToLongFunction<Path> spazio) {
        this.inSottofondo = false;
        this.attesaCopia = attesa;
        this.limiteCopia = limite;
        if (spazio != null) {
            this.spazioLibero = spazio;
        }
    }

    @PreDestroy
    void chiudi() {
        // Il lavoro in corso vede chiuso (job/stop, ffmpeg fermato) e torna in coda per il riavvio.
        chiuso = true;
        esecutore.shutdownNow();
    }

    /** Il video in lavorazione: fase e avanzamento per la pagina, e l'annullamento. */
    private final class Lavoro {
        final String id;
        volatile String fase = "in partenza";
        volatile Integer percentuale;
        volatile boolean annullatoAMano;

        Lavoro(String id) {
            this.id = id;
        }

        void fase(String fase, Integer percentuale) {
            this.fase = fase;
            this.percentuale = percentuale;
        }

        String descrizione() {
            Integer p = percentuale;
            return fase + (p != null ? " " + p + "%" : "");
        }

        boolean annullato() {
            return annullatoAMano || chiuso;
        }

        /** rclone (job/stop), il turno e ffmpeg guardano il flag: niente interruzioni, che fermerebbero il job/stop. */
        void annulla() {
            annullatoAMano = true;
        }

        void controlla() {
            if (annullato()) {
                throw new Annullato();
            }
        }
    }

    /**
     * Si riprova da solo più tardi: spazio sul disco, rclone o pCloud che non
     * rispondono. Se {@code conta}, è uno dei {@code tentativi} dopo i quali
     * diventa un errore.
     */
    static final class Rimandato extends RuntimeException {
        final boolean conta;

        Rimandato(String messaggio) {
            this(messaggio, true);
        }

        Rimandato(String messaggio, boolean conta) {
            super(messaggio);
            this.conta = conta;
        }
    }

    /** Non passa da solo: si rifà con "Riprova". */
    static final class Errore extends RuntimeException {
        Errore(String messaggio) {
            super(messaggio);
        }
    }

    private static final class Annullato extends RuntimeException {
        Annullato() {
            super("annullato");
        }
    }

    private static final class TroppoGrandePerIlCloud extends RuntimeException {
        TroppoGrandePerIlCloud(String messaggio) {
            super(messaggio);
        }
    }
}
