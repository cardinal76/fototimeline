package it.fototimeline.utenti;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.databind.JsonNode;

import it.fototimeline.sicurezza.LoginProperties;
import it.fototimeline.sicurezza.Ruolo;

/**
 * Gli utenti del realm con l'Admin REST API di Keycloak, senza SDK: un token
 * del service account del client ({@code client_credentials}) e poche
 * chiamate HTTP. Il client vuole, del client {@code realm-management}, i ruoli
 * {@code manage-users}, {@code view-users} e {@code view-realm} (per leggere i
 * tre ruoli da assegnare): DEPLOY.md, "Utenti e ruoli".
 *
 * <p>I ruoli contano solo se assegnati direttamente all'utente (non dai gruppi):
 * è così che li mette questa pagina.
 */
@Component
class AdminKeycloak implements DirectoryUtenti {

    /** Gli utenti di una famiglia stanno in una pagina; si chiede a blocchi per sicurezza. */
    private static final int BLOCCO = 500;
    /** Quanto vale il link dell'email d'invito. */
    private static final Duration DURATA_INVITO = Duration.ofDays(3);

    private final UtentiProperties properties;
    private final LoginProperties login;
    private final RestClient rest;
    private final Clock clock;
    private Token token;

    AdminKeycloak(UtentiProperties properties, LoginProperties login, RestClient.Builder builder,
            Optional<Clock> clock) {
        this.properties = properties;
        this.login = login;
        this.rest = builder.build();
        this.clock = clock.orElse(Clock.systemUTC());
    }

    private record Token(String valore, Instant scade) {

        @Override
        public String toString() {
            return "Token[scade=" + scade + "]";
        }
    }

    @Override
    public String nonDisponibile() {
        if (!login.attivo()) {
            return "Il login è spento (l'app sul PC): gli utenti ci sono solo sul server.";
        }
        if (properties.realm() == null) {
            return "Manca l'indirizzo del realm di Keycloak (KEYCLOAK_ISSUER, .../realms/fototimeline).";
        }
        if (!properties.conSegreto()) {
            return "Manca il segreto del client " + properties.clientId()
                    + " (FOTOTIMELINE_UTENTI_CLIENT_SECRET nel .env): vedi DEPLOY.md, \"Utenti e ruoli\".";
        }
        return null;
    }

    // ------------------------------------------------------------ utenti

    @Override
    public List<UtenteKeycloak> elenco() {
        Map<String, Ruolo> ruoli = new HashMap<>();
        // Dal più basso al più alto: chi ne ha due resta col più alto.
        for (int i = Ruolo.values().length - 1; i >= 0; i--) {
            Ruolo r = Ruolo.values()[i];
            for (JsonNode u : aBlocchi("/roles/{nome}/users", login.nome(r), true)) {
                ruoli.put(u.path("id").asText(), r);
            }
        }
        List<UtenteKeycloak> utenti = new ArrayList<>();
        for (JsonNode u : aBlocchi("/users", null, false)) {
            utenti.add(utente(u, ruoli.get(u.path("id").asText())));
        }
        return utenti;
    }

    @Override
    public Optional<UtenteKeycloak> trova(String id) {
        JsonNode u;
        try {
            u = chiama(() -> admin(HttpMethod.GET, "/users/{id}", id).retrieve().body(JsonNode.class));
        } catch (KeycloakNonRiesce e) {
            if (e.stato() == 404) {
                return Optional.empty();
            }
            throw e;
        }
        JsonNode assegnati = chiama(() -> admin(HttpMethod.GET, "/users/{id}/role-mappings/realm", id)
                .retrieve().body(JsonNode.class));
        Ruolo ruolo = null;
        for (JsonNode r : assegnati == null ? List.<JsonNode>of() : assegnati) {
            Ruolo trovato = login.daNome(r.path("name").asText());
            if (trovato != null && (ruolo == null || trovato.ordinal() < ruolo.ordinal())) {
                ruolo = trovato;
            }
        }
        return Optional.of(utente(u, ruolo));
    }

    @Override
    public String crea(String username, String nome, String cognome, String email) {
        Map<String, Object> corpo = new HashMap<>();
        corpo.put("username", username);
        corpo.put("firstName", nome);
        corpo.put("lastName", cognome);
        corpo.put("email", email);
        corpo.put("enabled", true);
        URI dove = chiama(() -> admin(HttpMethod.POST, "/users").contentType(MediaType.APPLICATION_JSON).body(corpo)
                .retrieve().toBodilessEntity().getHeaders().getLocation());
        if (dove == null) {
            throw new KeycloakNonRiesce("Keycloak ha creato l'utente senza dire il suo id", 0);
        }
        String percorso = dove.getPath();
        return percorso.substring(percorso.lastIndexOf('/') + 1);
    }

