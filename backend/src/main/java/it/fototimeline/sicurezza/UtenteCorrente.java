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
        Authentication a = entrato();
        if (a == null) {
            return new Chi(null, null, null, null);
        }
        Ruolo ruolo = login.ruoloDi(a.getAuthorities());
        String username = username(a);
        if (a.getPrincipal() instanceof OidcUser u) {
            String nome = u.getFullName() != null ? u.getFullName() : username;
            return new Chi(username, nome, u.getEmail(), ruolo);
        }
        return new Chi(username, username, null, ruolo);
    }

    /**
     * Lo username di chi fa questa richiesta, senza passare dal bean: lo usa
     * {@code service.Visibilita} per le foto private. Null con il login spento,
     * senza sessione e sui thread in sottofondo (lavori, scheduler), che non
     * hanno una richiesta: lì le private di qualcuno non si vedono.
     */
    public static String usernameDellaRichiesta() {
        Authentication a = entrato();
        return a == null ? null : username(a);
    }

    private static Authentication entrato() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken ? null : a;
    }

    private static String username(Authentication a) {
        if (a.getPrincipal() instanceof OidcUser u && u.getPreferredUsername() != null) {
            return u.getPreferredUsername();
        }
        return a.getName();
    }
}
