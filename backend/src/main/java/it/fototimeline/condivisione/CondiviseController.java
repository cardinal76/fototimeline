package it.fototimeline.condivisione;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.condivisione.CondivisioniService.Galleria;
import it.fototimeline.condivisione.CondivisioniService.LinkNonValido;
import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Il lato pubblico dei link: senza login (ConfigurazioneSicurezza apre solo
 * {@code GET /c/*} e {@code GET /api/condivise/**}). Ogni richiesta passa da
 * {@link CondivisioniService#valida} e, per una foto, da
 * {@link CondivisioniService#foto(Condivisione, String)}: una foto che non è
 * nel link risponde 404 come un link sconosciuto.
 */
@RestController
public class CondiviseController {

    /** Le immagini restano un'ora nella cache del browser: un link revocato smette di funzionare presto. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).cachePrivate();

    private final CondivisioniService service;
    private final ArchivioFile archivio;
    private final LimiteRichieste limite;

    public CondiviseController(CondivisioniService service, ArchivioFile archivio, LimiteRichieste limite) {
        this.service = service;
        this.archivio = archivio;
        this.limite = limite;
    }

    /**
     * La pagina: è la stessa app Angular, che vedendo {@code /c/} mostra solo
     * la galleria (main.ts). Il token lo controlla poi la chiamata ai dati.
     */
    @GetMapping("/c/{token}")
    public ResponseEntity<Resource> pagina(@PathVariable String token) {
        Resource indice = new ClassPathResource("static/index.html");
        if (!indice.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("Referrer-Policy", "no-referrer")
                .header("X-Robots-Tag", "noindex, nofollow")
                .contentType(MediaType.TEXT_HTML)
                .body(indice);
    }

    @GetMapping("/api/condivise/{token}")
    public ResponseEntity<Galleria> galleria(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("X-Robots-Tag", "noindex, nofollow")
                .body(service.apri(token));
    }

    @GetMapping("/api/condivise/{token}/foto/{id}/miniatura")
    public ResponseEntity<Resource> miniatura(@PathVariable String token, @PathVariable String id) {
        Foto foto = service.foto(service.valida(token), id);
        return immagine(service.miniatura(foto), MediaType.IMAGE_JPEG);
    }

    /** La foto ridotta e senza metadati; per i video c'è {@code /video}. */
    @GetMapping("/api/condivise/{token}/foto/{id}/vista")
    public ResponseEntity<Resource> vista(@PathVariable String token, @PathVariable String id) {
        Foto foto = service.foto(service.valida(token), id);
        if (foto.isVideo()) {
            throw new LinkNonValido(HttpStatus.NOT_FOUND, "Link non valido.");
        }
        return immagine(service.vista(foto), MediaType.IMAGE_JPEG);
    }

    /**
     * Il video da guardare: l'originale, a pezzi con Range. Si vede anche senza
     * download permesso (altrimenti non si guarda).
     */
    @GetMapping("/api/condivise/{token}/foto/{id}/video")
    public ResponseEntity<Resource> video(@PathVariable String token, @PathVariable String id) {
        Foto foto = service.foto(service.valida(token), id);
        if (!foto.isVideo()) {
            throw new LinkNonValido(HttpStatus.NOT_FOUND, "Link non valido.");
        }
        return immagine(service.originale(foto), MediaType.parseMediaType(foto.getContentType()));
    }

    /** L'originale da scaricare, solo se il link lo permette. */
    @GetMapping("/api/condivise/{token}/foto/{id}/originale")
    public ResponseEntity<Resource> originale(@PathVariable String token, @PathVariable String id) {
        Condivisione c = service.valida(token);
        Foto foto = service.foto(c, id);
        if (!c.isDownload()) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        String nome = CondivisioniService.nomeDownload(foto, new HashSet<>());
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .contentType(MediaType.parseMediaType(foto.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(nome, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(service.originale(foto)));
    }

    /**
     * Tutti gli originali in uno zip, scritto direttamente sulla risposta
     * (niente async: un timeout lo taglierebbe a metà).
     */
    @GetMapping("/api/condivise/{token}/zip")
    public void zip(@PathVariable String token, HttpServletResponse risposta) throws IOException {
        Condivisione c = service.valida(token);
        if (!c.isDownload()) {
            risposta.setStatus(HttpStatus.FORBIDDEN.value());
            return;
        }
        // Prima di cominciare: a metà non si potrebbe più rispondere 503.
        archivio.verificaDisponibile();
        List<Foto> foto = service.foto(c);
        risposta.setContentType("application/zip");
        risposta.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        risposta.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(CondivisioniService.nomeZip(c.getTitolo()), StandardCharsets.UTF_8).build().toString());
        service.scriviZip(foto, risposta.getOutputStream());
    }

    private static ResponseEntity<Resource> immagine(Path file, MediaType tipo) {
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .contentType(tipo)
                .body(new FileSystemResource(file));
    }

    @ExceptionHandler(LinkNonValido.class)
    public ProblemDetail linkNonValido(LinkNonValido e, HttpServletRequest richiesta) {
        if (e.sconosciuto()) {
            limite.tokenSbagliato(richiesta.getRemoteAddr());
        }
        return ProblemDetail.forStatusAndDetail(e.stato(), e.getMessage());
    }

    /** Niente dettagli (il messaggio di ArchivioFile contiene il percorso). */
    @ExceptionHandler({ IllegalArgumentException.class, java.io.UncheckedIOException.class })
    public ProblemDetail errore() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Questa foto non si riesce a mostrare.");
    }

    /** Il messaggio per chi ha il link: non c'è un amministratore da chiamare. */
    @ExceptionHandler(ArchivioNonDisponibile.class)
    public ProblemDetail archivioSmontato() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "Gli originali in questo momento non sono raggiungibili: si vedono solo le miniature. Riprova più tardi.");
    }
}
