package it.fototimeline.notte;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.sicurezza.UtenteCorrente;

/** Il giro notturno in Strumenti, solo per l'amministratore (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/notte")
public class GiroNotturnoController {

    private final GiroNotturno giro;
    private final UtenteCorrente corrente;

    public GiroNotturnoController(GiroNotturno giro, UtenteCorrente corrente) {
        this.giro = giro;
        this.corrente = corrente;
    }

    /** Se è acceso, l'orario, il prossimo giro e l'ultimo (o quello in corso). */
    @GetMapping
    public GiroNotturno.StatoNotte stato() {
        return giro.stato();
    }

    /** "Lancia ora il giro notturno": 202; 409 se è già in corso o il cloud è smontato. */
    @PostMapping("/avvia")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public GiroNotturno.StatoNotte avvia() {
        giro.lanciaOra(corrente.chi().username());
        return giro.stato();
    }

    /** Ferma il giro: il lavoro in corso si annulla e i dopo non partono. */
    @PostMapping("/annulla")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void annulla() {
        giro.annulla();
    }
}
