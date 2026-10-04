package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.OrigineData;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Risultati.Esito;

/**
 * HEIC e video con i programmi veri. Dove heif-convert o ffmpeg mancano (un PC,
 * un runner senza) questi test si saltano: nel container ci sono sempre.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class MediaImportTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    StrumentiMedia media;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> service.elimina(f.getId()));
    }

    @Test
    void heicConDataGpsMiniaturaEVistaJpeg() throws IOException {
        assumeTrue(media.heicDisponibile(), "heif-convert non installato");

        var esito = service.importa("IMG_0042.HEIC", risorsa("/foto.heic"), null, null);

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        var foto = esito.foto();
        assertThat(foto.isVideo()).isFalse();
        assertThat(foto.getContentType()).isEqualTo("image/heic");
        assertThat(foto.getScattataIl()).isEqualTo(LocalDateTime.of(2022, 6, 10, 9, 15));
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(foto.getFotocamera()).isEqualTo("Apple iPhone 15");
        assertThat(foto.getLatitudine()).isCloseTo(45.4333, org.assertj.core.data.Offset.offset(0.001));
        assertThat(foto.getLarghezza()).isEqualTo(64);
        assertThat(foto.getAltezza()).isEqualTo(48);
        assertThat(foto.getPercorso()).isEqualTo("2022/06/10/IMG_0042.heic");

        // Il browser riceve un JPEG, l'originale resta HEIC.
        assertThat(service.contentTypeVista(foto)).isEqualTo("image/jpeg");
        assertThat(leggibile(service.fileVista(foto))).isTrue();
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();

        // Vista e miniatura si rifanno se mancano.
        Files.delete(service.fileVista(foto));
        Files.delete(service.fileMiniatura(foto));
        assertThat(leggibile(service.fileVista(foto))).isTrue();
        assertThat(leggibile(service.fileMiniatura(foto))).isTrue();
    }

    @Test
    void videoMp4ConDataUtcPosizioneEFotogramma() throws IOException {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");

        var esito = service.importa("VID_0001.mp4", risorsa("/video.mp4"), null, "Viaggi");

        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        var video = esito.foto();
        assertThat(video.isVideo()).isTrue();
        assertThat(video.getContentType()).isEqualTo("video/mp4");
        assertThat(video.getDurata()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.2));
        assertThat(video.getLarghezza()).isEqualTo(160);
        assertThat(video.getAltezza()).isEqualTo(90);
        assertThat(video.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(video.getGiorno()).isEqualTo("2021-05-01");
        assertThat(video.getLatitudine()).isEqualTo(41.9028);
        assertThat(leggibile(service.fileMiniatura(video))).isTrue();
        // Il video si serve così com'è.
        assertThat(service.fileVista(video)).isEqualTo(service.fileOriginale(video));

        Files.delete(service.fileMiniatura(video));
        assertThat(leggibile(service.fileMiniatura(video))).isTrue();
    }

    @Test
    void videoMovConLaDataDelliPhone() throws IOException {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");

        var video = service.importa("IMG_7.MOV", risorsa("/video.mov"), null, null).foto();

        assertThat(video.getScattataIl()).isEqualTo(LocalDateTime.of(2023, 7, 14, 21, 5));
        assertThat(video.getFotocamera()).isEqualTo("Apple iPhone 15");
        assertThat(video.getContentType()).isEqualTo("video/quicktime");
    }

    @Test
    void unFileFintoNonEntra() throws IOException {
        Path finto = Files.createTempFile("finto", ".mp4");
        Files.writeString(finto, "non è un video");
        var esito = service.importa("finto.mp4", finto, null, null);
        assertThat(esito.esito()).isEqualTo(Esito.ERRORE);
        assertThat(repository.count()).isZero();
    }

    private Path risorsa(String nome) throws IOException {
        Path copia = Files.createTempFile("risorsa", nome.substring(nome.lastIndexOf('.')));
        try (var in = getClass().getResourceAsStream(nome)) {
            Files.copy(in, copia, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return copia;
    }

    private static boolean leggibile(Path jpeg) throws IOException {
        BufferedImage img = ImageIO.read(jpeg.toFile());
        return img != null && img.getWidth() > 0;
    }
}
