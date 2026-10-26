package it.fototimeline.sicurezza;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

/**
 * Chi sta facendo la richiesta, dal contesto di Spring Security. Con il login
 * spento (il PC) nessuno, ma amministratore: il server è raggiungibile solo da lì.
 */
@Component
public class UtenteCorrente {

    /**
     * @param username null con il login spento: le foto caricate restano senza "caricata da"
     * @param ruolo    null se l'utente del realm non ha nessuno dei tre ruoli: non vede niente
     */
    public record Chi(String username, String nome, String email, Ruolo ruolo) {

        public boolean admin() {
            return ruolo != null && ruolo.admin();
        }

        /** Può cambiare le foto e scaricare gli originali (gestore o amministratore). */
        public boolean puoGestire() {
            return ruolo != null && ruolo.puoGestire();
        }

        /** True se può vedere e comandare un telefono di {@code proprietario}. */
        public boolean puo(String proprietario) {
            return admin() || username != null && username.equals(proprietario);
        }
    }

    private final LoginProperties login;

    public UtenteCorrente(LoginProperties login) {
        this.login = login;
    }

    public Chi chi() {
        if (!login.attivo()) {
            return new Chi(null, null, null, Ruolo.AMMINISTRATORE);
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            return new Chi(null, null, null, null);
        }
        Ruolo ruolo = login.ruoloDi(a.getAuthorities());
        if (a.getPrincipal() instanceof OidcUser u) {
            String username = u.getPreferredUsername() != null ? u.getPreferredUsername() : a.getName();
            String nome = u.getFullName() != null ? u.getFullName() : username;
            return new Chi(username, nome, u.getEmail(), ruolo);
        }
        return new Chi(a.getName(), a.getName(), null, ruolo);
    }
}
