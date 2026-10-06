package it.fototimeline.condivisione;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import it.fototimeline.condivisione.Condivisione.Origine;
import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import net.coobird.thumbnailator.Thumbnails;

/**
 * Link pubblici a un gruppo di foto. Il token è l'unica chiave: 256 bit
 * casuali, e ogni richiesta pubblica controlla che sia valido, non scaduto,
 * non revocato e che la foto chiesta sia sua.
 *
 * <p>Chi ha il link vede solo quello che c'è in {@link FotoCondivisa}: niente
 * percorso nel cloud, nome del file, descrizione, tag, album, fotocamera; il
 * GPS solo se chi crea il link lo sceglie. Le foto si vedono da un JPEG
 * ridotto e senza EXIF; gli originali (con i loro metadati) solo se il
 * download è permesso.
 */
@Service
public class CondivisioniService {

    /** Le scadenze che si possono scegliere, in giorni; null = mai. */
    static final Set<Integer> SCADENZE = Set.of(1, 7, 30);

    private static final SecureRandom CASO = new SecureRandom();
    private static final Pattern FORMA_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");
    /** Già compressi: nello zip non si ricomprimono. */
    private static final Set<String> COMPRESSI = Set.of("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "mp4",
            "m4v", "mov");
    private static final DateTimeFormatter NOME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH.mm.ss");
    private static final int LUNGHEZZA_TITOLO = 120;

    private final CondivisioneRepository repository;
    private final MongoTemplate mongo;
    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final CondivisioniProperties properties;
    /** Le viste ridotte si fanno una alla volta: una foto grande decodificata pesa decine di MB. */
    private final Semaphore riduzioni = new Semaphore(1);

