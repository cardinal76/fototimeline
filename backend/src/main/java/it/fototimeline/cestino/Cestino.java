package it.fototimeline.cestino;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;

/**
 * Il cestino: le foto eliminate (da visore, selezione e quasi uguali,
 * {@link FotoService#elimina}) restano qui {@code fototimeline.cestino.giorni}
 * giorni, con la scheda e il file dove sono, poi si eliminano per sempre da
 * sole ({@link #pulisci()}). Ripristinare ed eliminare per sempre vogliono il
 * cloud montato, come l'eliminazione.
 */
@Service
public class Cestino {

    private static final Logger log = LoggerFactory.getLogger(Cestino.class);
    /** Schede lette per volta svuotando il cestino. */
    private static final int BLOCCO = 200;

    /**
     * Una foto del cestino per la pagina "Cestino".
     *
     * @param siEliminaIl   quando se ne andrà da sola; null se il cestino non si svuota da solo
     * @param giorniRimasti giorni interi che mancano (0 = al prossimo controllo); null come sopra
     */
    public record VoceCestino(String id, String miniatura, String nomeOriginale, boolean video, Integer larghezza,
            Integer altezza, LocalDateTime scattataIl, Instant eliminataIl, Instant siEliminaIl, Long giorniRimasti) {
    }

    /** @param giorni i giorni del cestino; 0 = le foto restano finché non le si elimina a mano */
    public record PaginaCestino(List<VoceCestino> foto, long totale, int pagina, boolean altre, int giorni) {
    }

    private final MongoTemplate mongo;
    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final CestinoProperties properties;
    private final Clock clock;

    public Cestino(MongoTemplate mongo, FotoService fotoService, ArchivioFile archivio, CestinoProperties properties,
            Optional<Clock> clock) {
        this.mongo = mongo;
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Le foto nel cestino, dall'ultima eliminata. */
    public PaginaCestino elenco(int pagina, int dimensione) {
        Query query = new Query(FotoService.nelCestino());
        long totale = mongo.count(query, Foto.class);
        query.with(Sort.by(Sort.Order.desc(FotoService.CESTINO), Sort.Order.desc("_id")))
                .skip((long) pagina * dimensione)
                .limit(dimensione);
        query.fields().include("nomeOriginale", "video", "larghezza", "altezza", "rotazione", "scattataIl",
                FotoService.CESTINO);
        Instant adesso = clock.instant();
        List<VoceCestino> foto = mongo.find(query, Foto.class).stream().map(f -> voce(f, adesso)).toList();
        return new PaginaCestino(foto, totale, pagina, (long) (pagina + 1) * dimensione < totale, properties.giorni());
    }

    private VoceCestino voce(Foto f, Instant adesso) {
        Instant scade = scadenza(f.getEliminataIl());
        Long rimasti = scade == null ? null : Math.max(0, (Duration.between(adesso, scade).toHours() + 23) / 24);
        return new VoceCestino(f.getId(), FotoService.indirizzoMiniatura(f), f.getNomeOriginale(), f.isVideo(),
                f.getLarghezza(), f.getAltezza(), f.getScattataIl(), f.getEliminataIl(), scade, rimasti);
    }

    /** Quando una foto messa nel cestino in {@code eliminataIl} se ne va da sola; null = mai. */
    private Instant scadenza(Instant eliminataIl) {
        return properties.giorni() == 0 || eliminataIl == null ? null
                : eliminataIl.plus(Duration.ofDays(properties.giorni()));
    }

    /** Rimette nell'archivio le foto scelte; restituisce quante. Col cloud smontato 503. */
    public long ripristina(Collection<String> ids) {
        archivio.verificaDisponibile();
        return distinti(ids).stream().filter(fotoService::ripristina).count();
    }

    /** Elimina per sempre le foto scelte, file compresi; solo quelle nel cestino. Col cloud smontato 503. */
    public long eliminaPerSempre(Collection<String> ids) {
        archivio.verificaDisponibile();
        return distinti(ids).stream().filter(fotoService::eliminaPerSempre).count();
    }

    /** "Svuota cestino": tutte, per sempre. Col cloud smontato 503. */
    public long svuota() {
        archivio.verificaDisponibile();
        long eliminate = 0;
        String ultimo = "";
        for (List<Foto> blocco; !(blocco = blocco(FotoService.nelCestino(), ultimo)).isEmpty();) {
            for (Foto f : blocco) {
                if (fotoService.eliminaPerSempre(f.getId())) {
                    eliminate++;
                }
            }
            ultimo = blocco.getLast().getId();
        }
        log.info("Cestino svuotato: {} foto eliminate per sempre", eliminate);
        return eliminate;
    }

    /**
     * Elimina per sempre le foto
     * nel cestino da più di {@code giorni} giorni. Col cloud smontato aspetta
     * (le foto restano nel cestino) e recupera appena lo si monta; una foto
     * che non si riesce a cancellare resta e si riprova al giro dopo.
     *
     * @return quante ne ha eliminate
     */
    public long pulisci() {
        if (properties.giorni() == 0) {
            return 0;
        }
        if (!archivio.disponibile()) {
            log.debug("Cloud smontato: la pulizia del cestino aspetta il montaggio");
            return 0;
        }
        Instant limite = clock.instant().minus(Duration.ofDays(properties.giorni()));
        Criteria scadute = Criteria.where(FotoService.CESTINO).lt(limite);
        long eliminate = 0;
        int errori = 0;
        String ultimo = "";
        for (List<Foto> blocco; !(blocco = blocco(scadute, ultimo)).isEmpty();) {
            for (Foto f : blocco) {
                if (!archivio.disponibile()) {
                    log.info("Cloud smontato durante la pulizia del cestino: riprendo al prossimo giro");
                    return eliminate;
                }
                try {
                    if (fotoService.eliminaPerSempre(f.getId())) {
                        eliminate++;
                    }
                } catch (RuntimeException e) {
                    errori++;
                    log.warn("Non riesco a eliminare per sempre {} dal cestino (riprovo al prossimo giro): {}",
                            f.getId(), e.getMessage());
                }
            }
            ultimo = blocco.getLast().getId();
        }
        if (eliminate > 0 || errori > 0) {
            log.info("Pulizia del cestino: {} foto più vecchie di {} giorni eliminate per sempre, {} errori", eliminate,
                    properties.giorni(), errori);
        }
        return eliminate;
    }

    /** Ogni {@code fototimeline.cestino.controllo} (un'ora): {@link #pulisci()}. */
    @Scheduled(fixedDelayString = "${fototimeline.cestino.controllo:PT1H}",
            initialDelayString = "${fototimeline.cestino.controllo:PT1H}")
    public void controlla() {
        try {
            pulisci();
        } catch (RuntimeException e) {
            log.warn("Pulizia del cestino non riuscita: {}", e.getMessage());
        }
    }

    /** Il blocco successivo per {@code _id} crescente: una foto che non si cancella non si ripesca nello stesso giro. */
    private List<Foto> blocco(Criteria criteri, String dopo) {
        Query query = Query.query(Criteria.where("_id").gt(dopo)).addCriteria(criteri)
                .with(Sort.by("_id")).limit(BLOCCO);
        query.fields().include("_id");
        return mongo.find(query, Foto.class);
    }

    private static Collection<String> distinti(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("Nessuna foto scelta");
        }
        return new LinkedHashSet<>(ids);
    }
}
