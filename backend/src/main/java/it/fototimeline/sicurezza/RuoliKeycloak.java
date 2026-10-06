package it.fototimeline.sicurezza;

import java.util.Base64;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Aggiunge all'utente i ruoli di realm di Keycloak come ROLE_*.
 *
 * <p>Keycloak scrive {@code realm_access.roles} nell'access token, non nell'ID
 * token. L'access token arriva qui direttamente dal token endpoint, in HTTPS,
 * nello scambio del codice: per OIDC basta questo a fidarsi del contenuto,
 * senza verificarne la firma.
 *
 * <p>È anche il momento del login: l'utente entra nella collezione {@code utenti}.
 */
public class RuoliKeycloak extends OidcUserService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RegistroUtenti registro;
    private final String ruoloAdmin;

    public RuoliKeycloak(RegistroUtenti registro, String ruoloAdmin) {
        this.registro = registro;
        this.ruoloAdmin = ruoloAdmin;
    }

    @Override
    public OidcUser loadUser(OidcUserRequest richiesta) {
        OidcUser utente = super.loadUser(richiesta);
        Set<GrantedAuthority> autorita = new HashSet<>(utente.getAuthorities());
        autorita.addAll(ruoliDiRealm(richiesta.getAccessToken().getTokenValue()));
        var conRuoli = new DefaultOidcUser(autorita, utente.getIdToken(), utente.getUserInfo(), "preferred_username");
        boolean admin = autorita.stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + ruoloAdmin));
        try {
            registro.registra(conRuoli.getPreferredUsername(),
                    conRuoli.getFullName() != null ? conRuoli.getFullName() : conRuoli.getPreferredUsername(),
                    conRuoli.getEmail(), admin);
        } catch (RuntimeException e) {
            // Il registro serve per i nomi: un Mongo lento non deve impedire il login.
        }
        return conRuoli;
    }

    static Collection<GrantedAuthority> ruoliDiRealm(String accessToken) {
        String[] parti = accessToken.split("\\.");
        if (parti.length < 2) {
            return List.of();
        }
        try {
            Map<String, Object> claims = JSON.readValue(Base64.getUrlDecoder().decode(parti[1]), new TypeReference<>() { });
            if (claims.get("realm_access") instanceof Map<?, ?> accesso && accesso.get("roles") instanceof List<?> ruoli) {
                return ruoli.stream()
                        .map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList();
            }
        } catch (Exception e) {
            // Token non JWT o illeggibile: nessun ruolo.
        }
        return List.of();
    }
}
