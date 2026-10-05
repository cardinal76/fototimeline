package it.fototimeline.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import it.fototimeline.media.InfoVideo;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;
import it.fototimeline.service.Risultati.PaginaFoto;
import it.fototimeline.service.Risultati.PuntoMappa;
import it.fototimeline.service.Risultati.Ricordo;
import it.fototimeline.service.Risultati.VoceMese;

@Service
public class FotoService {

    private static final Logger log = LoggerFactory.getLogger(FotoService.class);

    static final Map<String, String> TIPI = Map.ofEntries(
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("png", "image/png"),
            Map.entry("gif", "image/gif"),
            Map.entry("bmp", "image/bmp"),
            Map.entry("webp", "image/webp"),
            Map.entry("heic", "image/heic"),
            Map.entry("heif", "image/heif"),
            Map.entry("mp4", "video/mp4"),
            Map.entry("m4v", "video/mp4"),
            Map.entry("mov", "video/quicktime"));

    /** Come si legge un file: le foto con Java, HEIC e video con programmi esterni (StrumentiMedia). */
    enum Genere { FOTO, HEIC, VIDEO }

    static Genere genere(String estensione) {
        return switch (estensione) {
            case "jpg", "jpeg", "png", "gif", "bmp", "webp" -> Genere.FOTO;
            case "heic", "heif" -> Genere.HEIC;
            case "mp4", "m4v", "mov" -> Genere.VIDEO;
            default -> null;
        };
    }

    private final FotoRepository repository;
    private final MongoTemplate mongo;
    private final ArchivioFile archivio;
    private final EstrattoreMetadati estrattore;
    private final StrumentiMedia media;
    private final Clock clock;

    public FotoService(FotoRepository repository, MongoTemplate mongo, ArchivioFile archivio,
            EstrattoreMetadati estrattore, StrumentiMedia media, Optional<Clock> clock) {
        this.repository = repository;
        this.mongo = mongo;
        this.archivio = archivio;
        this.estrattore = estrattore;
        this.media = media;
        this.clock = clock.orElse(Clock.systemDefaultZone());
    }

    // ---------------------------------------------------------------- ingresso

    /** Come {@link #importa(String, Path, Instant, String)}, da byte in memoria (test, file piccoli). */
    public Caricamento importa(String nome, byte[] contenuto, Instant ultimaModifica, String album) {
        Path temporaneo = null;
        try {
            temporaneo = Files.createTempFile("fototimeline-", "." + estensione(nome));
            Files.write(temporaneo, contenuto);
            return importa(nome, temporaneo, ultimaModifica, album);
        } catch (IOException e) {
            return new Caricamento(nome, Esito.ERRORE, null, e.getMessage());
        } finally {
            eliminaSilenzioso(temporaneo);
        }
    }

    /**
     * Mette in archivio una foto o un video, leggendo il file senza caricarlo
     * tutto in memoria (un video può pesare gigabyte). Se c'è già (stesso
     * SHA-256) non lo duplica; se il file non è leggibile risponde con un
     * errore senza lanciare.
     */
    public Caricamento importa(String nome, Path file, Instant ultimaModifica, String album) {
        archivio.verificaDisponibile();
        Letto letto;
        try {
            letto = leggi(nome, file);
        } catch (Scartato s) {
            return s.esito;
        }
        Foto foto = letto.foto();
        foto.setAlbum(pulisciAlbum(album));
        if (ultimaModifica != null) {
            data(foto, letto.data(), LocalDateTime.ofInstant(ultimaModifica, clock.getZone()), OrigineData.FILE);
        } else {
            data(foto, letto.data(), LocalDateTime.now(clock), OrigineData.CARICAMENTO);
        }

        try {
            foto.setPercorso(archivio.salvaOriginale(
                    nome, foto.getId(), letto.estensione(), foto.getScattataIl().toLocalDate(), file));
            anteprime(letto, file);
            return new Caricamento(nome, Esito.CARICATA, repository.insert(foto), null);
        } catch (DuplicateKeyException e) {
            // Stesso file caricato in parallelo: ha vinto l'altro.
            archivio.elimina(foto.getId(), foto.getPercorso());
            return new Caricamento(nome, Esito.DUPLICATA, repository.findByHash(foto.getHash()).orElse(null),
                    "Già in archivio");
        } catch (Exception e) {
            log.warn("Importazione di {} fallita", nome, e);
            archivio.elimina(foto.getId(), foto.getPercorso());
            return new Caricamento(nome, Esito.ERRORE, null, e.getMessage());
        } finally {
            eliminaSilenzioso(letto.anteprima());
        }
    }

