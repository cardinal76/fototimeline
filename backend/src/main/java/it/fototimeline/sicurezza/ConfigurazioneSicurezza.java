package it.fototimeline.sicurezza;

import java.io.IOException;
import java.net.InetAddress;
import java.util.LinkedHashMap;
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
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;
import org.springframework.security.web.access.RequestMatcherDelegatingAccessDeniedHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
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

    /** {@code share_target} del manifest: "Condividi → FotoTimeline" dal telefono. */
    public static final String RICEVI_CONDIVISI = "/ricevi-condivisi";
    /** Dove torna una condivisione che il service worker non ha preso: l'app dice di riprovare. */
    public static final String CONDIVISI_SENZA_APP = "/?condivisi=senza-app";

    @Bean
    SecurityFilterChain filtri(HttpSecurity http, LoginProperties login,
            @Value("${server.address:}") String indirizzo,
            ObjectProvider<ClientRegistrationRepository> registrazioni, RegistroUtenti utenti) throws Exception {
        csrf(http);
        condivisiSenzaApp(http);
        if (!login.attivo()) {
            controllaSoloLocale(indirizzo);
            http.authorizeHttpRequests(a -> a.anyRequest().permitAll());
            return http.build();
        }

        permessi(http, login);
        http.addFilterBefore(new SessioniChiuse(utenti), AuthorizationFilter.class);
        // La pagina di accesso dell'app (PaginaDiAccesso) al posto di quella generata da Spring; dopo
        // Keycloak si torna alla pagina da cui si veniva, e un login rifiutato torna lì con ?error.
        http.oauth2Login(o -> o
                .loginPage(PaginaDiAccesso.PAGINA)
                .successHandler(PaginaDiAccesso.dopoIlLogin())
                .userInfoEndpoint(u -> u.oidcUserService(new RuoliKeycloak(utenti, login))));
        http.addFilterBefore(PaginaDiAccesso.ricordaDoveTornare(), OAuth2AuthorizationRequestRedirectFilter.class);
        // La pagina da riaprire la porta "torna", non la RequestCache (vedi PaginaDiAccesso).
        http.requestCache(r -> r.requestCache(new NullRequestCache()));
        // Le API prendono 401 (Angular mostra la pagina di accesso), le pagine vanno a /accesso.
        http.exceptionHandling(e -> e
                .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                        new AntPathRequestMatcher("/api/**"))
                .defaultAuthenticationEntryPointFor(PaginaDiAccesso.ingresso(), new AntPathRequestMatcher("/**")));
        // Esci: chiude la sessione dell'app e quella di Keycloak (end_session con l'ID token), poi
        // Keycloak torna a "/", che senza sessione porta alla pagina di accesso. "/" resta l'unico
        // post.logout.redirect.uris registrato sul client (DEPLOY.md, passo 3).
        http.logout(l -> l.logoutSuccessHandler(uscita(registrazioni.getObject())));
        return http.build();
    }

    /**
     * Chi può chiamare cosa. Tre ruoli ({@link Ruolo}), dal più alto:
     * <ul>
     * <li><b>amministratore</b>: tutto, compresi gli utenti e il menu Strumenti;</li>
     * <li><b>gestore</b>: le foto (caricare, modificare, ruotare, eliminare nel cestino,
     *     condividere, scaricare gli originali), niente Strumenti né utenti;</li>
     * <li><b>utilizzatore</b>: solo guardare. Niente originali: {@code /vista} gli dà la foto
     *     ridotta e {@code /file} solo i video da guardare (FotoController).</li>
     * </ul>
     * Chi è entrato senza nessuno dei tre vede solo {@code /api/io} e la pagina
     * "chiedi l'accesso". L'ordine conta: vince la prima regola che corrisponde.
     * Ogni indirizzo dei controller è elencato con il suo permesso in
     * {@code SicurezzaTest.INVENTARIO}: uno nuovo che manca fa fallire i test.
     */
    private static void permessi(HttpSecurity http, LoginProperties login) throws Exception {
        String admin = login.autorita(Ruolo.AMMINISTRATORE);
        String[] gestione = login.almeno(Ruolo.GESTORE);
        String[] lettura = login.almeno(Ruolo.UTILIZZATORE);
        http.authorizeHttpRequests(a -> {
            a.requestMatchers("/salute", "/error").permitAll();
            // La pagina di accesso: nome, logo e "Accedi". È l'app Angular (index.html e i bundle sotto),
            // senza dati: main.ts vede /accesso e non chiama nessuna API privata.
            a.requestMatchers(HttpMethod.GET, PaginaDiAccesso.PAGINA).permitAll();
            // L'app installabile (PWA): il browser li chiede senza cookie. Non c'è niente di privato.
            a.requestMatchers("/manifest.webmanifest", "/sw.js", "/offline.html", "/icone/**", "/favicon.ico")
                    .permitAll();
            // I link di condivisione: la pagina, i suoi dati e i file dell'app Angular che la disegna
            // (codice compilato, uguale per tutti, senza dati). Il token lo controlla CondiviseController
            // a ogni richiesta; LimiteRichieste frena chi prova token a caso. Solo GET: nient'altro si apre.
            a.requestMatchers(HttpMethod.GET, "/c/*", "/api/condivise/**").permitAll();
            a.requestMatchers(HttpMethod.GET, "/main-*.js", "/chunk-*.js", "/polyfills-*.js", "/styles-*.css")
                    .permitAll();

            // Chi sono e con che ruolo: anche senza ruolo, così l'app dice "chiedi l'accesso".
            a.requestMatchers(HttpMethod.GET, "/api/io").authenticated();

            // ---- Solo l'amministratore: gli utenti e tutto quello che sta nel menu Strumenti.
            a.requestMatchers("/api/utenti", "/api/utenti/**", "/api/gestione-utenti", "/api/gestione-utenti/**")
                    .hasAuthority(admin);
            // Lavori sull'archivio: Indicizza archivio, Calcola luoghi, Calcola eventi, Converti video
            // (anche lo stato della coda), Importa cartella col suo navigatore e lo stato dei lavori.
            a.requestMatchers("/api/archivio/**", "/api/importa", "/api/importazioni/**", "/api/cartelle")
                    .hasAuthority(admin);
            // Il giro notturno, che li mette in fila: stato e orario, "Lancia ora", Annulla.
            a.requestMatchers("/api/notte", "/api/notte/**").hasAuthority(admin);
            a.requestMatchers(HttpMethod.POST, "/api/cloud/**", "/api/contenuto/**", "/api/volti/riconosci",
                    "/api/volti/annulla").hasAuthority(admin);
            a.requestMatchers("/api/backup", "/api/backup/**", "/api/salute", "/api/salute/**",
                    "/api/diagnostica", "/api/diagnostica/**", "/api/ricordi-telegram", "/api/ricordi-telegram/**")
                    .hasAuthority(admin);
            // Telefoni, Google Foto (Picker e Takeout), Quasi uguali, Cestino (ripristino, eliminazione per
            // sempre, Svuota: l'eliminazione dal visore mette solo nel cestino, sotto).
            a.requestMatchers("/api/telefoni", "/api/telefoni/**", "/api/google", "/api/google/**",
                    "/api/quasi-uguali", "/api/quasi-uguali/**", "/api/cestino", "/api/cestino/**")
                    .hasAuthority(admin);
            // "Le mie condivisioni" (elenco e revoca) sta in Strumenti; crearne una no, sotto.
            a.requestMatchers(HttpMethod.GET, "/api/condivisioni").hasAuthority(admin);
            a.requestMatchers(HttpMethod.DELETE, "/api/condivisioni/*").hasAuthority(admin);
            // Le pagine Persone ed Eventi di Strumenti: nomi, unioni, nascoste, copertine, proposte.
            // Leggere (filtro "Persona", scelta dell'evento) e cambiare le persone e l'evento di una foto no.
            a.requestMatchers(HttpMethod.PUT, "/api/persone/*", "/api/eventi/*").hasAuthority(admin);
            a.requestMatchers(HttpMethod.POST, "/api/persone/**", "/api/volti/*/togli", "/api/eventi/unisci")
                    .hasAuthority(admin);
            a.requestMatchers(HttpMethod.GET, "/api/persone/*/volti", "/api/persone/*/proposte",
                    "/api/persone/*/simili").hasAuthority(admin);
            a.requestMatchers(HttpMethod.DELETE, "/api/eventi/*").hasAuthority(admin);

            // ---- Gestore (e amministratore): il lavoro sulle foto.
            // L'originale da scaricare: l'utilizzatore guarda soltanto.
            a.requestMatchers(new AndRequestMatcher(new AntPathRequestMatcher("/api/foto/*/file", "GET"),
                    ConfigurazioneSicurezza::chiedeLOriginale)).hasAnyAuthority(gestione);
            a.requestMatchers(HttpMethod.POST, "/api/foto", "/api/foto/multiple", "/api/foto/ruota",
                    "/api/foto/*/ruota", "/api/eventi", "/api/eventi/togli", "/api/eventi/*/dividi",
                    "/api/eventi/*/foto", "/api/eventi/per-date", "/api/volti/*/assegna", "/api/volti/*/nome",
                    "/api/volti/*/rifiuta", "/api/condivisioni", RICEVI_CONDIVISI).hasAnyAuthority(gestione);
            // Gli eventi creati a mano per date (nome, dal, al): li cambia ed elimina chi cambia l'evento delle
            // foto. Gli altri eventi (rinomina, nascondi, torna automatico) restano dell'admin, sopra.
            a.requestMatchers(HttpMethod.PUT, "/api/eventi/*/per-date").hasAnyAuthority(gestione);
            a.requestMatchers(HttpMethod.DELETE, "/api/eventi/*/per-date").hasAnyAuthority(gestione);
            a.requestMatchers(HttpMethod.PUT, "/api/foto/*", "/api/foto/*/luogo").hasAnyAuthority(gestione);
            a.requestMatchers(HttpMethod.DELETE, "/api/foto/*", "/api/foto/*/luogo").hasAnyAuthority(gestione);
            // Cercare un luogo serve solo a sceglierlo per una foto.
            a.requestMatchers(HttpMethod.GET, "/api/luoghi/cerca").hasAnyAuthority(gestione);

            // ---- Tutti e tre: guardare (timeline, ricerca, filtri, mappa, visore, "Accadde oggi").
            a.requestMatchers(HttpMethod.GET, "/api/**").hasAnyAuthority(lettura);
            // Un'API che qui sopra non c'è (un metodo nuovo dimenticato) non la chiama nessuno.
            a.requestMatchers("/api/**").denyAll();
            // Le pagine dell'app: chi è entrato, anche senza ruolo (vede "chiedi l'accesso").
            a.requestMatchers(HttpMethod.GET, "/**").authenticated();
            a.anyRequest().denyAll();
        });
    }

    /** Dopo l'uscita dall'app: end_session di Keycloak con l'ID token, che poi torna a "/". */
    static OidcClientInitiatedLogoutSuccessHandler uscita(ClientRegistrationRepository registrazioni) {
        var uscita = new OidcClientInitiatedLogoutSuccessHandler(registrazioni);
        uscita.setPostLogoutRedirectUri("{baseUrl}/");
        return uscita;
    }

    /** {@code ?scarica=true} o {@code ?originale=true}: l'originale da scaricare, non il video da guardare. */
    static boolean chiedeLOriginale(HttpServletRequest richiesta) {
        return vero(richiesta.getParameter("scarica")) || vero(richiesta.getParameter("originale"));
    }

    /** Come lo legge Spring per un {@code boolean}: vuoto, false, off, no e 0 sono falsi, il resto no. */
    private static boolean vero(String valore) {
        if (valore == null) {
            return false;
        }
        String v = valore.trim().toLowerCase(java.util.Locale.ROOT);
        return !(v.isEmpty() || v.equals("false") || v.equals("off") || v.equals("no") || v.equals("0"));
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

    /**
     * "Condividi → FotoTimeline" la riceve il service worker (sw.js), che tiene
     * i file sul telefono finché l'app non li carica con POST /api/foto. Se il
     * service worker non c'è (prima apertura, browser senza), la POST del
     * telefono arriva qui senza token CSRF: il CSRF la respinge come sempre (i
     * file non entrano) e invece di un 403 si torna all'app, che dice di
     * riprovare. Senza login quell'indirizzo porta poi alla pagina di accesso.
     */
    private static void condivisiSenzaApp(HttpSecurity http) throws Exception {
        AccessDeniedHandler respinta = new AccessDeniedHandlerImpl();
        AccessDeniedHandler versoLApp = (richiesta, risposta, eccezione) -> {
            if (eccezione instanceof CsrfException) {
                risposta.setStatus(HttpServletResponse.SC_SEE_OTHER);
                risposta.setHeader("Location", CONDIVISI_SENZA_APP);
            } else {
                respinta.handle(richiesta, risposta, eccezione);
            }
        };
        // Esplicito, non defaultAccessDeniedHandlerFor: con una sola voce Spring la userebbe per tutti gli indirizzi.
        var perIndirizzo = new LinkedHashMap<RequestMatcher, AccessDeniedHandler>();
        perIndirizzo.put(new AntPathRequestMatcher(RICEVI_CONDIVISI, HttpMethod.POST.name()), versoLApp);
        http.exceptionHandling(e -> e.accessDeniedHandler(new RequestMatcherDelegatingAccessDeniedHandler(perIndirizzo, respinta)));
    }

    // ------------------------------------------------------------ CSRF per una SPA

    private static void csrf(HttpSecurity http) throws Exception {
        http.csrf(c -> c
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(new CsrfPerSpa()))
                .addFilterAfter(new CookieCsrfSempre(), BasicAuthenticationFilter.class);
    }

    /**
     * Dall'header (Angular) il token arriva in chiaro. Da un form ({@code _csrf}) può arrivare con la
     * protezione XOR di Spring (una pagina generata dal server) oppure in chiaro: l'uscita
     * (sessione.ts, {@code esci()}) lo copia dal cookie {@code XSRF-TOKEN}, che è in chiaro.
     * Senza questo il form di "Esci" prendeva 403. Il valore resta da confrontare con quello
     * del cookie: un altro sito il cookie non lo legge.
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
            if (StringUtils.hasText(header)) {
                return chiaro.resolveCsrfTokenValue(request, token);
            }
            String decifrato = xor.resolveCsrfTokenValue(request, token);
            return decifrato != null ? decifrato : chiaro.resolveCsrfTokenValue(request, token);
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
