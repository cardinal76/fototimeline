package it.fototimeline.utenti;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.sicurezza.Ruolo;
import it.fototimeline.utenti.GestioneUtenti.Elenco;
import it.fototimeline.utenti.GestioneUtenti.NuovoUtente;
import it.fototimeline.utenti.GestioneUtenti.VoceUtente;

/**
 * La pagina Utenti: solo l'amministratore (ConfigurazioneSicurezza). Errori:
 * 400 dati non validi (anche la politica delle password di Keycloak), 404
 * utente che non c'è, 409 l'ultimo amministratore o la pagina non configurata,
 * 502 Keycloak che non risponde o non dà i permessi.
 */
@RestController
@RequestMapping("/api/gestione-utenti")
public class GestioneUtentiController {

    private final GestioneUtenti gestione;

    public GestioneUtentiController(GestioneUtenti gestione) {
        this.gestione = gestione;
    }

    @GetMapping
    public Elenco elenco() {
        return gestione.elenco();
    }

    @PostMapping
    public ResponseEntity<VoceUtente> crea(@RequestBody NuovoUtente nuovo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(gestione.crea(nuovo));
    }

    public record SceltaRuolo(Ruolo ruolo) {
    }

    @PutMapping("/{id}/ruolo")
    public VoceUtente ruolo(@PathVariable String id, @RequestBody SceltaRuolo scelta) {
        return gestione.cambiaRuolo(id, scelta == null ? null : scelta.ruolo());
    }

    public record SceltaAttivo(boolean attivo) {
    }

    @PutMapping("/{id}/attivo")
    public VoceUtente attivo(@PathVariable String id, @RequestBody SceltaAttivo scelta) {
        return gestione.attiva(id, scelta != null && scelta.attivo());
    }

    /** @param invito true = l'email di Keycloak per sceglierla, senza {@code password} */
    public record NuovaPassword(String password, boolean invito) {
    }

    @PostMapping("/{id}/password")
    public ResponseEntity<Void> password(@PathVariable String id, @RequestBody NuovaPassword nuova) {
        gestione.password(id, nuova == null ? null : nuova.password(), nuova != null && nuova.invito());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> elimina(@PathVariable String id) {
        gestione.elimina(id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail nonCe(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** I 400 e 409 di Keycloak (password troppo semplice, username o email già usati) restano tali. */
    @ExceptionHandler(KeycloakNonRiesce.class)
    public ProblemDetail keycloak(KeycloakNonRiesce e) {
        HttpStatus stato = switch (e.stato()) {
            case 400 -> HttpStatus.BAD_REQUEST;
            case 404 -> HttpStatus.NOT_FOUND;
            case 409 -> HttpStatus.CONFLICT;
            default -> HttpStatus.BAD_GATEWAY;
        };
        String messaggio = e.stato() == 409 ? "Esiste già un utente con questo username o questa email" : e.getMessage();
        return ProblemDetail.forStatusAndDetail(stato, messaggio);
    }
}
