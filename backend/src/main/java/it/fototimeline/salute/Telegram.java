package it.fototimeline.salute;

import java.net.URI;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manda un messaggio con il bot ({@code sendMessage} dell'API dei bot).
 *
 * <p>Il token sta nell'indirizzo: ogni messaggio d'errore che esce da qui lo
 * ha già tolto, perché finisce nei log e nelle risposte.
 */
@Component
public class Telegram {

    static final String API = "https://api.telegram.org";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TelegramProperties properties;
    private final RestClient rest;

    public Telegram(TelegramProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        this.rest = builder.build();
    }

    public boolean configurato() {
        return properties.configurato();
    }

    /** Testo in HTML di Telegram (b, i, a, code): il resto va già passato da {@link #html}. */
    public void invia(String testoHtml) {
        if (!configurato()) {
            throw new IllegalStateException(
                    "Avvisi Telegram spenti: mancano FOTOTIMELINE_TELEGRAM_TOKEN e FOTOTIMELINE_TELEGRAM_CHAT");
        }
        try {
            // Senza template: i due punti del token devono restare tali, non %3A.
            rest.post().uri(URI.create(API + "/bot" + properties.token().trim() + "/sendMessage"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "chat_id", properties.chat().trim(),
                            "text", testoHtml,
                            "parse_mode", "HTML",
                            "disable_web_page_preview", true))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new TelegramNonRiesce("Telegram ha rifiutato il messaggio: "
                    + senzaToken(descrizione(e.getResponseBodyAsString(), e.getStatusText())));
        } catch (RestClientException e) {
            throw new TelegramNonRiesce("Telegram non risponde: " + senzaToken(e.getMessage()));
        }
    }

    /** Il campo "description" della risposta di Telegram ("Bad Request: chat not found"). */
    private static String descrizione(String corpo, String riserva) {
        try {
            String d = JSON.readTree(corpo).path("description").asText(null);
            return d != null ? d : riserva;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            return riserva;
        }
    }

    /** Toglie il token, anche se scritto codificato, e qualunque cosa segua "/bot" in un indirizzo. */
    String senzaToken(String testo) {
        if (testo == null) {
            return null;
        }
        String token = properties.token();
        if (token != null && !token.isBlank()) {
            testo = testo.replace(token.trim(), "<token>").replace(token.trim().replace(":", "%3A"), "<token>");
        }
        return testo.replaceAll("/bot[^/\\s\"]+/", "/bot<token>/");
    }

    /** Per il parse_mode HTML: solo questi tre vanno sostituiti. */
    public static String html(String testo) {
        return testo == null ? "" : testo.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Telegram ha rifiutato o non risponde; il messaggio è senza token. */
    public static class TelegramNonRiesce extends RuntimeException {
        public TelegramNonRiesce(String messaggio) {
            super(messaggio);
        }
    }
}
