package it.fototimeline.service;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.media.JpegRuotato;
import it.fototimeline.media.Riduzione;
import it.fototimeline.media.Rotazione;
import it.fototimeline.quasiuguali.Impronta;
import it.fototimeline.service.EventiFoto.FotoRuotata;

/**
 * "Ruota a sinistra / a destra": la rotazione si salva nella scheda
 * ({@link Foto#getRotazione()}), mai nel file. L'archivio è verificato per
 * contenuto (SHA-256 in {@code Foto.hash}, il confronto con pCloud, i "già
 * presenti" delle importazioni): riscrivere l'originale, anche solo il tag
 * EXIF, lo farebbe sembrare un'altra foto.
 *
 * <p>Dove vale la rotazione:
 * <ul>
 *   <li>miniatura: girata dalla dritta, sul disco del server
 *       ({@link ArchivioFile#miniaturaRuotata}), quindi anche col cloud
 *       smontato; da lei timeline, mappa, ricordi, quasi uguali, contenuto;</li>
 *   <li>larghezza e altezza della scheda: scambiate a 90° e 270°;</li>
 *   <li>vista del visore ({@link #vista}): per JPEG e HEIC (la vista JPEG) la
 *       copia col tag Orientation cambiato, senza perdita e senza file nuovi;
 *       per PNG, GIF, BMP e WebP un JPEG girato accanto alle viste;</li>
 *   <li>download e link condivisi ({@link #originale}): i JPEG girati senza
 *       perdita; gli altri formati come sono;</li>
 *   <li>impronta (pHash) e indice del contenuto: rifatti dalla miniatura
 *       girata, cioè da come si vede la foto. Una foto girata per
 *       raddrizzarla torna "quasi uguale" alla sua copia dritta.</li>
 * </ul>
 * I video si girano solo a vedere (nel visore e nei link, col CSS): la
 * miniatura è girata, il file e il download no.
 */
@Service
public class Rotazioni {

    private static final Logger log = LoggerFactory.getLogger(Rotazioni.class);

    /** Il lato della vista girata dei formati senza EXIF (PNG, GIF, BMP, WebP). */
    static final int LATO_VISTA = 4096;

    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final MongoTemplate mongo;
    private final ApplicationEventPublisher eventi;

    /** Una rotazione alla volta: "90 + 90" con due clic veloci deve fare 180. */
    private final ReentrantLock girando = new ReentrantLock();

    public Rotazioni(FotoService fotoService, ArchivioFile archivio, MongoTemplate mongo,
            ApplicationEventPublisher eventi) {
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.mongo = mongo;
        this.eventi = eventi;
    }

