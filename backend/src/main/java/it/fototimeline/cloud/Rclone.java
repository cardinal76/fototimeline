package it.fototimeline.cloud;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Client dell'API rc di {@code rclone rcd}: un POST con i parametri in JSON
 * per comando ({@code operations/list}, {@code operations/copyfile}, ...).
 * Lo usa la sincronizzazione del telefono; {@link Cloud} ne condivide la
 * configurazione ({@link #client}).
 */
@Component
public class Rclone {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> OGGETTO = new TypeReference<>() {
    };

    private final RestClient rest;

    public Rclone(CloudProperties properties, RestClient.Builder builder) {
        this.rest = properties.gestito() ? client(properties, builder) : null;
    }

    /** Indirizzo e basic auth di rclone; i timeout sono spring.http.client.* in application.yml. */
    static RestClient client(CloudProperties properties, RestClient.Builder builder) {
        return builder.baseUrl(properties.rcUrl())
                .defaultHeaders(h -> h.setBasicAuth(properties.rcUtente(), properties.rcPassword()))
                .build();
    }

    /**
     * Esegue un comando e restituisce la risposta. Se rclone rifiuta o non
     * risponde, {@link RcloneNonRiesce} con il suo messaggio.
     */
    public Map<String, Object> chiama(String comando, Map<String, ?> parametri) {
        if (rest == null) {
            throw new IllegalStateException("Nessun rclone da comandare: l'archivio è un disco locale");
        }
        String testo;
        try {
            // rclone risponde JSON ma con Content-Type text/plain: si legge come testo.
            testo = rest.post().uri("/" + comando)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(parametri)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientResponseException e) {
            throw new RcloneNonRiesce(comando + ": " + errore(e.getResponseBodyAsString()));
        } catch (RestClientException e) {
            throw new RcloneNonRiesce(comando + ": rclone non risponde (" + e.getMessage() + ")");
        }
        try {
            return testo == null || testo.isBlank() ? Map.of() : JSON.readValue(testo, OGGETTO);
        } catch (JsonProcessingException e) {
            throw new RcloneNonRiesce(comando + ": risposta di rclone illeggibile: " + testo);
        }
    }

    /** Il campo "error" della risposta di rclone, se c'è; altrimenti il testo intero. */
    private static String errore(String corpo) {
        try {
            if (JSON.readValue(corpo, OGGETTO).get("error") instanceof String messaggio) {
                return messaggio;
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            // Non è JSON: va bene il testo così com'è.
        }
        return corpo;
    }
}
