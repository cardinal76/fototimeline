package it.fototimeline.google;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.fototimeline.service.FotoService.MetadatiEsterni;

/** L'abbinamento file ↔ JSON di Google Takeout e la lettura del JSON, coi nomi strani veri. */
class SidecarTakeoutTest {

    private static Map<String, String> abbina(List<String> media, List<String> json) {
        return SidecarTakeout.abbina(media, json);
    }

    @Test
    void nomeConJsonClassico() {
        assertThat(abbina(List.of("IMG_0001.jpg"), List.of("IMG_0001.jpg.json")))
                .containsExactly(Map.entry("IMG_0001.jpg", "IMG_0001.jpg.json"));
    }

    @Test
    void supplementalMetadataAncheTroncato() {
        assertThat(abbina(
                List.of("IMG_0001.jpg", "IMG_0002.HEIC", "IMG_0003.png", "PXL_20230101_101010123.jpg"),
                List.of("IMG_0001.jpg.supplemental-metadata.json", "IMG_0002.HEIC.supplemental-metad.json",
                        "IMG_0003.png.suppl.json", "PXL_20230101_101010123.jpg.supplemental-me.json")))
                .containsEntry("IMG_0001.jpg", "IMG_0001.jpg.supplemental-metadata.json")
                .containsEntry("IMG_0002.HEIC", "IMG_0002.HEIC.supplemental-metad.json")
                .containsEntry("IMG_0003.png", "IMG_0003.png.suppl.json")
                .containsEntry("PXL_20230101_101010123.jpg", "PXL_20230101_101010123.jpg.supplemental-me.json");
    }

    @Test
    void nomiLunghiTroncatiA46O47Caratteri() {
        // Il nome del JSON (senza .json) tagliato a 46 caratteri, dentro il nome della foto.
        String foto = "Screenshot_20200101-123456_com.whatsapp.android.jpg"; // 51 caratteri
        String json = foto.substring(0, 46) + ".json";
        // Tagliato a 47 col supplemental: il nome della foto c'è tutto, il suffisso quasi niente.
        String foto2 = "WhatsApp Image 2021-07-04 at 18.22.31 (1)xy.jpeg";
        String json2 = (foto2 + ".supplemental-metadata").substring(0, 47) + ".json";
        assertThat(abbina(List.of(foto, foto2), List.of(json, json2)))
                .containsEntry(foto, json)
                .containsEntry(foto2, json2);
    }

    @Test
    void anticoSenzaEstensione() {
        assertThat(abbina(List.of("IMG_0001.jpg"), List.of("IMG_0001.json")))
                .containsEntry("IMG_0001.jpg", "IMG_0001.json");
    }

    @Test
    void ilNumeroTraParentesiVaDopoLEstensioneNelJson() {
        assertThat(abbina(
                List.of("IMG_0001.jpg", "IMG_0001(1).jpg", "IMG_0001(2).jpg", "IMG_0005(1).jpg"),
                List.of("IMG_0001.jpg.json", "IMG_0001.jpg(1).json", "IMG_0001.jpg(2).json",
                        "IMG_0005.jpg.supplemental-metadata(1).json")))
                .containsEntry("IMG_0001.jpg", "IMG_0001.jpg.json")
                .containsEntry("IMG_0001(1).jpg", "IMG_0001.jpg(1).json")
                .containsEntry("IMG_0001(2).jpg", "IMG_0001.jpg(2).json")
                .containsEntry("IMG_0005(1).jpg", "IMG_0005.jpg.supplemental-metadata(1).json");
    }

    @Test
    void ilNumeroNelNomeDelJsonSeGoogleLoHaMessoLi() {
        assertThat(abbina(List.of("IMG_0001(1).jpg"), List.of("IMG_0001(1).jpg.json", "IMG_0001.jpg.json")))
                .containsEntry("IMG_0001(1).jpg", "IMG_0001(1).jpg.json");
    }

    @Test
    void leModificateUsanoIlJsonDellOriginale() {
        assertThat(abbina(
                List.of("IMG_0001.jpg", "IMG_0001-edited.jpg", "IMG_0002-modificato.jpg", "IMG_0003(1)-edited.jpg"),
                List.of("IMG_0001.jpg.json", "IMG_0002.jpg.supplemental-metadata.json", "IMG_0003(1).jpg.json")))
                .containsEntry("IMG_0001-edited.jpg", "IMG_0001.jpg.json")
                .containsEntry("IMG_0002-modificato.jpg", "IMG_0002.jpg.supplemental-metadata.json")
                .containsEntry("IMG_0003(1)-edited.jpg", "IMG_0003(1).jpg.json");
        assertThat(abbina(List.of("IMG_0004-edited(1).jpg"), List.of("IMG_0004.jpg(1).json", "IMG_0004.jpg.json")))
                .containsEntry("IMG_0004-edited(1).jpg", "IMG_0004.jpg(1).json");
        assertThat(SidecarTakeout.senzaModifica("IMG_1-EDITED.JPG")).isEqualTo("IMG_1.JPG");
        assertThat(SidecarTakeout.senzaModifica("IMG_1.jpg")).isNull();
    }

    @Test
    void liveEFotoInMovimentoPrendonoIlJsonDellaFoto() {
        assertThat(abbina(
                List.of("IMG_0001.HEIC", "IMG_0001.MOV", "PXL_20230101_101010123.MP.jpg", "PXL_20230101_101010123.MP",
                        "PXL_20230101_202020456.jpg", "PXL_20230101_202020456.MP4"),
                List.of("IMG_0001.HEIC.json", "PXL_20230101_101010123.MP.jpg.supplemental-metadata.json",
                        "PXL_20230101_202020456.jpg.json")))
                .containsEntry("IMG_0001.MOV", "IMG_0001.HEIC.json")
                .containsEntry("PXL_20230101_101010123.MP", "PXL_20230101_101010123.MP.jpg.supplemental-metadata.json")
                .containsEntry("PXL_20230101_202020456.MP4", "PXL_20230101_202020456.jpg.json");
    }

