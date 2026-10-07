package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FileScartato;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Automatica;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.service.MemoriaImportazione;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;

/** Le regole in più della cartella automatica: cosa non si legge, cosa si ricorda, cosa non è un errore. */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class ImportazioneAutomaticaTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
    }

    @Autowired
    ImportazioneCartelle importazione;

    @Autowired
    MemoriaImportazione memoria;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    private Path telefono;

    @BeforeEach
    void prepara() throws IOException {
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        memoria.dimenticaTutti();
        telefono = Files.createTempDirectory(disco, "telefono");
    }

    /** Senza attesa e senza niente in arrivo. */
    private Automatica regole() {
        return regole(file -> false, Duration.ZERO);
    }

    private Automatica regole(Predicate<Path> inArrivo, Duration attesa) {
        return new Automatica(memoria, inArrivo, Instant.now(), attesa);
    }

    private Importazione importa(Automatica regole, Registro registro) throws IOException {
        return importazione.importa(telefono, false, true, registro, file -> null, regole);
    }

    @Test
    void unFileGiaFallitoUgualeNonSiRileggeFinoAQuandoNonCambia() throws IOException {
        Path rotto = telefono.resolve("IMG_0001.jpg");
        Files.write(rotto, "non sono un jpeg".getBytes(StandardCharsets.UTF_8));

        Registro primo = new Registro();
        Importazione prima = importa(regole(), primo);
        assertThat(prima.errori()).isEqualTo(1);
        assertThat(primo.lette).containsExactly("IMG_0001.jpg");
        assertThat(memoria.elenco()).singleElement().satisfies(f -> {
            assertThat(f.percorso()).isEqualTo(rotto.toString());
            assertThat(f.motivo()).startsWith("Immagine illeggibile");
            assertThat(f.dimensione()).isEqualTo(Files.size(rotto));
        });

        // Secondo giro: non arriva nemmeno a FotoService.
        Registro secondo = new Registro();
        Importazione dopo = importa(regole(), secondo);
        assertThat(secondo.lette).isEmpty();
        assertThat(secondo.totale).isZero();
        assertThat(secondo.giaFallite).isEqualTo(1);
        assertThat(dopo.errori()).isZero();
        assertThat(importazione.daImportare(telefono, regole())).isFalse();

        // Cambiato: si rilegge, e stavolta entra.
        Files.write(rotto, immagine(Color.ORANGE));
        assertThat(importazione.daImportare(telefono, regole())).isTrue();
        Registro terzo = new Registro();
        assertThat(importa(regole(), terzo).importate()).isEqualTo(1);
        assertThat(terzo.lette).containsExactly("IMG_0001.jpg");
        assertThat(rotto).doesNotExist();
        assertThat(memoria.elenco()).isEmpty();
    }

    @Test
    void unaCopiaAMetaRicordataRipassaQuandoArrivaInteraConLaStessaData() throws IOException {
        // rclone dà alla copia la data del file su pCloud: quella a metà e quella intera hanno la stessa.
        FileTime suPCloud = FileTime.from(Instant.parse("2026-03-01T20:16:14Z"));
        Path copia = telefono.resolve("Xiaomi - DCIM - Camera - 20260301_211614_558.jpg");
        byte[] intero = immagine(Color.GREEN);
        // Il contenuto non conta: basta che l'importazione non lo sappia leggere.
        Files.write(copia, "copia a metà".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(copia, suPCloud);
        assertThat(importa(regole(), new Registro()).errori()).isEqualTo(1);
        assertThat(memoria.elenco()).singleElement()
                .satisfies(f -> assertThat(f.motivo()).startsWith("Immagine illeggibile"));
        Registro ricordato = new Registro();
        importa(regole(), ricordato);
        assertThat(ricordato.giaFallite).isEqualTo(1);

        // La sincronizzazione toglie la copia a metà e la ricopia intera, con lo stesso nome e la stessa data.
        Files.delete(copia);
        Files.write(copia, intero);
        Files.setLastModifiedTime(copia, suPCloud);

        Registro dopo = new Registro();
        assertThat(importa(regole(), dopo).importate()).isEqualTo(1);
        assertThat(dopo.lette).containsExactly(copia.getFileName().toString());
        assertThat(copia).doesNotExist();
        assertThat(memoria.elenco()).isEmpty();
    }

    @Test
    void unFileCifratoDaLifetimeCloudLoDiceEContaUnaVoltaSola() throws IOException {
        byte[] cifrato = new byte[4096];
        System.arraycopy("LCB2".getBytes(StandardCharsets.US_ASCII), 0, cifrato, 0, 4);
        Files.write(telefono.resolve("IMG_20250929_122800.jpg"), cifrato);
        Files.write(telefono.resolve("VID_20250929_122900.mp4"), cifrato);

        Importazione prima = importa(regole(), new Registro());
        assertThat(prima.errori()).isEqualTo(2);
        assertThat(prima.messaggi()).allSatisfy(m -> assertThat(m).contains(FotoService.CIFRATO_LIFETIME));
        assertThat(memoria.elenco()).extracting(FileScartato::motivo).containsOnly(FotoService.CIFRATO_LIFETIME);

        Registro secondo = new Registro();
        assertThat(importa(regole(), secondo).errori()).isZero();
        assertThat(secondo.giaFallite).isEqualTo(2);
        assertThat(repository.count()).isZero();
    }

    @Test
    void unFileCheSparisceDuranteIlGiroNonEUnErrore() throws IOException {
        Files.write(telefono.resolve("IMG_0001.png"), immagine(Color.RED));
        Path secondo = telefono.resolve("IMG_0002.png");
        Files.write(secondo, immagine(Color.GREEN));
        Registro registro = new Registro() {
            @Override
            public void fatta(Path file, Caricamento esito, boolean rimossa) {
                super.fatta(file, esito, rimossa);
                // Qualcun altro lo toglie mentre si legge il primo.
                try {
                    Files.deleteIfExists(secondo);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        };

        Importazione esito = importa(regole(), registro);

        assertThat(esito.errori()).isZero();
        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.rimossi()).isEqualTo(2);
        assertThat(registro.sparite).containsExactly("IMG_0002.png");
        assertThat(memoria.elenco()).isEmpty();
    }

    @Test
    void unFileAncoraInCopiaSiLasciaAlGiroDopo() throws IOException {
        Path video = telefono.resolve("Pixel - IMG_0001.png");
        Files.write(video, immagine(Color.BLUE));

        Registro registro = new Registro();
        Importazione esito = importa(regole(file -> file.equals(video), Duration.ZERO), registro);

        assertThat(esito.trovate()).isZero();
        assertThat(esito.errori()).isZero();
        assertThat(registro.inArrivo).isEqualTo(1);
        assertThat(video).exists();
        assertThat(importazione.daImportare(telefono, regole(file -> file.equals(video), Duration.ZERO))).isFalse();
        assertThat(memoria.elenco()).isEmpty();

        // Copia finita: al giro dopo entra.
        assertThat(importa(regole(), new Registro()).importate()).isEqualTo(1);
        assertThat(video).doesNotExist();
    }

    @Test
    void unFileAppenaModificatoOINascostoAspetta() throws IOException {
        Path nuovo = telefono.resolve("IMG_0001.png");
        Files.write(nuovo, immagine(Color.YELLOW));
        Files.write(telefono.resolve(".IMG_0002.png.partial"), new byte[] {1, 2});
        Files.write(telefono.resolve(".IMG_0003.png"), new byte[] {1, 2});

        Automatica conAttesa = regole(file -> false, Duration.ofMinutes(10));
        Registro registro = new Registro();
        Importazione esito = importa(conAttesa, registro);

        assertThat(esito.trovate()).isZero();
        assertThat(registro.inArrivo).isEqualTo(1);
        assertThat(importazione.daImportare(telefono, conAttesa)).isFalse();

        // Passata l'attesa entra; i nascosti non si toccano mai.
        Automatica dopo = new Automatica(memoria, file -> false, Instant.now().plus(Duration.ofMinutes(11)),
                Duration.ofMinutes(10));
        assertThat(importa(dopo, new Registro()).importate()).isEqualTo(1);
        assertThat(telefono.resolve(".IMG_0003.png")).exists();
        assertThat(memoria.elenco()).isEmpty();
    }

    @Test
    void soloGliErroriDelFileSiRicordano() {
        assertThat(FotoService.erroreDelFile("Immagine illeggibile (Nessun lettore per questa immagine)")).isTrue();
        assertThat(FotoService.erroreDelFile("Video illeggibile (ffmpeg non ha estratto il fotogramma)")).isTrue();
        assertThat(FotoService.erroreDelFile(FotoService.CIFRATO_LIFETIME)).isTrue();
        // Il cloud che non risponde o un programma che manca possono passare: si riprova.
        assertThat(FotoService.erroreDelFile("File illeggibile (Input/output error)")).isFalse();
        assertThat(FotoService.erroreDelFile("Per i video servono ffmpeg e ffprobe sul server")).isFalse();
        assertThat(FotoService.erroreDelFile(null)).isFalse();
    }

    @Test
    void lImportazioneManualeNonRicordaNiente() throws IOException {
        Files.write(telefono.resolve("IMG_0001.jpg"), "rotta".getBytes(StandardCharsets.UTF_8));

        importazione.importa(telefono, false, true, Avanzamento.NESSUNO, file -> null);
        Importazione ancora = importazione.importa(telefono, false, true, Avanzamento.NESSUNO, file -> null);

        assertThat(ancora.errori()).isEqualTo(1);
        assertThat(memoria.elenco()).isEmpty();
    }

    /** Segue un giro: i file passati a FotoService, quelli spariti, i saltati. */
    static class Registro implements Avanzamento {
        final List<String> lette = new ArrayList<>();
        final List<String> sparite = new ArrayList<>();
        int totale = -1;
        int giaFallite;
        int inArrivo;

        @Override
        public void inizio(int totale) {
            this.totale = totale;
        }

        @Override
        public void saltate(int giaFallite, int inArrivo) {
            this.giaFallite = giaFallite;
            this.inArrivo = inArrivo;
        }

        @Override
        public void fatta(Path file, Caricamento esito, boolean rimossa) {
            lette.add(file.getFileName().toString());
            assertThat(esito.esito()).isIn(Esito.CARICATA, Esito.DUPLICATA, Esito.ERRORE);
        }

        @Override
        public void sparita(Path file) {
            sparite.add(file.getFileName().toString());
        }
    }

    private static byte[] immagine(Color colore) throws IOException {
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
