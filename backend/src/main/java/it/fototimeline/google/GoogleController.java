package it.fototimeline.google;

import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import jakarta.servlet.http.HttpSession;
import it.fototimeline.google.ClientGoogle.GoogleNonRiesce;
import it.fototimeline.google.SceltaGoogleFoto.Collegamento;
import it.fototimeline.google.SceltaGoogleFoto.NonConfigurato;
import it.fototimeline.google.SceltaGoogleFoto.StatoOAuth;
import it.fototimeline.google.SceltaGoogleFoto.StatoScelta;
import it.fototimeline.sicurezza.UtenteCorrente;
import it.fototimeline.sicurezza.UtenteCorrente.Chi;

/**
 * "Scegli da Google Foto": collegamento OAuth, scelta col Picker, scollegamento.
 * Tutto per chi è entrato (ConfigurazioneSicurezza); ognuno usa il suo
 * account Google. Client ID e secret non escono mai di qui.
 */
@RestController
@RequestMapping("/api/google")
public class GoogleController {

    private static final Logger log = LoggerFactory.getLogger(GoogleController.class);
    /** Dove sta il {@code state} del consenso, nella sessione di chi l'ha chiesto. */
    static final String STATO = GoogleController.class.getName() + ".stato";
    /** Sul PC, senza login: tutti sono la stessa persona. */
    static final String LOCALE = "locale";

    private final SceltaGoogleFoto scelta;
    private final UtenteCorrente corrente;
    private final GoogleProperties properties;

    public GoogleController(SceltaGoogleFoto scelta, UtenteCorrente corrente, GoogleProperties properties) {
        this.scelta = scelta;
        this.corrente = corrente;
        this.properties = properties;
    }

    /** C'è la funzione? Sono collegato? La mia scelta in corso. */
    @GetMapping
    public Collegamento stato() {
        return scelta.collegamento(utente());
    }

    /** Il consenso di Google: ci si arriva navigando (non con una XHR), e Google torna al callback. */
    @GetMapping("/collega")
    public ResponseEntity<Void> collega(HttpSession sessione) {
        StatoOAuth stato = scelta.nuovoStato(utente());
        String url = scelta.urlConsenso(stato, redirectUri());
        sessione.setAttribute(STATO, stato);
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(url)).build();
    }

    /** Google torna qui: si controlla {@code state}, si scambia il codice, si torna all'app. */
    @GetMapping("/callback")
    public ResponseEntity<Void> callback(@RequestParam(required = false) String code,
            @RequestParam(required = false) String state, @RequestParam(required = false) String error,
            HttpSession sessione) {
        if (!scelta.configurato()) {
            throw new NonConfigurato();
        }
        StatoOAuth atteso = sessione.getAttribute(STATO) instanceof StatoOAuth s ? s : null;
        sessione.removeAttribute(STATO);
        String utente = utente();
        if (!scelta.statoValido(atteso, state, utente)) {
            log.warn("Google: callback con state non valido per {}", utente);
            return torna("errore");
        }
        if (error != null || code == null || code.isBlank()) {
            return torna("negato");
        }
        try {
            scelta.collega(utente, code, redirectUri());
            return torna("collegato");
        } catch (GoogleNonRiesce | IllegalStateException e) {
            log.warn("Google: collegamento di {} non riuscito: {}", utente, e.getMessage());
            return torna("errore");
        }
    }

    @PostMapping("/scollega")
    public ResponseEntity<Void> scollega() {
        scelta.scollega(utente());
        return ResponseEntity.noContent().build();
    }

    /** Una nuova scelta: l'app apre {@code pickerUri} e segue lo stato. */
    @PostMapping("/scelta")
    public ResponseEntity<StatoScelta> nuova() {
        return ResponseEntity.status(HttpStatus.CREATED).body(scelta.nuova(utente()));
    }

    @GetMapping("/scelta")
    public ResponseEntity<StatoScelta> corrente() {
        return scelta.scelta(utente()).map(ResponseEntity::ok).orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/scelta/annulla")
    public ResponseEntity<Void> annulla() {
        scelta.annulla(utente());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/scelta/chiudi")
    public ResponseEntity<Void> chiudi() {
        scelta.dimentica(utente());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NonConfigurato.class)
    public ProblemDetail nonConfigurato(NonConfigurato e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(GoogleNonRiesce.class)
    public ProblemDetail google(GoogleNonRiesce e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private String utente() {
        Chi chi = corrente.chi();
        return chi.username() != null ? chi.username() : LOCALE;
    }

    /** Quello scritto nelle credenziali di Google: fisso se impostato, altrimenti dall'indirizzo dell'app. */
    private String redirectUri() {
        if (properties.redirectUri() != null) {
            return properties.redirectUri();
        }
        return ServletUriComponentsBuilder.fromCurrentContextPath().path("/api/google/callback").build().toUriString();
    }

    private static ResponseEntity<Void> torna(String esito) {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/?google=" + esito)).build();
    }
}
