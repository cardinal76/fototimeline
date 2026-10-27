package it.fototimeline.utenti;

/**
 * Keycloak ha risposto male (o non ha risposto). Il messaggio è per
 * l'amministratore e non contiene mai token né il segreto del client.
 */
public class KeycloakNonRiesce extends RuntimeException {

    private final int stato;

    public KeycloakNonRiesce(String messaggio, int stato) {
        super(messaggio);
        this.stato = stato;
    }

    /** Lo stato HTTP di Keycloak; 0 se non ha risposto. */
    public int stato() {
        return stato;
    }
}
