package it.fototimeline.salute;

import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * La pagina "Salute", solo per gli amministratori (ConfigurazioneSicurezza).
 * Non è {@code /salute}, che resta l'healthcheck di Docker.
 */
@RestController
@RequestMapping("/api/salute")
public class SaluteController {

    private final Salute salute;
    private final AvvisiSalute avvisi;
    private final Telegram telegram;

    public SaluteController(Salute salute, AvvisiSalute avvisi, Telegram telegram) {
        this.salute = salute;
        this.avvisi = avvisi;
        this.telegram = telegram;
    }

    /** Il rapporto, e se gli avvisi Telegram sono accesi (mai il token). */
    public record Risposta(StatoSalute stato, List<VoceSalute> voci, Instant controllatoIl, boolean avvisiTelegram) {
    }

    @GetMapping
    public Risposta salute() {
        Salute.Rapporto r = salute.controlla();
        return new Risposta(r.stato(), r.voci(), r.controllatoIl(), telegram.configurato());
    }

    /** 409 se Telegram non è configurato, 502 se Telegram rifiuta. */
    @PostMapping("/prova")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void prova() {
        avvisi.prova();
    }
}
