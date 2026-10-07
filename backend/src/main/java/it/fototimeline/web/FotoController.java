package it.fototimeline.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ConversioneVideo;
import it.fototimeline.service.ConversioneVideo.StatoConversioni;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.FotoService.Operazione;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.FileScartato;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati.Cartella;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.PaginaFoto;
import it.fototimeline.service.Risultati.PuntoMappa;
import it.fototimeline.service.Risultati.Ricordo;
import it.fototimeline.service.Risultati.VoceMese;
import it.fototimeline.sicurezza.RegistroUtenti;
import it.fototimeline.sicurezza.UtenteCorrente;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class FotoController {

    private final FotoService service;
    private final ImportazioneCartelle importazione;
    private final LavoriImportazione lavori;
    private final UtenteCorrente corrente;
    private final RegistroUtenti utenti;
    private final ConversioneVideo conversione;

    public FotoController(FotoService service, ImportazioneCartelle importazione, LavoriImportazione lavori,
            UtenteCorrente corrente, RegistroUtenti utenti, ConversioneVideo conversione) {
        this.service = service;
        this.importazione = importazione;
        this.lavori = lavori;
        this.corrente = corrente;
        this.utenti = utenti;
        this.conversione = conversione;
    }

    @GetMapping("/foto")
    public PaginaFoto cerca(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate al,
            @RequestParam(required = false) String nazione,
            @RequestParam(required = false) String regione,
            @RequestParam(required = false) String luogo,
            @RequestParam(required = false) String caricataDa,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "60") int dimensione) {
        return service.cerca(new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa),
                Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }

    @GetMapping("/timeline")
    public List<VoceMese> timeline(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite,
            @RequestParam(required = false) String nazione,
            @RequestParam(required = false) String regione,
            @RequestParam(required = false) String luogo,
            @RequestParam(required = false) String caricataDa) {
        return service.timeline(new FiltroFoto(q, tag, album, preferite, null, null, nazione, regione, luogo, caricataDa));
    }

    @GetMapping("/mappa")
    public List<PuntoMappa> mappa(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite,
            @RequestParam(required = false) String nazione,
            @RequestParam(required = false) String regione,
            @RequestParam(required = false) String luogo,
            @RequestParam(required = false) String caricataDa) {
        return service.mappa(new FiltroFoto(q, tag, album, preferite, null, null, nazione, regione, luogo, caricataDa));
    }

    /** "Accadde oggi": stesso giorno negli anni passati; {@code data} per provare altri giorni. */
    @GetMapping("/ricordi")
    public List<Ricordo> ricordi(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate data) {
        return service.ricordi(data != null ? data : LocalDate.now(), 12);
    }

    @GetMapping("/foto/{id}")
    public ResponseEntity<Foto> foto(@PathVariable String id) {
        return ResponseEntity.of(service.trova(id));
    }

    @PostMapping(path = "/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Caricamento> carica(@RequestParam("file") List<MultipartFile> file,
            @RequestParam(required = false) String album) throws IOException {
        var esiti = new java.util.ArrayList<Caricamento>();
        String chi = corrente.chi().username();
        for (MultipartFile f : file) {
            // Su file e non in memoria: un video può pesare gigabyte.
            Path temporaneo = Files.createTempFile("fototimeline-caricamento-", "");
            try {
                f.transferTo(temporaneo);
                esiti.add(service.importa(f.getOriginalFilename(), temporaneo, null, album, chi));
            } finally {
                Files.deleteIfExists(temporaneo);
            }
        }
        return esiti;
    }

    public record RichiestaImportazione(@NotBlank String cartella, boolean albumDaCartella, boolean sposta) {
    }

    /** Avvia l'importazione in sottofondo; lo stato si segue con GET /api/importazioni/corrente. */
    @PostMapping("/importa")
    public ResponseEntity<StatoLavoro> importa(@Valid @RequestBody RichiestaImportazione r) {
        Path cartella = importazione.consentita(Path.of(r.cartella().trim()));
        return ResponseEntity.accepted()
                .body(lavori.avvia(cartella, r.albumDaCartella(), r.sposta(), LavoriImportazione.Origine.MANUALE,
                        corrente.chi().username()));
    }

    /**
     * "Indicizza archivio" (solo admin, ConfigurazioneSicurezza): in sottofondo
     * come un'importazione, con lo stesso stato e la stessa barra.
     */
    @PostMapping("/archivio/indicizza")
    public ResponseEntity<StatoLavoro> indicizza() {
        return ResponseEntity.accepted().body(lavori.avviaIndicizzazione());
    }

    @GetMapping("/importazioni/corrente")
    public ResponseEntity<StatoLavoro> importazioneCorrente() {
        return lavori.corrente().map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/importazioni/annulla")
    public ResponseEntity<Void> annullaImportazione() {
        return lavori.annulla() ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
    }

    /** I file della cartella automatica falliti per colpa loro, che non si rileggono finché non cambiano. */
    @GetMapping("/importazioni/scartati")
    public List<FileScartato> scartati() {
        return lavori.scartati();
    }

    /** "Riprova" (solo admin): al prossimo giro si rileggono anche quelli. */
    @PostMapping("/importazioni/scartati/riprova")
    public Map<String, Long> riprovaScartati() {
        return Map.of("dimenticati", lavori.riprovaScartati());
    }

    /** Sottocartelle per il navigatore di "Importa cartella"; senza percorso parte dalla radice consentita. */
    @GetMapping("/cartelle")
    public Cartella cartelle(@RequestParam(required = false) String percorso) throws IOException {
        return importazione.elenca(percorso == null || percorso.isBlank() ? null : Path.of(percorso));
    }

    @PutMapping("/foto/{id}")
    public ResponseEntity<Foto> modifica(@PathVariable String id, @RequestBody Modifica modifica) {
        return ResponseEntity.of(service.modifica(id, modifica));
    }

    @DeleteMapping("/foto/{id}")
    public ResponseEntity<Void> elimina(@PathVariable String id) {
        return service.elimina(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    public record RichiestaMultipla(@NotNull List<String> ids, @NotNull Operazione operazione, String valore) {
    }

    public record EsitoMultiplo(long modificate) {
    }

    @PostMapping("/foto/multiple")
    public EsitoMultiplo multipla(@Valid @RequestBody RichiestaMultipla r) {
        return new EsitoMultiplo(service.operazioneMultipla(r.ids(), r.operazione(), r.valore()));
    }

    @GetMapping("/tag")
    public List<String> tag() {
        return service.tag();
    }

    @GetMapping("/album")
    public List<String> album() {
        return service.album();
    }

    /** Una voce del filtro "Caricate da". */
    public record CaricateDa(String username, String nome, long conteggio) {
    }

    /** Chi ha portato foto e quante, col nome visto al login (o lo username, se non è mai entrato). */
    @GetMapping("/caricate-da")
    public List<CaricateDa> caricateDa() {
        var nomi = utenti.nomi();
        return service.caricateDa().stream()
                .map(c -> new CaricateDa(c.username(), nomi.getOrDefault(c.username(), c.username()), c.conteggio()))
                .toList();
    }

    @GetMapping("/foto/{id}/miniatura")
    public ResponseEntity<Resource> miniatura(@PathVariable String id) {
        return service.trova(id)
                .map(foto -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                        .contentType(MediaType.IMAGE_JPEG)
                        .<Resource>body(new FileSystemResource(service.fileMiniatura(foto))))
                .orElse(ResponseEntity.notFound().build());
    }

    /** Quello che il browser sa mostrare: per gli HEIC un JPEG, per il resto l'originale. */
    @GetMapping("/foto/{id}/vista")
    public ResponseEntity<Resource> vista(@PathVariable String id) {
        return service.trova(id)
                .map(foto -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                        .contentType(MediaType.parseMediaType(service.contentTypeVista(foto)))
                        .<Resource>body(new FileSystemResource(service.fileVista(foto))))
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Il file; con Range (i video nel browser) risponde a pezzi. Per i video
     * la versione compatibile (H.264 in MP4) se c'è; {@code originale=true} o
     * {@code scarica=true} danno sempre l'originale.
     */
    @GetMapping("/foto/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean scarica,
            @RequestParam(defaultValue = "false") boolean originale) {
        return service.trova(id)
                .map(foto -> {
                    var servito = service.fileDaServire(foto, originale || scarica);
                    var disposizione = (scarica ? ContentDisposition.attachment() : ContentDisposition.inline())
                            .filename(servito.nome(), StandardCharsets.UTF_8)
                            .build();
                    // Un video in attesa della versione compatibile: allo stesso indirizzo arriverà quella.
                    var cache = servito.definitivo()
                            ? CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable()
                            : CacheControl.noCache().cachePrivate();
                    return ResponseEntity.ok()
                            .cacheControl(cache)
                            .contentType(MediaType.parseMediaType(servito.contentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposizione.toString())
                            .<Resource>body(new FileSystemResource(servito.file()));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    /** La coda delle versioni compatibili dei video: quanti fatti, quanti da fare, quello in corso. */
    @GetMapping("/archivio/video")
    public StatoConversioni conversioni() {
        return conversione.stato();
    }

    /** "Converti video" (solo admin): mette in coda i video già in archivio che ne hanno bisogno. */
    @PostMapping("/archivio/video/converti")
    public ResponseEntity<StatoConversioni> convertiVideo() {
        return ResponseEntity.accepted().body(conversione.avvia());
    }

    @PostMapping("/archivio/video/annulla")
    public ResponseEntity<StatoConversioni> annullaConversioni() {
        conversione.annulla();
        return ResponseEntity.accepted().body(conversione.stato());
    }
}