    public CondivisioniService(CondivisioneRepository repository, MongoTemplate mongo, FotoService fotoService,
            ArchivioFile archivio, CondivisioniProperties properties) {
        this.repository = repository;
        this.mongo = mongo;
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- gestione (utenti collegati)

    /**
     * Le foto si scelgono in uno dei tre modi: {@code ids} (la selezione),
     * {@code album}, oppure {@code dal}/{@code al} (giorni di scatto).
     *
     * @param giorni 1, 7 o 30; null = non scade
     */
    public record NuovaCondivisione(String titolo, List<String> ids, String album, LocalDate dal, LocalDate al,
            Integer giorni, boolean download, boolean posizione) {
    }

    /** Per l'elenco "Le mie condivisioni": qui il token c'è, è di chi l'ha creato. */
    public record VoceCondivisione(String id, String token, String titolo, int foto, Origine origine,
            String descrizioneOrigine, String creataDa, Instant creataIl, Instant scadeIl, boolean download,
            boolean posizione, long visite, Instant ultimoAccesso, boolean revocata, boolean scaduta) {
    }

    public Condivisione crea(NuovaCondivisione n, String utente) {
        if (n.giorni() != null && !SCADENZE.contains(n.giorni())) {
            throw new IllegalArgumentException("Scadenza non valida: 1, 7, 30 giorni o mai.");
        }
        boolean perIds = n.ids() != null && !n.ids().isEmpty();
        boolean perAlbum = n.album() != null && !n.album().isBlank();
        boolean perDate = n.dal() != null || n.al() != null;
        if ((perIds ? 1 : 0) + (perAlbum ? 1 : 0) + (perDate ? 1 : 0) != 1) {
            throw new IllegalArgumentException("Scegli le foto in un solo modo: selezione, album o date.");
        }

        Condivisione c = new Condivisione();
        Criteria criteri;
        if (perIds) {
            c.setOrigine(Origine.SELEZIONE);
            criteri = Criteria.where("id").in(n.ids().stream().distinct().limit(properties.massimoFoto() + 1L).toList());
        } else if (perAlbum) {
            c.setOrigine(Origine.ALBUM);
            c.setDescrizioneOrigine(n.album().trim());
            criteri = Criteria.where("album").is(n.album().trim());
        } else {
            LocalDate dal = n.dal() != null ? n.dal() : n.al();
            LocalDate al = n.al() != null ? n.al() : n.dal();
            if (al.isBefore(dal)) {
                throw new IllegalArgumentException("La data di fine viene prima di quella di inizio.");
            }
            c.setOrigine(Origine.DATE);
            c.setDescrizioneOrigine(dal.equals(al) ? dal.toString() : dal + " – " + al);
            criteri = Criteria.where("scattataIl").gte(dal.atStartOfDay()).lt(al.plusDays(1).atStartOfDay());
        }
        Query query = new Query(criteri).with(Sort.by(Sort.Order.asc("scattataIl"), Sort.Order.asc("_id")));
        query.fields().include("_id");
        List<String> foto = mongo.find(query, Foto.class).stream().map(Foto::getId).toList();
        if (foto.isEmpty()) {
            throw new IllegalArgumentException("Nessuna foto da condividere.");
        }
        if (foto.size() > properties.massimoFoto()) {
            throw new IllegalArgumentException("Troppe foto: al massimo " + properties.massimoFoto() + " per link.");
        }

        Instant adesso = Instant.now();
        c.setToken(nuovoToken());
        c.setTitolo(titolo(n, c));
        c.setFoto(foto);
        c.setScadeIl(n.giorni() == null ? null : adesso.plus(Duration.ofDays(n.giorni())));
        c.setDownload(n.download());
        c.setPosizione(n.posizione());
        c.setCreataDa(utente);
        c.setCreataIl(adesso);
        return repository.save(c);
    }

    /** I link di un utente; l'amministratore li vede tutti. */
    public List<VoceCondivisione> elenco(String utente, boolean admin) {
        Instant adesso = Instant.now();
        return (admin ? repository.findAllByOrderByCreataIlDesc() : repository.findByCreataDaOrderByCreataIlDesc(utente))
                .stream()
                .map(c -> voce(c, adesso))
                .toList();
    }

    /** Revoca: solo chi l'ha creato o un amministratore. False se non c'è o non è sua. */
    public boolean revoca(String id, String utente, boolean admin) {
        return repository.findById(id)
                .filter(c -> admin || c.getCreataDa().equals(utente))
                .map(c -> {
                    if (!c.isRevocata()) {
                        c.setRevocata(true);
                        c.setRevocataIl(Instant.now());
                        repository.save(c);
                    }
                    return true;
                })
                .orElse(false);
    }

    public static VoceCondivisione voce(Condivisione c, Instant adesso) {
        return new VoceCondivisione(c.getId(), c.getToken(), c.getTitolo(), c.getFoto().size(), c.getOrigine(),
                c.getDescrizioneOrigine(), c.getCreataDa(), c.getCreataIl(), c.getScadeIl(), c.isDownload(),
                c.isPosizione(), c.getVisite(), c.getUltimoAccesso(), c.isRevocata(), c.scaduta(adesso));
    }

    // ---------------------------------------------------------------- lato pubblico

    /** Una foto come la vede chi ha il link. */
    public record FotoCondivisa(String id, boolean video, Integer larghezza, Integer altezza,
            LocalDateTime scattataIl, Double durata, String titolo, Double latitudine, Double longitudine) {
    }

    /**
     * La galleria pubblica.
     *
     * @param archivioDisponibile false col cloud smontato: si vedono solo le miniature
     */
    public record Galleria(String titolo, Instant scadeIl, boolean download, boolean archivioDisponibile,
            List<FotoCondivisa> foto) {
    }

    /** Link sconosciuto (404, conta per il freno) o scaduto/revocato (410). */
    public static class LinkNonValido extends RuntimeException {
        private final HttpStatus stato;

        public LinkNonValido(HttpStatus stato, String messaggio) {
            super(messaggio);
            this.stato = stato;
        }

        public HttpStatus stato() {
            return stato;
        }

        /** Chi prova link o foto a caso: vale per il blocco. */
        public boolean sconosciuto() {
            return stato == HttpStatus.NOT_FOUND;
        }
    }

    /** La condivisione del token, se valida. */
    public Condivisione valida(String token) {
        if (token == null || !FORMA_TOKEN.matcher(token).matches()) {
            throw sconosciuto();
        }
        Condivisione c = repository.findByToken(token).orElseThrow(CondivisioniService::sconosciuto);
        if (c.isRevocata()) {
            throw new LinkNonValido(HttpStatus.GONE, "Questo link è stato revocato da chi l'ha creato.");
        }
        if (c.scaduta(Instant.now())) {
            throw new LinkNonValido(HttpStatus.GONE, "Questo link è scaduto.");
        }
        return c;
    }

    /** Apertura della galleria: conta la visita. */
    public Galleria apri(String token) {
        Condivisione c = valida(token);
        mongo.updateFirst(Query.query(Criteria.where("id").is(c.getId())),
                new Update().inc("visite", 1).set("ultimoAccesso", Instant.now()), Condivisione.class);
        List<FotoCondivisa> foto = foto(c).stream()
                .map(f -> new FotoCondivisa(f.getId(), f.isVideo(), f.getLarghezza(), f.getAltezza(),
                        f.getScattataIl(), f.getDurata(), f.getTitolo(),
                        c.isPosizione() ? f.getLatitudine() : null,
                        c.isPosizione() ? f.getLongitudine() : null))
                .toList();
        return new Galleria(c.getTitolo(), c.getScadeIl(), c.isDownload(), archivio.disponibile(), foto);
    }

    /** Le foto ancora esistenti della condivisione, nel suo ordine. */
    public List<Foto> foto(Condivisione c) {
        Map<String, Foto> perId = mongo.find(Query.query(Criteria.where("id").in(c.getFoto())), Foto.class).stream()
                .collect(Collectors.toMap(Foto::getId, Function.identity()));
        return c.getFoto().stream().map(perId::get).filter(f -> f != null).toList();
    }

    /** Una foto, solo se fa parte della condivisione: per tutte le altre 404, come un link sconosciuto. */
    public Foto foto(Condivisione c, String id) {
        if (!c.getFoto().contains(id)) {
            throw sconosciuto();
        }
        return fotoService.trova(id).orElseThrow(CondivisioniService::sconosciuto);
    }

    public Path miniatura(Foto foto) {
        return fotoService.fileMiniatura(foto);
    }

    /**
     * La foto per il visore: un JPEG al massimo di {@code latoVista} pixel e
     * senza metadati, fatto la prima volta dall'originale (serve il cloud
     * montato) e poi tenuto accanto alle miniature.
     */
    public Path vista(Foto foto) {
        Path file = archivio.vistaCondivisa(foto.getId());
        if (Files.exists(file)) {
            return file;
        }
        try {
            riduzioni.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrotto");
        }
        Path temporaneo = null;
        try {
            if (Files.exists(file)) {
                return file;
            }
            // Per gli HEIC la vista JPEG (già dritta), per le altre l'originale.
            Path sorgente = fotoService.fileVista(foto);
            boolean exif = !heic(foto);
            Files.createDirectories(file.getParent());
            temporaneo = Files.createTempFile(file.getParent(), "riduzione-", ".jpg");
            int lato = properties.latoVista();
            var costruttore = Thumbnails.of(sorgente.toFile());
            if (foto.getLarghezza() != null && foto.getAltezza() != null
                    && foto.getLarghezza() <= lato && foto.getAltezza() <= lato) {
                costruttore.scale(1.0);
            } else {
                costruttore.size(lato, lato);
            }
            // Thumbnailator riscrive l'immagine: l'EXIF (e il GPS) non passa.
            costruttore.useExifOrientation(exif)
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .outputFormat("jpg")
                    .outputQuality(0.88)
                    .toFile(temporaneo.toFile());
            Files.move(temporaneo, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return file;
        } catch (IOException e) {
            throw new UncheckedIOException("Non riesco a preparare la foto condivisa " + foto.getId(), e);
        } finally {
            riduzioni.release();
            if (temporaneo != null) {
                try {
                    Files.deleteIfExists(temporaneo);
                } catch (IOException e) {
                    // Resta un file temporaneo: niente di grave.
                }
            }
        }
    }

    /** L'originale (video da guardare, o download): passa da ArchivioFile, che controlla il cloud. */
    public Path originale(Foto foto) {
        return fotoService.fileOriginale(foto);
    }

    /** Il nome con cui si scarica: la data di scatto, mai il nome o il percorso dell'archivio. */
    public static String nomeDownload(Foto foto, Set<String> usati) {
        String base = foto.getScattataIl() != null ? NOME.format(foto.getScattataIl()) : "foto";
        String estensione = estensione(foto);
        String nome = base + "." + estensione;
        for (int n = 2; !usati.add(nome); n++) {
            nome = "%s (%d).%s".formatted(base, n, estensione);
        }
        return nome;
    }

    /**
     * Lo zip degli originali, scritto man mano su {@code uscita}: un file alla
     * volta a pezzi di 64 KB, mai tutto in memoria.
     *
     * <p>JPEG e video vanno senza compressione ({@link Deflater#NO_COMPRESSION}):
     * non si guadagnerebbe niente. Non STORED, perché STORED vuole dimensione e
     * CRC prima del contenuto, cioè leggere ogni file due volte dal cloud.
     */
    public void scriviZip(List<Foto> foto, OutputStream uscita) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(uscita);
        Set<String> usati = new HashSet<>();
        byte[] buffer = new byte[64 * 1024];
        for (Foto f : foto) {
            Path file = originale(f);
            ZipEntry voce = new ZipEntry(nomeDownload(f, usati));
            if (f.getScattataIl() != null) {
                voce.setTimeLocal(f.getScattataIl());
            }
            zip.setLevel(COMPRESSI.contains(estensione(f)) ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION);
            zip.putNextEntry(voce);
            try (InputStream in = Files.newInputStream(file)) {
                int letti;
                while ((letti = in.read(buffer)) > 0) {
                    zip.write(buffer, 0, letti);
                }
            }
            zip.closeEntry();
            zip.flush();
        }
        zip.finish();
        zip.flush();
    }

    // ---------------------------------------------------------------- utilità

    static String nuovoToken() {
        byte[] caso = new byte[32];
        CASO.nextBytes(caso);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(caso);
    }

    private static LinkNonValido sconosciuto() {
        return new LinkNonValido(HttpStatus.NOT_FOUND, "Link non valido.");
    }

    private static String titolo(NuovaCondivisione n, Condivisione c) {
        String t = n.titolo() == null ? "" : n.titolo().strip().replaceAll("\\p{Cntrl}", " ");
        if (t.isEmpty()) {
            t = switch (c.getOrigine()) {
                case ALBUM -> c.getDescrizioneOrigine();
                case DATE -> "Foto del " + c.getDescrizioneOrigine();
                case SELEZIONE -> "Foto del " + LocalDate.now(ZoneId.systemDefault());
            };
        }
        return t.length() > LUNGHEZZA_TITOLO ? t.substring(0, LUNGHEZZA_TITOLO).strip() : t;
    }

    private static String estensione(Foto foto) {
        String p = foto.getPercorso() == null ? "" : foto.getPercorso();
        int punto = p.lastIndexOf('.');
        String e = punto < 0 || punto < p.lastIndexOf('/') ? "" : p.substring(punto + 1).toLowerCase(Locale.ROOT);
        return e.matches("[a-z0-9]{1,5}") ? e : "bin";
    }

    private static boolean heic(Foto foto) {
        String e = estensione(foto);
        return e.equals("heic") || e.equals("heif");
    }

    /** Il titolo come nome di file per lo zip. */
    public static String nomeZip(String titolo) {
        String nome = titolo == null ? "" : titolo.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return (nome.isEmpty() ? "foto" : nome) + ".zip";
    }
}
