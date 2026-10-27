package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

/**
 * Il percorso vero di FotoService con un file che supera il tempo massimo:
 * qui due foto ferme ad aspettare il turno per la miniatura, tenuto dal test.
 * Si abbandonano, il lavoro finisce contandole come errori di lettura, e
 * quando il turno si libera i loro thread non scrivono niente: né schede, né
 * file spostati, né miniature. Al giro dopo entrano.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class FileAbbandonatoTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
        r.add("fototimeline.importazione-automatica.cartella", () -> disco.resolve("telefono").toString());
        r.add("fototimeline.importazione-automatica.attesa", () -> "PT0S");
        r.add("fototimeline.indicizzazione.paralleli", () -> "2");
    }

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    MemoriaImportazione memoria;

    /**
     * Spia: il tempo massimo corto vale solo per il primo giro, quello che deve
     * abbandonare. Il secondo è un'importazione normale, coi tempi veri: con
     * mezzo secondo anche lì, su un runner carico il secondo file (che aspetta
     * il turno dietro al primo, poi miniatura e scheda) lo superava e veniva
     * abbandonato anche lui ("importate" 1 invece di 2).
     */
    @MockitoSpyBean
    IndicizzazioneProperties tempi;

    private final CountDownLatch lascia = new CountDownLatch(1);
    private CompletableFuture<Void> turno;

    @BeforeEach
    void svuota() throws Exception {
        Files.createDirectories(disco.resolve("telefono"));
        Files.createDirectories(disco.resolve("archivio"));
    }

    @AfterEach
    void lasciaIlTurno() throws Exception {
        lascia.countDown();
        if (turno != null) {
            turno.get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void unFileOltreIlTempoMassimoSiAbbandonaSenzaScrivereNiente() throws Exception {
        Path telefono = disco.resolve("telefono");
        for (int i = 0; i < 2; i++) {
            Files.write(telefono.resolve("IMG_" + i + ".png"), immagine(new Color(60 * i, 120, 200 - 50 * i)));
        }
        tieniIlTurno();

        // Il turno lo tiene il test: le due foto lo aspettano finché scade il loro tempo.
        doReturn(Duration.ofMillis(500)).when(tempi).tempoMassimo(anyLong());
        lavori.controllaCartellaAutomatica();
        StatoLavoro fine = aspettaFine();

        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.fatte()).isEqualTo(2);
        assertThat(fine.errori()).isEqualTo(2);
        assertThat(fine.messaggi()).allSatisfy(m -> assertThat(m)
                .contains("File illeggibile (fermo da").contains("«in attesa del turno per la miniatura»"));
        // Errori di lettura: la cartella automatica non li ricorda.
        assertThat(memoria.elenco()).isEmpty();
        // Il turno torna libero solo adesso: i thread abbandonati smettono senza toccare niente.
        lascia.countDown();
        turno.get(10, TimeUnit.SECONDS);
        aspetta(() -> service.turniLiberi() == 1, "turno libero");
        assertThat(repository.count()).isZero();
        assertThat(originaliInArchivio()).isEmpty();
        try (Stream<Path> rimasti = Files.list(telefono)) {
            assertThat(rimasti).hasSize(2);
        }

        // Al giro dopo, coi tempi veri, entrano. (Nessun lavoro in corso: si può stubbare.)
        doCallRealMethod().when(tempi).tempoMassimo(anyLong());
        lavori.controllaCartellaAutomatica();
        StatoLavoro dopo = aspettaFine();
        assertThat(dopo.id()).isNotEqualTo(fine.id());
        assertThat(dopo.importate()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(2);
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
