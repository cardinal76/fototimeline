package it.fototimeline.service;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.TimeZone;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.drew.imaging.ImageMetadataReader;
import com.drew.lang.GeoLocation;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;

@Component
public class EstrattoreMetadati {

    private static final Logger log = LoggerFactory.getLogger(EstrattoreMetadati.class);
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    public MetadatiFoto estrai(byte[] contenuto) {
        try {
            return estrai(ImageMetadataReader.readMetadata(new ByteArrayInputStream(contenuto), contenuto.length));
        } catch (Exception e) {
            log.debug("Metadati illeggibili: {}", e.getMessage());
            return MetadatiFoto.VUOTI;
        }
    }

    /** Anche HEIC: metadata-extractor legge l'EXIF dentro i file HEIF. */
    public MetadatiFoto estrai(Path file) {
        try {
            return estrai(ImageMetadataReader.readMetadata(file.toFile()));
        } catch (Exception e) {
            log.debug("Metadati illeggibili: {}", e.getMessage());
            return MetadatiFoto.VUOTI;
        }
    }

    private MetadatiFoto estrai(Metadata metadata) {
        var ifd0 = metadata.getFirstDirectoryOfType(ExifIFD0Directory.class);
        var sub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
        var gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);

        // L'EXIF scrive l'ora locale senza fuso: letta e riconvertita in UTC resta quella scritta.
        Date data = null;
        if (sub != null) {
            data = sub.getDateOriginal(UTC);
            if (data == null) {
                data = sub.getDateDigitized(UTC);
            }
        }
        if (data == null && ifd0 != null) {
            data = ifd0.getDate(ExifIFD0Directory.TAG_DATETIME, UTC);
        }
        LocalDateTime scattataIl = data == null ? null : LocalDateTime.ofInstant(data.toInstant(), ZoneOffset.UTC);

        String fotocamera = null;
        int orientamento = 1;
        if (ifd0 != null) {
            fotocamera = fotocamera(ifd0.getString(ExifIFD0Directory.TAG_MAKE), ifd0.getString(ExifIFD0Directory.TAG_MODEL));
            Integer o = ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            if (o != null) {
                orientamento = o;
            }
        }

        Double lat = null;
        Double lon = null;
        GeoLocation posizione = gps == null ? null : gps.getGeoLocation();
        if (posizione != null && !posizione.isZero()) {
            lat = posizione.getLatitude();
            lon = posizione.getLongitude();
        }

        return new MetadatiFoto(scattataIl, fotocamera, lat, lon, orientamento);
    }

    /** "Apple" + "iPhone 13" → "Apple iPhone 13"; "Canon" + "Canon EOS R6" → "Canon EOS R6". */
    static String fotocamera(String marca, String modello) {
        marca = marca == null ? "" : marca.trim();
        modello = modello == null ? "" : modello.trim();
        if (modello.isEmpty()) {
            return marca.isEmpty() ? null : marca;
        }
        if (marca.isEmpty() || modello.toLowerCase().startsWith(marca.toLowerCase().split(" ")[0])) {
            return modello;
        }
        return marca + " " + modello;
    }
}
