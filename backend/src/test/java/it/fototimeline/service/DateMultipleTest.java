package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.eventi.EventiService;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService.EsitoDate;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.NuoveDate.Modo;
import it.fototimeline.service.NuoveDate.Richiesta;
import it.fototimeline.service.NuoveDate.Spostamento;

/**
 * "📅 Data…" sulla selezione ({@code POST /api/foto/data}): i tre modi,
 * l'ordine che resta, i file nelle cartelle dei giorni nuovi, l'origine
 * MANUALE, gli eventi per date che le prendono; cestino, foto senza data e
 * file spariti si saltano o finiscono negli errori, senza fermare le altre.
 * I permessi (gestore e admin) sono in SicurezzaTest, le private degli altri
 * in FotoPrivateTest.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
@AutoConfigureMockMvc
class DateMultipleTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    private static final LocalDate GIORNO = LocalDate.of(1993, 6, 20);

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    EventiService eventi;

    @Autowired
    MockMvc mvc;

    private int colore;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        mongo.remove(new Query(), "eventi");
    }

    @Test
    void stessoGiornoTieneLOraDiOgnunaELeSenzaOraVannoAMezzogiorno() throws Exception {
        Foto mattina = foto("mattina.png", LocalDateTime.of(2024, 8, 15, 9, 15, 30));
        Foto sera = foto("sera.png", LocalDateTime.of(2024, 8, 16, 18, 40));
        // La data dalla cartella ("Indicizza archivio") non ha un'ora vera: va alle 12.
        Foto cartella = foto("cartella.png", LocalDateTime.of(2024, 8, 17, 0, 0));
        origine(cartella, OrigineData.CARTELLA);

        mvc.perform(post("/api/foto/data").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":" + ids(mattina, sera, cartella)
                                + ",\"modo\":\"STESSO_GIORNO\",\"giorno\":\"1993-06-20\",\"mantieniOra\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modificate").value(3))
                .andExpect(jsonPath("$.saltate").value(0))
                .andExpect(jsonPath("$.errori").isEmpty());

        assertThat(rilette(mattina, sera, cartella)).extracting(Foto::getScattataIl).containsExactly(
                LocalDateTime.of(1993, 6, 20, 9, 15, 30), LocalDateTime.of(1993, 6, 20, 18, 40),
                LocalDateTime.of(1993, 6, 20, 12, 0));
        assertThat(rilette(mattina, sera, cartella)).allSatisfy(f -> {
            assertThat(f.getOrigineData()).isEqualTo(OrigineData.MANUALE);
            assertThat(f.getGiorno()).isEqualTo("1993-06-20");
            assertThat(f.getPercorso()).startsWith("1993/06/20/");
            assertThat(archivio.resolve(f.getPercorso())).exists();
        });
        // Le cartelle rimaste vuote se ne vanno.
        assertThat(archivio.resolve("2024")).doesNotExist();
    }

    @Test
    void stessoGiornoSenzaTenereLOraMetteTutteAlleDodiciInFila() {
        Foto prima = foto("a.png", LocalDateTime.of(2024, 8, 15, 9, 0));
        Foto seconda = foto("b.png", LocalDateTime.of(2024, 8, 15, 20, 0));

        service.cambiaDate(List.of(seconda.getId(), prima.getId()),
                new Richiesta(Modo.STESSO_GIORNO, GIORNO, false, null, null));

        assertThat(rilette(prima, seconda)).extracting(Foto::getScattataIl).containsExactly(
                GIORNO.atTime(12, 0), GIORNO.atTime(12, 0, 1));
    }

    @Test
    void stessaOraAggiungeUnSecondoPerFotoNellOrdineDiPrima() {
        // Due allo stesso momento: dopo vale l'id, come nella timeline.
        Foto terza = foto("c.png", LocalDateTime.of(2022, 1, 3, 8, 0));
        Foto prima = foto("a.png", LocalDateTime.of(2020, 5, 1, 10, 0));
        Foto gemella = foto("b.png", LocalDateTime.of(2020, 5, 1, 10, 0));
        List<Foto> gemelle = new ArrayList<>(List.of(prima, gemella));
        gemelle.sort(NuoveDate.ORDINE);

        EsitoDate esito = service.cambiaDate(List.of(terza.getId(), gemella.getId(), prima.getId()),
                new Richiesta(Modo.STESSA_ORA, null, null, LocalDateTime.of(1993, 6, 20, 10, 0), null));

        assertThat(esito.modificate()).isEqualTo(3);
        assertThat(rilette(gemelle.get(0), gemelle.get(1), terza)).extracting(Foto::getScattataIl).containsExactly(
                LocalDateTime.of(1993, 6, 20, 10, 0, 0), LocalDateTime.of(1993, 6, 20, 10, 0, 1),
                LocalDateTime.of(1993, 6, 20, 10, 0, 2));
        assertThat(rilette(terza)).allSatisfy(f -> assertThat(f.getPercorso()).isEqualTo("1993/06/20/c.png"));
    }

    @Test
    void spostaTieneLeDistanzeESaltaLeFotoSenzaData() {
        Foto a = foto("a.png", LocalDateTime.of(2024, 12, 31, 23, 30));
        Foto b = foto("b.png", LocalDateTime.of(2025, 1, 2, 7, 5));
        Foto senzaData = new Foto();
        senzaData.setNomeOriginale("senza-data.jpg");
        senzaData.setHash("senza-data");
        senzaData.setPercorso("senza-data.jpg");
        senzaData = repository.save(senzaData);

        // La fotocamera era avanti di un anno, un giorno e mezz'ora.
        EsitoDate esito = service.cambiaDate(List.of(a.getId(), b.getId(), senzaData.getId()),
                new Richiesta(Modo.SPOSTA, null, null, null, new Spostamento(-1, 0, -1, 0, -30)));

        assertThat(esito.modificate()).isEqualTo(2);
        assertThat(esito.saltate()).isEqualTo(1);
        List<Foto> dopo = rilette(a, b);
        assertThat(dopo).extracting(Foto::getScattataIl).containsExactly(
                LocalDateTime.of(2023, 12, 30, 23, 0), LocalDateTime.of(2025 - 1, 1, 1, 6, 35));
        assertThat(Duration.between(dopo.get(0).getScattataIl(), dopo.get(1).getScattataIl()))
                .isEqualTo(Duration.between(a.getScattataIl(), b.getScattataIl()));
        assertThat(dopo.get(0).getPercorso()).isEqualTo("2023/12/30/a.png");
        assertThat(dopo.get(1).getPercorso()).isEqualTo("2024/01/01/b.png");
        assertThat(repository.findById(senzaData.getId()).orElseThrow().getScattataIl()).isNull();
    }

    @Test
    void cestinoSaltatoUgualiInvariateEUnFileSparitoNonFermaLeAltre() throws IOException {
        Foto buona = foto("buona.png", LocalDateTime.of(2024, 3, 1, 10, 0));
        Foto cestinata = foto("cestino.png", LocalDateTime.of(2024, 3, 2, 10, 0));
        Foto giaCosi = foto("gia.png", GIORNO.atTime(15, 0));
        Foto sparita = foto("sparita.png", LocalDateTime.of(2024, 3, 3, 10, 0));
        service.elimina(cestinata.getId());
        Files.delete(archivio.resolve(sparita.getPercorso()));

        EsitoDate esito = service.cambiaDate(
                List.of(buona.getId(), cestinata.getId(), giaCosi.getId(), sparita.getId(), "non-esiste"),
                new Richiesta(Modo.STESSO_GIORNO, GIORNO, true, null, null));

        assertThat(esito.modificate()).isEqualTo(1);
        assertThat(esito.invariate()).isEqualTo(1);
        assertThat(esito.saltate()).isEqualTo(2);
        assertThat(esito.errori()).singleElement().satisfies(e -> {
            assertThat(e.id()).isEqualTo(sparita.getId());
            assertThat(e.nome()).isEqualTo("sparita.png");
            assertThat(e.motivo()).contains("Non riesco a spostare").contains("il file non c'è più")
                    .doesNotContain(archivio.toString());
        });
        assertThat(rilette(buona)).singleElement().satisfies(f -> assertThat(f.getPercorso()).isEqualTo("1993/06/20/buona.png"));
        // Nel cestino non si tocca; quella col file sparito resta com'era.
        Foto c = repository.findById(cestinata.getId()).orElseThrow();
        assertThat(c.getScattataIl()).isEqualTo(LocalDateTime.of(2024, 3, 2, 10, 0));
        assertThat(c.getOrigineData()).isEqualTo(OrigineData.FILE);
        Foto s = repository.findById(sparita.getId()).orElseThrow();
        assertThat(s.getScattataIl()).isEqualTo(LocalDateTime.of(2024, 3, 3, 10, 0));
        assertThat(s.getOrigineData()).isEqualTo(OrigineData.FILE);
    }

    @Test
    void unEventoPerDateLePrendeQuandoCadonoNeiSuoiGiorni() {
        var vacanze = eventi.creaPerDate("Vacanze 1993", GIORNO, GIORNO.plusDays(3));
        Foto a = foto("a.png", LocalDateTime.of(2024, 8, 15, 9, 0));
        Foto b = foto("b.png", LocalDateTime.of(2024, 8, 16, 9, 0));
        assertThat(rilette(a, b)).allSatisfy(f -> assertThat(f.getEvento()).isNull());

        service.cambiaDate(List.of(a.getId(), b.getId()), new Richiesta(Modo.STESSO_GIORNO, GIORNO, true, null, null));

        assertThat(rilette(a, b)).allSatisfy(f -> {
            assertThat(f.getEvento()).isEqualTo(vacanze.id());
            assertThat(f.isEventoFisso()).isTrue();
        });
        assertThat(eventi.evento(vacanze.id()).orElseThrow().conteggio()).isEqualTo(2);

        // Spostate fuori dai suoi giorni ne escono.
        service.cambiaDate(List.of(a.getId(), b.getId()),
                new Richiesta(Modo.SPOSTA, null, null, null, new Spostamento(0, 1, 0, 0, 0)));
        assertThat(rilette(a, b)).allSatisfy(f -> assertThat(f.getEvento()).isNull());
        assertThat(eventi.evento(vacanze.id()).orElseThrow().conteggio()).isZero();
    }

    @Test
    void richiesteIncompleteOTroppeFotoSonoUn400() throws Exception {
        Foto a = foto("a.png", LocalDateTime.of(2024, 8, 15, 9, 0));
        for (String corpo : List.of(
                "{\"ids\":" + ids(a) + ",\"modo\":\"STESSO_GIORNO\"}",
                "{\"ids\":" + ids(a) + ",\"modo\":\"STESSA_ORA\"}",
                "{\"ids\":" + ids(a) + ",\"modo\":\"SPOSTA\",\"sposta\":{\"anni\":0,\"mesi\":0,\"giorni\":0,\"ore\":0,\"minuti\":0}}",
                "{\"ids\":" + ids(a) + ",\"modo\":\"STESSO_GIORNO\",\"giorno\":\"1700-01-01\"}",
                "{\"ids\":" + ids(a) + "}")) {
            mvc.perform(post("/api/foto/data").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(corpo))
                    .andExpect(status().isBadRequest());
        }
        List<String> troppe = IntStream.rangeClosed(0, FotoService.MASSIMO_DATE).mapToObj(i -> "id-" + i).toList();
        assertThatThrownBy(() -> service.cambiaDate(troppe, new Richiesta(Modo.STESSO_GIORNO, GIORNO, true, null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Al più " + FotoService.MASSIMO_DATE);
        // Uno spostamento che porta fuori dagli anni ammessi è un errore della foto, che resta com'era.
        EsitoDate esito = service.cambiaDate(List.of(a.getId()),
                new Richiesta(Modo.SPOSTA, null, null, null, new Spostamento(-300, 0, 0, 0, 0)));
        assertThat(esito.errori()).singleElement().satisfies(e -> assertThat(e.motivo()).contains("1724"));
        assertThat(rilette(a)).singleElement().satisfies(f -> assertThat(f.getScattataIl().getYear()).isEqualTo(2024));
    }

    // ------------------------------------------------------------ utilità

    /** Una foto con quella data (e il file nella sua cartella), come se venisse dal file: origine FILE. */
    private Foto foto(String nome, LocalDateTime quando) {
        var caricata = service.importa(nome, immagine(), Instant.parse("2024-01-01T10:00:00Z"), null).foto();
        assertThat(caricata).isNotNull();
        service.modifica(caricata.getId(), new Modifica(null, null, List.of(), null, null, quando));
        origine(caricata, OrigineData.FILE);
        return repository.findById(caricata.getId()).orElseThrow();
    }

    private void origine(Foto foto, OrigineData origine) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())), new Update().set("origineData", origine),
                Foto.class);
    }

    private List<Foto> rilette(Foto... foto) {
        return java.util.Arrays.stream(foto).map(f -> repository.findById(f.getId()).orElseThrow()).toList();
    }

    private static String ids(Foto... foto) {
        return java.util.Arrays.stream(foto).map(f -> "\"" + f.getId() + "\"")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));
    }

    /** Ogni foto un colore suo: l'hash è unico. */
    private byte[] immagine() {
        BufferedImage img = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(new Color(0x102030 + (colore++ * 0x050301)));
        g.fillRect(0, 0, 8, 8);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
