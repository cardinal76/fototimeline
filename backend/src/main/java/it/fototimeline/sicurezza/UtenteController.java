package it.fototimeline.sicurezza;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.service.ImportazioneAutomaticaProperties;
import it.fototimeline.service.ImportazioneCartelle;

@RestController
public class UtenteController {

    private final LoginProperties login;
    private final ImportazioneCartelle importazione;
    private final ImportazioneAutomaticaProperties automatica;

    public UtenteController(LoginProperties login, ImportazioneCartelle importazione,
            ImportazioneAutomaticaProperties automatica) {
        this.login = login;
        this.importazione = importazione;
        this.automatica = automatica;
    }

    /**
     * Chi è collegato e cosa può fare. Con il login spento (il PC) si è
     * amministratori: il server è raggiungibile solo da qui.
     */
    public record Io(boolean login, String nome, boolean admin, String radiceImportazione,
            String cartellaAutomatica) {
    }

    @GetMapping("/api/io")
    public Io io(@AuthenticationPrincipal OidcUser utente) {
        String radice = importazione.radice() == null ? null : importazione.radice().toString();
        String automatica = this.automatica.cartella() == null ? null : this.automatica.cartella().toString();
        if (!login.attivo() || utente == null) {
            return new Io(login.attivo(), null, !login.attivo(), radice, automatica);
        }
        String nome = utente.getFullName() != null ? utente.getFullName() : utente.getPreferredUsername();
        boolean admin = utente.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + login.ruoloAdmin()));
        return new Io(true, nome, admin, radice, automatica);
    }

    /** Per l'healthcheck di Docker: risponde anche senza login. */
    @GetMapping("/salute")
    public String salute() {
        return "ok";
    }
}
