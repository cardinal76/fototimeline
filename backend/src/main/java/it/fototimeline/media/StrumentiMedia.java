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
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;
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
 * ({@code ffmpeg}), e la versione compatibile dei video che non tutti i
 * browser sanno riprodurre ({@link #converti}). Se un programma manca, quel
 * formato viene rifiutato con un messaggio chiaro; il resto dell'app funziona lo stesso.
 */
@Component
public class StrumentiMedia {

    private static final Logger log = LoggerFactory.getLogger(StrumentiMedia.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long TIMEOUT_SECONDI = 300;
    /** "+41.9028+012.4964+050.000/" (ISO 6709, come lo scrivono iPhone e Android). */
    private static final Pattern ISO6709 = Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)");

    /** Audio che Chrome, Firefox e Safari riproducono tutti in un MP4. */
    private static final Set<String> AUDIO_COMPATIBILI = Set.of("aac", "mp3");
    /** H.264 a 8 bit 4:2:0: il 10 bit e il 4:2:2 molti browser non li decodificano. */
    private static final Set<String> PIXEL_COMPATIBILI = Set.of("yuv420p", "yuvj420p");

    private final MediaProperties properties;
    private final boolean heic;
    private final boolean video;
    /** {@code nice} c'è (nel container sì): le conversioni girano a priorità bassa. */
    private final boolean nice;

    public StrumentiMedia(MediaProperties properties) {
        this.properties = properties;
        this.heic = esiste(properties.heifConvert(), "--version");
        this.video = esiste(properties.ffmpeg(), "-version") && esiste(properties.ffprobe(), "-version");
        this.nice = esiste("nice", "--version");
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

    /**
     * La versione compatibile: H.264 8 bit + AAC in MP4 con {@code +faststart},
     * lato corto al massimo {@code risoluzioneMassima} (i 4K scendono, i video
     * piccoli restano come sono), girata come va vista (ffmpeg applica la
     * rotazione del telefono e la toglie dai metadati). Gira a priorità bassa
     * ({@code nice -n 19}) con al massimo {@code thread} thread.
     *
     * @param secondiFatti riceve i secondi di video già convertiti, per l'avanzamento
     * @param annullata    controllata mentre ffmpeg lavora: se diventa true lo si ferma
     */
    public void converti(Path video, Path mp4, int risoluzioneMassima, int thread,
            DoubleConsumer secondiFatti, BooleanSupplier annullata) throws IOException {
        List<String> comando = new ArrayList<>();
        if (nice) {
            comando.addAll(List.of("nice", "-n", "19"));
        }
        String t = String.valueOf(thread);
        comando.addAll(List.of(properties.ffmpeg(), "-y", "-nostdin", "-v", "error", "-nostats",
                "-progress", "pipe:1", "-filter_threads", t, "-threads", t, "-i", video.toString(),
                "-map", "0:v:0", "-map", "0:a:0?", "-sn", "-dn",
                "-vf", scala(risoluzioneMassima) + ",format=yuv420p",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "160k",
                "-movflags", "+faststart", "-threads", t,
                "-f", "mp4", mp4.toString()));
        Process p = new ProcessBuilder(comando).start();
        var errori = new StringBuilder();
        Thread lettore = Thread.ofVirtual().start(() -> {
            try {
                errori.append(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                // il processo è morto: lo dice il codice di uscita
            }
        });
        // Annullare deve fermarlo anche se ffmpeg non scrive niente (cloud lento).
        Thread guardiano = Thread.ofVirtual().start(() -> {
            try {
                while (p.isAlive()) {
                    if (annullata.getAsBoolean()) {
                        p.destroyForcibly();
                        return;
                    }
                    Thread.sleep(300);
                }
            } catch (InterruptedException e) {
                p.destroyForcibly();
            }
        });
        try (var righe = p.inputReader(StandardCharsets.UTF_8)) {
            String riga;
            while ((riga = righe.readLine()) != null) {
                if (riga.startsWith("out_time_us=")) {
                    try {
                        secondiFatti.accept(Long.parseLong(riga.substring(12).strip()) / 1_000_000.0);
                    } catch (NumberFormatException e) {
                        // "N/A" all'inizio
                    }
                }
            }
        }
        try {
            p.waitFor();
            lettore.join(1000);
            guardiano.interrupt();
        } catch (InterruptedException e) {
            p.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException("Interrotto", e);
        }
        if (annullata.getAsBoolean()) {
            throw new IOException("Conversione annullata");
        }
        if (p.exitValue() != 0) {
            String messaggio = errori.toString().strip();
            throw new IOException("ffmpeg è uscito con " + p.exitValue()
                    + (messaggio.isEmpty() ? "" : ": " + ultimeRighe(messaggio)));
        }
        if (!Files.exists(mp4) || Files.size(mp4) == 0) {
            throw new IOException("ffmpeg non ha prodotto il video");
        }
    }

    /**
     * Il filtro che porta il lato corto a {@code massimo} se lo supera, con
     * l'altro lato in proporzione e pari (libx264 vuole dimensioni pari). Il
     * filtro vede il video già ruotato: un verticale 2160×3840 diventa 1080×1920.
     */
    static String scala(int massimo) {
        return ("scale=w='if(gt(iw,ih),-2,trunc(min(iw,%1$d)/2)*2)'"
                + ":h='if(gt(iw,ih),trunc(min(ih,%1$d)/2)*2,-2)'").formatted(massimo);
    }

    private static String ultimeRighe(String testo) {
        return testo.length() <= 500 ? testo : "…" + testo.substring(testo.length() - 500);
    }

    /**
     * True se Chrome, Firefox e Safari riproducono il video così com'è: H.264
     * a 8 bit 4:2:0 con audio AAC o MP3 (o senza audio), in MP4/MOV. HEVC, VP9,
     * ProRes, H.264 a 10 bit o audio PCM vogliono la versione compatibile. Un
     * MP4 col {@code moov} in fondo (senza faststart) va bene lo stesso: il
     * browser lo legge chiedendo a pezzi ({@code Range}) la fine del file.
     */
    static boolean compatibile(String formato, String codecVideo, String pixel, String codecAudio) {
        boolean contenitore = formato != null && (formato.contains("mp4") || formato.contains("mov"));
        return contenitore
                && "h264".equals(codecVideo)
                && (pixel == null || PIXEL_COMPATIBILI.contains(pixel))
                && (codecAudio == null || AUDIO_COMPATIBILI.contains(codecAudio));
    }

    static InfoVideo interpreta(JsonNode probe, ZoneId zona) {
        JsonNode flusso = null;
        JsonNode audio = null;
        for (JsonNode s : probe.path("streams")) {
            String tipo = s.path("codec_type").asText();
            if (flusso == null && "video".equals(tipo) && s.path("disposition").path("attached_pic").asInt(0) == 0) {
                flusso = s;
            } else if (audio == null && "audio".equals(tipo)) {
                audio = s;
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
        String codecVideo = testo(flusso, "codec_name");
        String codecAudio = audio == null ? null : testo(audio, "codec_name");
        boolean compatibile = compatibile(testo(formato, "format_name"), codecVideo, testo(flusso, "pix_fmt"), codecAudio);
        return new InfoVideo(larghezza, altezza, durata, ripresoIl, fotocamera, lat, lon,
                codecVideo, codecAudio, compatibile);
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
