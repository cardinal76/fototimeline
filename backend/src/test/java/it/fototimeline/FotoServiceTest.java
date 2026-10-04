package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.OrigineData;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.FotoService.Operazione;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.VoceMese;

@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class FotoServiceTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    private static final FiltroFoto TUTTO = new FiltroFoto(null, null, null, null, null, null);

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> service.elimina(f.getId()));
    }

    @Test
    void leggeDataFotocameraGpsEOrientamentoDallExif() throws IOException {
        byte[] jpg = getClass().getResourceAsStream("/con-exif.jpg").readAllBytes();

        var esito = service.importa("IMG_0001.JPG", jpg, Instant.parse("2025-01-01T00:00:00Z"), null);

        assertThat(esito.esito()).isEqualTo(Esito.CARICATA);
        var foto = esito.foto();
        assertThat(foto.getScattataIl()).isEqualTo(LocalDateTime.of(2021, 7, 14, 18, 30, 5));
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(foto.getGiorno()).isEqualTo("2021-07-14");
        assertThat(foto.getFotocamera()).isEqualTo("Apple iPhone 13");
        assertThat(foto.getLatitudine()).isEqualTo(41.9);
        assertThat(foto.getLongitudine()).isEqualTo(12.5);
        // 64x32 con orientamento 6: in verticale.
        assertThat(foto.getLarghezza()).isEqualTo(32);
        assertThat(foto.getAltezza()).isEqualTo(64);
        assertThat(foto.getPercorso()).isEqualTo("2021/07/14/IMG_0001.jpg");
        assertThat(service.fileOriginale(foto)).exists();
        assertThat(service.fileMiniatura(foto)).exists();
    }

    @Test
    void nonDuplicaLaStessaFoto() throws IOException {
        byte[] png = immagine(Color.RED, "png");
        assertThat(service.importa("a.png", png, null, null).esito()).isEqualTo(Esito.CARICATA);
        assertThat(service.importa("copia.png", png, null, null).esito()).isEqualTo(Esito.DUPLICATA);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void rifiutaFileCheNonSonoImmagini() {
        assertThat(service.importa("nota.txt", "ciao".getBytes(), null, null).esito()).isEqualTo(Esito.ERRORE);
        assertThat(service.importa("finta.jpg", "ciao".getBytes(), null, null).esito()).isEqualTo(Esito.ERRORE);
        assertThat(repository.count()).isZero();
    }

    @Test
    void timelineRaggruppaPerMeseEFiltraPerDate() throws IOException {
        importa(Color.RED, "2024-05-03T10:00:00Z");
        importa(Color.GREEN, "2024-05-20T10:00:00Z");
        importa(Color.BLUE, "2023-12-31T10:00:00Z");

        List<VoceMese> mesi = service.timeline(TUTTO);
        assertThat(mesi).containsExactly(new VoceMese(2024, 5, 2), new VoceMese(2023, 12, 1));

        var pagina = service.cerca(TUTTO, 0, 2);
        assertThat(pagina.totale()).isEqualTo(3);
        assertThat(pagina.altre()).isTrue();
        assertThat(pagina.foto()).extracting(f -> f.getGiorno()).containsExactly("2024-05-20", "2024-05-03");

        var finoAGennaio = service.cerca(new FiltroFoto(null, null, null, null, null, LocalDate.of(2024, 1, 31)), 0, 10);
        assertThat(finoAGennaio.foto()).extracting(f -> f.getGiorno()).containsExactly("2023-12-31");
    }

    @Test
    void modificaCercaEOperazioniMultiple() throws IOException {
        var a = importa(Color.RED, "2024-05-03T10:00:00Z");
        var b = importa(Color.GREEN, "2024-05-04T10:00:00Z");

        service.modifica(a, new Modifica("Mare a Sperlonga", null, List.of(" Mare ", "estate", ""), "Vacanze 2024",
                true, LocalDateTime.of(2024, 8, 10, 12, 0)));
        var modificata = service.trova(a).orElseThrow();
        assertThat(modificata.getTag()).containsExactly("estate", "mare");
        assertThat(modificata.getGiorno()).isEqualTo("2024-08-10");
        assertThat(modificata.getOrigineData()).isEqualTo(OrigineData.MANUALE);

        assertThat(service.cerca(new FiltroFoto("sperlonga", null, null, null, null, null), 0, 10).totale()).isEqualTo(1);
        assertThat(service.cerca(new FiltroFoto(null, "MARE", null, null, null, null), 0, 10).totale()).isEqualTo(1);
        assertThat(service.cerca(new FiltroFoto(null, null, null, true, null, null), 0, 10).totale()).isEqualTo(1);

        importa(Color.BLUE, "2024-05-05T10:00:00Z"); // senza tag: non deve rompere l'elenco
        assertThat(service.tag()).containsExactly("estate", "mare");

        service.operazioneMultipla(List.of(a, b), Operazione.AGGIUNGI_TAG, "famiglia, Mare");
        assertThat(service.tag()).containsExactly("estate", "famiglia", "mare");
        service.operazioneMultipla(List.of(a, b), Operazione.IMPOSTA_ALBUM, "Roma");
        assertThat(service.album()).containsExactly("Roma");

        service.operazioneMultipla(List.of(a, b), Operazione.ELIMINA, null);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void importaUnaCartellaUsandoLeSottocartelleComeAlbum(@TempDir Path cartella) throws IOException {
        Files.createDirectories(cartella.resolve("Natale"));
        Path foto = cartella.resolve("Natale/albero.png");
        Files.write(foto, immagine(Color.GREEN, "png"));
        Files.setLastModifiedTime(foto, FileTime.from(Instant.parse("2022-12-25T09:00:00Z")));
        Files.write(cartella.resolve("sciolta.jpg"), immagine(Color.BLUE, "jpg"));
        Files.writeString(cartella.resolve("leggimi.txt"), "non è una foto");

        var esito = service.importaCartella(cartella, true);

        assertThat(esito.trovate()).isEqualTo(2);
        assertThat(esito.importate()).isEqualTo(2);
        assertThat(service.album()).containsExactly("Natale");
        var natale = service.cerca(new FiltroFoto(null, null, "Natale", null, null, null), 0, 10).foto().getFirst();
        assertThat(natale.getOrigineData()).isEqualTo(OrigineData.FILE);
        assertThat(natale.getScattataIl().getYear()).isEqualTo(2022);

        assertThat(service.importaCartella(cartella, true).duplicate()).isEqualTo(2);
    }

    @Test
    void organizzaGliOriginaliInCartellePerGiorno() throws IOException {
        var primo = service.importa("IMG_0001.png", immagine(Color.RED, "png"), Instant.parse("2024-08-15T10:00:00Z"), null).foto();
        var omonimo = service.importa("IMG_0001.png", immagine(Color.GREEN, "png"), Instant.parse("2024-08-15T11:00:00Z"), null).foto();
        var altroGiorno = service.importa("C:\\Foto\\IMG_0001.png", immagine(Color.BLUE, "png"), Instant.parse("2024-08-16T10:00:00Z"), null).foto();

        assertThat(primo.getPercorso()).isEqualTo("2024/08/15/IMG_0001.png");
        assertThat(omonimo.getPercorso()).isEqualTo("2024/08/15/IMG_0001 (2).png");
        assertThat(altroGiorno.getPercorso()).isEqualTo("2024/08/16/IMG_0001.png");
        assertThat(archivio.resolve("2024/08/15/IMG_0001 (2).png")).exists();

        // Cambiando la data, il file cambia cartella e quella vuota sparisce.
        service.modifica(altroGiorno.getId(), new Modifica(null, null, List.of(), null, null, LocalDateTime.of(2023, 1, 2, 9, 0)));
        assertThat(service.trova(altroGiorno.getId()).orElseThrow().getPercorso()).isEqualTo("2023/01/02/IMG_0001.png");
        assertThat(archivio.resolve("2023/01/02/IMG_0001.png")).exists();
        assertThat(archivio.resolve("2024/08/16")).doesNotExist();

        service.elimina(primo.getId());
        service.elimina(omonimo.getId());
        assertThat(archivio.resolve("2024")).doesNotExist();
    }

    @Test
    void riordinaUnArchivioConLaVecchiaStruttura() throws IOException {
        var foto = service.importa("vecchia.png", immagine(Color.RED, "png"), Instant.parse("2022-03-05T10:00:00Z"), null).foto();
        Path vecchio = archivio.resolve("originali/2022/03/" + foto.getId() + ".png");
        Files.createDirectories(vecchio.getParent());
        Files.move(archivio.resolve(foto.getPercorso()), vecchio);
        foto.setPercorso("originali/2022/03/" + foto.getId() + ".png");
        repository.save(foto);

        assertThat(service.riordinaArchivio()).isEqualTo(1);
        assertThat(service.trova(foto.getId()).orElseThrow().getPercorso())
                .isEqualTo("2022/03/05/vecchia.png");
        assertThat(archivio.resolve("originali")).doesNotExist();
        assertThat(service.riordinaArchivio()).isZero();
    }

    private String importa(Color colore, String quando) throws IOException {
        return service.importa("foto.png", immagine(colore, "png"), Instant.parse(quando), null).foto().getId();
    }

    private static byte[] immagine(Color colore, String formato) throws IOException {
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }
}
