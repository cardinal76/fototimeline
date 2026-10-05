package it.fototimeline.ricordi;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** "Ricordi su Telegram", solo per gli amministratori (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/ricordi-telegram")
public class RicordiTelegramController {

    private final RicordiTelegram ricordi;

    public RicordiTelegramController(RicordiTelegram ricordi) {
        this.ricordi = ricordi;
    }

    @GetMapping
    public RicordiTelegram.Stato stato() {
        return ricordi.stato();
    }

    /** 400 se l'ora o il numero di foto non vanno. */
    @PutMapping
    public RicordiTelegram.Stato salva(@RequestBody RicordiTelegram.Impostazioni impostazioni) {
        return ricordi.salva(impostazioni);
    }

    /** I ricordi di oggi subito, anche se già mandati: 409 se Telegram non è configurato, 502 se rifiuta. */
    @PostMapping("/prova")
    public RicordiTelegram.Esito prova() {
        return ricordi.prova();
    }
}
