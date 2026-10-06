package it.fototimeline.salute;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Manda messaggi e foto con il bot ({@code sendMessage}, {@code sendPhoto},
 * {@code sendMediaGroup} dell'API dei bot).
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
        invia(null, testoHtml);
    }

    /** Come {@link #invia(String)}, a un'altra chat; null o vuota = quella degli avvisi. */
    public void invia(String chat, String testoHtml) {
        verificaConfigurato();
        chiama("sendMessage", MediaType.APPLICATION_JSON, Map.of(
                "chat_id", chat(chat),
                "text", testoHtml,
                "parse_mode", "HTML",
                "disable_web_page_preview", true));
    }

    /** Foto al massimo in un messaggio ({@code sendMediaGroup}). */
    public static final int MASSIMO_FOTO = 10;

    /**
     * Da una a dieci foto (JPEG sul disco), caricate come multipart: una con
     * {@code sendPhoto}, di più come album con {@code sendMediaGroup}. La
     * didascalia, in HTML come {@link #invia(String)}, va sulla prima e
     * Telegram la mostra sotto l'album.
     *
     * @param chat null o vuota = quella degli avvisi
     */
    public void inviaFoto(String chat, List<Path> foto, String didascaliaHtml) {
        verificaConfigurato();
        if (foto.isEmpty() || foto.size() > MASSIMO_FOTO) {
            throw new IllegalArgumentException("Da 1 a " + MASSIMO_FOTO + " foto, non " + foto.size());
        }
        MultiValueMap<String, Object> parti = new LinkedMultiValueMap<>();
        parti.add("chat_id", chat(chat));
        if (foto.size() == 1) {
            parti.add("photo", new FileSystemResource(foto.get(0)));
            parti.add("caption", didascaliaHtml);
            parti.add("parse_mode", "HTML");
            chiama("sendPhoto", MediaType.MULTIPART_FORM_DATA, parti);
            return;
        }
        List<Map<String, Object>> media = new ArrayList<>();
        for (int i = 0; i < foto.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", "photo");
            m.put("media", "attach://foto" + i);
            if (i == 0) {
                m.put("caption", didascaliaHtml);
                m.put("parse_mode", "HTML");
            }
            media.add(m);
            parti.add("foto" + i, new FileSystemResource(foto.get(i)));
        }
        try {
            parti.add("media", JSON.writeValueAsString(media));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        chiama("sendMediaGroup", MediaType.MULTIPART_FORM_DATA, parti);
    }

    private void verificaConfigurato() {
        if (!configurato()) {
            throw new IllegalStateException(
                    "Avvisi Telegram spenti: mancano FOTOTIMELINE_TELEGRAM_TOKEN e FOTOTIMELINE_TELEGRAM_CHAT");
        }
    }

    private String chat(String chat) {
        return (chat == null || chat.isBlank() ? properties.chat() : chat).trim();
    }

    private void chiama(String metodo, MediaType tipo, Object corpo) {
        try {
            // Senza template: i due punti del token devono restare tali, non %3A.
            rest.post().uri(URI.create(API + "/bot" + properties.token().trim() + "/" + metodo))
                    .contentType(tipo)
                    .body(corpo)
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
    public String senzaToken(String testo) {
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
