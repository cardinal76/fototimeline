package it.fototimeline.sicurezza;

import java.util.Collection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.security.core.GrantedAuthority;

/**
 * Login con Keycloak. Spento in locale (il server ascolta solo su 127.0.0.1),
 * acceso sul server, dove l'app è su internet.
 *
 * @param attivo             se false tutto è aperto, come sul PC
 * @param ruoloAdmin         ruolo di realm dell'{@link Ruolo#AMMINISTRATORE}
 * @param ruoloGestore       ruolo di realm del {@link Ruolo#GESTORE}
 * @param ruoloUtilizzatore  ruolo di realm dell'{@link Ruolo#UTILIZZATORE}
 */
@ConfigurationProperties("fototimeline.login")
public record LoginProperties(boolean attivo, String ruoloAdmin, String ruoloGestore, String ruoloUtilizzatore) {

    public LoginProperties {
        ruoloAdmin = oppure(ruoloAdmin, "fototimeline-admin");
        ruoloGestore = oppure(ruoloGestore, "fototimeline-gestore");
        ruoloUtilizzatore = oppure(ruoloUtilizzatore, "fototimeline-utilizzatore");
    }

    /** Il nome del ruolo di realm su Keycloak. */
    public String nome(Ruolo ruolo) {
        return switch (ruolo) {
            case AMMINISTRATORE -> ruoloAdmin;
            case GESTORE -> ruoloGestore;
            case UTILIZZATORE -> ruoloUtilizzatore;
        };
    }

    /** L'autorità di Spring Security che ha chi ha quel ruolo ({@code ROLE_} + il nome di Keycloak). */
    public String autorita(Ruolo ruolo) {
        return "ROLE_" + nome(ruolo);
    }

    /** Le autorità di questo ruolo e di quelli sopra: per le regole degli indirizzi. */
    public String[] almeno(Ruolo ruolo) {
        return java.util.Arrays.stream(Ruolo.values())
                .filter(r -> r.ordinal() <= ruolo.ordinal())
                .map(this::autorita)
                .toArray(String[]::new);
    }

    /** Il ruolo più alto fra le autorità; null se non ce n'è nessuno dei tre. */
    public Ruolo ruoloDi(Collection<? extends GrantedAuthority> autorita) {
        for (Ruolo r : Ruolo.values()) {
            String cercata = autorita(r);
            if (autorita.stream().anyMatch(a -> cercata.equals(a.getAuthority()))) {
                return r;
            }
        }
        return null;
    }

    /** Il ruolo dato il nome di Keycloak; null se non è uno dei tre. */
    public Ruolo daNome(String nome) {
        for (Ruolo r : Ruolo.values()) {
            if (nome(r).equals(nome)) {
                return r;
            }
        }
        return null;
    }

    private static String oppure(String valore, String predefinito) {
        return valore == null || valore.isBlank() ? predefinito : valore.trim();
    }
}
