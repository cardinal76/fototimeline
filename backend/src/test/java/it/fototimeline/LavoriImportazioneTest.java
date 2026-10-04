package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class LavoriImportazioneTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
        r.add("fototimeline.importazione-automatica.cartella", () -> disco.resolve("telefono").toString());
    }

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        Files.createDirectories(disco.resolve("telefono"));
    }

    @Test
    void importaInSottofondoESegueLAvanzamento(@TempDir Path cartella) throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.write(cartella.resolve("foto" + i + ".png"), immagine(new Color(i * 40, 90, 120)));
        }
        Files.writeString(cartella.resolve("note.txt"), "non è una foto");

        StatoLavoro partito = lavori.avvia(cartella, false, false, Origine.MANUALE);
        assertThat(partito.stato()).isEqualTo(Stato.IN_CORSO);

        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.trovate()).isEqualTo(5);
        assertThat(fine.fatte()).isEqualTo(5);
        assertThat(fine.importate()).isEqualTo(5);
        assertThat(fine.finitoIl()).isAfterOrEqualTo(fine.iniziatoIl());
        assertThat(repository.count()).isEqualTo(5);
    }

    @Test
    void unaAllaVolta(@TempDir Path cartella) throws Exception {
        for (int i = 0; i < 40; i++) {
            Files.write(cartella.resolve("f" + i + ".png"), immagine(new Color(i * 6, i * 3, 200)));
        }
        lavori.avvia(cartella, false, false, Origine.MANUALE);
        assertThatThrownBy(() -> lavori.avvia(cartella, false, false, Origine.MANUALE))
                .isInstanceOf(IllegalStateException.class);
        lavori.annulla();
        assertThat(aspettaFine().stato()).isIn(Stato.ANNULLATA, Stato.FINITA);
    }

    @Test
    void laCartellaAutomaticaSiSvuotaNellArchivio() throws Exception {
        Path telefono = disco.resolve("telefono");
        Files.createDirectories(telefono.resolve("WhatsApp"));
        Files.write(telefono.resolve("IMG_1.png"), immagine(Color.RED));
        Files.write(telefono.resolve("WhatsApp/IMG_2.png"), immagine(Color.GREEN));

        lavori.controllaCartellaAutomatica();
        StatoLavoro fine = aspettaFine();

        assertThat(fine.origine()).isEqualTo(Origine.AUTOMATICA);
        assertThat(fine.importate()).isEqualTo(2);
        assertThat(fine.rimossi()).isEqualTo(2);
        assertThat(telefono).exists().isEmptyDirectory();
        assertThat(service.album()).containsExactly("WhatsApp");

        // Vuota: il controllo successivo non fa partire niente.
        lavori.controllaCartellaAutomatica();
        assertThat(lavori.corrente().orElseThrow().id()).isEqualTo(fine.id());
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
        throw new AssertionError("Importazione ancora in corso dopo 30 secondi");
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
