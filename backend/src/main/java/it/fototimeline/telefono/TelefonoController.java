package it.fototimeline.telefono;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.telefono.SincronizzazioneTelefono.Modifica;
import it.fototimeline.telefono.SincronizzazioneTelefono.Stato;

/** La sincronizzazione del telefono: tutto solo per gli amministratori, anche leggere (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/telefono")
public class TelefonoController {

    private final SincronizzazioneTelefono sincronizzazione;

    public TelefonoController(SincronizzazioneTelefono sincronizzazione) {
        this.sincronizzazione = sincronizzazione;
    }

    @GetMapping
    public Stato stato() {
        return sincronizzazione.stato();
    }

    @PutMapping
    public Stato salva(@RequestBody Modifica modifica) {
        return sincronizzazione.salva(modifica);
    }

    /** "Sincronizza ora": 202 e il giro va avanti in sottofondo; 409 se ce n'è già uno. */
    @PostMapping("/sincronizza")
    public ResponseEntity<Stato> sincronizza() {
        return ResponseEntity.accepted().body(sincronizzazione.avvia());
    }
}
