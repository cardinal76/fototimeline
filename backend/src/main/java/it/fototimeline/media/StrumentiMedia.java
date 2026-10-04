package it.fototimeline.media;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Le conversioni che Java da solo non sa fare: HEIC in JPEG ({@code heif-convert})
 * e per i video lettura dei metadati ({@code ffprobe}) e di un fotogramma
 * ({@code ffmpeg}). Se un programma manca, quel formato viene rifiutato con un
 * messaggio chiaro; il resto dell'app funziona lo stesso.
 */
@Component
public class StrumentiMedia {

    private static final Logger log = LoggerFactory.getLogger(StrumentiMedia.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDI = 300;
    /** "+41.9028+012.4964+050.000/" (ISO 6709, come lo scrivono iPhone e Android). */
    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    private final MediaProperties properties;
    private final boolean heic;
    private final boolean video;

    public StrumentiMedia(MediaProperties properties) {
        this.properties = properties;
        this.heic = esiste(properties.heifConvert(), "--version");
        this.video = esiste(properties.ffmpeg(), "-version") && esiste(properties.ffprobe(), "-version");
        log.info("Formati extra: HEIC {}, video {}", heic ? "sì" : "no (manca heif-convert)",
                video ? "sì" : "no (mancano ffmpeg/ffprobe)");
    }

    public boolean heicDisponibile() {
        return heic;
    }

    public boolean videoDisponibile() {
        return video;
    }

    /** Converte un HEIC in JPEG, già ruotato come va visto. */
    public void heicInJpeg(Path heif, Path jpeg) throws IOException {
        esegui(List.of(properties.heifConvert(), "-q", "92", heif.toString(), jpeg.toString()));
        if (!Files.exists(jpeg) || Files.size(jpeg) == 0) {
            throw new IOException("heif-convert non ha prodotto il JPEG");
        }
    }

    /** Un fotogramma del video (a un secondo, o a metà se è più corto), già ruotato. */
    public void fotogramma(Path video, Double durata, Path jpeg) throws IOException {
        double secondo = durata == null ? 0 : Math.min(1.0, durata / 2);
        esegui(List.of(properties.ffmpeg(), "-y", "-v", "error", "-ss", String.valueOf(secondo),
                "-i", video.toString(), "-frames:v", "1", "-q:v", "3", jpeg.toString()));
        if (!Files.exists(jpeg) || Files.size(jpeg) == 0) {
            throw new IOException("ffmpeg non ha estratto il fotogramma");
        }
    }

    public InfoVideo leggiVideo(Path video, ZoneId zona) throws IOException {
        String uscita = esegui(List.of(properties.ffprobe(), "-v", "error", "-print_format", "json",
                "-show_format", "-show_streams", video.toString()));
        return interpreta(JSON.readTree(uscita), zona);
    }

    static InfoVideo interpreta(JsonNode probe, ZoneId zona) {
        JsonNode flusso = null;
        for (JsonNode s : probe.path("streams")) {
            if ("video".equals(s.path("codec_type").asText())) {
                flusso = s;
                break;
            }
        }
        if (flusso == null) {
            throw new IllegalArgumentException("Nel file non c'è un video");
        }
        Integer larghezza = flusso.hasNonNull("width") ? flusso.get("width").asInt() : null;
        Integer altezza = flusso.hasNonNull("height") ? flusso.get("height").asInt() : null;
        int rotazione = flusso.path("tags").path("rotate").asInt(0);
        for (JsonNode d : flusso.path("side_data_list")) {
            if (d.has("rotation")) {
                rotazione = d.get("rotation").asInt();
            }
        }
        if (Math.abs(rotazione) % 180 == 90) {
            Integer t = larghezza;
            larghezza = altezza;
            altezza = t;
        }

        JsonNode formato = probe.path("format");
        Double durata = formato.hasNonNull("duration") ? formato.get("duration").asDouble() : null;
        JsonNode tag = formato.path("tags");

        // Con fuso (iPhone): l'ora locale di ripresa. Senza (creation_time, UTC): convertita nel fuso dato.
        LocalDateTime ripresoIl = dataConFuso(testo(tag, "com.apple.quicktime.creationdate"));
        if (ripresoIl == null) {
            String utc = testo(tag, "creation_time");
            if (utc == null) {
                utc = testo(flusso.path("tags"), "creation_time");
            }
            ripresoIl = dataUtc(utc, zona);
        }

        String marca = testo(tag, "com.apple.quicktime.make");
        String modello = testo(tag, "com.apple.quicktime.model");
        String fotocamera = modello == null ? marca : marca == null || modello.startsWith(marca) ? modello : marca + " " + modello;

        Double lat = null;
        Double lon = null;
        String posizione = testo(tag, "com.apple.quicktime.location.ISO6709");
        if (posizione == null) {
            posizione = testo(tag, "location");
        }
        if (posizione != null) {
            Matcher m = ISO6709.matcher(posizione);
            if (m.find()) {
                lat = Double.parseDouble(m.group(1));
                lon = Double.parseDouble(m.group(2));
            }
        }
        return new InfoVideo(larghezza, altezza, durata, ripresoIl, fotocamera, lat, lon);
    }

    private static String testo(JsonNode nodo, String campo) {
        JsonNode v = nodo.get(campo);
        return v == null || v.asText().isBlank() ? null : v.asText();
    }

    private static LocalDateTime dataConFuso(String s) {
        if (s == null) {
            return null;
        }
        for (String formato : List.of("yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd'T'HH:mm:ssXXX")) {
            try {
                return OffsetDateTime.parse(s, DateTimeFormatter.ofPattern(formato)).toLocalDateTime();
            } catch (DateTimeParseException e) {
                // il prossimo
            }
        }
        return null;
    }

    private static LocalDateTime dataUtc(String s, ZoneId zona) {
        if (s == null) {
            return null;
        }
        try {
            LocalDateTime d = OffsetDateTime.parse(s).atZoneSameInstant(zona).toLocalDateTime();
            // Molti programmi scrivono 1970 o 1904 quando non sanno la data.
            return d.getYear() < 1990 ? null : d;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private String esegui(List<String> comando) throws IOException {
        Process p = new ProcessBuilder(comando).redirectErrorStream(false).start();
        // L'uscita si legge mentre gira: se il buffer si riempie il processo si blocca.
        var uscita = new StringBuilder();
        var errori = new StringBuilder();
        Thread lettore = Thread.ofVirtual().start(() -> {
            try {
                errori.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // il processo è morto: lo dice il codice di uscita
            }
        });
        uscita.append(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        try {
            if (!p.waitFor(TIMEOUT_SECONDI, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(comando.getFirst() + " non ha finito in " + TIMEOUT_SECONDI + " secondi");
            }
            lettore.join(1000);
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        }
        if (p.exitValue() != 0) {
            throw new IOException(comando.getFirst() + " è uscito con " + p.exitValue() + ": " + errori.toString().strip());
        }
        return uscita.toString();
    }

    private static boolean esiste(String programma, String opzione) {
        try {
            Process p = new ProcessBuilder(new ArrayList<>(List.of(programma, opzione)))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
