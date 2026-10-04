package it.fototimeline.cloud;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Monta e smonta il cloud chiedendolo a rclone ({@code mount/mount},
 * {@code mount/unmount}, {@code mount/listmounts} dell'API rc).
 *
 * <p>Senza {@code fototimeline.cloud.rc-url} non c'è niente da gestire e
 * l'archivio è sempre disponibile: è il caso del PC.
 */
@Component
public class Cloud {

    private static final Logger log = LoggerFactory.getLogger(Cloud.class);
    /** Lo stato si chiede a rclone al massimo ogni tanto: lo si controlla a ogni originale servito. */
    private static final Duration VALIDITA_STATO = Duration.ofSeconds(5);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final CloudProperties properties;
    private final RestClient rclone;
    private final Clock clock;

    private volatile Boolean montatoInCache;
    private volatile Instant controllatoIl = Instant.EPOCH;

    public Cloud(CloudProperties properties, RestClient.Builder builder, Optional<Clock> clock) {
        this.properties = properties;
        this.clock = clock.orElse(Clock.systemUTC());
        if (properties.gestito()) {
            // Timeout: spring.http.client.* in application.yml.
            this.rclone = builder.baseUrl(properties.rcUrl())
                    .defaultHeaders(h -> h.setBasicAuth(properties.rcUtente(), properties.rcPassword()))
                    .build();
        } else {
            this.rclone = null;
        }
    }

    public record Stato(boolean gestito, boolean montato, String remoto, String errore) {
    }

    public Stato stato() {
        if (!properties.gestito()) {
            return new Stato(false, true, null, null);
        }
        try {
            return new Stato(true, montato(true), properties.remoto(), null);
        } catch (RestClientException e) {
            return new Stato(true, false, properties.remoto(), "rclone non risponde: " + e.getMessage());
        }
    }

    /** True se gli originali sono raggiungibili. */
    public boolean disponibile() {
        if (!properties.gestito()) {
            return true;
        }
        try {
            return montato(false);
        } catch (RestClientException e) {
            log.warn("rclone non risponde: {}", e.getMessage());
            return false;
        }
    }

    public void verifica() {
        if (!disponibile()) {
            throw new ArchivioNonDisponibile();
        }
    }

    public synchronized Stato monta() {
        richiedeGestione();
        return comandaRclone("Montaggio", this::eseguiMonta);
    }

    private void eseguiMonta() {
        if (!montato(true)) {
            log.info("Monto {} su {}", properties.remoto(), properties.puntoMontaggio());
            rclone.post().uri("/mount/mount")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "fs", properties.remoto(),
                            "mountPoint", properties.puntoMontaggio(),
                            "mountOpt", Map.of("AllowOther", true),
                            "vfsOpt", opzioniVfs()))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    public synchronized Stato smonta() {
        richiedeGestione();
        return comandaRclone("Smontaggio", this::eseguiSmonta);
    }

    private void eseguiSmonta() {
        if (montato(true)) {
            log.info("Smonto {}", properties.puntoMontaggio());
            rclone.post().uri("/mount/unmount")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("mountPoint", properties.puntoMontaggio()))
                    .retrieve()
                    .toBodilessEntity();
        }
    }

    /** Esegue e rilegge lo stato; se rclone risponde male, un errore con il suo messaggio. */
    private Stato comandaRclone(String cosa, Runnable comando) {
        try {
            comando.run();
        } catch (RestClientResponseException e) {
            throw new RcloneNonRiesce(cosa + " non riuscito: " + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new RcloneNonRiesce(cosa + " non riuscito, rclone non risponde: " + e.getMessage());
        } finally {
            dimentica();
        }
        return stato();
    }

    /**
     * Opzioni del VFS di rclone, nella forma numerica che ogni versione accetta
     * via API: CacheMode 3 = full, dimensioni in byte, durate in nanosecondi.
     * Cache "full": gli originali già visti si riaprono dal disco di server2.
     */
    Map<String, Object> opzioniVfs() {
        return Map.of(
                "CacheMode", 3,
                "CacheMaxSize", 5L * 1024 * 1024 * 1024,
                "CacheMaxAge", Duration.ofHours(24).toNanos(),
                "DirCacheTime", Duration.ofMinutes(1).toNanos(),
                "UID", properties.uid(),
                "GID", properties.uid(),
                "Umask", 2);
    }

    private boolean montato(boolean fresco) {
        Instant adesso = clock.instant();
        Boolean inCache = montatoInCache;
        if (!fresco && inCache != null && controllatoIl.plus(VALIDITA_STATO).isAfter(adesso)) {
            return inCache;
        }
        // rclone risponde JSON ma con Content-Type text/plain: si legge come testo.
        String testo = rclone.post().uri("/mount/listmounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of())
                .retrieve()
                .body(String.class);
        Map<?, ?> risposta;
        try {
            risposta = testo == null ? null : JSON.readValue(testo, Map.class);
        } catch (JsonProcessingException e) {
            throw new RestClientException("risposta di rclone illeggibile: " + testo, e);
        }
        boolean montato = risposta != null
                && risposta.get("mountPoints") instanceof List<?> punti
                && punti.stream().anyMatch(p -> p instanceof Map<?, ?> m
                        && properties.puntoMontaggio().equals(m.get("MountPoint")));
        montatoInCache = montato;
        controllatoIl = adesso;
        return montato;
    }

    private void dimentica() {
        montatoInCache = null;
    }

    private void richiedeGestione() {
        if (!properties.gestito()) {
            throw new IllegalStateException("Nessun cloud da montare: l'archivio è un disco locale");
        }
    }
}
