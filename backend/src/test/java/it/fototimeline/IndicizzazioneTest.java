package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;

/** "Indicizza archivio": le foto messe nell'archivio da fuori (lo script che carica nel cloud). */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class IndicizzazioneTest {

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
    ImportazioneCartelle importazione;

    @Autowired
    LavoriImportazione lavori;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        // Anche i file rimasti senza scheda; le miniature restano.
        try (Stream<Path> s = Files.list(archivio)) {
            for (Path p : s.filter(p -> !p.getFileName().toString().equals(".miniature")).toList()) {
                cancellaTutto(p);
            }
        }
    }

    @Test
    void unaFotoMessaAManoEntraSenzaMuoversi() throws IOException {
        Path file = scrivi("2024/05/03/IMG_0001.jpg", immagine(Color.RED, "jpg"));

        Importazione esito = indicizza();

        assertThat(esito.trovate()).isEqualTo(1);
        assertThat(esito.importate()).isEqualTo(1);
        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getPercorso()).isEqualTo("2024/05/03/IMG_0001.jpg");
        assertThat(foto.getNomeOriginale()).isEqualTo("IMG_0001.jpg");
        assertThat(foto.getGiorno()).isEqualTo("2024-05-03");
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.CARTELLA);
        assertThat(file).exists();
        assertThat(service.fileOriginale(foto)).isEqualTo(file);
        assertThat(archivio.resolve(".miniature/" + foto.getId() + ".jpg")).exists();

        // Una seconda volta non cambia niente.
        Importazione ancora = indicizza();
        assertThat(ancora.importate()).isZero();
        assertThat(ancora.duplicate()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void conLExifDiUnAltroGiornoVaNellaCartellaGiusta() throws IOException {
        Path sbagliata = scrivi("2024/01/01/con-exif.jpg", getClass().getResourceAsStream("/con-exif.jpg").readAllBytes());

        assertThat(indicizza().importate()).isEqualTo(1);

        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(foto.getPercorso()).isEqualTo("2021/07/14/con-exif.jpg");
        assertThat(archivio.resolve("2021/07/14/con-exif.jpg")).exists();
        assertThat(sbagliata).doesNotExist();
        // La cartella rimasta vuota sparisce.
        assertThat(archivio.resolve("2024")).doesNotExist();
    }

    @Test
    void fuoriDalleCartelleDelGiornoUsaLaDataDelFile() throws IOException {
        Path sparsa = scrivi("Da sistemare/sparsa.png", immagine(Color.BLUE, "png"));
        Files.setLastModifiedTime(sparsa, FileTime.from(Instant.parse("2019-03-08T10:00:00Z")));

        assertThat(indicizza().importate()).isEqualTo(1);

        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.FILE);
        assertThat(foto.getGiorno()).isEqualTo("2019-03-08");
        assertThat(foto.getPercorso()).isEqualTo("2019/03/08/sparsa.png");
        assertThat(sparsa).doesNotExist();
    }

    @Test
    void saltaLeCartelleColPunto() throws IOException {
        scrivi(".backup/vecchia.jpg", immagine(Color.GREEN, "jpg"));
        scrivi(".miniature/finta.jpg", immagine(Color.YELLOW, "jpg"));
        scrivi("2024/05/03/._IMG_0001.jpg", new byte[] {0, 5, 22, 7});
        scrivi("2024/05/03/note.txt", "non è una foto".getBytes());

        Importazione esito = indicizza();

        assertThat(esito.trovate()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void unaCopiaIdenticaNonDiventaUnaSecondaScheda() throws IOException {
        byte[] png = immagine(Color.MAGENTA, "png");
        var caricata = service.importa("a.png", png, Instant.parse("2024-05-03T10:00:00Z"), null);
        assertThat(caricata.esito()).isEqualTo(Esito.CARICATA);
        Path copia = scrivi("2023/12/25/copia.png", png);

        Importazione esito = indicizza();

        assertThat(esito.importate()).isZero();
        assertThat(esito.duplicate()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getPercorso()).isEqualTo(caricata.foto().getPercorso());
        // Il file non si tocca.
        assertThat(copia).exists();
    }

    @Test
    void unFileIllegibileEUnErroreENonFermaIlResto() throws IOException {
        Path rotta = scrivi("2024/05/03/rotta.jpg", "non è un JPEG".getBytes());
        scrivi("2024/05/04/buona.jpg", immagine(Color.ORANGE, "jpg"));

        Importazione esito = indicizza();

        assertThat(esito.errori()).isEqualTo(1);
        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.messaggi()).singleElement().asString().contains("rotta.jpg");
        assertThat(rotta).exists();
    }

    @Test
    void inSottofondoComeUnImportazione() throws Exception {
        scrivi("2024/05/03/a.jpg", immagine(Color.RED, "jpg"));
        scrivi("2024/05/04/b.jpg", immagine(Color.GREEN, "jpg"));

        StatoLavoro partito = lavori.avviaIndicizzazione();
        assertThat(partito.origine()).isEqualTo(Origine.INDICIZZAZIONE);

        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.origine()).isEqualTo(Origine.INDICIZZAZIONE);
        assertThat(fine.trovate()).isEqualTo(2);
        assertThat(fine.importate()).isEqualTo(2);
        assertThat(repository.findAll()).extracting(Foto::getGiorno)
                .containsExactlyInAnyOrder("2024-05-03", "2024-05-04");
    }

    private Importazione indicizza() throws IOException {
        return importazione.indicizza(Avanzamento.NESSUNO);
    }

    private static Path scrivi(String percorso, byte[] contenuto) throws IOException {
        Path p = archivio.resolve(percorso);
        Files.createDirectories(p.getParent());
        return Files.write(p, contenuto);
    }

    private static void cancellaTutto(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }

    private StatoLavoro aspettaFine() throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(limite)) {
            var stato = lavori.corrente();
            if (stato.isEmpty() || stato.get().stato() != Stato.IN_CORSO) {
                return stato.orElse(null);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Indicizzazione ancora in corso dopo 30 secondi");
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
