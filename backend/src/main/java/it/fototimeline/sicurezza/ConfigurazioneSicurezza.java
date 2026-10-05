package it.fototimeline.sicurezza;

import java.io.IOException;
import java.net.InetAddress;
import java.util.function.Supplier;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Due modi:
 * <ul>
 * <li><b>login spento</b> (il PC): tutto aperto, il server ascolta solo su
 *     127.0.0.1. Se lo si mette su un altro indirizzo senza login, non parte.</li>
 * <li><b>login acceso</b> (server2): login OIDC sul Keycloak, sessione con
 *     cookie. Le foto si caricano con {@code <img>}, che non può mandare un
 *     token: per questo il login lo fa il backend e non Angular.</li>
 * </ul>
 * In entrambi i casi c'è il CSRF col cookie {@code XSRF-TOKEN}, che Angular
 * rimanda da solo nell'header {@code X-XSRF-TOKEN}.
 */
@Configuration
public class ConfigurazioneSicurezza {

    static final String REGISTRAZIONE = "keycloak";

    @Bean
    SecurityFilterChain filtri(HttpSecurity http, LoginProperties login,
            @Value("${server.address:}") String indirizzo,
            ObjectProvider<ClientRegistrationRepository> registrazioni) throws Exception {
        csrf(http);
        if (!login.attivo()) {
            controllaSoloLocale(indirizzo);
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        String autorizzazioneApi = login.richiedeRuolo() ? "ROLE_" + login.ruolo() : null;
        http.authorizeHttpRequests(a -> {
            a.requestMatchers("/salute", "/error").permitAll();
            // L'app installabile (PWA): il browser li chiede senza cookie. Non c'è niente di privato.
            a.requestMatchers("/manifest.webmanifest", "/sw.js", "/offline.html", "/icone/**", "/favicon.ico")
                    .permitAll();
            a.requestMatchers(HttpMethod.POST, "/api/cloud/**", "/api/backup", "/api/archivio/**")
                    .hasAuthority("ROLE_" + login.ruoloAdmin());
            if (autorizzazioneApi != null) {
                a.anyRequest().hasAuthority(autorizzazioneApi);
            } else {
                a.anyRequest().authenticated();
            }
        });
        http.oauth2Login(o -> o.userInfoEndpoint(u -> u.oidcUserService(new RuoliKeycloak())));
        // Le chiamate XHR prendono 401 (Angular manda al login), le pagine il redirect a Keycloak.
        http.exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**"))
                .defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/" + REGISTRAZIONE),
                        new AntPathRequestMatcher("/**")));
        http.logout(l -> {
            var uscita = new OidcClientInitiatedLogoutSuccessHandler(registrazioni.getObject());
            uscita.setPostLogoutRedirectUri("{baseUrl}/");
            l.logoutSuccessHandler(uscita);
        });
        return http.build();
    }

    /** Senza login l'app deve restare raggiungibile solo da questo PC. */
    static void controllaSoloLocale(String indirizzo) {
        boolean locale;
        try {
            locale = StringUtils.hasText(indirizzo) && InetAddress.getByName(indirizzo).isLoopbackAddress();
        } catch (IOException e) {
            locale = false;
        }
        if (!locale) {
            throw new IllegalStateException("""
                    Login spento ma il server ascolta su "%s": chiunque in rete vedrebbe le foto. \
                    Usa server.address=127.0.0.1 oppure accendi il login (FOTOTIMELINE_LOGIN=true).""".formatted(indirizzo));
        }
    }

    // ------------------------------------------------------------ CSRF per una SPA

    private static void csrf(HttpSecurity http) throws Exception {
        http.csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfPerSpa()))
                .addFilterAfter(new CookieCsrfSempre(), BasicAuthenticationFilter.class);
    }

    /**
     * Dall'header (Angular) il token arriva in chiaro, da un form ({@code _csrf},
     * per esempio l'uscita) con la protezione XOR di Spring.
     */
    static final class CsrfPerSpa implements CsrfTokenRequestHandler {
        private final CsrfTokenRequestHandler chiaro = new CsrfTokenRequestAttributeHandler();
        private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

        @Override
        public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> token) {
            xor.handle(request, response, token);
            // Carica subito il token così il cookie viene scritto nella risposta.
            token.get();
        }

        @Override
        public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken token) {
            String header = request.getHeader(token.getHeaderName());
            return (StringUtils.hasText(header) ? chiaro : xor).resolveCsrfTokenValue(request, token);
        }
    }

    /** Il token CSRF è "differito": senza toccarlo il cookie non verrebbe mai scritto. */
    static final class CookieCsrfSempre extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain catena)
                throws ServletException, IOException {
            if (request.getAttribute(CsrfToken.class.getName()) instanceof CsrfToken token) {
                token.getToken();
            }
            catena.doFilter(request, response);
        }
    }
}
