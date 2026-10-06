package it.fototimeline.condivisione;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Freno per indirizzo IP sui path pubblici dei link ({@code /c/**},
 * {@code /api/condivise/**}), in memoria: un tetto di richieste al minuto, e
 * un blocco per chi colleziona link sconosciuti (chi prova a indovinarli).
 *
 * <p>L'indirizzo è {@code getRemoteAddr()}: dietro Caddy lo riscrive
 * {@code ForwardedHeaderFilter} da {@code X-Forwarded-For}, che Caddy imposta
 * lui (non si fida di quello del client).
 */
@Component
public class LimiteRichieste extends OncePerRequestFilter {

    /** Oltre questo numero di indirizzi si fanno pulizie delle finestre scadute. */
    private static final int PULIZIA = 10_000;

    private final CondivisioniProperties properties;
    private final Clock orologio;
    private final ConcurrentHashMap<String, Finestra> richieste = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Finestra> sbagliati = new ConcurrentHashMap<>();

    public LimiteRichieste(CondivisioniProperties properties, Optional<Clock> orologio) {
        this.properties = properties;
        this.orologio = orologio.orElse(Clock.systemUTC());
    }

    /** Conteggio in una finestra fissa che parte dalla prima richiesta. */
    private record Finestra(Instant inizio, int conteggio) {
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String percorso = request.getRequestURI().substring(request.getContextPath().length());
        return !percorso.startsWith("/c/") && !percorso.startsWith("/api/condivise/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain catena)
            throws ServletException, IOException {
        String ip = chiave(request.getRemoteAddr());
        if (bloccato(ip) || conta(richieste, ip, Duration.ofMinutes(1)) > properties.richiesteAlMinuto()) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", "60");
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"status\":429,\"detail\":\"Troppe richieste: riprova fra qualche minuto.\"}");
            return;
        }
        catena.doFilter(request, response);
    }

    /** Un link sconosciuto o una foto che non sta nel link: conta verso il blocco. */
    public void tokenSbagliato(String ip) {
        conta(sbagliati, chiave(ip), properties.finestraTokenSbagliati());
    }

    /**
     * L'indirizzo che si conta: un IPv6 per la sua rete /64, che di solito è
     * di un solo cliente (cambiare indirizzo dentro la /64 non aggira il freno).
     */
    static String chiave(String ip) {
        if (ip == null || ip.indexOf(':') < 0) {
            return ip;
        }
        try {
            // Un indirizzo letterale: nessuna richiesta al DNS.
            byte[] b = InetAddress.getByName(ip).getAddress();
            if (b.length != 16) {
                return ip;
            }
            return HexFormat.of().formatHex(b, 0, 8) + "::/64";
        } catch (UnknownHostException e) {
            return ip;
        }
    }

    boolean bloccato(String ip) {
        Finestra f = sbagliati.get(ip);
        return f != null && !scaduta(f, properties.finestraTokenSbagliati()) && f.conteggio() >= properties.tokenSbagliati();
    }

    private int conta(ConcurrentHashMap<String, Finestra> mappa, String ip, Duration durata) {
        if (mappa.size() > PULIZIA) {
            mappa.values().removeIf(f -> scaduta(f, durata));
            if (mappa.size() > PULIZIA) {
                // Troppi indirizzi insieme: meglio ripartire da zero che finire la memoria.
                mappa.clear();
            }
        }
        return mappa.compute(ip, (k, f) -> f == null || scaduta(f, durata)
                ? new Finestra(orologio.instant(), 1)
                : new Finestra(f.inizio(), f.conteggio() + 1)).conteggio();
    }

    private boolean scaduta(Finestra f, Duration durata) {
        return !orologio.instant().isBefore(f.inizio().plus(durata));
    }
}
