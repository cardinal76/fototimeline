package it.fototimeline.cloud;

import java.time.Duration;
import java.util.HashMap;
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

    /** Quanti job/status di fila senza risposta prima di arrendersi. */
    private static final int STATUS_SENZA_RISPOSTA = 5;

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

    /**
     * {@code operations/copyfile} asincrono ({@code _async}): un video da un
     * gigabyte fra due cloud richiede minuti, più di quanto si aspetta una
     * chiamata HTTP (il read-timeout la annullerebbe, e con lei la copia). Si
     * segue con {@code job/status} ogni {@code attesa}; se dopo {@code limite}
     * non ha finito, {@code job/stop} e {@link RcloneNonRiesce}. Qualche
     * {@code job/status} senza risposta si tollera: la copia intanto va avanti.
     */
    public void copiaFile(Map<String, ?> parametri, Duration attesa, Duration limite) {
        Map<String, Object> asincrona = new HashMap<>(parametri);
        asincrona.put("_async", true);
        Map<String, Object> risposta = chiama("operations/copyfile", asincrona);
        if (!(risposta.get("jobid") instanceof Number id)) {
            // Senza job: rclone ha già finito.
            return;
        }
        long jobid = id.longValue();
        long scadenza = System.nanoTime() + limite.toNanos();
        int senzaRisposta = 0;
        while (true) {
            Map<String, Object> stato = null;
            try {
                stato = chiama("job/status", Map.of("jobid", jobid));
                senzaRisposta = 0;
            } catch (RcloneNonRiesce e) {
                if (++senzaRisposta >= STATUS_SENZA_RISPOSTA) {
                    throw e;
                }
            }
            if (stato != null && Boolean.TRUE.equals(stato.get("finished"))) {
                if (!Boolean.TRUE.equals(stato.get("success"))) {
                    throw new RcloneNonRiesce("operations/copyfile: " + (stato.get("error") instanceof String s
                            && !s.isBlank() ? s : "errore di rclone"));
                }
                return;
            }
            if (System.nanoTime() - scadenza > 0) {
                ferma(jobid);
                throw new RcloneNonRiesce("operations/copyfile: non finita dopo " + limite.toMinutes()
                        + " minuti, fermata");
            }
            try {
                Thread.sleep(attesa);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ferma(jobid);
                throw new RcloneNonRiesce("operations/copyfile: interrotta");
            }
        }
    }

    private void ferma(long jobid) {
        try {
            chiama("job/stop", Map.of("jobid", jobid));
        } catch (RuntimeException e) {
            // Già finita o rclone ripartito: niente da fermare.
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
