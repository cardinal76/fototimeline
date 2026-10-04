package it.fototimeline.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

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
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.FotoService.Operazione;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Importazione;
import it.fototimeline.service.Risultati.PaginaFoto;
import it.fototimeline.service.Risultati.VoceMese;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class FotoController {

    private final FotoService service;

    public FotoController(FotoService service) {
        this.service = service;
    }

    @GetMapping("/foto")
    public PaginaFoto cerca(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate al,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "60") int dimensione) {
        return service.cerca(new FiltroFoto(q, tag, album, preferite, dal, al),
                Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }

    @GetMapping("/timeline")
    public List<VoceMese> timeline(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite) {
        return service.timeline(new FiltroFoto(q, tag, album, preferite, null, null));
    }

    @GetMapping("/foto/{id}")
    public ResponseEntity<Foto> foto(@PathVariable String id) {
        return ResponseEntity.of(service.trova(id));
    }

    @PostMapping(path = "/foto", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public List<Caricamento> carica(@RequestParam("file") List<MultipartFile> file,
            @RequestParam(required = false) String album) throws IOException {
        var esiti = new java.util.ArrayList<Caricamento>();
        for (MultipartFile f : file) {
            esiti.add(service.importa(f.getOriginalFilename(), f.getBytes(), null, album));
        }
        return esiti;
    }

    public record RichiestaImportazione(@NotBlank String cartella, boolean albumDaCartella) {
    }

    @PostMapping("/importa")
    public Importazione importa(@Valid @RequestBody RichiestaImportazione r) throws IOException {
        return service.importaCartella(Path.of(r.cartella().trim()), r.albumDaCartella());
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

    @GetMapping("/foto/{id}/miniatura")
    public ResponseEntity<Resource> miniatura(@PathVariable String id) {
        return service.trova(id)
                .map(foto -> ResponseEntity.ok()
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                        .contentType(MediaType.IMAGE_JPEG)
                        .<Resource>body(new FileSystemResource(service.fileMiniatura(foto))))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/foto/{id}/file")
    public ResponseEntity<Resource> file(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean scarica) {
        return service.trova(id)
                .map(foto -> {
                    var disposizione = (scarica ? ContentDisposition.attachment() : ContentDisposition.inline())
                            .filename(foto.getNomeOriginale(), StandardCharsets.UTF_8)
                            .build();
                    return ResponseEntity.ok()
                            .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                            .contentType(MediaType.parseMediaType(foto.getContentType()))
                            .header(HttpHeaders.CONTENT_DISPOSITION, disposizione.toString())
                            .<Resource>body(new FileSystemResource(service.fileOriginale(foto)));
                })
                .orElse(ResponseEntity.notFound().build());
    }
}
