package it.fototimeline.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.StatoConversione;
import it.fototimeline.media.InfoVideo;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.media.VideoProperties;
import jakarta.annotation.PreDestroy;

/**
 * La coda delle versioni compatibili dei video (H.264/AAC in MP4) per quelli
 * che non tutti i browser riproducono (HEVC dell'iPhone, VP9, ProRes...).
 *
 * <p>Lo stato sta sulla scheda ({@link Foto#getConversione()}), non in
 * memoria: dopo un riavvio la coda riparte da dov'era. Un video alla volta,
 * su un solo thread, con ffmpeg a priorità bassa e pochi thread: sul server
 * la CPU serve anche alla timeline. Col cloud smontato la coda aspetta (i
 * video restano in coda, non vanno in errore) e riparte da sola al montaggio.
 *
 * <p>Un video entra in coda all'importazione se ffprobe dice che non è
 * compatibile; "Converti video" ({@link #avvia()}) ci mette quelli già in
 * archivio, compresi quelli mai analizzati, che la coda prima guarda con
 * ffprobe e converte solo se serve.
 */
@Service
public class ConversioneVideo {

    private static final Logger log = LoggerFactory.getLogger(ConversioneVideo.class);
    private static final int MAX_MESSAGGI = 20;
    private static final List<StatoConversione> DA_FARE = List.of(StatoConversione.IN_CODA, StatoConversione.IN_CORSO);

