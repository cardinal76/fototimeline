package it.fototimeline.google;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Le chiamate a Google: OAuth 2.0 (consenso, scambio del codice, rinnovo,
 * revoca) e Google Photos Picker API (sessione, elenco delle foto scelte,
 * download). Nessun SDK: poche chiamate HTTP con il {@link RestClient} di
 * Spring.
 *
 * <p>I messaggi d'errore dicono lo stato HTTP e il campo {@code error} di
 * Google, mai i token né il client secret.
 */
@Component
class ClientGoogle {

    private final GoogleProperties properties;
    private final RestClient rest;

    ClientGoogle(GoogleProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.rest = builder.build();
    }

    /** Google ha risposto male: il messaggio è per le persone e non contiene segreti. */
    static final class GoogleNonRiesce extends RuntimeException {
        private final int stato;

        GoogleNonRiesce(String messaggio, int stato) {
            super(messaggio);
            this.stato = stato;
        }

        /** Lo stato HTTP (0 se Google non ha risposto). */
        int stato() {
            return stato;
        }
    }

    // ------------------------------------------------------------ OAuth

    /** I token di una risposta di {@code /token}; {@code refresh} c'è solo al primo consenso. */
    record Token(String accesso, String refresh, Instant scade) {

        @Override
        public String toString() {
            return "Token[scade=" + scade + "]";
        }
    }

    /** La pagina di consenso: solo lo scope del Picker, accesso offline (refresh token), consenso sempre chiesto. */
    String urlConsenso(String stato, String redirectUri) {
        return UriComponentsBuilder.fromUriString(properties.urlConsenso())
                .queryParam("client_id", properties.clientId())
                .queryParam("redirect_uri", redirectUri)
                .queryParam("response_type", "code")
                .queryParam("scope", GoogleProperties.SCOPE)
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent")
                .queryParam("include_granted_scopes", "false")
                .queryParam("state", stato)
                .encode()
                .build()
                .toUriString();
    }

