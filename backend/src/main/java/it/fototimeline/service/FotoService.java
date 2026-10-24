package it.fototimeline.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.StringOperators;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.luoghi.GeocodificaInversa;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.dominio.StatoConversione;
import it.fototimeline.media.InfoVideo;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.quasiuguali.Impronta;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.EventiFoto.FotoAggiunta;
import it.fototimeline.service.EventiFoto.FotoEliminata;
import it.fototimeline.service.EventiFoto.FotoNelCestino;
import it.fototimeline.service.EventiFoto.FotoRipristinata;
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

    /**
     * I file caricati dal sito o dalle app di LifetimeCloud: cifrati nel
     * browser, via WebDAV arrivano così e non si possono leggere.
     */
    public static final String CIFRATO_LIFETIME = "File cifrato da LifetimeCloud (caricato dal sito o dall'app): "
            + "non si può leggere, va caricato via WebDAV";
    private static final byte[] INTESTAZIONE_LCB2 = {'L', 'C', 'B', '2'};

    /**
     * True se l'errore dipende dal contenuto del file (cifrato, formato
     * sconosciuto, immagine o video che non si leggono): rileggerlo uguale
     * darebbe lo stesso errore. Gli altri (cloud che non risponde, programmi
     * mancanti sul server) possono passare da soli.
     */
    public static boolean erroreDelFile(String messaggio) {
        return messaggio != null && !memoriaFinita(messaggio) && (messaggio.equals(CIFRATO_LIFETIME)
                || messaggio.startsWith("Formato non supportato")
                || messaggio.startsWith("Immagine illeggibile") || messaggio.startsWith("Video illeggibile"));
    }

    /**
     * True se dietro l'errore c'è la memoria della JVM finita, non il file:
     * anche se un lettore la avvolge in "Immagine illeggibile (...)", il file
     * va riprovato, non ricordato tra i già falliti.
     */
    static boolean memoriaFinita(String messaggio) {
        return messaggio.contains("OutOfMemoryError") || messaggio.contains("Java heap space")
                || messaggio.contains("GC overhead limit");
    }

    /**
     * L'inizio del messaggio quando il file non si riesce a leggere (il cloud
     * montato che non risponde: "Input/output error"). Non è un errore del
     * file: chi importa lo riprova ({@link ImportazioneCartelle}) e la
     * cartella automatica non lo ricorda.
     */
    public static final String ILLEGGIBILE = "File illeggibile";

    /**
     * Il messaggio di un file il cui contenuto (stesso SHA-256) è una foto nel
     * cestino: non rientra come nuova (l'hash è unico) e non si ripristina da
     * sola, nemmeno dall'importazione automatica o da "Indicizza archivio".
     * Esito DUPLICATA: con "sposta" la copia nell'origine si toglie come per
     * ogni doppione, perché il contenuto è ancora nell'archivio.
     */
    public static final String NEL_CESTINO = "Nel cestino: si ripristina da Strumenti → Cestino";

    /** True se l'errore è di lettura ({@link #ILLEGGIBILE}): riprovando può andare. */
    public static boolean erroreDiLettura(String messaggio) {
        return messaggio != null && messaggio.startsWith(ILLEGGIBILE + " (");
    }

    /** Il messaggio di un errore di lettura. */
    public static String illeggibile(Throwable e) {
        return ILLEGGIBILE + " (" + e.getMessage() + ")";
    }

    /**
     * True se dietro l'eccezione c'è il disco (o il cloud montato) che non ha
     * dato il file, non il suo contenuto: ImageIO e Thumbnailator avvolgono
     * l'"Input/output error" del mount in eccezioni loro.
     */
    static boolean letturaNonRiuscita(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof FileSystemException
                    || t instanceof IOException && t.getMessage() != null && t.getMessage().contains("Input/output error")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    /** Il messaggio di un'eccezione dopo la lettura: di lettura se lo è, altrimenti il suo. */
    private static String messaggio(Exception e) {
        return letturaNonRiuscita(e) ? illeggibile(e) : e.getMessage();
    }

    /**
     * Le parti che prendono molta memoria o CPU, una alla volta anche quando
     * si importano più file insieme ({@code fototimeline.indicizzazione.paralleli}):
     * la miniatura decodifica l'immagine (sottocampionata, {@link it.fototimeline.media.Riduzione}:
     * pochi MB anche per una foto da 50 MP, che intera ne prenderebbe ~460),
     * heif-convert e ffmpeg sono processi grossi, fuori dall'heap ma nella
     * memoria del container. Hash, metadati e attese di rete restano in parallelo.
     */
    private final Semaphore pesanti = new Semaphore(1, true);

    interface Pesante<T> {
        T fai() throws Exception;
    }

    <T> T unaAllaVolta(Pesante<T> lavoro) throws Exception {
        pesanti.acquireUninterruptibly();
        try {
            return lavoro.fai();
        } finally {
            pesanti.release();
        }
    }

    /**
     * Come {@link #unaAllaVolta(Pesante)}, per un file di un lavoro che si
     * può annullare: se lo fermano mentre aspetta il suo turno (gli altri
     * file del lavoro hanno miniature e ffmpeg in corso) smette di aspettare
     * e lancia {@link Annullata}. Il lavoro pesante, una volta cominciato,
     * finisce; ma se il file viene abbandonato perché appeso (una lettura dal
     * mount che non torna, {@link FileInLavorazione#abbandona}) il turno torna
     * subito agli altri, senza aspettare il suo thread: un file appeso non
     * deve fermare le miniature di tutti.
     */
    <T> T unaAllaVolta(Pesante<T> lavoro, ControlloFile.Fase fase, ControlloFile controllo) throws Exception {
        if (controllo == NON_ANNULLABILE) {
            return unaAllaVolta(lavoro);
        }
        controllo.fase(ControlloFile.Fase.ATTESA_TURNO);
        try {
            while (true) {
                if (controllo.fermato()) {
                    throw new Annullata();
                }
                if (pesanti.tryAcquire(ATTESA_TURNO, TimeUnit.MILLISECONDS)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Annullata();
        }
        // Una volta sola: lo può restituire chi abbandona il file o, alla fine, questo thread.
        AtomicBoolean restituito = new AtomicBoolean();
        Runnable rilascio = () -> {
            if (restituito.compareAndSet(false, true)) {
                pesanti.release();
            }
        };
        if (!controllo.tieni(rilascio)) {
            // Abbandonato proprio adesso: il turno torna subito.
            rilascio.run();
            throw new Annullata();
        }
        try {
            controllo.fase(fase);
            return lavoro.fai();
        } finally {
            controllo.lascia(rilascio);
            rilascio.run();
        }
    }

    /** Per i test: quanti file aspettano il turno per le parti pesanti. */
    int inAttesaDelTurno() {
        return pesanti.getQueueLength();
    }

    /** Per i test: i turni liberi per le parti pesanti (1 quando nessuno lavora). */
    int turniLiberi() {
        return pesanti.availablePermits();
    }

    /** Ogni quanto chi aspetta il turno per le parti pesanti guarda se il suo lavoro è stato annullato. */
    private static final long ATTESA_TURNO = 200;

    /** Per chi non si annulla (caricamenti, Takeout): si aspetta il turno senza guardare. */
    static final ControlloFile NON_ANNULLABILE = ControlloFile.NESSUNO;

    /**
     * Il lavoro (importazione, indicizzazione) è stato annullato mentre il
     * file aspettava: il file non è stato toccato (niente in archivio, niente
     * scheda, l'origine dov'era) e non va contato.
     */
    public static final class Annullata extends RuntimeException {
        public Annullata() {
            super("Annullata", null, false, false);
        }
    }

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
    private final GeocodificaInversa luoghi;
    private final LuoghiService luoghiManuali;
    private final ConversioneVideo conversione;
    private final Clock clock;
    private final ApplicationEventPublisher eventi;

    public FotoService(FotoRepository repository, MongoTemplate mongo, ArchivioFile archivio,
            EstrattoreMetadati estrattore, StrumentiMedia media, GeocodificaInversa luoghi, LuoghiService luoghiManuali,
            ConversioneVideo conversione, Optional<Clock> clock, ApplicationEventPublisher eventi) {
        this.repository = repository;
        this.mongo = mongo;
        this.archivio = archivio;
        this.estrattore = estrattore;
        this.media = media;
        this.luoghi = luoghi;
        this.luoghiManuali = luoghiManuali;
        this.conversione = conversione;
        this.clock = clock.orElse(Clock.systemDefaultZone());
        this.eventi = eventi;
    }

    // ---------------------------------------------------------------- ingresso

    /** Come {@link #importa(String, Path, Instant, String)}, da byte in memoria (test, file piccoli). */
    public Caricamento importa(String nome, byte[] contenuto, Instant ultimaModifica, String album) {
        return importa(nome, contenuto, ultimaModifica, album, null);
    }

    public Caricamento importa(String nome, byte[] contenuto, Instant ultimaModifica, String album, String caricataDa) {
        Path temporaneo = null;
        try {
            temporaneo = Files.createTempFile("fototimeline-", "." + estensione(nome));
            Files.write(temporaneo, contenuto);
            return importa(nome, temporaneo, ultimaModifica, album, caricataDa);
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
        return importa(nome, file, ultimaModifica, album, null);
    }

    /**
     * @param caricataDa lo username di chi la porta (chi carica, chi importa, il
     *                   proprietario del telefono); null se non si sa. Un
     *                   duplicato tiene quello che aveva.
     */
    public Caricamento importa(String nome, Path file, Instant ultimaModifica, String album, String caricataDa) {
        return importa(nome, file, ultimaModifica, album, caricataDa, null);
    }

    /**
     * Quello che sa di una foto chi la porta da fuori (il JSON di Google
     * Takeout, la data di Google Foto): vale solo dove il file non dice
     * niente. Data: EXIF → questa ({@link OrigineData#GOOGLE}) → data del
     * file → caricamento. GPS solo se l'EXIF non ce l'ha (0,0 non conta);
     * descrizione solo se c'è; preferita se lo è.
     */
    public record MetadatiEsterni(LocalDateTime scattataIl, Double latitudine, Double longitudine,
            String descrizione, boolean preferita) {

        public static final MetadatiEsterni NESSUNO = new MetadatiEsterni(null, null, null, null, false);

        /** True se ci sono coordinate buone: entrambe, nei limiti, non 0,0 (Google lo usa per "nessuna"). */
        public boolean conPosizione() {
            return latitudine != null && longitudine != null && Math.abs(latitudine) <= 90 && Math.abs(longitudine) <= 180
                    && !(latitudine == 0 && longitudine == 0);
        }
    }

    /**
     * Come {@link #importa(String, Path, Instant, String, String)}, coi
     * metadati di chi la porta ({@code esterni}, anche null). Il file non si
     * tocca: quello che viene da fuori sta solo nella scheda.
     */
    public Caricamento importa(String nome, Path file, Instant ultimaModifica, String album, String caricataDa,
            MetadatiEsterni esterni) {
        return importa(nome, file, ultimaModifica, album, caricataDa, esterni, false, NON_ANNULLABILE);
    }

    /**
     * Per le cartelle ({@link ImportazioneCartelle}).
     *
     * @param sposta    true se l'origine va tolta: se sta sullo stesso disco
     *                  dell'archivio (sul server lo stesso mount di rclone) il
     *                  file si sposta, senza ricopiarlo ({@link ArchivioFile#salvaOriginale});
     *                  altrimenti si copia e, entrata la scheda, si toglie. Un
     *                  doppione resta dov'è: lo toglie chi chiama.
     * @param controllo guardato mentre si legge l'hash, mentre si aspetta il
     *                  turno per le parti pesanti e prima di ogni scrittura: se
     *                  dice {@link ControlloFile#fermato fermato} (annullato, o
     *                  abbandonato perché appeso) il file si lascia com'era e si
     *                  lancia {@link Annullata}. Riceve anche la fase.
     */
    public Caricamento importa(String nome, Path file, Instant ultimaModifica, String album, String caricataDa,
            boolean sposta, ControlloFile controllo) {
        return importa(nome, file, ultimaModifica, album, caricataDa, null, sposta, controllo);
    }

    private Caricamento importa(String nome, Path file, Instant ultimaModifica, String album, String caricataDa,
            MetadatiEsterni esterni, boolean sposta, ControlloFile controllo) {
        archivio.verificaDisponibile();
        Letto letto;
        try {
            letto = leggi(nome, file, controllo);
        } catch (Scartato s) {
            return s.esito;
        }
        Foto foto = letto.foto();
        foto.setAlbum(pulisciAlbum(album));
        foto.setCaricataDa(vuotoANull(caricataDa));
        if (esterni != null) {
            applica(foto, esterni);
        }
        LocalDateTime dataEsterna = esterni != null ? esterni.scattataIl() : null;
        if (letto.data() == null && dataEsterna != null) {
            foto.setScattataIl(dataEsterna);
            foto.setOrigineData(OrigineData.GOOGLE);
        } else if (ultimaModifica != null) {
            data(foto, letto.data(), LocalDateTime.ofInstant(ultimaModifica, clock.getZone()), OrigineData.FILE);
        } else {
            data(foto, letto.data(), LocalDateTime.now(clock), OrigineData.CARICAMENTO);
        }

        ArchivioFile.Salvato salvato = null;
        try {
            // Le anteprime prima di mettere il file in archivio, dall'origine: finché
            // si aspetta il turno (annullabile) l'archivio non è ancora stato toccato.
            anteprime(letto, file, controllo);
            if (controllo.fermato()) {
                throw new Annullata();
            }
            controllo.fase(ControlloFile.Fase.SPOSTAMENTO);
            salvato = archivio.salvaOriginale(nome, foto.getId(), letto.estensione(),
                    foto.getScattataIl().toLocalDate(), file, sposta);
            foto.setPercorso(salvato.percorso());
            controllo.fase(ControlloFile.Fase.SALVATAGGIO);
            // Abbandonato mentre spostava (il MOVE sul cloud che non tornava): niente scheda, il file torna indietro.
            controllo.primaDiScrivere();
            Foto inserita = repository.insert(foto);
            if (sposta && !salvato.spostato()) {
                togliOrigine(nome, file);
            }
            segnalaConversione(foto);
            eventi.publishEvent(new FotoAggiunta(inserita.getId()));
            return new Caricamento(nome, Esito.CARICATA, inserita, null);
        } catch (Annullata a) {
            // Prima dell'archivio: via le miniature. Dopo: l'originale torna nell'origine, o la copia si toglie.
            annullaSalvataggio(foto, salvato, file);
            throw a;
        } catch (DuplicateKeyException e) {
            // Stesso file caricato in parallelo: ha vinto l'altro.
            annullaSalvataggio(foto, salvato, file);
            return giaPresente(nome, repository.findByHash(foto.getHash()).orElse(null));
        } catch (Exception e) {
            log.warn("Importazione di {} fallita", nome, e);
            annullaSalvataggio(foto, salvato, file);
            return new Caricamento(nome, Esito.ERRORE, null, messaggio(e));
        } finally {
            eliminaSilenzioso(letto.anteprima());
        }
    }

    /**
     * La scheda non è entrata: via miniature e originale. Un originale
     * spostato torna nell'origine, mai cancellato: era l'unica copia.
     */
    private void annullaSalvataggio(Foto foto, ArchivioFile.Salvato salvato, Path origine) {
        if (salvato != null && salvato.spostato()) {
            archivio.elimina(foto.getId(), null);
            archivio.rimetti(salvato.percorso(), origine);
        } else {
            archivio.elimina(foto.getId(), salvato != null ? salvato.percorso() : null);
        }
    }

    /** Dopo una copia per spostare (dischi diversi): la scheda c'è, l'origine si toglie. */
    private static void togliOrigine(String nome, Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Importata ma non riesco a togliere {}: {}", nome, e.getMessage());
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
        return indicizza(file, NON_ANNULLABILE);
    }

    /** Come {@link #indicizza(Path)}; {@code controllo} come in {@link #importa(String, Path, Instant, String, String, boolean, ControlloFile)}. */
    public Caricamento indicizza(Path file, ControlloFile controllo) {
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
            letto = leggi(nome, file, controllo);
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
            anteprime(letto, file, controllo);
            controllo.fase(ControlloFile.Fase.SALVATAGGIO);
            controllo.primaDiScrivere();
            foto = repository.insert(foto);
            segnalaConversione(foto);
        } catch (Annullata a) {
            archivio.elimina(foto.getId(), null);
            throw a;
        } catch (DuplicateKeyException e) {
            archivio.elimina(foto.getId(), null);
            return giaPresente(nome, repository.findByHash(foto.getHash()).orElse(null));
        } catch (Exception e) {
            log.warn("Indicizzazione di {} fallita", percorso, e);
            archivio.elimina(foto.getId(), null);
            return new Caricamento(nome, Esito.ERRORE, null, messaggio(e));
        } finally {
            eliminaSilenzioso(letto.anteprima());
        }
        // Dopo l'inserimento: se lo spostamento non riesce la scheda è giusta lo stesso, e il riordino ci riprova.
        try {
            controllo.fase(ControlloFile.Fase.SPOSTAMENTO);
            // Abbandonato intanto: la scheda è giusta lo stesso, lo spostamento lo rifà il riordino.
            if (!controllo.fermato() && mettiAlSuoPosto(foto)) {
                foto = repository.save(foto);
            }
        } catch (RuntimeException e) {
            log.warn("Indicizzata ma non riesco a spostare {}: {}", percorso, e.getMessage());
        }
        // Dopo il salvataggio dello spostamento, che altrimenti cancellerebbe il segno messo dalla ricerca per contenuto.
        eventi.publishEvent(new FotoAggiunta(foto.getId()));
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
    private Letto leggi(String nome, Path file, ControlloFile controllo) throws Scartato {
        String estensione = estensione(nome);
        Genere genere = genere(estensione);
        if (genere == null) {
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Formato non supportato"));
        }
        String hash;
        long dimensione;
        // Una sola lettura dall'inizio alla fine: i primi byte dicono se è cifrato,
        // tutto il file dà l'hash. Dal cloud è l'unico scaricamento: quello che
        // segue (metadati, miniatura) rilegge il file dalla cache di rclone.
        controllo.fase(ControlloFile.Fase.LETTURA);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] inizio = in.readNBytes(INTESTAZIONE_LCB2.length);
            // Prima di tutto (anche degli strumenti che mancano): un file cifrato resta
            // illeggibile comunque, ed è un errore del file, da ricordare; e non vale la
            // pena di scaricarlo tutto per l'hash.
            if (Arrays.equals(inizio, INTESTAZIONE_LCB2)) {
                throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, CIFRATO_LIFETIME));
            }
            if (genere == Genere.HEIC && !media.heicDisponibile()) {
                throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Per le foto HEIC serve heif-convert sul server"));
            }
            if (genere == Genere.VIDEO && !media.videoDisponibile()) {
                throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, "Per i video servono ffmpeg e ffprobe sul server"));
            }
            MessageDigest sha = sha256();
            sha.update(inizio);
            dimensione = inizio.length + aggiungi(sha, in, controllo);
            hash = HexFormat.of().formatHex(sha.digest());
        } catch (IOException e) {
            log.warn("{} illeggibile: {}", nome, e.toString());
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, illeggibile(e)));
        }
        if (controllo.fermato()) {
            // La lettura appesa è tornata, ma il file è stato abbandonato (o il lavoro annullato).
            throw new Annullata();
        }
        controllo.fase(ControlloFile.Fase.METADATI);
        Optional<Foto> esistente = repository.findByHash(hash);
        if (esistente.isPresent()) {
            throw new Scartato(giaPresente(nome, esistente.get()));
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
                    Path jpeg = anteprima;
                    unaAllaVolta(() -> {
                        media.heicInJpeg(file, jpeg);
                        return null;
                    }, ControlloFile.Fase.CONVERSIONE_HEIC, controllo);
                    controllo.fase(ControlloFile.Fase.METADATI);
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
                    conversione.prepara(foto, info);
                    anteprima = Files.createTempFile("fototimeline-", ".jpg");
                    Path jpeg = anteprima;
                    unaAllaVolta(() -> {
                        media.fotogramma(file, info.durata(), jpeg);
                        return null;
                    }, ControlloFile.Fase.FOTOGRAMMA, controllo);
                    data = info.ripresoIl();
                }
                default -> throw new IllegalStateException(genere.name());
            }
            // Dal GPS il nome del luogo: in memoria, niente rete.
            luoghi.applica(foto);
            return new Letto(foto, genere, estensione, anteprima, data);
        } catch (Annullata a) {
            eliminaSilenzioso(anteprima);
            throw a;
        } catch (Exception e) {
            eliminaSilenzioso(anteprima);
            log.warn("{} illeggibile: {}", nome, e.toString());
            if (letturaNonRiuscita(e)) {
                // Il cloud che non ha dato il file, non il file rotto: non è un errore del file.
                throw new Scartato(new Caricamento(nome, Esito.ERRORE, null, illeggibile(e)));
            }
            throw new Scartato(new Caricamento(nome, Esito.ERRORE, null,
                    (genere == Genere.VIDEO ? "Video illeggibile" : "Immagine illeggibile")
                            + (e.getMessage() != null ? " (" + e.getMessage() + ")" : "")));
        }
    }

    /** Lo stesso contenuto c'è già: in archivio o nel cestino ({@link #NEL_CESTINO}). */
    private static Caricamento giaPresente(String nome, Foto esistente) {
        return new Caricamento(nome, Esito.DUPLICATA, esistente,
                esistente != null && esistente.nelCestino() ? NEL_CESTINO : "Già in archivio");
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

    /** Miniatura, per gli HEIC vista JPEG e, per le foto, l'impronta dalla miniatura. */
    private void anteprime(Letto letto, Path file, ControlloFile controllo) throws Exception {
        unaAllaVolta(() -> {
            faiAnteprime(letto, file);
            return null;
        }, ControlloFile.Fase.MINIATURA, controllo);
    }

    private void faiAnteprime(Letto letto, Path file) throws IOException {
        Path anteprima = letto.anteprima();
        archivio.creaMiniatura(letto.foto().getId(), anteprima != null ? anteprima : file, anteprima == null);
        if (letto.genere() == Genere.HEIC) {
            archivio.salvaVista(letto.foto().getId(), anteprima);
        }
        if (letto.genere() != Genere.VIDEO) {
            try {
                letto.foto().setImpronta(Impronta.calcola(archivio.miniatura(letto.foto().getId())));
            } catch (IOException | RuntimeException e) {
                // Non blocca l'importazione: la riempie "Calcola impronte".
                log.warn("Impronta di {} non calcolata: {}", letto.foto().getNomeOriginale(), e.toString());
            }
        }
    }

    /** Un video non compatibile appena entrato: la coda parte (in sottofondo). */
    private void segnalaConversione(Foto foto) {
        if (foto.getConversione() == StatoConversione.IN_CODA) {
            conversione.segnala();
        }
    }

    /** I metadati da fuori dove il file non dice niente (la data la sceglie chi chiama). */
    private void applica(Foto foto, MetadatiEsterni esterni) {
        if (foto.getLatitudine() == null && esterni.conPosizione()) {
            foto.setLatitudine(esterni.latitudine());
            foto.setLongitudine(esterni.longitudine());
            luoghi.applica(foto);
        }
        String descrizione = vuotoANull(esterni.descrizione());
        if (descrizione != null && foto.getDescrizione() == null) {
            foto.setDescrizione(descrizione);
        }
        if (esterni.preferita()) {
            foto.setPreferita(true);
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
        Query query = new Query(criteriConNazioni(filtro));
        long totale = mongo.count(query, Foto.class);
        query.with(Sort.by(Sort.Order.desc("scattataIl"), Sort.Order.desc("_id")))
                .skip((long) pagina * dimensione)
                .limit(dimensione);
        List<Foto> foto = mongo.find(query, Foto.class);
        return new PaginaFoto(foto, totale, pagina, (long) (pagina + 1) * dimensione < totale);
    }

    private static final Pattern MESE = Pattern.compile("\\d{4}-\\d{2}");

    /** I mesi che hanno foto, dal più recente, con i filtri ma senza il taglio per date. */
    public List<VoceMese> timeline(FiltroFoto filtro) {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(criteriConNazioni(filtro.senzaDate())),
                Aggregation.project().and(StringOperators.Substr.valueOf("giorno").substring(0, 7)).as("mese"),
                Aggregation.group("mese").count().as("conteggio"),
                Aggregation.sort(Sort.Direction.DESC, "_id"));
        return mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults().stream()
                // Le foto con data sconosciuta non hanno "giorno": $substr dà "" (non null).
                // Non hanno un mese: restano fuori dalla colonna, senza farla fallire.
                .filter(d -> d.get("_id") instanceof String mese && MESE.matcher(mese).matches())
                .map(d -> {
                    String[] am = d.getString("_id").split("-");
                    return new VoceMese(Integer.parseInt(am[0]), Integer.parseInt(am[1]),
                            ((Number) d.get("conteggio")).longValue());
                })
                .toList();
    }

    /**
     * Le foto con posizione GPS, con i filtri della timeline; e quelle con una
     * città scelta a mano, alle sue coordinate (approssimativa), se non hanno
     * il GPS o il GPS è più lontano dalla città della distanza dei luoghi
     * (GPS sbagliato). Col GPS vicino resta il GPS: è più preciso.
     */
    public List<PuntoMappa> mappa(FiltroFoto filtro) {
        Query query = new Query(new Criteria().andOperator(
                criteriConNazioni(filtro),
                new Criteria().orOperator(
                        Criteria.where("latitudine").ne(null).and("longitudine").ne(null),
                        Criteria.where("latitudineLuogo").ne(null).and("longitudineLuogo").ne(null))));
        query.fields().include("latitudine", "longitudine", "giorno", "titolo", "video", "luogo", "latitudineLuogo",
                "longitudineLuogo");
        return mongo.find(query, Foto.class).stream()
                .map(f -> {
                    boolean approssimativa = f.getLatitudineLuogo() != null && f.getLongitudineLuogo() != null
                            && (f.getLatitudine() == null || f.getLongitudine() == null
                                    || GeocodificaInversa.distanzaKm(f.getLatitudine(), f.getLongitudine(),
                                            f.getLatitudineLuogo(), f.getLongitudineLuogo()) > luoghi.distanzaMassimaKm());
                    return new PuntoMappa(f.getId(), approssimativa ? f.getLatitudineLuogo() : f.getLatitudine(),
                            approssimativa ? f.getLongitudineLuogo() : f.getLongitudine(), f.getGiorno(), f.getTitolo(),
                            f.isVideo(), f.getLuogo(), approssimativa);
                })
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
                .addCriteria(nonNelCestino())
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
                Aggregation.match(nonNelCestino()),
                Aggregation.unwind("tag"),
                Aggregation.group("tag"),
                Aggregation.sort(Sort.Direction.ASC, "_id"));
        return mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults().stream()
                .map(d -> d.getString("_id"))
                .toList();
    }

    public List<String> album() {
        return mongo.findDistinct(new Query(nonNelCestino()), "album", Foto.class, String.class).stream()
                .filter(a -> a != null && !a.isBlank())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    /** Per il filtro "Caricate da": chi ha portato foto e quante, dal più attivo. Senza le foto di nessuno. */
    public List<Caricatore> caricateDa() {
        var aggregazione = Aggregation.newAggregation(
                Aggregation.match(Criteria.where("caricataDa").ne(null).and(CESTINO).is(null)),
                Aggregation.group("caricataDa").count().as("conteggio"),
                Aggregation.sort(Sort.by(Sort.Order.desc("conteggio"), Sort.Order.asc("_id"))));
        return mongo.aggregate(aggregazione, Foto.class, Document.class).getMappedResults().stream()
                .map(d -> new Caricatore(d.getString("_id"), ((Number) d.get("conteggio")).longValue()))
                .toList();
    }

    public record Caricatore(String username, long conteggio) {
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

    /**
     * Il file per {@code /file}.
     *
     * @param definitivo true se a quell'indirizzo non cambierà (si può tenere in
     *                   cache per sempre); false per un video che aspetta la
     *                   versione compatibile
     */
    public record FileServito(Path file, String contentType, String nome, boolean definitivo) {
    }

    /**
     * Quello che va al browser per {@code /file}: per i video la versione
     * compatibile, se c'è; l'originale per il resto, o se lo si chiede
     * ({@code originale}, per esempio per scaricarlo).
     */
    public FileServito fileDaServire(Foto foto, boolean originale) {
        if (foto.isVideo() && !originale && foto.getConversione() == StatoConversione.FATTA) {
            Path compatibile = archivio.compatibile(foto.getId());
            if (Files.exists(compatibile)) {
                String nome = foto.getNomeOriginale();
                int punto = nome.lastIndexOf('.');
                return new FileServito(compatibile, "video/mp4", (punto < 0 ? nome : nome.substring(0, punto)) + ".mp4", true);
            }
        }
        boolean definitivo = originale || !foto.isVideo() || Boolean.TRUE.equals(foto.getCompatibile());
        return new FileServito(fileOriginale(foto), foto.getContentType(), foto.getNomeOriginale(), definitivo);
    }

    public String contentTypeVista(Foto foto) {
        return genere(estensione(foto.getPercorso())) == Genere.HEIC ? "image/jpeg" : foto.getContentType();
    }

    private void rifaiAnteprime(Foto foto) {
        try {
            unaAllaVolta(() -> {
                faiDiNuovoAnteprime(foto);
                return null;
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void faiDiNuovoAnteprime(Foto foto) {
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

    /** Una foto nel cestino non si modifica (vuoto, cioè 404): prima si ripristina. */
    public Optional<Foto> modifica(String id, Modifica m) {
        return repository.findById(id).filter(foto -> !foto.nelCestino()).map(foto -> {
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

    /**
     * IMPOSTA_LUOGO: {@code valore} è la chiave di un luogo di GeoNames
     * ("citta:2643123", vedi {@link LuoghiService#imposta}), vuoto per "nessun
     * luogo"; LUOGO_DA_GPS torna al luogo calcolato dal GPS.
     */
    public enum Operazione {
        ELIMINA, AGGIUNGI_TAG, TOGLI_TAG, IMPOSTA_ALBUM, PREFERITA, NON_PREFERITA, IMPOSTA_LUOGO, LUOGO_DA_GPS
    }

    /** Applica la stessa operazione a più foto; restituisce quante ne ha toccate. */
    public long operazioneMultipla(List<String> ids, Operazione operazione, String valore) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // Tag, album, preferite e luoghi non toccano le foto nel cestino.
        Query query = Query.query(Criteria.where("_id").in(ids).and(CESTINO).is(null));
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
            case IMPOSTA_LUOGO -> luoghiManuali.imposta(ids, valore);
            case LUOGO_DA_GPS -> luoghiManuali.usaGps(ids);
        };
    }

    // ---------------------------------------------------------------- cestino

    /** Il campo di {@link Foto#getEliminataIl()}: null (o assente) per le foto nell'archivio. */
    public static final String CESTINO = "eliminataIl";

    /**
     * Le foto che non sono nel cestino: ogni ricerca, conteggio e
     * aggregazione delle foto parte da qui (o da {@link #criteri(FiltroFoto)},
     * che lo comprende).
     */
    public static Criteria nonNelCestino() {
        return Criteria.where(CESTINO).is(null);
    }

    /** Le foto nel cestino. */
    public static Criteria nelCestino() {
        return Criteria.where(CESTINO).ne(null);
    }

    /**
     * "Elimina" (visore, selezione, quasi uguali): la foto va nel cestino.
     * Solo la scheda cambia ({@code eliminataIl}); originale, miniature e
     * video compatibile restano dove sono finché non la si elimina per
     * sempre ({@link #eliminaPerSempre}), a mano o dopo i giorni del cestino.
     * Col cloud smontato no, come prima.
     *
     * @return false se non c'è o è già nel cestino
     */
    public boolean elimina(String id) {
        archivio.verificaDisponibile();
        boolean messa = mongo.updateFirst(Query.query(Criteria.where("_id").is(id).and(CESTINO).is(null)),
                new Update().set(CESTINO, clock.instant()), Foto.class).getModifiedCount() > 0;
        if (messa) {
            eventi.publishEvent(new FotoNelCestino(id));
        }
        return messa;
    }

    /**
     * Rimette una foto del cestino nell'archivio, com'era (album, tag, data):
     * il file non si è mai mosso. Col cloud smontato no, come l'eliminazione.
     *
     * @return false se non c'è o non è nel cestino
     */
    public boolean ripristina(String id) {
        archivio.verificaDisponibile();
        Foto foto = mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and(CESTINO).ne(null)),
                new Update().unset(CESTINO), FindAndModifyOptions.options().returnNew(true), Foto.class);
        if (foto == null) {
            return false;
        }
        // Un video che aspettava la versione compatibile: nel cestino la coda lo saltava.
        segnalaConversione(foto);
        eventi.publishEvent(new FotoRipristinata(id));
        return true;
    }

    /**
     * Elimina per sempre una foto del cestino: originale, miniature, video
     * compatibile, poi la scheda. Prima i file: se non si riesce a
     * cancellarli la scheda resta nel cestino (e la pulizia ci riprova),
     * invece di lasciare nell'archivio un file senza scheda che "Indicizza
     * archivio" riprenderebbe come nuovo. Col cloud smontato no.
     *
     * @return false se non c'è o non è nel cestino (una foto dell'archivio
     *         non si elimina per sempre senza passare dal cestino)
     */
    public boolean eliminaPerSempre(String id) {
        archivio.verificaDisponibile();
        Query nelCestino = Query.query(Criteria.where("_id").is(id).and(CESTINO).ne(null));
        Foto foto = mongo.findOne(nelCestino, Foto.class);
        if (foto == null) {
            return false;
        }
        archivio.elimina(foto.getId(), foto.getPercorso());
        mongo.remove(nelCestino, Foto.class);
        eventi.publishEvent(new FotoEliminata(foto.getId()));
        return true;
    }

    // ---------------------------------------------------------------- utilità

    /** Come {@link #criteri(FiltroFoto)}, e la ricerca libera trova le nazioni anche per gli altri nomi ("Olanda"). */
    private Criteria criteriConNazioni(FiltroFoto f) {
        return criteri(f, f.q() == null || f.q().isBlank() ? Set.of() : luoghi.nazioniPerNome(f.q()));
    }

    /**
     * I filtri della timeline come criteri di Mongo (li usa anche la ricerca
     * per contenuto), sempre senza le foto nel cestino.
     */
    public static Criteria criteri(FiltroFoto f) {
        return criteri(f, Set.of());
    }

    /**
     * @param nazioni codici delle nazioni che la ricerca libera {@code q}
     *                trova per nome (anche con un altro nome: "Olanda" per NL)
     */
    public static Criteria criteri(FiltroFoto f, Collection<String> nazioni) {
        List<Criteria> e = new ArrayList<>();
        e.add(nonNelCestino());
        if (f.q() != null && !f.q().isBlank()) {
            Pattern p = Pattern.compile(Pattern.quote(f.q().trim()), Pattern.CASE_INSENSITIVE);
            List<Criteria> o = new ArrayList<>(List.of(
                    Criteria.where("titolo").regex(p),
                    Criteria.where("descrizione").regex(p),
                    Criteria.where("nomeOriginale").regex(p),
                    Criteria.where("album").regex(p),
                    Criteria.where("fotocamera").regex(p),
                    Criteria.where("tag").regex(p),
                    Criteria.where("luogo").regex(p),
                    Criteria.where("provincia").regex(p),
                    Criteria.where("regione").regex(p),
                    Criteria.where("nazione").regex(p)));
            if (!nazioni.isEmpty()) {
                o.add(Criteria.where("codiceNazione").in(nazioni));
            }
            e.add(new Criteria().orOperator(o));
        }
        if (f.tag() != null && !f.tag().isBlank()) {
            e.add(Criteria.where("tag").is(f.tag().trim().toLowerCase(Locale.ROOT)));
        }
        if (f.album() != null && !f.album().isBlank()) {
            e.add(Criteria.where("album").is(f.album().trim()));
        }
        // Il luogo scelto dall'elenco (/api/luoghi): nazione, poi regione, provincia e luogo dentro di lei.
        if (f.nazione() != null && !f.nazione().isBlank()) {
            e.add(Criteria.where("codiceNazione").is(f.nazione().trim().toUpperCase(Locale.ROOT)));
        }
        if (f.regione() != null && !f.regione().isBlank()) {
            e.add(Criteria.where("regione").is(f.regione().trim()));
        }
        if (f.provincia() != null && !f.provincia().isBlank()) {
            e.add(Criteria.where("provincia").is(f.provincia().trim()));
        }
        if (f.luogo() != null && !f.luogo().isBlank()) {
            e.add(Criteria.where("luogo").is(f.luogo().trim()));
        }
        if (Boolean.TRUE.equals(f.preferite())) {
            e.add(Criteria.where("preferita").is(true));
        }
        if (f.caricataDa() != null && !f.caricataDa().isBlank()) {
            e.add(Criteria.where("caricataDa").is(f.caricataDa().trim()));
        }
        // Un evento (pacchetto eventi): le foto che il calcolo o una modifica a mano gli hanno dato.
        if (f.evento() != null && !f.evento().isBlank()) {
            e.add(Criteria.where("evento").is(f.evento().trim()));
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
        return new Criteria().andOperator(e);
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

    /** True se il nome è di un formato che l'archivio accetta (foto, HEIC, video). */
    public static boolean eMedia(String nome) {
        return TIPI.containsKey(estensione(nome));
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

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Passa il resto di {@code in} a {@code sha} e dice quanti byte erano. Un
     * video dal cloud sono minuti: fra un pezzo e l'altro guarda se il lavoro
     * è stato annullato ({@link Annullata}; il file non è stato toccato).
     */
    private static long aggiungi(MessageDigest sha, InputStream in, ControlloFile controllo) throws IOException {
        byte[] pezzo = new byte[64 * 1024];
        long letti = 0;
        for (int n; (n = in.read(pezzo)) >= 0; ) {
            if (controllo.fermato()) {
                throw new Annullata();
            }
            sha.update(pezzo, 0, n);
            letti += n;
        }
        return letti;
    }
}
