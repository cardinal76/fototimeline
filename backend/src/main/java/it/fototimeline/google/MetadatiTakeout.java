package it.fototimeline.google;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import it.fototimeline.service.FotoService.MetadatiEsterni;

/**
 * Quello che serve del JSON di Google Takeout di una foto:
 * <pre>
 * { "title": "IMG_1.jpg", "description": "Al mare",
 *   "photoTakenTime": { "timestamp": "1690000000", "formatted": "..." },
 *   "creationTime": { "timestamp": "..." },
 *   "geoData": { "latitude": 41.9, "longitude": 12.5, ... },
 *   "geoDataExif": { "latitude": 0.0, "longitude": 0.0, ... },
 *   "favorited": true }
 * </pre>
 * Le coordinate 0,0 vogliono dire "nessuna". {@code photoTakenTime} è in UTC:
 * diventa l'ora locale nel fuso dell'app (come l'EXIF dei video).
 *
 * @param scattataIl photoTakenTime, o creationTime se manca; null se nessuno dei due
 */
record MetadatiTakeout(String titolo, Instant scattataIl, Double latitudine, Double longitudine, String descrizione,
        boolean preferita) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static MetadatiTakeout leggi(InputStream in) throws IOException {
        return da(JSON.readTree(in));
    }

    static MetadatiTakeout da(JsonNode n) {
        if (n == null || !n.isObject()) {
            throw new IllegalArgumentException("non è un JSON di Google Foto");
        }
        Instant data = istante(n.path("photoTakenTime"));
        if (data == null) {
            data = istante(n.path("creationTime"));
        }
        Double[] posizione = posizione(n.path("geoData"));
        if (posizione == null) {
            posizione = posizione(n.path("geoDataExif"));
        }
        String descrizione = testo(n.path("description"));
        return new MetadatiTakeout(testo(n.path("title")), data, posizione == null ? null : posizione[0],
                posizione == null ? null : posizione[1], descrizione, n.path("favorited").asBoolean(false));
    }

    /** Per FotoService: la data nel fuso dell'app. */
    MetadatiEsterni esterni(ZoneId fuso) {
        LocalDateTime data = scattataIl == null ? null : LocalDateTime.ofInstant(scattataIl, fuso);
        return new MetadatiEsterni(data, latitudine, longitudine, descrizione, preferita);
    }

    /** {"timestamp": "1690000000"} (secondi, come stringa o numero); null se manca o è 0. */
    private static Instant istante(JsonNode n) {
        JsonNode t = n.path("timestamp");
        long secondi;
        if (t.isNumber()) {
            secondi = t.asLong();
        } else if (t.isTextual()) {
            try {
                secondi = Long.parseLong(t.asText().trim());
            } catch (NumberFormatException e) {
                return null;
            }
        } else {
            return null;
        }
        return secondi <= 0 ? null : Instant.ofEpochSecond(secondi);
    }

    /** Latitudine e longitudine, o null se mancano, sono 0,0 o fuori dai limiti. */
    private static Double[] posizione(JsonNode n) {
        if (!n.path("latitude").isNumber() || !n.path("longitude").isNumber()) {
            return null;
        }
        double lat = n.path("latitude").asDouble();
        double lon = n.path("longitude").asDouble();
        if (lat == 0 && lon == 0 || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return null;
        }
        return new Double[] {lat, lon};
    }

    private static String testo(JsonNode n) {
        return n.isTextual() && !n.asText().isBlank() ? n.asText().trim() : null;
    }

    /** Il titolo di un album dal suo {@code metadata.json}: {"title": "Vacanze 2019", ...}. */
    static String titoloAlbum(InputStream in) throws IOException {
        JsonNode n = JSON.readTree(in);
        return n == null ? null : testo(n.path("title"));
    }
}
