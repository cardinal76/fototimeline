package it.fototimeline.sicurezza;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Login con Keycloak. Spento in locale (il server ascolta solo su 127.0.0.1),
 * acceso sul server, dove l'app è su internet.
 *
 * @param attivo se false tutto è aperto, come sul PC
 * @param ruolo  ruolo di realm richiesto; vuoto = basta un utente del realm
 * @param ruoloAdmin ruolo di realm per montare e smontare il cloud, fare il backup e indicizzare l'archivio
 */
@ConfigurationProperties("fototimeline.login")
public record LoginProperties(boolean attivo, String ruolo, String ruoloAdmin) {

    public LoginProperties {
        if (ruoloAdmin == null || ruoloAdmin.isBlank()) {
            ruoloAdmin = "fototimeline-admin";
        }
    }

    public boolean richiedeRuolo() {
        return ruolo != null && !ruolo.isBlank();
    }
}