    @Override
    public void impostaRuolo(String id, Ruolo ruolo) {
        Map<Ruolo, JsonNode> definiti = new EnumMap<>(Ruolo.class);
        for (Ruolo r : Ruolo.values()) {
            ruoloDelRealm(login.nome(r)).ifPresent(rap -> definiti.put(r, rap));
        }
        if (!definiti.containsKey(ruolo)) {
            throw new IllegalStateException("Il ruolo " + login.nome(ruolo)
                    + " non c'è nel realm: va creato su Keycloak (DEPLOY.md, \"Utenti e ruoli\")");
        }
        JsonNode assegnati = chiama(() -> admin(HttpMethod.GET, "/users/{id}/role-mappings/realm", id)
                .retrieve().body(JsonNode.class));
        List<JsonNode> daTogliere = new ArrayList<>();
        boolean giaCe = false;
        for (JsonNode r : assegnati == null ? List.<JsonNode>of() : assegnati) {
            Ruolo suo = login.daNome(r.path("name").asText());
            if (suo == ruolo) {
                giaCe = true;
            } else if (suo != null) {
                daTogliere.add(r);
            }
        }
        if (!giaCe) {
            List<JsonNode> daDare = List.of(definiti.get(ruolo));
            chiama(() -> admin(HttpMethod.POST, "/users/{id}/role-mappings/realm", id)
                    .contentType(MediaType.APPLICATION_JSON).body(daDare).retrieve().toBodilessEntity());
        }
        if (!daTogliere.isEmpty()) {
            chiama(() -> admin(HttpMethod.DELETE, "/users/{id}/role-mappings/realm", id)
                    .contentType(MediaType.APPLICATION_JSON).body(daTogliere).retrieve().toBodilessEntity());
        }
    }

    @Override
    public void attiva(String id, boolean attivo) {
        chiama(() -> admin(HttpMethod.PUT, "/users/{id}", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("enabled", attivo)).retrieve().toBodilessEntity());
    }

