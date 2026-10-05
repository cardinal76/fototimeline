package it.fototimeline.google;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.google.ImportazioneTakeout.Avanzamento;
import it.fototimeline.google.ImportazioneTakeout.Modifica;
import it.fototimeline.google.ImportazioneTakeout.Stato;
import it.fototimeline.sicurezza.UtenteCorrente;

/** "Importa da Google Takeout": solo amministratori (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/google/takeout")
public class TakeoutController {

    private final ImportazioneTakeout takeout;
    private final UtenteCorrente corrente;

    public TakeoutController(ImportazioneTakeout takeout, UtenteCorrente corrente) {
        this.takeout = takeout;
        this.corrente = corrente;
    }

    @GetMapping
    public Stato stato() {
        return takeout.stato();
    }

    @PutMapping
    public Stato salva(@RequestBody Modifica modifica) {
        return takeout.salva(modifica);
    }

    /** 202: parte in sottofondo; 409 se è già in corso o qui non c'è rclone. */
    @PostMapping("/avvia")
    public ResponseEntity<Avanzamento> avvia() {
        return ResponseEntity.accepted().body(takeout.avvia(corrente.chi().username()));
    }

    @PostMapping("/annulla")
    public ResponseEntity<Void> annulla() {
        takeout.annulla();
        return ResponseEntity.noContent().build();
    }

    /** Toglie uno zip dal registro: al prossimo "Avvia" si rifà (le foto già entrate si saltano). */
    @PostMapping("/zip/{id}/riprova")
    public ResponseEntity<Void> riprova(@PathVariable String id) {
        return takeout.dimentica(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
