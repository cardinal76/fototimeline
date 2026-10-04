package it.fototimeline.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.StringOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;
import it.fototimeline.service.Risultati.PaginaFoto;
import it.fototimeline.service.Risultati.VoceMese;

@Service
public class FotoService {

    private static final Logger log = LoggerFactory.getLogger(FotoService.class);

    static final Map<String, String> TIPI = Map.of(
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "png", "image/png",
            "gif", "image/gif",
            "bmp", "image/bmp",
            "webp", "image/webp");

    private final FotoRepository repository;
    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final EstrattoreMetadati estrattore;
    private final Clock clock;

    public FotoService(FotoRepository repository, MongoTemplate mongo, ArchivioFile archivio,
            EstrattoreMetadati estrattore, Optional<Clock> clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.archivio = archivio;
        this.estrattore = estrattore;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    // ---------------------------------------------------------------- ingresso

    /**
     * Mette in archivio una foto. Se c'è già (stesso SHA-256) non la duplica;
     * se il file non è un'immagine leggibile risponde con un errore senza lanciare.
     */
    public Caricamento importa(String nome, byte[] contenuto, Instant ultimaModifica, String album) {
        archivio.verificaDisponibile();
        String estensione = estensione(nome);
        if (!TIPI.containsKey(estensione)) {
            return new Caricamento(nome, Esito.ERRORE, null, "Formato non supportato");
        }

        String hash = sha256(contenuto);
        Optional<Foto> esistente = repository.findByHash(hash);
        if (esistente.isPresent()) {
            return new Caricamento(nome, Esito.DUPLICATA, esistente.get(), "Già in archivio");
        }

        int[] dimensioni;
        try {
            dimensioni = dimensioni(contenuto);
        } catch (IOException e) {
            return new Caricamento(nome, Esito.ERRORE, null, "Immagine illeggibile");
        }

        MetadatiFoto metadati = estrattore.estrai(contenuto);
        Foto foto = new Foto();
        foto.setId(UUID.randomUUID().toString());
        foto.setNomeOriginale(nome);
        foto.setContentType(TIPI.get(estensione));
        foto.setDimensione(contenuto.length);
        foto.setHash(hash);
        foto.setCaricataIl(clock.instant());
        if (dimensioni != null) {
            foto.setLarghezza(metadati.ruotata90() ? dimensioni[1] : dimensioni[0]);
            foto.setAltezza(metadati.ruotata90() ? dimensioni[0] : dimensioni[1]);
        }
        if (metadati.scattataIl() != null) {
            foto.setScattataIl(metadati.scattataIl());
            foto.setOrigineData(OrigineData.EXIF);
        } else if (ultimaModifica != null) {
            foto.setScattataIl(LocalDateTime.ofInstant(ultimaModifica, clock.getZone()));
            foto.setOrigineData(OrigineData.FILE);
        } else {
            foto.setScattataIl(LocalDateTime.now(clock));
            foto.setOrigineData(OrigineData.CARICAMENTO);
        }
        foto.setFotocamera(metadati.fotocamera());
        foto.setLatitudine(metadati.latitudine());
        foto.setLongitudine(metadati.longitudine());
        foto.setAlbum(pulisciAlbum(album));

        try {
            foto.setPercorso(archivio.salvaOriginale(
                    nome, foto.getId(), estensione, foto.getScattataIl().toLocalDate(), contenuto));
            archivio.creaMiniatura(foto.getId(), contenuto);
            return new Caricamento(nome, Esito.CARICATA, repository.insert(foto), null);
        } catch (DuplicateKeyException e) {
            // Stesso file caricato in parallelo: ha vinto l'altro.
            archivio.elimina(foto.getId(), foto.getPercorso());
            return new Caricamento(nome, Esito.DUPLICATA, repository.findByHash(hash).orElse(null), "Già in archivio");
        } catch (Exception e) {
            log.warn("Importazione di {} fallita", nome, e);
            archivio.elimina(foto.getId(), foto.getPercorso());
            return new Caricamento(nome, Esito.ERRORE, null, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- lettura

    public PaginaFoto cerca(FiltroFoto filtro, int pagina, int dimensione) {
        Query query = new Query(criteri(filtro));
        long totale = mongo.count(query, Foto.class);
        query.with(Sort.by(Sort.Order.desc("scattataIl"), Sort.Order.desc("_id")))
                .skip((long) pagina * dimensione)
                .limit(dimensione);
        List<Foto> foto = mongo.find(query, Foto.class);
        return new PaginaFoto(foto, totale, pagina, (long) (pagina + 1) * dimensione < totale);
    }

    /** I mesi che hanno foto, dal più recente, con i filtri ma senza il taglio per date. */
    public List<VoceMese> timeline(FiltroFoto filtro) {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(criteri(filtro.senzaDate())),
                Aggregation.project().and(StringOperators.Substr.valueOf("giorno").substring(0, 7)).as("mese"),
                Aggregation.group("mese").count().as("conteggio"),
                Aggregation.sort(Sort.Direction.DESC, "_id"));
        return mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults().stream()
                .filter(d -> d.getString("_id") != null)
                .map(d -> {
                    String[] am = d.getString("_id").split("-");
                    return new VoceMese(Integer.parseInt(am[0]), Integer.parseInt(am[1]),
                            ((Number) d.get("conteggio")).longValue());
                })
                .toList();
    }

    public Optional<Foto> trova(String id) {
        return repository.findById(id);
    }

    public List<String> tag() {
        // Non distinct: per Mongo un array vuoto vale "undefined" e il driver non lo legge come stringa.
        var aggregazione = Aggregation.newAggregation(
                Aggregation.unwind("tag"),
                Aggregation.group("tag"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        return mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults().stream()
                .map(d -> d.getString("_id"))
                .toList();
    }

    public List<String> album() {
        return mongo.findDistinct(new Query(), "album", Foto.class, String.class).stream()
                .filter(a -> a != null && !a.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public Path fileOriginale(Foto foto) {
        return archivio.originale(foto.getPercorso());
    }

    /**
     * La miniatura; se manca (archivio nel cloud e disco del server nuovo, o
     * cartella delle miniature cancellata) la rifà dall'originale.
     */
    public Path fileMiniatura(Foto foto) {
        Path miniatura = archivio.miniatura(foto.getId());
        if (!Files.exists(miniatura)) {
            try {
                archivio.creaMiniatura(foto.getId(), Files.readAllBytes(fileOriginale(foto)));
            } catch (IOException e) {
                throw new UncheckedIOException("Non riesco a rifare la miniatura di " + foto.getPercorso(), e);
            }
        }
        return miniatura;
    }

    // ---------------------------------------------------------------- modifica

    public record Modifica(String titolo, String descrizione, Collection<String> tag, String album,
            Boolean preferita, LocalDateTime scattataIl) {
    }

    public Optional<Foto> modifica(String id, Modifica m) {
        return repository.findById(id).map(foto -> {
            foto.setTitolo(vuotoANull(m.titolo()));
            foto.setDescrizione(vuotoANull(m.descrizione()));
            foto.setTag(pulisciTag(m.tag()));
            foto.setAlbum(pulisciAlbum(m.album()));
            if (m.preferita() != null) {
                foto.setPreferita(m.preferita());
            }
            if (m.scattataIl() != null && !m.scattataIl().equals(foto.getScattataIl())) {
                foto.setScattataIl(m.scattataIl());
                foto.setOrigineData(OrigineData.MANUALE);
                mettiAlSuoPosto(foto);
            }
            return repository.save(foto);
        });
    }

    /** Sposta l'originale nella cartella del giorno di scatto, se non c'è già. */
    private boolean mettiAlSuoPosto(Foto foto) {
        boolean nomeDaId = foto.getPercorso().substring(foto.getPercorso().lastIndexOf('/') + 1).startsWith(foto.getId());
        if (ArchivioFile.alSuoPosto(foto.getPercorso(), foto.getScattataIl().toLocalDate()) && !nomeDaId) {
            return false;
        }
        try {
            // Col nome originale: gli archivi vecchi avevano l'id come nome del file.
            String nome = ArchivioFile.nomeSicuro(foto.getNomeOriginale(), foto.getId(), estensione(foto.getPercorso()));
            foto.setPercorso(archivio.sposta(foto.getPercorso(), foto.getScattataIl().toLocalDate(), nome));
            return true;
        } catch (IOException e) {
            throw new UncheckedIOException("Non riesco a spostare " + foto.getPercorso(), e);
        }
    }

    /**
     * Mette ogni originale nella cartella anno/mese/giorno della sua data di
     * scatto. Serve per gli archivi fatti prima delle cartelle per giorno e
     * rimette a posto quello che è stato spostato a mano. Restituisce quante
     * foto ha spostato.
     */
    public int riordinaArchivio() {
        if (!archivio.disponibile()) {
            log.info("Archivio smontato: il riordino aspetta il montaggio");
            return 0;
        }
        int spostate = 0;
        for (Foto foto : repository.findAll()) {
            try {
                if (Files.exists(archivio.originale(foto.getPercorso())) && mettiAlSuoPosto(foto)) {
                    repository.save(foto);
                    spostate++;
                }
            } catch (RuntimeException e) {
                log.warn("Non riesco a riordinare {}: {}", foto.getPercorso(), e.getMessage());
            }
        }
        return spostate;
    }

    public enum Operazione { ELIMINA, AGGIUNGI_TAG, TOGLI_TAG, IMPOSTA_ALBUM, PREFERITA, NON_PREFERITA }

    /** Applica la stessa operazione a più foto; restituisce quante ne ha toccate. */
    public long operazioneMultipla(List<String> ids, Operazione operazione, String valore) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        Query query = Query.query(Criteria.where("_id").in(ids));
        return switch (operazione) {
            case ELIMINA -> ids.stream().filter(this::elimina).count();
            case AGGIUNGI_TAG -> {
                Set<String> tag = tagDa(valore);
                yield tag.isEmpty() ? 0 : mongo.updateMulti(query, new Update().addToSet("tag").each(tag.toArray()), Foto.class)
                        .getModifiedCount();
            }
            case TOGLI_TAG -> {
                Set<String> tag = tagDa(valore);
                yield tag.isEmpty() ? 0 : mongo.updateMulti(query, new Update().pullAll("tag", tag.toArray()), Foto.class)
                        .getModifiedCount();
            }
            case IMPOSTA_ALBUM -> mongo.updateMulti(query, new Update().set("album", pulisciAlbum(valore)), Foto.class)
                    .getModifiedCount();
            case PREFERITA -> mongo.updateMulti(query, new Update().set("preferita", true), Foto.class).getModifiedCount();
            case NON_PREFERITA -> mongo.updateMulti(query, new Update().set("preferita", false), Foto.class).getModifiedCount();
        };
    }

    public boolean elimina(String id) {
        archivio.verificaDisponibile();
        return repository.findById(id).map(foto -> {
            repository.delete(foto);
            archivio.elimina(foto.getId(), foto.getPercorso());
            return true;
        }).orElse(false);
    }

    // ---------------------------------------------------------------- utilità

    static Criteria criteri(FiltroFoto f) {
        List<Criteria> e = new ArrayList<>();
        if (f.q() != null && !f.q().isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(f.q().trim()), Pattern.CASE_INSENSITIVE);
            e.add(new Criteria().orOperator(
                    Criteria.where("titolo").regex(p),
                    Criteria.where("descrizione").regex(p),
                    Criteria.where("nomeOriginale").regex(p),
                    Criteria.where("album").regex(p),
                    Criteria.where("fotocamera").regex(p),
                    Criteria.where("tag").regex(p)));
        }
        if (f.tag() != null && !f.tag().isBlank()) {
            e.add(Criteria.where("tag").is(f.tag().trim().toLowerCase(Locale.ROOT)));
        }
        if (f.album() != null && !f.album().isBlank()) {
            e.add(Criteria.where("album").is(f.album().trim()));
        }
        if (Boolean.TRUE.equals(f.preferite())) {
            e.add(Criteria.where("preferita").is(true));
        }
        if (f.dal() != null || f.al() != null) {
            Criteria data = Criteria.where("scattataIl");
            if (f.dal() != null) {
                data = data.gte(f.dal().atStartOfDay());
            }
            if (f.al() != null) {
                data = data.lt(f.al().plusDays(1).atStartOfDay());
            }
            e.add(data);
        }
        return e.isEmpty() ? new Criteria() : new Criteria().andOperator(e);
    }

    static Set<String> pulisciTag(Collection<String> tag) {
        if (tag == null) {
            return new TreeSet<>();
        }
        return tag.stream()
                .filter(t -> t != null)
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> tagDa(String elenco) {
        return pulisciTag(elenco == null ? List.of() : List.of(elenco.split(",")));
    }

    static String pulisciAlbum(String album) {
        return vuotoANull(album);
    }

    private static String vuotoANull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String estensione(String nome) {
        int punto = nome == null ? -1 : nome.lastIndexOf('.');
        return punto < 0 ? "" : nome.substring(punto + 1).toLowerCase(Locale.ROOT);
    }

    /** Larghezza e altezza grezze (prima dell'orientamento EXIF), senza decodificare tutta l'immagine. */
    private static int[] dimensioni(byte[] contenuto) throws IOException {
        try (var in = ImageIO.createImageInputStream(new ByteArrayInputStream(contenuto))) {
            var lettori = in == null ? null : ImageIO.getImageReaders(in);
            if (lettori == null || !lettori.hasNext()) {
                throw new IOException("Nessun lettore per questa immagine");
            }
            var lettore = lettori.next();
            try {
                lettore.setInput(in);
                return new int[] {lettore.getWidth(0), lettore.getHeight(0)};
            } finally {
                lettore.dispose();
            }
        }
    }

    private static String sha256(byte[] contenuto) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contenuto));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