    /**
     * "Indicizza archivio": dà la scheda a un file che sta già nell'archivio
     * ma che l'app non conosce (messo nel cloud da fuori). Stessa lettura di
     * {@link #importa(String, Path, Instant, String)}, ma il file non si copia:
     * resta dov'è, salvo spostarlo nella cartella del suo giorno se la data
     * letta dice un altro giorno. Senza data nei metadati vale la cartella
     * AAAA/MM/GG in cui si trova, altrimenti la data di modifica del file.
     */
    public Caricamento indicizza(Path file) {
        archivio.verificaDisponibile();
        String nome = file.getFileName().toString();
        String percorso;
        try {
            percorso = archivio.percorso(file);
        } catch (IllegalArgumentException e) {
            return new Caricamento(nome, Esito.ERRORE, null, e.getMessage());
        }
        // Prima del resto: dal cloud calcolare l'hash vuol dire scaricare il file.
        if (repository.existsByPercorso(percorso)) {
            return new Caricamento(nome, Esito.DUPLICATA, null, "Già in archivio");
        }
        Letto letto;
        try {
            letto = leggi(nome, file);
        } catch (Scartato s) {
            // Anche una copia identica di una foto che sta altrove: il file resta dov'è, senza scheda.
            return s.esito;
        }
        Foto foto = letto.foto();
        foto.setPercorso(percorso);
        try {
            LocalDateTime modifica = LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), clock.getZone());
            LocalDate giorno = ArchivioFile.giornoDellaCartella(percorso);
            if (giorno != null) {
                data(foto, letto.data(), giorno.atTime(modifica.toLocalTime()), OrigineData.CARTELLA);
            } else {
                data(foto, letto.data(), modifica, OrigineData.FILE);
            }
            anteprime(letto, file);
            foto = repository.insert(foto);
        } catch (DuplicateKeyException e) {
            archivio.elimina(foto.getId(), null);
            return new Caricamento(nome, Esito.DUPLICATA, repository.findByHash(foto.getHash()).orElse(null),
                    "Già in archivio");
        } catch (Exception e) {
            log.warn("Indicizzazione di {} fallita", percorso, e);
            archivio.elimina(foto.getId(), null);
            return new Caricamento(nome, Esito.ERRORE, null, e.getMessage());
        } finally {
            eliminaSilenzioso(letto.anteprima());
        }
        // Dopo l'inserimento: se lo spostamento non riesce la scheda è giusta lo stesso, e il riordino ci riprova.
        try {
            if (mettiAlSuoPosto(foto)) {
                foto = repository.save(foto);
            }
        } catch (RuntimeException e) {
            log.warn("Indicizzata ma non riesco a spostare {}: {}", percorso, e.getMessage());
        }
        return new Caricamento(nome, Esito.CARICATA, foto, null);
    }

    /** Quello che si sa di un file dopo averlo letto, prima di metterlo in archivio. */
    private record Letto(Foto foto, Genere genere, String estensione, Path anteprima, LocalDateTime data) {
    }

    /** Il file non entra: l'esito da restituire (formato, duplicato, illeggibile). */
    private static final class Scartato extends Exception {
        private final transient Caricamento esito;

        Scartato(Caricamento esito) {
            super(esito.messaggio(), null, false, false);
            this.esito = esito;
        }
    }

    /**
     * Legge hash, dimensioni e metadati di un file e prepara la scheda (senza
     * data e percorso, che dipendono da chi la chiama). {@code anteprima} è il
     * JPEG da cui fare la miniatura (e per gli HEIC la vista), null per le foto
     * normali; {@code data} quella dei metadati, se c'è.
     */
    private Letto leggi(String nome, Path file) throws Scartato {
        String estensione = estensione(nome);
        Genere genere = genere(estensione);
        if (genere == null) {
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Formato non supportato"));
        }
        if (genere == Genere.HEIC && !media.heicDisponibile()) {
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Per le foto HEIC serve heif-convert sul server"));
        }
        if (genere == Genere.VIDEO && !media.videoDisponibile()) {
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Per i video servono ffmpeg e ffprobe sul server"));
        }

        String hash;
        long dimensione;
        try {
            hash = sha256(file);
            dimensione = Files.size(file);
        } catch (IOException e) {
            log.warn("{} illeggibile: {}", nome, e.toString());
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "File illeggibile (" + e.getMessage() + ")"));
        }
        Optional<Foto> esistente = repository.findByHash(hash);
        if (esistente.isPresent()) {
            throw new Scartato(new Caricamento(nome, Esito.DUPLICATA, esistente.get(), "Già in archivio"));
        }

        Foto foto = new Foto();
        foto.setId(UUID.randomUUID().toString());
        foto.setNomeOriginale(nome);
        foto.setContentType(TIPI.get(estensione));
        foto.setDimensione(dimensione);
        foto.setHash(hash);
        foto.setCaricataIl(clock.instant());
        foto.setVideo(genere == Genere.VIDEO);

        Path anteprima = null;
        try {
            LocalDateTime data;
            switch (genere) {
                case FOTO -> {
                    int[] dimensioni = dimensioni(file);
                    MetadatiFoto metadati = estrattore.estrai(file);
                    foto.setLarghezza(metadati.ruotata90() ? dimensioni[1] : dimensioni[0]);
                    foto.setAltezza(metadati.ruotata90() ? dimensioni[0] : dimensioni[1]);
                    applica(foto, metadati);
                    data = metadati.scattataIl();
                }
                case HEIC -> {
                    anteprima = Files.createTempFile("fototimeline-", ".jpg");
                    media.heicInJpeg(file, anteprima);
                    // heif-convert lo scrive già dritto: le dimensioni sono quelle del JPEG.
                    int[] dimensioni = dimensioni(anteprima);
                    foto.setLarghezza(dimensioni[0]);
                    foto.setAltezza(dimensioni[1]);
                    MetadatiFoto metadati = estrattore.estrai(file);
                    applica(foto, metadati);
                    data = metadati.scattataIl();
                }
                case VIDEO -> {
                    InfoVideo info = media.leggiVideo(file, clock.getZone());
                    foto.setLarghezza(info.larghezza());
                    foto.setAltezza(info.altezza());
                    foto.setDurata(info.durata());
                    foto.setFotocamera(info.fotocamera());
                    foto.setLatitudine(info.latitudine());
                    foto.setLongitudine(info.longitudine());
                    anteprima = Files.createTempFile("fototimeline-", ".jpg");
                    media.fotogramma(file, info.durata(), anteprima);
                    data = info.ripresoIl();
                }
                default -> throw new IllegalStateException(genere.name());
            }
            return new Letto(foto, genere, estensione, anteprima, data);
        } catch (Exception e) {
            eliminaSilenzioso(anteprima);
            log.warn("{} illeggibile: {}", nome, e.toString());
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null,
                    (genere == Genere.VIDEO ? "Video illeggibile" : "Immagine illeggibile")
                            + (e.getMessage() != null ? " (" + e.getMessage() + ")" : "")));
        }
    }

    /** La data dei metadati se c'è (EXIF), altrimenti la riserva con la sua origine. */
    private static void data(Foto foto, LocalDateTime metadati, LocalDateTime riserva, OrigineData origineRiserva) {
        if (metadati != null) {
            foto.setScattataIl(metadati);
            foto.setOrigineData(OrigineData.EXIF);
        } else {
            foto.setScattataIl(riserva);
            foto.setOrigineData(origineRiserva);
        }
    }

    /** Miniatura e, per gli HEIC, vista JPEG. */
    private void anteprime(Letto letto, Path file) throws IOException {
        Path anteprima = letto.anteprima();
        archivio.creaMiniatura(letto.foto().getId(), anteprima != null ? anteprima : file, anteprima == null);
        if (letto.genere() == Genere.HEIC) {
            archivio.salvaVista(letto.foto().getId(), anteprima);
        }
    }

    private static void applica(Foto foto, MetadatiFoto metadati) {
        foto.setFotocamera(metadati.fotocamera());
        foto.setLatitudine(metadati.latitudine());
        foto.setLongitudine(metadati.longitudine());
    }

    private static void eliminaSilenzioso(Path p) {
        if (p != null) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException e) {
                log.debug("Non riesco a togliere il temporaneo {}", p);
            }
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

    /** Le foto con posizione GPS, con i filtri della timeline. */
    public List<PuntoMappa> mappa(FiltroFoto filtro) {
        Query query = new Query(new Criteria().andOperator(
                criteri(filtro),
                Criteria.where("latitudine").ne(null),
                Criteria.where("longitudine").ne(null)));
        query.fields().include("latitudine", "longitudine", "giorno", "titolo", "video");
        return mongo.find(query, Foto.class).stream()
                .map(f -> new PuntoMappa(f.getId(), f.getLatitudine(), f.getLongitudine(), f.getGiorno(),
                        f.getTitolo(), f.isVideo()))
                .toList();
    }

    /**
     * "Accadde oggi": le foto scattate lo stesso giorno e mese di {@code oggi}
     * negli anni passati, dal più recente; al massimo {@code perAnno} per anno.
     */
    public List<Ricordo> ricordi(LocalDate oggi, int perAnno) {
        String meseGiorno = "-%02d-%02d".formatted(oggi.getMonthValue(), oggi.getDayOfMonth());
        Query query = Query.query(Criteria.where("giorno").regex(Pattern.quote(meseGiorno) + "$")
                .lt(oggi.getYear() + "-"))
                .with(Sort.by(Sort.Order.desc("scattataIl")));
        Map<Integer, List<Foto>> perAnni = new java.util.LinkedHashMap<>();
        for (Foto f : mongo.find(query, Foto.class)) {
            List<Foto> anno = perAnni.computeIfAbsent(f.getScattataIl().getYear(), a -> new ArrayList<>());
            if (anno.size() < perAnno) {
                anno.add(f);
            }
        }
        return perAnni.entrySet().stream()
                .map(e -> new Ricordo(e.getKey(), oggi.getYear() - e.getKey(), e.getValue()))
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
            rifaiAnteprime(foto);
        }
        return miniatura;
    }

    /**
     * Quello che il browser sa mostrare: per gli HEIC la vista JPEG (rifatta se
     * manca), per il resto l'originale.
     */
    public Path fileVista(Foto foto) {
        if (genere(estensione(foto.getPercorso())) != Genere.HEIC) {
            return fileOriginale(foto);
        }
        Path vista = archivio.vista(foto.getId());
        if (!Files.exists(vista)) {
            rifaiAnteprime(foto);
        }
        return vista;
    }

    public String contentTypeVista(Foto foto) {
        return genere(estensione(foto.getPercorso())) == Genere.HEIC ? "image/jpeg" : foto.getContentType();
    }

    private void rifaiAnteprime(Foto foto) {
        Path originale = fileOriginale(foto);
        Path anteprima = null;
        try {
            switch (genere(estensione(foto.getPercorso()))) {
                case HEIC -> {
                    anteprima = Files.createTempFile("fototimeline-", ".jpg");
                    media.heicInJpeg(originale, anteprima);
                    archivio.salvaVista(foto.getId(), anteprima);
                }
                case VIDEO -> {
                    anteprima = Files.createTempFile("fototimeline-", ".jpg");
                    media.fotogramma(originale, foto.getDurata(), anteprima);
                }
                case null, default -> {
                    // FOTO: si legge direttamente.
                }
            }
            archivio.creaMiniatura(foto.getId(), anteprima != null ? anteprima : originale, anteprima == null);
        } catch (IOException e) {
            throw new UncheckedIOException("Non riesco a rifare la miniatura di " + foto.getPercorso(), e);
        } finally {
            eliminaSilenzioso(anteprima);
        }
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
    private static int[] dimensioni(Path file) throws IOException {
        try (var in = ImageIO.createImageInputStream(file.toFile())) {
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

    private static String sha256(Path file) throws IOException {
        try (var in = new DigestInputStream(Files.newInputStream(file), MessageDigest.getInstance("SHA-256"))) {
            in.transferTo(OutputStream.nullOutputStream());
            return HexFormat.of().formatHex(in.getMessageDigest().digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
