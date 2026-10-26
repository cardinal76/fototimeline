package it.fototimeline.sicurezza;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * La pagina di accesso, senza login ({@link PaginaDiAccesso}): è la stessa app
 * Angular, che vedendo {@code /accesso} mostra solo nome, logo e "Accedi"
 * (main.ts) e non chiama nessuna API privata. Come {@code /c/{token}}:
 * {@code index.html} lo serve un controller, perché la radice chiede il login.
 */
@RestController
public class AccessoController {

    @GetMapping(PaginaDiAccesso.PAGINA)
    public ResponseEntity<Resource> pagina() {
        Resource indice = new ClassPathResource("static/index.html");
        if (!indice.exists()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .header("X-Robots-Tag", "noindex, nofollow")
                .contentType(MediaType.TEXT_HTML)
                .body(indice);
    }
}
