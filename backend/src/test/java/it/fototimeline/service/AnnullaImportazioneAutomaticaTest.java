package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

/**
 * La cartella automatica in produzione: tre file insieme, che aspettano il
 * turno per la miniatura ({@code FotoService.unaAllaVolta}) mentre un altro
 * (qui il test) lo tiene. Annulla deve fermare anche chi aspetta, e
 * "Indicizza archivio" deve poter partire lo stesso.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class AnnullaImportazioneAutomaticaTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
        r.add("fototimeline.importazione-automatica.cartella", () -> disco.resolve("telefono").toString());
        r.add("fototimeline.importazione-automatica.attesa", () -> "PT0S");
        r.add("fototimeline.indicizzazione.paralleli", () -> "3");
    }

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    MemoriaImportazione memoria;

    /** Il turno per le parti pesanti, tenuto dal test finché non lo si lascia. */
    private final CountDownLatch lascia = new CountDownLatch(1);
    private CompletableFuture<Void> turno;

    @BeforeEach
    void svuota() throws Exception {
        aspetta(() -> lavori.corrente().map(s -> s.stato() != Stato.IN_CORSO).orElse(true), "lavori finiti");
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        memoria.dimenticaTutti();
        for (String c : List.of("telefono", "archivio")) {
            Path cartella = disco.resolve(c);
            if (Files.isDirectory(cartella)) {
                try (Stream<Path> file = Files.walk(cartella)) {
                    file.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(cartella))
                            .filter(p -> !p.startsWith(cartella.resolve(ArchivioFile.MINIATURE)))
                            .forEach(p -> p.toFile().delete());
                }
            }
            Files.createDirectories(cartella);
        }
    }

    @AfterEach
    void lasciaIlTurno() throws Exception {
        lascia.countDown();
        if (turno != null) {
            turno.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void annullaFermaAncheIFileCheAspettanoIlTurno() throws Exception {
        Path telefono = cinqueFoto();
        tieniIlTurno();

        lavori.controllaCartellaAutomatica();
        // Tre file letti (hash e metadati), fermi ad aspettare la miniatura.
        aspetta(() -> service.inAttesaDelTurno() == 3, "tre file in attesa del turno");
        assertThat(lavori.annulla()).isTrue();
        assertThat(lavori.corrente().orElseThrow().annullamento()).isTrue();

        // Finisce mentre il turno è ancora tenuto: prima restava appesa lì finché non si liberava.
        StatoLavoro fine = aspettaFine();
        assertThat(lascia.getCount()).isEqualTo(1);
        assertThat(fine.origine()).isEqualTo(Origine.AUTOMATICA);
        assertThat(fine.stato()).isEqualTo(Stato.ANNULLATA);
        assertThat(fine.annullamento()).isFalse();
        // Nessun file toccato: niente schede, niente in archivio, tutti ancora nel telefono.
        assertThat(fine.fatte()).isZero();
        assertThat(fine.errori()).isZero();
        assertThat(repository.count()).isZero();
        assertThat(originaliInArchivio()).isEmpty();
        try (Stream<Path> rimasti = Files.list(telefono)) {
            assertThat(rimasti).hasSize(5);
        }
        try (Stream<Path> miniature = Files.list(disco.resolve("archivio").resolve(ArchivioFile.MINIATURE))) {
            assertThat(miniature.filter(Files::isRegularFile)).isEmpty();
        }
    }

    @Test
    void indicizzaArchivioParteMentreGiraLAutomatica() throws Exception {
        Path telefono = cinqueFoto();
        // Una foto nell'archivio messa da fuori, senza scheda: la trova l'indicizzazione.
        Path daFuori = Files.createDirectories(disco.resolve("archivio/2023/04/05")).resolve("da-fuori.png");
        Files.write(daFuori, immagine(Color.MAGENTA));
        tieniIlTurno();
        lavori.controllaCartellaAutomatica();
        aspetta(() -> service.inAttesaDelTurno() == 3, "tre file in attesa del turno");

        // Prima: IllegalStateException ("C'è già un'importazione in corso").
        StatoLavoro indicizzazione = lavori.avviaIndicizzazione();
        assertThat(indicizzazione.origine()).isEqualTo(Origine.INDICIZZAZIONE);
        assertThat(indicizzazione.stato()).isEqualTo(Stato.IN_CORSO);
        assertThat(indicizzazione.inAttesa()).isTrue();
        // L'automatica ha lasciato i suoi file al giro dopo, senza aspettare il turno.
        aspetta(() -> lavori.corrente().map(s -> !s.inAttesa()).orElse(false), "indicizzazione cominciata");
        assertThat(lascia.getCount()).isEqualTo(1);
        lascia.countDown();

        StatoLavoro fine = aspettaFine();
        assertThat(fine.id()).isEqualTo(indicizzazione.id());
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.importate()).isEqualTo(1);
        try (Stream<Path> rimasti = Files.list(telefono)) {
            assertThat(rimasti).hasSize(5);
        }

        // Al giro dopo l'automatica riprende e porta tutto nell'archivio.
        lavori.controllaCartellaAutomatica();
        StatoLavoro automatica = aspettaFine();
        assertThat(automatica.origine()).isEqualTo(Origine.AUTOMATICA);
        assertThat(automatica.stato()).isEqualTo(Stato.FINITA);
        assertThat(automatica.importate()).isEqualTo(5);
        assertThat(repository.count()).isEqualTo(6);
    }

    @Test
    void laCartellaAutomaticaSpostaIFileSenzaRicopiarli() throws Exception {
        Path telefono = disco.resolve("telefono");
        Path foto = telefono.resolve("IMG_1.png");
        Files.write(foto, immagine(Color.ORANGE));
        Object inode = Files.getAttribute(foto, "unix:ino");
        Files.setLastModifiedTime(foto, java.nio.file.attribute.FileTime.from(Instant.parse("2024-03-02T10:00:00Z")));

        lavori.controllaCartellaAutomatica();
        StatoLavoro fine = aspettaFine();

        assertThat(fine.importate()).isEqualTo(1);
        assertThat(fine.rimossi()).isEqualTo(1);
        assertThat(foto).doesNotExist();
        Foto scheda = repository.findAll().getFirst();
        assertThat(scheda.getPercorso()).isEqualTo("2024/03/02/IMG_1.png");
        Path inArchivio = disco.resolve("archivio").resolve(scheda.getPercorso());
        // Lo stesso file, rinominato: niente copia (sul mount di rclone niente download e upload).
        assertThat(Files.getAttribute(inArchivio, "unix:ino")).isEqualTo(inode);
        assertThat(disco.resolve("archivio").resolve(ArchivioFile.MINIATURE).resolve(scheda.getId() + ".jpg")).exists();
    }

    /** Cinque foto diverse nella cartella automatica. */
    private Path cinqueFoto() throws IOException {
        Path telefono = disco.resolve("telefono");
        for (int i = 0; i < 5; i++) {
            Files.write(telefono.resolve("IMG_" + i + ".png"), immagine(new Color(40 * i, 200 - 30 * i, 90)));
        }
        return telefono;
    }

    /** Il test prende il turno per le parti pesanti e lo tiene fino a {@link #lascia}. */
    private void tieniIlTurno() throws Exception {
        CountDownLatch preso = new CountDownLatch(1);
        turno = CompletableFuture.runAsync(() -> {
            try {
                service.unaAllaVolta(() -> {
                    preso.countDown();
                    return lascia.await(60, TimeUnit.SECONDS);
                });
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        assertThat(preso.await(10, TimeUnit.SECONDS)).isTrue();
    }

    private List<Path> originaliInArchivio() throws IOException {
        Path archivio = disco.resolve("archivio");
        try (Stream<Path> s = Files.walk(archivio)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> !p.startsWith(archivio.resolve(ArchivioFile.MINIATURE)))
                    .toList();
        }
    }

    private StatoLavoro aspettaFine() throws InterruptedException {
        aspetta(() -> lavori.corrente().map(s -> s.stato() != Stato.IN_CORSO).orElse(true), "lavoro finito");
        return lavori.corrente().orElse(null);
    }

    /** Aspetta che {@code condizione} diventi vera, al massimo 20 secondi. */
    private static void aspetta(BooleanSupplier condizione, String cosa) throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(20));
        while (!condizione.getAsBoolean()) {
            if (Instant.now().isAfter(limite)) {
                throw new AssertionError("Dopo 20 secondi ancora no: " + cosa);
            }
            Thread.sleep(20);
        }
    }

    private static byte[] immagine(Color colore) throws IOException {
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.setColor(colore.darker());
        g.fillRect(0, 0, 20, 15);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
