package it.fototimeline.utenti;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import it.fototimeline.sicurezza.RegistroUtenti;
import it.fototimeline.sicurezza.Ruolo;
import it.fototimeline.sicurezza.Utente;

/**
 * La pagina Utenti (solo l'amministratore): creare, cambiare ruolo,
 * disattivare e riattivare, nuova password, eliminare. Gli utenti stanno su
 * Keycloak ({@link DirectoryUtenti}); la collezione {@code utenti} tiene i nomi
 * per "Caricata da" e il momento da cui le sessioni aperte non valgono più.
 *
 * <p>L'ultimo amministratore attivo non perde il ruolo, non si disattiva e
 * non si elimina: senza, nessuno potrebbe più rimediare dall'app. Le modifiche
 * sono una alla volta ({@code synchronized}), così due amministratori che si
 * tolgono il ruolo a vicenda non restano entrambi senza.
 */
@Service
public class GestioneUtenti {

    private static final Logger log = LoggerFactory.getLogger(GestioneUtenti.class);
    /** Come li accetta Keycloak (in minuscolo): niente spazi né caratteri strani in "Caricata da". */
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9][a-z0-9._@-]{1,59}");
    private static final int PASSWORD_MINIMA = 8;

    private final DirectoryUtenti directory;
    private final RegistroUtenti registro;

    public GestioneUtenti(DirectoryUtenti directory, RegistroUtenti registro) {
        this.directory = directory;
        this.registro = registro;
    }

    /**
     * @param ultimoAccesso dalla collezione {@code utenti}; null se non è mai entrato
     */
    public record VoceUtente(String id, String username, String nome, String cognome, String email, boolean attivo,
            Ruolo ruolo, Instant ultimoAccesso) {
    }

    /** @param motivo perché la pagina non funziona (configurazione), se {@code disponibile} è false */
    public record Elenco(boolean disponibile, String motivo, List<VoceUtente> utenti) {
    }

    /**
     * @param password la password iniziale (temporanea: Keycloak la fa cambiare); ignorata con {@code invito}
     * @param invito   niente password: Keycloak manda l'email per sceglierla
     */
    public record NuovoUtente(String username, String nome, String cognome, String email, Ruolo ruolo,
            String password, boolean invito) {
    }

    public Elenco elenco() {
        String motivo = directory.nonDisponibile();
        if (motivo != null) {
            return new Elenco(false, motivo, List.of());
        }
        Map<String, Utente> visti = registro.elenco().stream()
                .collect(Collectors.toMap(Utente::username, Function.identity()));
        List<VoceUtente> utenti = directory.elenco().stream()
                .map(u -> voce(u, visti.get(u.username())))
                .sorted(Comparator.comparing((VoceUtente v) -> nomeCompleto(v.nome(), v.cognome(), v.username())
                        .toLowerCase(Locale.ITALIAN)).thenComparing(VoceUtente::username))
                .toList();
        return new Elenco(true, null, utenti);
    }

    public synchronized VoceUtente crea(NuovoUtente n) {
        disponibile();
        if (n == null) {
            throw new IllegalArgumentException("Mancano i dati dell'utente");
        }
        String username = n.username() == null ? "" : n.username().trim().toLowerCase(Locale.ROOT);
        if (!USERNAME.matcher(username).matches()) {
            throw new IllegalArgumentException(
                    "Username non valido: da 2 a 60 tra lettere minuscole, numeri, punto, trattino, chiocciola");
        }
        if (n.ruolo() == null) {
            throw new IllegalArgumentException("Scegli il ruolo");
        }
        String email = pulito(n.email());
        if (email != null && !email.matches("[^@\\s]+@[^@\\s]+\\.[^@\\s]+")) {
            throw new IllegalArgumentException("Email non valida");
        }
        if (n.invito() && email == null) {
            throw new IllegalArgumentException("Per l'invito serve l'email");
        }
        if (!n.invito()) {
            controllaPassword(n.password());
        }
        String nome = pulito(n.nome());
        String cognome = pulito(n.cognome());
        String id = directory.crea(username, nome, cognome, email);
        try {
            directory.impostaRuolo(id, n.ruolo());
            if (n.invito()) {
                directory.invito(id);
            } else {
                directory.password(id, n.password(), true);
            }
        } catch (RuntimeException e) {
            // O tutto o niente: un utente a metà (senza ruolo o senza password) non resta in giro.
            try {
                directory.elimina(id);
            } catch (RuntimeException ignorata) {
                log.warn("Utente {} creato a metà su Keycloak e non tolto: {}", username, ignorata.getMessage());
            }
            throw e;
        }
        registro.aggiorna(username, nomeCompleto(nome, cognome, username), email, n.ruolo());
        log.info("Utente {} creato con il ruolo {}", username, n.ruolo());
        return voce(utente(id), registro.trova(username).orElse(null));
    }

    public synchronized VoceUtente cambiaRuolo(String id, Ruolo ruolo) {
        disponibile();
        if (ruolo == null) {
            throw new IllegalArgumentException("Scegli il ruolo");
        }
        UtenteKeycloak u = utente(id);
        if (ruolo != Ruolo.AMMINISTRATORE) {
            nonLUltimoAmministratore(u, "togliergli il ruolo di amministratore");
        }
        directory.impostaRuolo(id, ruolo);
        registro.aggiorna(u.username(), nomeCompleto(u.nome(), u.cognome(), u.username()), u.email(), ruolo);
        chiudiSessioni(u);
        log.info("Utente {}: ruolo {}", u.username(), ruolo);
        return voce(utente(id), registro.trova(u.username()).orElse(null));
    }

    public synchronized VoceUtente attiva(String id, boolean attivo) {
        disponibile();
        UtenteKeycloak u = utente(id);
        if (!attivo) {
            nonLUltimoAmministratore(u, "disattivarlo");
        }
        directory.attiva(id, attivo);
        if (!attivo) {
            chiudiSessioni(u);
        }
        log.info("Utente {} {}", u.username(), attivo ? "riattivato" : "disattivato");
        return voce(utente(id), registro.trova(u.username()).orElse(null));
    }

    /** Una password nuova (temporanea) o l'email per sceglierla; le sessioni aperte si chiudono. */
    public synchronized void password(String id, String password, boolean invito) {
        disponibile();
        UtenteKeycloak u = utente(id);
        if (invito) {
            if (u.email() == null) {
                throw new IllegalArgumentException(u.username() + " non ha un'email: scegli una password");
            }
            directory.invito(id);
        } else {
            controllaPassword(password);
            directory.password(id, password, true);
        }
        chiudiSessioni(u);
        log.info("Utente {}: {}", u.username(), invito ? "email per la password nuova" : "password reimpostata");
    }

    /** Lo toglie da Keycloak; le foto restano, con "Caricata da" e il suo nome. */
    public synchronized void elimina(String id) {
        disponibile();
        UtenteKeycloak u = utente(id);
        nonLUltimoAmministratore(u, "eliminarlo");
        directory.elimina(id);
        registro.eliminato(u.username());
        registro.chiudiSessioni(u.username());
        log.info("Utente {} eliminato", u.username());
    }

    // ------------------------------------------------------------ regole

    private void nonLUltimoAmministratore(UtenteKeycloak u, String cosa) {
        if (u.ruolo() != Ruolo.AMMINISTRATORE || !u.attivo()) {
            return;
        }
        boolean altri = directory.elenco().stream()
                .anyMatch(a -> a.attivo() && a.ruolo() == Ruolo.AMMINISTRATORE && !a.id().equals(u.id()));
        if (!altri) {
            throw new IllegalStateException(u.username()
                    + " è l'ultimo amministratore: prima dai il ruolo di amministratore a qualcun altro, poi potrai "
                    + cosa);
        }
    }

    /** Su Keycloak (per rientrare serve la password) e nell'app (il ruolo vecchio è nella sessione). */
    private void chiudiSessioni(UtenteKeycloak u) {
        registro.chiudiSessioni(u.username());
        try {
            directory.chiudiSessioni(u.id());
        } catch (RuntimeException e) {
            // Nell'app le sessioni sono chiuse lo stesso: rientrando, Keycloak dà il ruolo nuovo.
            log.warn("Sessioni di {} non chiuse su Keycloak: {}", u.username(), e.getMessage());
        }
    }

    private void disponibile() {
        String motivo = directory.nonDisponibile();
        if (motivo != null) {
            throw new IllegalStateException(motivo);
        }
    }

    private UtenteKeycloak utente(String id) {
        return directory.trova(id).orElseThrow(() -> new NoSuchElementException("Nessun utente " + id));
    }

    private static void controllaPassword(String password) {
        if (password == null || password.length() < PASSWORD_MINIMA) {
            throw new IllegalArgumentException("La password vuole almeno " + PASSWORD_MINIMA + " caratteri");
        }
    }

    private static VoceUtente voce(UtenteKeycloak u, Utente visto) {
        return new VoceUtente(u.id(), u.username(), u.nome(), u.cognome(), u.email(), u.attivo(), u.ruolo(),
                visto == null ? null : visto.ultimoAccesso());
    }

    private static String nomeCompleto(String nome, String cognome, String username) {
        String completo = ((nome == null ? "" : nome) + " " + (cognome == null ? "" : cognome)).trim();
        return completo.isEmpty() ? username : completo;
    }

    private static String pulito(String testo) {
        return testo == null || testo.isBlank() ? null : testo.trim();
    }
}