    @Override
    public void password(String id, String password, boolean temporanea) {
        chiama(() -> admin(HttpMethod.PUT, "/users/{id}/reset-password", id).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("type", "password", "value", password, "temporary", temporanea))
                .retrieve().toBodilessEntity());
    }

    @Override
    public void invito(String id) {
        try {
            chiama(() -> rest.method(HttpMethod.PUT)
                    .uri(properties.indirizzoAdmin() + "/users/{id}/execute-actions-email?lifespan={secondi}", id,
                            DURATA_INVITO.toSeconds())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(List.of("UPDATE_PASSWORD"))
                    .retrieve().toBodilessEntity());
        } catch (KeycloakNonRiesce e) {
            if (e.stato() == 400 || e.stato() >= 500) {
                throw new KeycloakNonRiesce("Keycloak non ha mandato l'email d'invito (" + e.getMessage()
                        + "): l'utente ha un'email? Il realm ha il server SMTP (Realm settings → Email)?", e.stato());
            }
            throw e;
        }
    }

    @Override
    public void elimina(String id) {
        chiama(() -> admin(HttpMethod.DELETE, "/users/{id}", id).retrieve().toBodilessEntity());
    }

    @Override
    public void chiudiSessioni(String id) {
        chiama(() -> admin(HttpMethod.POST, "/users/{id}/logout", id).retrieve().toBodilessEntity());
    }

    // ------------------------------------------------------------ chiamate

    private Optional<JsonNode> ruoloDelRealm(String nome) {
        try {
            return Optional.ofNullable(chiama(() -> admin(HttpMethod.GET, "/roles/{nome}", nome)
                    .retrieve().body(JsonNode.class)));
        } catch (KeycloakNonRiesce e) {
            if (e.stato() == 404) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * Gli elementi di un elenco dell'Admin API, a blocchi di {@link #BLOCCO}.
     * Un ruolo che non c'è nel realm (404) non ha utenti.
     */
    private List<JsonNode> aBlocchi(String percorso, String variabile, boolean manca404) {
        List<JsonNode> tutti = new ArrayList<>();
        for (int primo = 0;; primo += BLOCCO) {
            int da = primo;
            JsonNode blocco;
            try {
                blocco = chiama(() -> rest.get()
                        .uri(properties.indirizzoAdmin() + percorso + "?briefRepresentation=true&first={primo}&max={max}",
                                variabile == null ? new Object[] {da, BLOCCO} : new Object[] {variabile, da, BLOCCO})
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve().body(JsonNode.class));
            } catch (KeycloakNonRiesce e) {
                if (manca404 && e.stato() == 404) {
                    return tutti;
                }
                throw e;
            }
            if (blocco == null || !blocco.isArray()) {
                return tutti;
            }
            blocco.forEach(tutti::add);
            if (blocco.size() < BLOCCO) {
                return tutti;
            }
        }
    }

    private RestClient.RequestBodySpec admin(HttpMethod metodo, String percorso, Object... variabili) {
        return rest.method(metodo)
                .uri(properties.indirizzoAdmin() + percorso, variabili)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token())
                .accept(MediaType.APPLICATION_JSON);
    }

    /** Il token del service account, tenuto finché non sta per scadere. */
    private synchronized String token() {
        String motivo = nonDisponibile();
        if (motivo != null) {
            throw new IllegalStateException(motivo);
        }
        Instant adesso = clock.instant();
        if (token != null && token.scade().isAfter(adesso.plusSeconds(30))) {
            return token.valore();
        }
        MultiValueMap<String, String> modulo = new LinkedMultiValueMap<>();
        modulo.add("grant_type", "client_credentials");
        modulo.add("client_id", properties.clientId());
        modulo.add("client_secret", properties.clientSecret());
        JsonNode risposta = chiama(() -> rest.post().uri(properties.indirizzoToken())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(modulo)
                .retrieve().body(JsonNode.class), true);
        if (risposta == null || !risposta.hasNonNull("access_token")) {
            throw new KeycloakNonRiesce("Keycloak non ha dato il token del client " + properties.clientId(), 0);
        }
        token = new Token(risposta.get("access_token").asText(),
                adesso.plusSeconds(Math.max(risposta.path("expires_in").asLong(60), 1)));
        return token.valore();
    }

    private <T> T chiama(Supplier<T> chiamata) {
        return chiama(chiamata, false);
    }

    /** Gli errori di Keycloak con lo stato e il suo messaggio, mai il token o il segreto. */
    private <T> T chiama(Supplier<T> chiamata, boolean perIlToken) {
        try {
            return chiamata.get();
        } catch (RestClientResponseException e) {
            int stato = e.getStatusCode().value();
            if (perIlToken || stato == 401) {
                synchronized (this) {
                    token = null;
                }
                throw new KeycloakNonRiesce("Keycloak non accetta il client " + properties.clientId()
                        + " (" + stato + messaggio(e) + "): controlla FOTOTIMELINE_UTENTI_CLIENT_SECRET e che il client"
                        + " abbia il service account", stato);
            }
            if (stato == 403) {
                throw new KeycloakNonRiesce("Il client " + properties.clientId()
                        + " non ha i permessi: servono manage-users, view-users e view-realm (DEPLOY.md)", stato);
            }
            throw new KeycloakNonRiesce("Keycloak risponde " + stato + messaggio(e), stato);
        } catch (RestClientException e) {
            throw new KeycloakNonRiesce("Keycloak non risponde: " + e.getMostSpecificCause().getClass().getSimpleName(), 0);
        }
    }

    /** {@code errorMessage} (Admin API) o {@code error_description} (token) di Keycloak. */
    private static String messaggio(RestClientResponseException e) {
        try {
            JsonNode corpo = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
            for (String campo : new String[] {"errorMessage", "error_description", "error"}) {
                if (corpo != null && corpo.hasNonNull(campo)) {
                    return ": " + corpo.get(campo).asText();
                }
            }
        } catch (Exception ignorata) {
            // Corpo vuoto o non JSON: basta lo stato.
        }
        return "";
    }

    private static UtenteKeycloak utente(JsonNode u, Ruolo ruolo) {
        return new UtenteKeycloak(u.path("id").asText(), u.path("username").asText(), testo(u, "firstName"),
                testo(u, "lastName"), testo(u, "email"), u.path("enabled").asBoolean(true), ruolo);
    }

    private static String testo(JsonNode u, String campo) {
        return u.hasNonNull(campo) && !u.get(campo).asText().isBlank() ? u.get(campo).asText() : null;
    }
}
