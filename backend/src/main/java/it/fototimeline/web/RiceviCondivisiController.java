package it.fototimeline.web;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.sicurezza.ConfigurazioneSicurezza;

/**
 * L'action dello {@code share_target} del manifest. La condivisione dal telefono
 * la prende il service worker; quella che arriva qui (senza service worker) di
 * solito si ferma prima, al CSRF (ConfigurazioneSicurezza.condivisiSenzaApp).
 * In ogni caso qui i file non si leggono: si torna all'app, che dice di riprovare.
 * I caricamenti passano solo da POST /api/foto.
 */
@RestController
public class RiceviCondivisiController {

    @PostMapping(ConfigurazioneSicurezza.RICEVI_CONDIVISI)
    public ResponseEntity<Void> ricevi() {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(ConfigurazioneSicurezza.CONDIVISI_SENZA_APP))
                .build();
    }
}
