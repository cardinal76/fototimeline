package it.fototimeline.google;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Il collegamento a Google di un utente: il refresh token, cifrato
 * ({@link CifraturaToken}), mai in chiaro in Mongo né nel backup.
 *
 * @param utente username Keycloak ({@code locale} sul PC senza login)
 */
@Document("google_token")
record TokenGoogle(@Id String utente, String refreshCifrato, Instant collegatoIl) {

    /** Senza il token, anche cifrato. */
    @Override
    public String toString() {
        return "TokenGoogle[" + utente + ", collegato il " + collegatoIl + "]";
    }
}
