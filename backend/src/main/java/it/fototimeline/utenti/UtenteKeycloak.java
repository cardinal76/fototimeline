package it.fototimeline.utenti;

import it.fototimeline.sicurezza.Ruolo;

/**
 * Un utente del realm come lo vede la pagina Utenti.
 *
 * @param id    l'id di Keycloak (UUID), quello degli indirizzi dell'Admin API
 * @param ruolo il più alto dei tre ruoli di FotoTimeline assegnati direttamente; null = nessuno
 */
public record UtenteKeycloak(String id, String username, String nome, String cognome, String email, boolean attivo,
        Ruolo ruolo) {
}
