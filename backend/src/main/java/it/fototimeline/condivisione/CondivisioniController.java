package it.fototimeline.condivisione;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.condivisione.CondivisioniService.NuovaCondivisione;
import it.fototimeline.condivisione.CondivisioniService.VoceCondivisione;
import it.fototimeline.sicurezza.LoginProperties;

/**
 * Creare, vedere e revocare i link (dietro login, come il resto di /api).
 * Ogni utente collegato può condividere: vede già tutte le foto, e il link è
 * a nome suo. Ognuno vede e revoca i suoi; l'amministratore tutti.
 */
@RestController
@RequestMapping("/api/condivisioni")
public class CondivisioniController {

    /** Con il login spento (il PC) c'è un solo utente, ed è amministratore. */
    static final String UTENTE_LOCALE = "locale";

    private final CondivisioniService service;
    private final LoginProperties login;

    public CondivisioniController(CondivisioniService service, LoginProperties login) {
        this.service = service;
        this.login = login;
    }

    @PostMapping
    public ResponseEntity<VoceCondivisione> crea(@RequestBody NuovaCondivisione nuova, Authentication utente) {
        Condivisione c = service.crea(nuova, nome(utente));
        return ResponseEntity.status(HttpStatus.CREATED).body(CondivisioniService.voce(c, Instant.now()));
    }

    @GetMapping
    public List<VoceCondivisione> elenco(Authentication utente) {
        return service.elenco(nome(utente), admin(utente));
    }

    /** Revoca (il documento resta, con le visite): 404 anche se c'è ma è di un altro. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> revoca(@PathVariable String id, Authentication utente) {
        return service.revoca(id, nome(utente), admin(utente))
                ? ResponseEntity.noContent().build()
                : ResponseEntity.notFound().build();
    }

    private String nome(Authentication utente) {
        return !login.attivo() || utente == null ? UTENTE_LOCALE : utente.getName();
    }

    private boolean admin(Authentication utente) {
        return !login.attivo() || utente != null && utente.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + login.ruoloAdmin()));
    }
}
