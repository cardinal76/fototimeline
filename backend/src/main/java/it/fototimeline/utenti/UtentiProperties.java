package it.fototimeline.utenti;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La pagina Utenti: gli utenti stanno su Keycloak e si creano e cambiano con
 * la sua Admin REST API, col service account di un client confidenziale
 * (DEPLOY.md, "Utenti e ruoli"). Il segreto non va mai nei log né nelle risposte.
 *
 * @param keycloak     l'issuer del realm, per esempio {@code https://presenze.footballer.it/auth/realms/fototimeline}
 * @param clientId     il client col service account ({@code manage-users}, {@code view-users}, {@code view-realm})
 * @param clientSecret il suo segreto; vuoto = la pagina dice cosa manca
 */
@ConfigurationProperties("fototimeline.utenti")
public record UtentiProperties(String keycloak, String clientId, String clientSecret) {

    private static final String REALMS = "/realms/";

    public UtentiProperties {
        keycloak = keycloak == null ? "" : keycloak.trim().replaceAll("/+$", "");
        clientId = clientId == null || clientId.isBlank() ? "fototimeline-utenti" : clientId.trim();
    }

    /** Il nome del realm dall'issuer; null se l'issuer non ha {@code /realms/<nome>}. */
    public String realm() {
        int i = keycloak.lastIndexOf(REALMS);
        if (i < 0 || i + REALMS.length() >= keycloak.length()) {
            return null;
        }
        String realm = keycloak.substring(i + REALMS.length());
        return realm.contains("/") ? null : realm;
    }

    /** Keycloak senza il realm: {@code https://presenze.footballer.it/auth}. */
    public String base() {
        return keycloak.substring(0, keycloak.lastIndexOf(REALMS));
    }

    public String indirizzoToken() {
        return keycloak + "/protocol/openid-connect/token";
    }

    /** {@code <base>/admin/realms/<realm>}: tutte le chiamate della pagina Utenti. */
    public String indirizzoAdmin() {
        return base() + "/admin/realms/" + realm();
    }

    public boolean conSegreto() {
        return clientSecret != null && !clientSecret.isBlank();
    }

    @Override
    public String toString() {
        return "UtentiProperties[keycloak=" + keycloak + ", clientId=" + clientId + "]";
    }
}