    @Test
    void ilVideoColSuoJsonTieneIlSuo() {
        assertThat(abbina(List.of("IMG_0001.HEIC", "IMG_0001.MOV"), List.of("IMG_0001.HEIC.json", "IMG_0001.MOV.json")))
                .containsEntry("IMG_0001.MOV", "IMG_0001.MOV.json");
    }

    @Test
    void nomiSimiliNonSiConfondonoEIJsonDellAlbumNonContano() {
        assertThat(abbina(List.of("IMG_12.jpg", "IMG_1.jpg", "senza.jpg"),
                List.of("IMG_1.jpg.json", "metadata.json", "metadati.json", "print-subscriptions.json")))
                .containsOnlyKeys("IMG_1.jpg")
                .containsEntry("IMG_1.jpg", "IMG_1.jpg.json");
    }

    @Test
    void cartelleAnnoEAlbum() {
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto/Photos from 2019", null)).isNull();
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto/Foto dal 2019", "Foto dal 2019")).isNull();
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto/Vacanze", null)).isEqualTo("Vacanze");
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto/Vacanze(1)", "Vacanze al mare"))
                .isEqualTo("Vacanze al mare");
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto/Cestino", null)).isNull();
        assertThat(ImportazioneTakeout.album("Takeout/Google Foto", null)).isNull();
        assertThat(ImportazioneTakeout.nomeInArchivio("PXL_1.MP")).isEqualTo("PXL_1.mp4");
        assertThat(ImportazioneTakeout.nomeInArchivio("IMG_1.JPG")).isEqualTo("IMG_1.JPG");
        assertThat(ImportazioneTakeout.nomeInArchivio("film.avi")).isNull();
    }

    // ------------------------------------------------------------ il JSON

    private static MetadatiTakeout leggi(String json) throws IOException {
        return MetadatiTakeout.leggi(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void leggeDataPosizioneDescrizionePreferita() throws IOException {
        MetadatiTakeout m = leggi("""
                {"title": "IMG_0001.jpg", "description": "Al mare con la nonna",
                 "creationTime": {"timestamp": "1700000000"},
                 "photoTakenTime": {"timestamp": "1690000000", "formatted": "22 lug 2023, 04:26:40 UTC"},
                 "geoData": {"latitude": 41.9028, "longitude": 12.4964, "altitude": 20.0},
                 "geoDataExif": {"latitude": 45.0, "longitude": 9.0},
                 "favorited": true}
                """);
        assertThat(m.titolo()).isEqualTo("IMG_0001.jpg");
        assertThat(m.scattataIl()).isEqualTo(Instant.ofEpochSecond(1690000000));
        assertThat(m.latitudine()).isEqualTo(41.9028);
        assertThat(m.longitudine()).isEqualTo(12.4964);
        assertThat(m.descrizione()).isEqualTo("Al mare con la nonna");
        assertThat(m.preferita()).isTrue();
        MetadatiEsterni e = m.esterni(ZoneId.of("Europe/Rome"));
        // 22 luglio 2023, 04:26:40 UTC = 06:26:40 in Italia (ora legale).
        assertThat(e.scattataIl()).isEqualTo(LocalDateTime.of(2023, 7, 22, 6, 26, 40));
        assertThat(e.conPosizione()).isTrue();
    }

    @Test
    void zeroZeroNonEUnaPosizioneEGeoDataExifFaDaRiserva() throws IOException {
        MetadatiTakeout senza = leggi("""
                {"photoTakenTime": {"timestamp": "1690000000"},
                 "geoData": {"latitude": 0.0, "longitude": 0.0}, "geoDataExif": {"latitude": 0.0, "longitude": 0.0},
                 "description": "  "}
                """);
        assertThat(senza.latitudine()).isNull();
        assertThat(senza.descrizione()).isNull();
        assertThat(senza.preferita()).isFalse();
        assertThat(senza.esterni(ZoneId.of("UTC")).conPosizione()).isFalse();

        MetadatiTakeout exif = leggi("""
                {"photoTakenTime": {"timestamp": 1690000000},
                 "geoData": {"latitude": 0.0, "longitude": 0.0}, "geoDataExif": {"latitude": 45.46, "longitude": 9.19}}
                """);
        assertThat(exif.scattataIl()).isEqualTo(Instant.ofEpochSecond(1690000000));
        assertThat(exif.latitudine()).isEqualTo(45.46);
    }

    @Test
    void senzaPhotoTakenTimeValeCreationTime() throws IOException {
        assertThat(leggi("{\"creationTime\": {\"timestamp\": \"1600000000\"}}").scattataIl())
                .isEqualTo(Instant.ofEpochSecond(1600000000));
        assertThat(leggi("{\"photoTakenTime\": {\"timestamp\": \"0\"}}").scattataIl()).isNull();
        assertThat(MetadatiTakeout.titoloAlbum(new ByteArrayInputStream(
                "{\"title\":\"Vacanze 2019\",\"date\":{}}".getBytes(StandardCharsets.UTF_8)))).isEqualTo("Vacanze 2019");
    }

    @Test
    void durateDiGoogle() {
        assertThat(ClientGoogle.durata("5s", null)).hasSeconds(5);
        assertThat(ClientGoogle.durata("1.500s", null)).hasMillis(1500);
        assertThat(ClientGoogle.durata("boh", java.time.Duration.ofSeconds(3))).hasSeconds(3);
    }
}