    Token scambiaCodice(String codice, String redirectUri, Instant adesso) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("code", codice);
        f.add("client_id", properties.clientId());
        f.add("client_secret", properties.clientSecret());
        f.add("redirect_uri", redirectUri);
        f.add("grant_type", "authorization_code");
        return token(f, adesso, "Collegamento a Google");
    }

    Token rinnova(String refresh, Instant adesso) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("refresh_token", refresh);
        f.add("client_id", properties.clientId());
        f.add("client_secret", properties.clientSecret());
        f.add("grant_type", "refresh_token");
        return token(f, adesso, "Rinnovo dell'accesso a Google");
    }

    private Token token(MultiValueMap<String, String> modulo, Instant adesso, String cosa) {
        JsonNode r = chiama(cosa, () -> rest.post().uri(URI.create(properties.urlToken()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(modulo)
                .retrieve()
                .body(JsonNode.class));
        String accesso = testo(r, "access_token");
        if (accesso == null) {
            throw new GoogleNonRiesce(cosa + ": Google non ha dato un token", 0);
        }
        long secondi = r.path("expires_in").asLong(3600);
        return new Token(accesso, testo(r, "refresh_token"), adesso.plusSeconds(secondi));
    }

    /** "Scollega Google": il refresh token non vale più (e con lui gli access token). */
    void revoca(String token) {
        MultiValueMap<String, String> f = new LinkedMultiValueMap<>();
        f.add("token", token);
        chiama("Revoca su Google", () -> rest.post().uri(URI.create(properties.urlRevoca()))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(f)
                .retrieve()
                .toBodilessEntity());
    }

    // ------------------------------------------------------------ Picker

    /**
     * Una sessione del Picker.
     *
     * @param pickerUri     da aprire nel browser (con {@code /autoclose} la scheda si chiude da sola)
     * @param attesa        ogni quanto chiedere se l'utente ha finito ({@code pollingConfig.pollInterval})
     * @param tempoMassimo  per quanto ancora chiederlo ({@code pollingConfig.timeoutIn})
     * @param scelte        true quando l'utente ha finito di scegliere ({@code mediaItemsSet})
     */
    record SessionePicker(String id, String pickerUri, Duration attesa, Duration tempoMassimo, Instant scade,
            boolean scelte) {
    }

    /** Una foto o un video scelto. {@code video}: si scarica con {@code =dv}, le foto con {@code =d}. */
    record Scelta(String id, String nome, String tipoMime, String baseUrl, boolean video, Instant creataIl) {
    }

    /** Una pagina di foto scelte e il segno per la successiva (null se finite). */
    record PaginaScelte(List<Scelta> scelte, String prossima) {
    }

    SessionePicker creaSessione(String accesso) {
        return sessione(chiama("Sessione di Google Foto", () -> rest.post().uri(URI.create(properties.urlPicker() + "/sessions"))
                .headers(h -> h.setBearerAuth(accesso))
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(JsonNode.class)));
    }

    SessionePicker leggiSessione(String accesso, String id) {
        return sessione(chiama("Sessione di Google Foto", () -> rest.get()
                .uri(URI.create(properties.urlPicker() + "/sessions/" + percorso(id)))
                .headers(h -> h.setBearerAuth(accesso))
                .retrieve()
                .body(JsonNode.class)));
    }

    void eliminaSessione(String accesso, String id) {
        chiama("Chiusura della sessione di Google Foto", () -> rest.delete()
                .uri(URI.create(properties.urlPicker() + "/sessions/" + percorso(id)))
                .headers(h -> h.setBearerAuth(accesso))
                .retrieve()
                .toBodilessEntity());
    }

    PaginaScelte elencoScelte(String accesso, String sessione, String pagina) {
        UriComponentsBuilder u = UriComponentsBuilder.fromUriString(properties.urlPicker() + "/mediaItems")
                .queryParam("sessionId", sessione)
                .queryParam("pageSize", 100);
        if (pagina != null) {
            u.queryParam("pageToken", pagina);
        }
        URI uri = u.encode().build().toUri();
        JsonNode r = chiama("Elenco delle foto scelte", () -> rest.get().uri(uri)
                .headers(h -> h.setBearerAuth(accesso))
                .retrieve()
                .body(JsonNode.class));
        List<Scelta> scelte = new ArrayList<>();
        for (JsonNode m : r.path("mediaItems")) {
            JsonNode file = m.path("mediaFile");
            String baseUrl = testo(file, "baseUrl");
            if (baseUrl == null) {
                continue;
            }
            String tipo = testo(file, "mimeType");
            boolean video = "VIDEO".equals(testo(m, "type")) || tipo != null && tipo.startsWith("video/");
            scelte.add(new Scelta(testo(m, "id"), testo(file, "filename"), tipo, baseUrl, video,
                    istante(testo(m, "createTime"))));
        }
        return new PaginaScelte(scelte, testo(r, "nextPageToken"));
    }

    /**
     * Scarica l'originale: {@code baseUrl=d} (foto, con l'EXIF) o
     * {@code baseUrl=dv} (video), con il token nell'header, a pezzi su disco.
     */
    void scarica(String accesso, Scelta s, Path destinazione) {
        URI uri = URI.create(s.baseUrl() + (s.video() ? "=dv" : "=d"));
        chiama("Download di " + (s.nome() != null ? s.nome() : "una foto"), () -> rest.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accesso)
                .exchange((richiesta, risposta) -> {
                    if (risposta.getStatusCode().isError()) {
                        throw new GoogleNonRiesce("Download non riuscito (HTTP " + risposta.getStatusCode().value() + ")",
                                risposta.getStatusCode().value());
                    }
                    try (InputStream in = risposta.getBody()) {
                        Files.copy(in, destinazione, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return null;
                }));
    }

    // ------------------------------------------------------------ utilità

    private interface Chiamata<T> {
        T esegui() throws IOException;
    }

    /** Esegue e traduce gli errori in {@link GoogleNonRiesce}, con il codice d'errore di Google ma senza il corpo intero. */
    private static <T> T chiama(String cosa, Chiamata<T> chiamata) {
        try {
            return chiamata.esegui();
        } catch (GoogleNonRiesce e) {
            throw e;
        } catch (RestClientResponseException e) {
            throw new GoogleNonRiesce(cosa + " non riuscito: HTTP " + e.getStatusCode().value() + motivo(e),
                    e.getStatusCode().value());
        } catch (RestClientException | IOException e) {
            throw new GoogleNonRiesce(cosa + " non riuscito: Google non risponde", 0);
        }
    }

    /** Il campo "error" (OAuth: "invalid_grant") o "error.status" (API: "PERMISSION_DENIED"), se c'è. */
    private static String motivo(RestClientResponseException e) {
        try {
            JsonNode n = new com.fasterxml.jackson.databind.ObjectMapper().readTree(e.getResponseBodyAsString());
            JsonNode errore = n.path("error");
            String codice = errore.isTextual() ? errore.asText() : testo(errore, "status");
            return codice != null && codice.matches("[A-Za-z_]{1,60}") ? " (" + codice + ")" : "";
        } catch (IOException | RuntimeException x) {
            return "";
        }
    }

    private static SessionePicker sessione(JsonNode n) {
        String id = testo(n, "id");
        if (id == null) {
            throw new GoogleNonRiesce("Sessione di Google Foto senza id", 0);
        }
        JsonNode polling = n.path("pollingConfig");
        return new SessionePicker(id, testo(n, "pickerUri"), durata(testo(polling, "pollInterval"), Duration.ofSeconds(5)),
                durata(testo(polling, "timeoutIn"), Duration.ofMinutes(30)), istante(testo(n, "expireTime")),
                n.path("mediaItemsSet").asBoolean(false));
    }

    /** Le durate di Google: "5s", "1.500s". */
    static Duration durata(String testo, Duration riserva) {
        if (testo == null || !testo.endsWith("s")) {
            return riserva;
        }
        try {
            BigDecimal secondi = new BigDecimal(testo.substring(0, testo.length() - 1));
            return Duration.ofMillis(secondi.movePointRight(3).longValue());
        } catch (NumberFormatException e) {
            return riserva;
        }
    }

    private static Instant istante(String testo) {
        try {
            return testo == null ? null : Instant.parse(testo);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static String testo(JsonNode n, String campo) {
        JsonNode v = n == null ? null : n.path(campo);
        return v != null && v.isTextual() && !v.asText().isBlank() ? v.asText() : null;
    }

    private static String percorso(String id) {
        return UriComponentsBuilder.newInstance().pathSegment(id).build().encode().toUriString().substring(1);
    }
}
