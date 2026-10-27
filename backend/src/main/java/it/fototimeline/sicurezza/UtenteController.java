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
     * @param ruolo    cosa può fare; null = è entrato ma non ha nessun ruolo: l'app dice di chiedere l'accesso
     * @param admin    il ruolo è {@link Ruolo#AMMINISTRATORE}
     * @param gestore  può cambiare le foto e scaricare gli originali (gestore o amministratore)
     */
    public record Io(boolean login, String username, String nome, Ruolo ruolo, boolean admin, boolean gestore,
            String radiceImportazione, String cartellaAutomatica) {
    }

    /** L'app lo chiede a ogni apertura: è anche il momento in cui l'utente si registra (ultimo accesso). */
    @GetMapping("/api/io")
    public Io io() {
        Chi chi = corrente.chi();
        if (chi.username() != null) {
            registro.registra(chi.username(), chi.nome(), chi.email(), chi.ruolo());
        }
        // Le cartelle del server solo a chi importa (Strumenti).
        String radice = !chi.admin() || importazione.radice() == null ? null : importazione.radice().toString();
        String automatica = !chi.admin() || this.automatica.cartella() == null ? null
                : this.automatica.cartella().toString();
        return new Io(login.attivo(), chi.username(), chi.nome(), chi.ruolo(), chi.admin(), chi.puoGestire(), radice,
                automatica);
    }

    /**
     * Gli utenti visti finora (solo admin, ConfigurazioneSicurezza): per scegliere il proprietario di un
     * telefono. Quelli eliminati dalla pagina Utenti no (la scheda resta solo per il nome in "Caricata da").
     */
    @GetMapping("/api/utenti")
    public List<Utente> utenti() {
        return registro.elenco().stream().filter(u -> u.eliminatoIl() == null).toList();
    }

    /** Per l'healthcheck di Docker: risponde anche senza login. */
    @GetMapping("/salute")
    public String salute() {
        return "ok";
    }
}
