package it.fototimeline.utenti;

import java.util.List;
import java.util.Optional;

import it.fototimeline.sicurezza.Ruolo;

/**
 * Dove stanno gli utenti: il realm di Keycloak ({@link AdminKeycloak}). Le
 * regole (l'ultimo amministratore, le sessioni da chiudere) sono in
 * {@link GestioneUtenti}; qui solo le chiamate. Un'interfaccia perché nei test
 * c'è un Keycloak finto in memoria.
 */
public interface DirectoryUtenti {

    /** Null se si può usare; altrimenti perché no, detto per l'amministratore. */
    String nonDisponibile();

    List<UtenteKeycloak> elenco();

    Optional<UtenteKeycloak> trova(String id);

    /** Un utente nuovo, attivo, senza password né ruolo: ne restituisce l'id. */
    String crea(String username, String nome, String cognome, String email);

    /** Toglie gli altri due ruoli di FotoTimeline e dà questo; gli altri ruoli del realm restano. */
    void impostaRuolo(String id, Ruolo ruolo);

    void attiva(String id, boolean attivo);

    /** Una password; temporanea = Keycloak la fa cambiare al primo accesso. */
    void password(String id, String password, boolean temporanea);

    /** L'email di Keycloak con il link per scegliere la password (serve l'SMTP del realm). */
    void invito(String id);

    void elimina(String id);

    /** Chiude le sessioni su Keycloak: per rientrare serve di nuovo la password. */
    void chiudiSessioni(String id);
}
