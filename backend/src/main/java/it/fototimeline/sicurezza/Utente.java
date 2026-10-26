package it.fototimeline.sicurezza;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un utente del realm visto almeno una volta: si registra da solo al login
 * (e a ogni apertura dell'app), o quando l'amministratore lo crea dalla
 * pagina Utenti. Serve per scegliere il proprietario di un telefono e per i
 * nomi del filtro "Caricate da". Gli utenti veri, con password e ruoli,
 * stanno su Keycloak ({@code GestioneUtenti}).
 *
 * @param username        {@code preferred_username} di Keycloak: è quello che finisce in {@code Foto.caricataDa}
 * @param admin           se all'ultimo accesso aveva il ruolo di amministratore
 * @param ruolo           il ruolo all'ultimo accesso o all'ultimo cambio dalla pagina Utenti; null = nessuno
 * @param eliminatoIl     tolto da Keycloak dalla pagina Utenti: la scheda resta per il nome in "Caricata da"
 * @param sessioniChiuseIl le sessioni aperte prima di questo momento non valgono più (ruolo cambiato,
 *                        disattivato, password nuova, eliminato): si rientra da Keycloak
 */
@Document("utenti")
public record Utente(@Id String username, String nome, String email, Instant primoAccesso, Instant ultimoAccesso,
        boolean admin, Ruolo ruolo, Instant eliminatoIl, Instant sessioniChiuseIl) {

    public Utente(String username, String nome, String email, Instant primoAccesso, Instant ultimoAccesso,
            boolean admin) {
        this(username, nome, email, primoAccesso, ultimoAccesso, admin, admin ? Ruolo.AMMINISTRATORE : null, null, null);
    }
}
