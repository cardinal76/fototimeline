package it.fototimeline.sicurezza;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

/**
 * Il ruolo entra nella sessione al login, e la sessione dura ore: dopo un
 * cambio dalla pagina Utenti (ruolo, disattivato, password nuova, eliminato)
 * le sessioni aperte prima non valgono più. La richiesta prosegue come se
 * non fosse entrato: le API rispondono 401 (Angular torna al login) e le
 * pagine portano a Keycloak, che dà il ruolo nuovo o rifiuta chi è disattivato.
 */
final class SessioniChiuse extends OncePerRequestFilter {

    private final RegistroUtenti registro;

    SessioniChiuse(RegistroUtenti registro) {
        this.registro = registro;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest richiesta, HttpServletResponse risposta, FilterChain catena)
            throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof OidcUser u && chiusa(u, a.getName())) {
            HttpSession sessione = richiesta.getSession(false);
            if (sessione != null) {
                sessione.invalidate();
            }
            SecurityContextHolder.clearContext();
        }
        catena.doFilter(richiesta, risposta);
    }

    private boolean chiusa(OidcUser u, String nome) {
        String username = u.getPreferredUsername() != null ? u.getPreferredUsername() : nome;
        Instant chiuse = registro.sessioniChiuseIl(username);
        if (chiuse == null) {
            return false;
        }
        // L'ID token ha i secondi: un login nello stesso secondo della chiusura vale.
        Instant entrato = u.getIssuedAt();
        return entrato == null || entrato.isBefore(chiuse.truncatedTo(ChronoUnit.SECONDS));
    }
}
