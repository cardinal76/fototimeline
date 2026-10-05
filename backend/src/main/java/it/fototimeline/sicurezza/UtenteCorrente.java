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

    /** @param username null con il login spento: le foto caricate restano senza "caricata da" */
    public record Chi(String username, String nome, String email, boolean admin) {

        /** True se può vedere e comandare un telefono di {@code proprietario}. */
        public boolean puo(String proprietario) {
            return admin || username != null && username.equals(proprietario);
        }
    }

    private final LoginProperties login;

    public UtenteCorrente(LoginProperties login) {
        this.login = login;
    }

    public Chi chi() {
        if (!login.attivo()) {
            return new Chi(null, null, null, true);
        }
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !a.isAuthenticated() || a instanceof AnonymousAuthenticationToken) {
            return new Chi(null, null, null, false);
        }
        boolean admin = a.getAuthorities().stream()
                .anyMatch(r -> r.getAuthority().equals("ROLE_" + login.ruoloAdmin()));
        if (a.getPrincipal() instanceof OidcUser u) {
            String username = u.getPreferredUsername() != null ? u.getPreferredUsername() : a.getName();
            String nome = u.getFullName() != null ? u.getFullName() : username;
            return new Chi(username, nome, u.getEmail(), admin);
        }
        return new Chi(a.getName(), a.getName(), null, admin);
    }
}
