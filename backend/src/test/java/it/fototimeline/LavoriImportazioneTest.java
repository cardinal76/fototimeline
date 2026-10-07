package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FileScartato;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.MemoriaImportazione;
import it.fototimeline.service.ProvenienzaFile;

@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class LavoriImportazioneTest {

    /** Al posto dei telefoni: i file che iniziano con "anna-" li ha portati Anna. */
    @TestConfiguration
    static class Provenienza {
        @Bean
        ProvenienzaFile daAnna() {
            return file -> file.getFileName().toString().startsWith("anna-") ? "anna" : null;
        }
    }

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
        r.add("fototimeline.importazione-automatica.cartella", () -> disco.resolve("telefono").toString());
        // I file dei test sono appena scritti: senza attesa, se no li lascerebbe al giro dopo.
        r.add("fototimeline.importazione-automatica.attesa", () -> "PT0S");
    }

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    StrumentiMedia media;

    @Autowired
    MemoriaImportazione memoria;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        memoria.dimenticaTutti();
        Path telefono = disco.resolve("telefono");
        if (Files.isDirectory(telefono)) {
            try (var file = Files.walk(telefono)) {
                file.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        Files.createDirectories(telefono);
    }

    @Test
    void importaInSottofondoESegueLAvanzamento(@TempDir Path cartella) throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.write(cartella.resolve("foto" + i + ".png"), immagine(new Color(i * 40, 90, 120)));
        }
        Files.writeString(cartella.resolve("note.txt"), "non è una foto");

        StatoLavoro partito = lavori.avvia(cartella, false, false, Origine.MANUALE, "marco");
        assertThat(partito.stato()).isEqualTo(Stato.IN_CORSO);

        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.trovate()).isEqualTo(5);
        assertThat(fine.fatte()).isEqualTo(5);
        assertThat(fine.importate()).isEqualTo(5);
        assertThat(fine.finitoIl()).isAfterOrEqualTo(fine.iniziatoIl());
        assertThat(repository.count()).isEqualTo(5);
        // Chi ha lanciato "Importa cartella" le ha portate tutte.
        assertThat(repository.findAll()).extracting(Foto::getCaricataDa).containsOnly("marco");
    }

    @Test
    void unaAllaVolta(@TempDir Path cartella) throws Exception {
        for (int i = 0; i < 40; i++) {
            Files.write(cartella.resolve("f" + i + ".png"), immagine(new Color(i * 6, i * 3, 200)));
        }
        lavori.avvia(cartella, false, false, Origine.MANUALE, null);
        assertThatThrownBy(() -> lavori.avvia(cartella, false, false, Origine.MANUALE, null))
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
        Files.write(telefono.resolve("anna-IMG_3.png"), immagine(Color.BLUE));

        lavori.controllaCartellaAutomatica();
        StatoLavoro fine = aspettaFine();

        assertThat(fine.origine()).isEqualTo(Origine.AUTOMATICA);
        assertThat(fine.importate()).isEqualTo(3);
        assertThat(fine.rimossi()).isEqualTo(3);
        assertThat(telefono).exists().isEmptyDirectory();
        assertThat(service.album()).containsExactly("WhatsApp");
        // Di chi è lo dice la provenienza (i telefoni), non chi ha lanciato niente.
        assertThat(repository.findAll()).extracting(Foto::getNomeOriginale, Foto::getCaricataDa)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IMG_1.png", null),
                        org.assertj.core.groups.Tuple.tuple("IMG_2.png", null),
                        org.assertj.core.groups.Tuple.tuple("anna-IMG_3.png", "anna"));

        // Vuota: il controllo successivo non fa partire niente.
        lavori.controllaCartellaAutomatica();
        assertThat(lavori.corrente().orElseThrow().id()).isEqualTo(fine.id());
    }

    @Test
    void laCartellaAutomaticaPrendeAncheHeicEVideo() throws Exception {
        assumeTrue(media.heicDisponibile(), "heif-convert non installato");
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        Path telefono = disco.resolve("telefono");
        copia("/foto.heic", telefono.resolve("IMG_0001.HEIC"));
        copia("/video.mp4", telefono.resolve("IMG_0002.mp4"));

        lavori.controllaCartellaAutomatica();
        StatoLavoro fine = aspettaFine();

        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.messaggi()).isEmpty();
        assertThat(fine.importate()).isEqualTo(2);
        assertThat(fine.rimossi()).isEqualTo(2);
        assertThat(telefono).isEmptyDirectory();
        assertThat(repository.findAll()).extracting(Foto::isVideo).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void unFileRottoSiRicordaENonFaRipartireIlGiroSeNonCambia() throws Exception {
        Path telefono = disco.resolve("telefono");
        Path rotto = telefono.resolve("IMG_rotta.jpg");
        Files.write(rotto, new byte[] {1, 2, 3, 4, 5, 6, 7, 8});
        Files.write(telefono.resolve("IMG_buona.png"), immagine(Color.MAGENTA));

        lavori.controllaCartellaAutomatica();
        StatoLavoro primo = aspettaFine();
        assertThat(primo.importate()).isEqualTo(1);
        assertThat(primo.errori()).isEqualTo(1);
        assertThat(primo.messaggi()).singleElement().asString().contains("IMG_rotta.jpg", "Immagine illeggibile");
        assertThat(lavori.scartati()).extracting(FileScartato::percorso).containsExactly(rotto.toString());

        // Resta solo quello rotto, uguale: nessun giro a vuoto.
        lavori.controllaCartellaAutomatica();
        assertThat(lavori.corrente().orElseThrow().id()).isEqualTo(primo.id());

        // Cambiato (per esempio ricopiato intero): si rilegge.
        Files.write(rotto, immagine(Color.CYAN));
        lavori.controllaCartellaAutomatica();
        StatoLavoro secondo = aspettaFine();
        assertThat(secondo.id()).isNotEqualTo(primo.id());
        assertThat(secondo.importate()).isEqualTo(1);
        assertThat(secondo.errori()).isZero();
        assertThat(lavori.scartati()).isEmpty();
    }

    @Test
    void riprovaRileggeIFileGiaFalliti() throws Exception {
        Path telefono = disco.resolve("telefono");
        Files.write(telefono.resolve("IMG_rotta.jpg"), new byte[] {9, 9, 9, 9, 9});
        Files.write(telefono.resolve("IMG_altra.png"), immagine(Color.PINK));
        lavori.controllaCartellaAutomatica();
        aspettaFine();

        assertThat(lavori.riprovaScartati()).isEqualTo(1);
        lavori.controllaCartellaAutomatica();
        StatoLavoro daCapo = aspettaFine();

        assertThat(daCapo.trovate()).isEqualTo(1);
        assertThat(daCapo.errori()).isEqualTo(1);
        assertThat(daCapo.saltate()).isZero();
    }

    private void copia(String risorsa, Path dove) throws IOException {
        try (var in = getClass().getResourceAsStream(risorsa)) {
            Files.copy(in, dove);
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
