package it.fototimeline.sicurezza;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.sicurezza.UtenteCorrente.Chi;

@RestController
public class UtenteController {

    private final LoginProperties login;
    private final ImportazioneCartelle importazione;
    private final ImportazioneAutomaticaProperties automatica;
    private final UtenteCorrente corrente;
    private final RegistroUtenti registro;

    public UtenteController(LoginProperties login, ImportazioneCartelle importazione,
            ImportazioneAutomaticaProperties automatica, UtenteCorrente corrente, RegistroUtenti registro) {
        this.login = login;
        this.importazione = importazione;
        this.automatica = automatica;
        this.corrente = corrente;
        this.registro = registro;
    }

    /**
     * Chi è collegato e cosa può fare. Con il login spento (il PC) si è
     * amministratori: il server è raggiungibile solo da qui.
     *
     * @param username quello che finisce in "caricata da" (null con il login spento)
     */
    public record Io(boolean login, String username, String nome, boolean admin, String radiceImportazione,
            String cartellaAutomatica) {
    }

    /** L'app lo chiede a ogni apertura: è anche il momento in cui l'utente si registra (ultimo accesso). */
    @GetMapping("/api/io")
    public Io io() {
        String radice = importazione.radice() == null ? null : importazione.radice().toString();
        String automatica = this.automatica.cartella() == null ? null : this.automatica.cartella().toString();
        Chi chi = corrente.chi();
        if (chi.username() != null) {
            registro.registra(chi.username(), chi.nome(), chi.email(), chi.admin());
        }
        return new Io(login.attivo(), chi.username(), chi.nome(), chi.admin(), radice, automatica);
    }

    /** Gli utenti visti finora (solo admin, ConfigurazioneSicurezza): per scegliere il proprietario di un telefono. */
    @GetMapping("/api/utenti")
    public List<Utente> utenti() {
        return registro.elenco();
    }

    /** Per l'healthcheck di Docker: risponde anche senza login. */
    @GetMapping("/salute")
    public String salute() {
        return "ok";
    }
}
