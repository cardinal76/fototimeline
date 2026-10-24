package it.fototimeline.telefono;

import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import it.fototimeline.sicurezza.UtenteCorrente;
import it.fototimeline.sicurezza.UtenteCorrente.Chi;
import it.fototimeline.telefono.SincronizzazioneTelefono.Elenco;
import it.fototimeline.telefono.SincronizzazioneTelefono.Modifica;
import it.fototimeline.telefono.SincronizzazioneTelefono.Stato;

/**
 * I telefoni della famiglia. Gli amministratori vedono e cambiano tutto; gli
 * altri vedono solo i telefoni di cui sono proprietari e possono lanciarne un
 * giro, ma non cambiarli (la cartella su pCloud la decide l'admin).
 */
@RestController
@RequestMapping("/api/telefoni")
public class TelefoniController {

    private final SincronizzazioneTelefono sincronizzazione;
    private final VideoGrandi videoGrandi;
    private final UtenteCorrente corrente;

    public TelefoniController(SincronizzazioneTelefono sincronizzazione, VideoGrandi videoGrandi,
            UtenteCorrente corrente) {
        this.sincronizzazione = sincronizzazione;
        this.videoGrandi = videoGrandi;
        this.corrente = corrente;
    }

    @GetMapping
    public Elenco elenco() {
        Chi chi = corrente.chi();
        return sincronizzazione.elenco(s -> chi.puo(s.proprietario()));
    }

    @PostMapping
    public ResponseEntity<Stato> crea(@RequestBody Modifica modifica) {
        soloAdmin();
        return ResponseEntity.status(HttpStatus.CREATED).body(sincronizzazione.crea(modifica));
    }

    @PutMapping("/{id}")
    public Stato salva(@PathVariable String id, @RequestBody Modifica modifica) {
        soloAdmin();
        try {
            return sincronizzazione.salva(id, modifica);
        } catch (NoSuchElementException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    /** Il registro delle copie resta: rimesso con la stessa cartella, il telefono non ricopia niente. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> elimina(@PathVariable String id) {
        soloAdmin();
        return sincronizzazione.elimina(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /** "Sincronizza ora": 202 e il giro va in coda; 409 se questo telefono è già in coda o qui non c'è rclone. */
    @PostMapping("/{id}/sincronizza")
    public ResponseEntity<Stato> sincronizza(@PathVariable String id) {
        Chi chi = corrente.chi();
        var sorgente = sincronizzazione.sorgente(id)
                .filter(s -> chi.puo(s.proprietario()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nessun telefono " + id));
        return ResponseEntity.accepted().body(sincronizzazione.avvia(sorgente.id()));
    }

    /**
     * Ferma la ricompressione di un video troppo grande (in coda o in corso);
     * l'originale resta su pCloud com'era. Solo admin; 404 se non c'è, 409
     * se non c'era niente da fermare.
     */
    @PostMapping("/video-grandi/{id}/annulla")
    public ResponseEntity<Void> annullaVideoGrande(@PathVariable String id) {
        soloAdmin();
        trovaVideoGrande(id);
        if (!videoGrandi.annulla(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Questo video non è in coda né in corso");
        }
        return ResponseEntity.accepted().build();
    }

    /** "Riprova": un video troppo grande in errore, annullato o rimandato torna in coda. Solo admin. */
    @PostMapping("/video-grandi/{id}/riprova")
    public ResponseEntity<Void> riprovaVideoGrande(@PathVariable String id) {
        soloAdmin();
        trovaVideoGrande(id);
        if (!videoGrandi.riprova(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Questo video non è in errore, annullato o rimandato");
        }
        return ResponseEntity.accepted().build();
    }

    private void trovaVideoGrande(String id) {
        if (videoGrandi.video(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nessun video " + id);
        }
    }

    private void soloAdmin() {
        if (!corrente.chi().admin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Solo un amministratore cambia i telefoni");
        }
    }
}
