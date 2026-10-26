package it.fototimeline.sicurezza;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * La pagina di accesso ({@code /accesso}): chi apre l'app senza sessione non
 * finisce dritto su Keycloak ma su una pagina dell'app, pubblica, con il
 * pulsante "Accedi". Dopo il login si torna alla pagina da cui si veniva.
 *
 * <p>Il giro:
 * <ol>
 * <li>una pagina senza sessione ({@link #ingresso()}) manda a
 *     {@code /accesso?torna=<pagina>}; per {@code /} senza parametri solo
 *     {@code /accesso}. Le API rispondono 401 e il frontend fa lo stesso
 *     ({@code sessione.ts});</li>
 * <li>"Accedi" porta a {@code /oauth2/authorization/keycloak?torna=<pagina>}:
 *     {@link #ricordaDoveTornare()} mette {@code torna} nella sessione, poi
 *     Spring prosegue verso Keycloak;</li>
 * <li>tornati da Keycloak, {@link #dopoIlLogin()} manda lì (o a {@code /}).</li>
 * </ol>
 * Niente RequestCache di Spring: si ricorderebbe anche una miniatura chiesta
 * da un {@code <img>} a sessione scaduta, e dopo il login si aprirebbe quella.
 * {@code torna} vale solo se è un percorso di questa app ({@link #sicuro}):
 * mai un altro sito.
 */
final class PaginaDiAccesso {

    static final String PAGINA = "/accesso";
    static final String PARAMETRO = "torna";
    /** Dove si ricorda la pagina, nella sessione, fra "Accedi" e il ritorno da Keycloak. */
    static final String ATTRIBUTO = PaginaDiAccesso.class.getName() + ".torna";
    private static final int LUNGHEZZA_MASSIMA = 2000;

    private PaginaDiAccesso() {
    }

    /** Per le pagine (non le API): {@code /accesso}, con la pagina da riaprire dopo il login. */
    static AuthenticationEntryPoint ingresso() {
        RedirectStrategy redirect = new DefaultRedirectStrategy();
        return (richiesta, risposta, eccezione) -> redirect.sendRedirect(richiesta, risposta, indirizzo(richiesta));
    }

    /** {@code /accesso}, più {@code ?torna=} se la pagina chiesta (GET) non è la radice nuda. */
    static String indirizzo(HttpServletRequest richiesta) {
        // Si torna solo a una pagina da aprire: una POST (la condivisione dal telefono) non si ripete.
        if (!"GET".equals(richiesta.getMethod())) {
            return PAGINA;
        }
        String percorso = richiesta.getRequestURI().substring(richiesta.getContextPath().length());
        String query = richiesta.getQueryString();
        String pagina = percorso + (query != null && !query.isEmpty() ? "?" + query : "");
        String torna = sicuro(pagina);
        if (torna == null || torna.equals("/")) {
            return PAGINA;
        }
        return PAGINA + "?" + PARAMETRO + "=" + URLEncoder.encode(torna, StandardCharsets.UTF_8);
    }

    /**
     * La pagina dove tornare, se è un percorso di questa app; null se no.
     * Rifiuta gli indirizzi assoluti e quelli che il browser leggerebbe come
     * un altro sito ({@code //altro}, {@code /\altro}), gli a capo, e le
     * pagine del login stesso (si girerebbe in tondo).
     */
    static String sicuro(String torna) {
        if (torna == null || torna.isEmpty() || torna.length() > LUNGHEZZA_MASSIMA || !torna.startsWith("/")) {
            return null;
        }
        if (torna.startsWith("//") || torna.startsWith("/\\")) {
            return null;
        }
        for (int i = 0; i < torna.length(); i++) {
            char c = torna.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '\\') {
                return null;
            }
        }
        for (String vietato : new String[] {PAGINA, "/oauth2/", "/login", "/logout", "/api/", "/error"}) {
            if (torna.equals(vietato) || torna.startsWith(vietato.endsWith("/") ? vietato : vietato + "/")
                    || torna.startsWith(vietato + "?")) {
                return null;
            }
        }
        return torna;
    }

    /** Su {@code /oauth2/authorization/*}: ricorda {@code torna} nella sessione, poi prosegue verso Keycloak. */
    static OncePerRequestFilter ricordaDoveTornare() {
        return new OncePerRequestFilter() {
            @Override
            protected boolean shouldNotFilter(HttpServletRequest richiesta) {
                String percorso = richiesta.getRequestURI().substring(richiesta.getContextPath().length());
                return !percorso.startsWith("/oauth2/authorization/");
            }

            @Override
            protected void doFilterInternal(HttpServletRequest richiesta, HttpServletResponse risposta,
                    FilterChain catena) throws ServletException, IOException {
                String torna = sicuro(richiesta.getParameter(PARAMETRO));
                if (torna != null) {
                    richiesta.getSession().setAttribute(ATTRIBUTO, torna);
                } else if (richiesta.getSession(false) != null) {
                    richiesta.getSession(false).removeAttribute(ATTRIBUTO);
                }
                catena.doFilter(richiesta, risposta);
            }
        };
    }

    /** Tornati da Keycloak: la pagina ricordata, o la timeline. */
    static AuthenticationSuccessHandler dopoIlLogin() {
        RedirectStrategy redirect = new DefaultRedirectStrategy();
        return (HttpServletRequest richiesta, HttpServletResponse risposta, Authentication autenticazione) -> {
            String torna = null;
            var sessione = richiesta.getSession(false);
            if (sessione != null && sessione.getAttribute(ATTRIBUTO) instanceof String s) {
                sessione.removeAttribute(ATTRIBUTO);
                torna = sicuro(s);
            }
            redirect.sendRedirect(richiesta, risposta, torna != null ? torna : "/");
        };
    }
}
