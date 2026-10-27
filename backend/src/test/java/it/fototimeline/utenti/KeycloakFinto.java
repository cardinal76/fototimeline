package it.fototimeline.utenti;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import it.fototimeline.sicurezza.Ruolo;

/** Il realm di Keycloak in memoria, per i test della pagina Utenti: nessuna chiamata HTTP. */
class KeycloakFinto implements DirectoryUtenti {

    final Map<String, UtenteKeycloak> utenti = new LinkedHashMap<>();
    /** id → password e se è temporanea. */
    final Map<String, String> password = new ConcurrentHashMap<>();
    final Set<String> invitati = ConcurrentHashMap.newKeySet();
    final Set<String> sessioniChiuse = ConcurrentHashMap.newKeySet();
    /** Se vero, l'email d'invito non parte (realm senza SMTP). */
    volatile boolean invitoRotto;

    synchronized String aggiungi(String username, Ruolo ruolo, boolean attivo) {
        String id = UUID.randomUUID().toString();
        utenti.put(id, new UtenteKeycloak(id, username, null, null, null, attivo, ruolo));
        return id;
    }

    synchronized void svuota() {
        utenti.clear();
        password.clear();
        invitati.clear();
        sessioniChiuse.clear();
        invitoRotto = false;
    }

    synchronized Optional<UtenteKeycloak> perUsername(String username) {
        return utenti.values().stream().filter(u -> u.username().equals(username)).findFirst();
    }

    @Override
    public String nonDisponibile() {
        return null;
    }

    @Override
    public synchronized List<UtenteKeycloak> elenco() {
        return new ArrayList<>(utenti.values());
    }

    @Override
    public synchronized Optional<UtenteKeycloak> trova(String id) {
        return Optional.ofNullable(utenti.get(id));
    }

    @Override
    public synchronized String crea(String username, String nome, String cognome, String email) {
        if (perUsername(username).isPresent()) {
            throw new KeycloakNonRiesce("User exists with same username", 409);
        }
        String id = UUID.randomUUID().toString();
        utenti.put(id, new UtenteKeycloak(id, username, nome, cognome, email, true, null));
        return id;
    }

    @Override
    public synchronized void impostaRuolo(String id, Ruolo ruolo) {
        UtenteKeycloak u = utenti.get(id);
        utenti.put(id, new UtenteKeycloak(id, u.username(), u.nome(), u.cognome(), u.email(), u.attivo(), ruolo));
    }

    @Override
    public synchronized void attiva(String id, boolean attivo) {
        UtenteKeycloak u = utenti.get(id);
        utenti.put(id, new UtenteKeycloak(id, u.username(), u.nome(), u.cognome(), u.email(), attivo, u.ruolo()));
    }

    @Override
    public void password(String id, String nuova, boolean temporanea) {
        password.put(id, nuova + (temporanea ? " (temporanea)" : ""));
    }

    @Override
    public void invito(String id) {
        if (invitoRotto) {
            throw new KeycloakNonRiesce("Keycloak non ha mandato l'email d'invito", 500);
        }
        invitati.add(id);
    }

    @Override
    public synchronized void elimina(String id) {
        utenti.remove(id);
    }

    @Override
    public void chiudiSessioni(String id) {
        sessioniChiuse.add(id);
    }
}