    /**
     * Fotografia della coda per l'API.
     *
     * @param daFare       video in coda, compreso quello in corso
     * @param fatti        finiti da quando è partito l'ultimo "Converti video" (o dall'avvio)
     * @param errori       non riusciti da quando è partito l'ultimo "Converti video"
     * @param convertiti   video che hanno la versione compatibile, in tutto
     * @param inAttesa     c'è da fare ma il cloud è smontato
     * @param percentuale  del video in corso, 0..100
     */
    public record StatoConversioni(boolean attiva, boolean inCorso, boolean inAttesa, long daFare, int fatti,
            int errori, long convertiti, String correnteId, String corrente, Integer percentuale,
            List<String> messaggi) {
    }

    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final StrumentiMedia media;
    private final VideoProperties properties;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "conversione-video");
        t.setDaemon(true);
        return t;
    });
    /** Un giro della coda è stato mandato al thread e non è ancora finito. */
    private final AtomicBoolean inGiro = new AtomicBoolean();
    /** Qualcuno ha segnalato mentre un giro stava finendo: se ne fa un altro. */
    private final AtomicBoolean richiesto = new AtomicBoolean();

    private volatile boolean annullaCorrente;
    private volatile boolean chiuso;
    private volatile boolean inAttesa;
    private volatile Foto corrente;
    private volatile double secondiFatti;
    private int fatti;
    private int errori;
    private final List<String> messaggi = new ArrayList<>();

    public ConversioneVideo(MongoTemplate mongo, ArchivioFile archivio, StrumentiMedia media,
            VideoProperties properties, Optional<Clock> clock) {
        this.mongo = mongo;
        this.archivio = archivio;
        this.media = media;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    /** False se le conversioni sono spente o ffmpeg manca: i video si servono come sono. */
    public boolean attiva() {
        return properties.attiva() && media.videoDisponibile();
    }

    /**
     * All'importazione: la scheda dice codec e compatibilità; se il video non
     * è compatibile entra in coda (la coda parte con {@link #segnala()}, dopo
     * il salvataggio della scheda).
     */
    public void prepara(Foto foto, InfoVideo info) {
        foto.setCodecVideo(info.codecVideo());
        foto.setCodecAudio(info.codecAudio());
        foto.setCompatibile(info.compatibile());
        foto.setConversione(!info.compatibile() && attiva() ? StatoConversione.IN_CODA : null);
    }

    /**
     * "Converti video": mette in coda i video che non hanno ancora la versione
     * compatibile e ne avrebbero bisogno (o che non si sa, e la coda li guarda),
     * compresi quelli finiti in errore e quelli il cui file convertito manca.
     */
    public synchronized StatoConversioni avvia() {
        if (!properties.attiva()) {
            throw new IllegalStateException("Le conversioni dei video sono spente (fototimeline.video.attiva)");
        }
        if (!media.videoDisponibile()) {
            throw new IllegalStateException("Per convertire i video servono ffmpeg e ffprobe sul server");
        }
        archivio.verificaDisponibile();
        if (!archivio.compatibiliDisponibili()) {
            throw new ArchivioNonDisponibile();
        }
        Criteria nonInCoda = new Criteria().orOperator(
                Criteria.where("conversione").exists(false),
                Criteria.where("conversione").nin(DA_FARE));
        // Mai analizzati (archivi di prima): la coda li guarda con ffprobe.
        mongo.updateMulti(Query.query(new Criteria().andOperator(
                        Criteria.where("video").is(true), Criteria.where("compatibile").is(null), nonInCoda)),
                inCoda(), Foto.class);
        // Da convertire, anche quelli non riusciti.
        mongo.updateMulti(Query.query(Criteria.where("video").is(true).and("compatibile").is(false)
                        .and("conversione").in(null, StatoConversione.ERRORE)),
                inCoda(), Foto.class);
        // Fatti, ma il file non c'è più (destinazione cambiata, cancellato a mano).
        Query fatte = Query.query(Criteria.where("conversione").is(StatoConversione.FATTA));
        fatte.fields().include("_id");
        for (Foto f : mongo.find(fatte, Foto.class)) {
            if (!Files.exists(archivio.compatibile(f.getId()))) {
                mongo.updateFirst(Query.query(Criteria.where("_id").is(f.getId())), inCoda(), Foto.class);
            }
        }
        synchronized (this.messaggi) {
            fatti = 0;
            errori = 0;
            messaggi.clear();
        }
        segnala();
        return stato();
    }

    /** Ferma il video in corso e svuota la coda; i video restano con l'originale. */
    public boolean annulla() {
        long tolti = mongo.updateMulti(Query.query(Criteria.where("conversione").in(DA_FARE)),
                new Update().unset("conversione"), Foto.class).getModifiedCount();
        boolean c = corrente != null;
        annullaCorrente = true;
        return tolti > 0 || c;
    }

    public StatoConversioni stato() {
        long daFare = mongo.count(Query.query(Criteria.where("conversione").in(DA_FARE)), Foto.class);
        long convertiti = mongo.count(Query.query(Criteria.where("conversione").is(StatoConversione.FATTA)), Foto.class);
        Foto f = corrente;
        Integer percentuale = null;
        if (f != null && f.getDurata() != null && f.getDurata() > 0) {
            percentuale = (int) Math.min(100, Math.round(secondiFatti * 100 / f.getDurata()));
        }
        synchronized (messaggi) {
            return new StatoConversioni(attiva(), f != null, inAttesa && daFare > 0, daFare, fatti, errori,
                    convertiti, f == null ? null : f.getId(), f == null ? null : f.getNomeOriginale(), percentuale,
                    List.copyOf(messaggi));
        }
    }

    /**
     * Dopo un riavvio: il video che era in corso torna in coda (il file
     * temporaneo è perso) e la coda riparte.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void riprendi() {
        long ripresi = mongo.updateMulti(Query.query(Criteria.where("conversione").is(StatoConversione.IN_CORSO)),
                inCoda(), Foto.class).getModifiedCount();
        if (ripresi > 0) {
            log.info("Conversione video ripresa dopo il riavvio: {} rimessi in coda", ripresi);
        }
        segnala();
    }

    /** Ogni {@code fototimeline.video.controllo}: riparte se c'è da fare (per esempio il cloud è appena montato). */
    @Scheduled(fixedDelayString = "${fototimeline.video.controllo:PT1M}", initialDelayString = "${fototimeline.video.controllo:PT1M}")
    public void controlla() {
        segnala();
    }

    /** Fa partire la coda, se non sta già girando. Non blocca. */
    public void segnala() {
        if (!attiva() || chiuso) {
            return;
        }
        if (inGiro.compareAndSet(false, true)) {
            esecutore.submit(this::giro);
        } else {
            richiesto.set(true);
        }
    }

    private void giro() {
        try {
            richiesto.set(false);
            while (!chiuso) {
                if (!archivio.disponibile() || !archivio.compatibiliDisponibili()) {
                    inAttesa = true;
                    return;
                }
                inAttesa = false;
                Foto foto = prossima();
                if (foto == null) {
                    return;
                }
                if (!lavora(foto)) {
                    return;
                }
            }
        } catch (RuntimeException e) {
            log.warn("Coda delle conversioni video fermata", e);
        } finally {
            corrente = null;
            inGiro.set(false);
            if (richiesto.getAndSet(false)) {
                segnala();
            }
        }
    }

    /** Il prossimo in coda, dal più vecchio, segnato IN_CORSO nello stesso colpo. */
    private Foto prossima() {
        Query query = Query.query(Criteria.where("conversione").is(StatoConversione.IN_CODA))
                .with(Sort.by("caricataIl", "_id"));
        return mongo.findAndModify(query, new Update().set("conversione", StatoConversione.IN_CORSO),
                FindAndModifyOptions.options().returnNew(true), Foto.class);
    }

    /** Un video: analisi se serve, conversione, scheda. False se la coda deve fermarsi (cloud smontato). */
    private boolean lavora(Foto foto) {
        annullaCorrente = false;
        secondiFatti = 0;
        corrente = foto;
        Instant inizio = clock.instant();
        Path temporaneo = null;
        try {
            Path originale = archivio.originale(foto.getPercorso());
            if (foto.getCompatibile() == null) {
                InfoVideo info = media.leggiVideo(originale, clock.getZone());
                foto.setDurata(info.durata());
                corrente = foto;
                aggiorna(foto, new Update().set("codecVideo", info.codecVideo()).set("codecAudio", info.codecAudio())
                        .set("compatibile", info.compatibile()));
                if (info.compatibile()) {
                    aggiorna(foto, new Update().unset("conversione"));
                    return true;
                }
            }
            temporaneo = Files.createTempFile("fototimeline-video-", ".mp4");
            media.converti(originale, temporaneo, properties.risoluzioneMassima(), properties.thread(),
                    s -> secondiFatti = s, () -> annullaCorrente || chiuso);
            archivio.salvaCompatibile(foto.getId(), temporaneo);
            if (!aggiorna(foto, new Update().set("conversione", StatoConversione.FATTA).unset("erroreConversione"))) {
                // Eliminato mentre lo convertivamo.
                Files.deleteIfExists(archivio.compatibile(foto.getId()));
                return true;
            }
            synchronized (messaggi) {
                fatti++;
            }
            log.info("Versione compatibile di {} fatta in {}", foto.getPercorso(), Duration.between(inizio, clock.instant()));
            return true;
        } catch (Exception e) {
            if (annullaCorrente || chiuso) {
                // annulla() ha già tolto la coda; alla chiusura il video resta IN_CORSO e riparte al riavvio.
                return !chiuso;
            }
            if (e instanceof ArchivioNonDisponibile || !archivio.disponibile() || !archivio.compatibiliDisponibili()) {
                log.info("Cloud smontato: la conversione di {} aspetta il montaggio", foto.getPercorso());
                aggiorna(foto, inCoda());
                inAttesa = true;
                return false;
            }
            String motivo = e.getMessage() == null ? e.toString() : e.getMessage();
            log.warn("Conversione di {} non riuscita: {}", foto.getPercorso(), motivo);
            aggiorna(foto, new Update().set("conversione", StatoConversione.ERRORE).set("erroreConversione", motivo));
            synchronized (messaggi) {
                errori++;
                if (messaggi.size() < MAX_MESSAGGI) {
                    messaggi.add(foto.getPercorso() + ": " + motivo);
                }
            }
            return true;
        } finally {
            if (temporaneo != null) {
                try {
                    Files.deleteIfExists(temporaneo);
                } catch (IOException e) {
                    log.debug("Non riesco a togliere il temporaneo {}", temporaneo);
                }
            }
        }
    }

    /** Aggiorna la scheda solo se il video c'è ancora ed è ancora in corso (non annullato). */
    private boolean aggiorna(Foto foto, Update update) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())
                        .and("conversione").is(StatoConversione.IN_CORSO)), update, Foto.class)
                .getMatchedCount() > 0;
    }

    private static Update inCoda() {
        return new Update().set("conversione", StatoConversione.IN_CODA).unset("erroreConversione");
    }

    @PreDestroy
    void chiudi() {
        chiuso = true;
        esecutore.shutdownNow();
    }
}
