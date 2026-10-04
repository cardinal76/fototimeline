package it.fototimeline.backup;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.imageio.ImageIO;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;

@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.backup.tenere=2",
})
class BackupMetadatiTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    BackupMetadati backup;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @BeforeEach
    void svuota() throws IOException {
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        for (var c : backup.elenco()) {
            Files.delete(backup.cartella().resolve(c.nome()));
        }
    }

    @Test
    void scriveUnDocumentoPerRigaRipristinabile() throws IOException {
        var foto = service.importa("mare.png", immagine(Color.BLUE), Instant.parse("2024-08-15T10:00:00Z"), null).foto();
        service.modifica(foto.getId(), new Modifica("Sperlonga", null, List.of("mare"), "Estate", true,
                LocalDateTime.of(2024, 8, 15, 18, 30)));
        service.importa("monti.png", immagine(Color.GREEN), Instant.parse("2023-01-02T10:00:00Z"), null);

        var copia = backup.esegui();

        assertThat(copia.nome()).startsWith("fototimeline-").endsWith(".json.gz");
        assertThat(backup.cartella()).isEqualTo(archivio.resolve(".backup"));
        List<String> righe = leggi(backup.cartella().resolve(copia.nome()));
        assertThat(righe).hasSize(2);
        Document sperlonga = righe.stream().map(Document::parse)
                .filter(d -> "Sperlonga".equals(d.getString("titolo"))).findFirst().orElseThrow();
        assertThat(sperlonga.getString("_id")).isEqualTo(foto.getId());
        assertThat(sperlonga.getList("tag", String.class)).containsExactly("mare");
        assertThat(sperlonga.getString("giorno")).isEqualTo("2024-08-15");
        // Le date in Extended JSON ({"$date": ...}): mongoimport le rimette come date.
        assertThat(sperlonga.get("scattataIl")).isInstanceOf(java.util.Date.class);
        assertThat(Files.list(backup.cartella()).filter(p -> p.toString().endsWith(".parziale"))).isEmpty();
    }

    @Test
    void tieneSoloGliUltimi() throws Exception {
        backup.esegui();
        Thread.sleep(1100);
        backup.esegui();
        Thread.sleep(1100);
        var ultima = backup.esegui();

        assertThat(backup.elenco()).hasSize(2).first().isEqualTo(ultima);
    }

    @Test
    void seNecessarioSoloSeLUltimoEVecchio() throws IOException {
        backup.seNecessario();
        assertThat(backup.elenco()).hasSize(1);

        // Appena fatto: niente di nuovo.
        backup.seNecessario();
        assertThat(backup.elenco()).hasSize(1);

        // Di due giorni fa: se ne fa un altro.
        Path vecchio = backup.cartella().resolve(backup.elenco().getFirst().nome());
        Files.move(vecchio, backup.cartella().resolve("fototimeline-2000-01-01-000000.json.gz"));
        Files.setLastModifiedTime(backup.cartella().resolve("fototimeline-2000-01-01-000000.json.gz"),
                FileTime.from(Instant.now().minusSeconds(2 * 86400)));
        backup.seNecessario();
        assertThat(backup.elenco()).hasSize(2);
    }

    private static List<String> leggi(Path gz) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
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
