package it.fototimeline.sicurezza;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un utente del realm visto almeno una volta: si registra da solo al login
 * (e a ogni apertura dell'app). Serve per scegliere il proprietario di un
 * telefono e per i nomi del filtro "Caricate da", senza l'API admin di Keycloak.
 *
 * @param username {@code preferred_username} di Keycloak: è quello che finisce in {@code Foto.caricataDa}
 * @param admin    se all'ultimo accesso aveva il ruolo di amministratore
 */
@Document("utenti")
public record Utente(@Id String username, String nome, String email, Instant primoAccesso, Instant ultimoAccesso,
        boolean admin) {
}