    /**
     * Gira la foto di {@code gradi} (90 a destra, -90 a sinistra, 180), in più
     * di come è già: la rotazione si somma (90 + 90 = 180, 270 + 90 = 0).
     * Una foto nel cestino non si gira (vuoto, cioè 404), come non si modifica.
     *
     * @throws IllegalArgumentException se {@code gradi} non è un multiplo di 90
     */
    public Optional<Foto> ruota(String id, int gradi) {
        int quanto = Rotazione.normalizza(gradi);
        girando.lock();
        try {
            Foto foto = fotoService.trova(id).filter(f -> !f.nelCestino()).orElse(null);
            if (foto == null || quanto == 0) {
                return Optional.ofNullable(foto);
            }
            int nuova = (foto.getRotazione() + quanto) % 360;
            foto.setRotazione(nuova);
            // Prima i file della rotazione nuova, poi la scheda: chi legge la scheda trova già la miniatura.
            Path miniatura = fotoService.fileMiniatura(foto);
            Update update = new Update().set("rotazione", nuova);
            if (Rotazione.scambiaLati(quanto) && foto.getLarghezza() != null && foto.getAltezza() != null) {
                update.set("larghezza", foto.getAltezza()).set("altezza", foto.getLarghezza());
            }
            if (!foto.isVideo()) {
                try {
                    update.set("impronta", Impronta.calcola(miniatura));
                } catch (IOException | RuntimeException e) {
                    // La rifà "Calcola impronte": quella di prima varrebbe per la foto non girata.
                    update.unset("impronta");
                    log.warn("Impronta di {} non ricalcolata: {}", foto.getNomeOriginale(), e.toString());
                }
            }
            // L'indice del contenuto si rifà (FotoRuotata); se visione non risponde lo riprende "Indicizza contenuto".
            update.unset("contenutoIndicizzato");
            long cambiate = mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and(FotoService.CESTINO).is(null)),
                    update, Foto.class).getModifiedCount();
            if (cambiate == 0) {
                // Finita nel cestino proprio adesso.
                return Optional.empty();
            }
            try {
                archivio.togliRuotate(id, nuova);
            } catch (IOException e) {
                log.debug("File girati di prima di {} non tolti: {}", id, e.toString());
            }
            eventi.publishEvent(new FotoRuotata(id));
            return fotoService.trova(id);
        } finally {
            girando.unlock();
        }
    }

    /** "Ruota" sulla selezione: quante ne ha girate (non quelle nel cestino). */
    public long ruota(List<String> ids, int gradi) {
        Rotazione.normalizza(gradi);
        return ids == null ? 0 : ids.stream().distinct().filter(id -> ruota(id, gradi).isPresent()).count();
    }

    /** Un file da mandare al browser, col suo tipo. */
    public record Servito(Resource risorsa, String contentType) {
    }

    /**
     * La vista del visore con la rotazione: per JPEG e HEIC la copia col tag
     * girato (stessi byte compressi), per gli altri formati un JPEG girato,
     * fatto la prima volta dall'originale. Senza rotazione è {@code /vista}
     * di sempre.
     */
    public Servito vista(Foto foto) {
        Path vista = fotoService.fileVista(foto);
        if (foto.getRotazione() == 0 || foto.isVideo()) {
            return new Servito(new FileSystemResource(vista), fotoService.contentTypeVista(foto));
        }
        if (jpeg(foto)) {
            try {
                return new Servito(new JpegGirato(vista, JpegRuotato.prepara(vista, foto.getRotazione()),
                        vista.getFileName().toString()), "image/jpeg");
            } catch (IOException e) {
                log.debug("{}: il tag non si scrive ({}), vista girata a parte", foto.getPercorso(), e.getMessage());
            }
        }
        return new Servito(new FileSystemResource(vistaGirata(foto, vista)), "image/jpeg");
    }

    /**
     * L'originale da scaricare (visore, link condivisi, zip): con una
     * rotazione e se è un JPEG la copia girata senza perdita, col tag
     * Orientation nuovo e gli stessi pixel; altrimenti il file com'è
     * (HEIC, PNG, video: la rotazione resta solo a vederli).
     */
    public Resource originale(Foto foto) {
        Path file = fotoService.fileOriginale(foto);
        return girato(foto, file).map(Resource.class::cast).orElseGet(() -> new FileSystemResource(file));
    }

    /** La copia girata dell'originale, se c'è una rotazione e si può scrivere senza perdita (JPEG). */
    public Optional<Resource> originaleGirato(Foto foto) {
        if (foto.getRotazione() == 0 || foto.isVideo()) {
            return Optional.empty();
        }
        return girato(foto, fotoService.fileOriginale(foto)).map(Resource.class::cast);
    }

    private Optional<JpegGirato> girato(Foto foto, Path file) {
        if (foto.getRotazione() == 0 || foto.isVideo() || !"image/jpeg".equals(foto.getContentType())) {
            return Optional.empty();
        }
        try {
            return Optional.of(new JpegGirato(file, JpegRuotato.prepara(file, foto.getRotazione()), foto.getNomeOriginale()));
        } catch (JpegRuotato.NonRuotabile e) {
            log.info("{} non si scarica girato ({}): va l'originale", foto.getPercorso(), e.getMessage());
            return Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** True se la vista è un JPEG: i JPEG e gli HEIC (la loro vista è un JPEG di heif-convert). */
    private boolean jpeg(Foto foto) {
        return "image/jpeg".equals(fotoService.contentTypeVista(foto));
    }

    /** La vista girata dei formati senza EXIF, fatta una volta e tenuta accanto alle viste. */
    private Path vistaGirata(Foto foto, Path sorgente) {
        Path file = archivio.vistaRuotata(foto.getId(), foto.getRotazione());
        if (Files.exists(file)) {
            return file;
        }
        try {
            return fotoService.unaAllaVolta(() -> {
                if (Files.exists(file)) {
                    return file;
                }
                Files.createDirectories(file.getParent());
                Path temporaneo = Files.createTempFile(file.getParent(), "riduzione-", ".jpg");
                try {
                    // La vista degli HEIC (e AVIF) è già dritta; gli altri si raddrizzano con l'EXIF, poi si girano.
                    boolean exif = !fotoService.vistaDritta(foto);
                    Riduzione.jpeg(sorgente, temporaneo, LATO_VISTA, exif, 0.92, false, foto.getRotazione());
                    Files.move(temporaneo, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporaneo);
                }
                return file;
            });
        } catch (IOException e) {
            throw new UncheckedIOException("Non riesco a girare la vista di " + foto.getPercorso(), e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Un JPEG girato col tag: la testa nuova e poi il file com'è, letto a
     * pezzi. Ha la sua lunghezza, così le risposte con Range funzionano.
     */
    static final class JpegGirato extends AbstractResource {
        private final Path file;
        private final JpegRuotato.Testa testa;
        private final String nome;

        JpegGirato(Path file, JpegRuotato.Testa testa, String nome) {
            this.file = file;
            this.testa = testa;
            this.nome = nome;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return JpegRuotato.apri(file, testa);
        }

        @Override
        public long contentLength() throws IOException {
            return JpegRuotato.lunghezza(file, testa);
        }

        @Override
        public long lastModified() throws IOException {
            return Files.getLastModifiedTime(file).toMillis();
        }

        @Override
        public boolean exists() {
            return true;
        }

        @Override
        public File getFile() throws IOException {
            // Non è un file su disco: chi lo serve deve leggerlo dallo stream.
            throw new FileNotFoundException("Copia girata di " + nome);
        }

        @Override
        public String getFilename() {
            return nome;
        }

        @Override
        public String getDescription() {
            return "JPEG girato (orientamento " + testa.orientamento() + ") di " + file;
        }

        @Override
        public boolean equals(Object altro) {
            return altro instanceof JpegGirato g && g.file.equals(file) && g.testa.orientamento() == testa.orientamento();
        }

        @Override
        public int hashCode() {
            return file.hashCode() * 31 + testa.orientamento();
        }
    }
}
