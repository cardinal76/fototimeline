package it.fototimeline.cestino;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import java.time.temporal.ChronoUnit;
import java.util.List;

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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.Cloud;
import it.fototimeline.condivisione.CondivisioniService;
import it.fototimeline.condivisione.CondivisioniService.NuovaCondivisione;
import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.FotoService.Operazione;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Automatica;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.service.MemoriaImportazione;
import it.fototimeline.service.Risultati.Esito;

/** Il cestino: dove va una foto eliminata, cosa la vede ancora, come torna e come se ne va per sempre. */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.cestino.giorni=30",
})
@AutoConfigureMockMvc
class CestinoTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
    }

    private static final FiltroFoto TUTTO = new FiltroFoto(null, null, null, null, null, null);

    @Autowired
    FotoService service;

    @Autowired
    Cestino cestino;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    ImportazioneCartelle importazione;

    @Autowired
    MemoriaImportazione memoria;

    @Autowired
    CondivisioniService condivisioni;

    @Autowired
    MockMvc mvc;

    @MockitoSpyBean
    Cloud cloud;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        memoria.dimenticaTutti();
    }

    @Test
    void unaFotoEliminataVaNelCestinoEdEsceDaTuttiGliElenchi() throws IOException {
        Foto resta = importa("resta.png", Color.RED, "2023-06-10T10:00:00Z", "Mare");
        byte[] jpg = getClass().getResourceAsStream("/con-exif.jpg").readAllBytes();
        Foto via = service.importa("IMG_0001.JPG", jpg, null, "Vacanze", "anna").foto();
        service.modifica(via.getId(), new Modifica("Tramonto", null, List.of("estate"), "Vacanze", true, null));
        Path originale = service.fileOriginale(via);
        Path miniatura = service.fileMiniatura(via);
        assertThat(service.cerca(TUTTO, 0, 10).totale()).isEqualTo(2);

        assertThat(service.elimina(via.getId())).isTrue();
        assertThat(service.elimina(via.getId())).as("già nel cestino").isFalse();

        // La scheda resta, coi suoi dati, e il file non si tocca.
        Foto nelCestino = repository.findById(via.getId()).orElseThrow();
        assertThat(nelCestino.getEliminataIl()).isNotNull();
        assertThat(nelCestino.getAlbum()).isEqualTo("Vacanze");
        assertThat(originale).exists();
        assertThat(miniatura).exists();

        // Timeline, ricerca, conteggi, mappa, ricordi, tag, album, "caricate da".
        assertThat(service.cerca(TUTTO, 0, 10).foto()).extracting(Foto::getId).containsExactly(resta.getId());
        assertThat(service.cerca(new FiltroFoto("tramonto", null, null, null, null, null), 0, 10).totale()).isZero();
        assertThat(service.cerca(new FiltroFoto(null, null, null, true, null, null), 0, 10).totale()).isZero();
        assertThat(service.timeline(TUTTO)).singleElement().satisfies(m -> {
            assertThat(m.anno()).isEqualTo(2023);
            assertThat(m.conteggio()).isEqualTo(1);
        });
        assertThat(service.mappa(TUTTO)).isEmpty();
        assertThat(service.ricordi(LocalDate.of(2025, 7, 14), 12)).isEmpty();
        assertThat(service.tag()).isEmpty();
        assertThat(service.album()).containsExactly("Mare");
        assertThat(service.caricateDa()).isEmpty();
        // Una foto nel cestino non si modifica e la selezione non la tocca.
        assertThat(service.modifica(via.getId(), new Modifica("x", null, List.of(), null, null, null))).isEmpty();
        assertThat(service.operazioneMultipla(List.of(via.getId()), Operazione.AGGIUNGI_TAG, "nuovo")).isZero();

        // Nella pagina "Cestino", con i giorni che restano.
        var pagina = cestino.elenco(0, 60);
        assertThat(pagina.totale()).isEqualTo(1);
        assertThat(pagina.giorni()).isEqualTo(30);
        assertThat(pagina.foto()).singleElement().satisfies(v -> {
            assertThat(v.id()).isEqualTo(via.getId());
            assertThat(v.miniatura()).isEqualTo("/api/foto/" + via.getId() + "/miniatura");
            assertThat(v.giorniRimasti()).isEqualTo(30);
            assertThat(v.siEliminaIl()).isEqualTo(nelCestino.getEliminataIl().plus(Duration.ofDays(30)));
        });
    }

    @Test
    void eliminaDallaSelezioneEdalVisoreVaNelCestino() throws Exception {
        Foto a = importa("a.png", Color.RED, "2024-01-01T10:00:00Z", null);
        Foto b = importa("b.png", Color.GREEN, "2024-01-02T10:00:00Z", null);
        Foto c = importa("c.png", Color.BLUE, "2024-01-03T10:00:00Z", null);

        assertThat(service.operazioneMultipla(List.of(a.getId(), b.getId()), Operazione.ELIMINA, null)).isEqualTo(2);
        mvc.perform(delete("/api/foto/" + c.getId()).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(delete("/api/foto/" + c.getId()).with(csrf())).andExpect(status().isNotFound());

        assertThat(repository.count()).isEqualTo(3);
        assertThat(service.cerca(TUTTO, 0, 10).totale()).isZero();
        mvc.perform(get("/api/cestino")).andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(3))
                // Dall'ultima eliminata.
                .andExpect(jsonPath("$.foto[0].id").value(c.getId()))
                .andExpect(jsonPath("$.foto[0].giorniRimasti").value(30));
        // La miniatura si vede ancora: serve alla pagina del cestino.
        mvc.perform(get("/api/foto/" + a.getId() + "/miniatura")).andExpect(status().isOk());
    }

    @Test
    void ripristinaRimetteLaFotoComEra() throws Exception {
        Foto foto = importa("torna.png", Color.ORANGE, "2022-08-15T10:00:00Z", "Ferragosto");
        Foto altra = importa("altra.png", Color.CYAN, "2022-08-16T10:00:00Z", null);
        service.elimina(foto.getId());
        service.elimina(altra.getId());

        mvc.perform(post("/api/cestino/ripristina").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + foto.getId() + "\",\"" + foto.getId() + "\",\"sconosciuta\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fatte").value(1));

        Foto tornata = repository.findById(foto.getId()).orElseThrow();
        assertThat(tornata.getEliminataIl()).isNull();
        assertThat(tornata.getPercorso()).isEqualTo(foto.getPercorso());
        assertThat(service.cerca(TUTTO, 0, 10).foto()).extracting(Foto::getId).containsExactly(foto.getId());
        assertThat(service.album()).containsExactly("Ferragosto");
        assertThat(cestino.elenco(0, 60).foto()).extracting(Cestino.VoceCestino::id).containsExactly(altra.getId());
        // Una foto dell'archivio non si "ripristina" e non si elimina per sempre senza passare dal cestino.
        assertThat(service.ripristina(foto.getId())).isFalse();
        assertThat(service.eliminaPerSempre(foto.getId())).isFalse();
        assertThat(service.fileOriginale(foto)).exists();
    }

    @Test
    void eliminaPerSempreTogliePureIlFile() throws Exception {
        Foto foto = importa("via.png", Color.MAGENTA, "2021-03-04T10:00:00Z", null);
        Foto altra = importa("altra.png", Color.PINK, "2021-03-05T10:00:00Z", null);
        Path originale = service.fileOriginale(foto);
        Path miniatura = service.fileMiniatura(foto);
        service.elimina(foto.getId());

        mvc.perform(post("/api/cestino/elimina").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + foto.getId() + "\",\"" + altra.getId() + "\"]}"))
                .andExpect(status().isOk())
                // L'altra non è nel cestino: resta.
                .andExpect(jsonPath("$.fatte").value(1));

        assertThat(repository.existsById(foto.getId())).isFalse();
        assertThat(originale).doesNotExist();
        assertThat(miniatura).doesNotExist();
        assertThat(disco.resolve("archivio/2021/03/04")).doesNotExist();
        assertThat(service.fileOriginale(altra)).exists();

        // Il contenuto è uscito dall'archivio: ora rientrerebbe come nuovo.
        assertThat(service.importa("via.png", immagine(Color.MAGENTA), null, null).esito()).isEqualTo(Esito.CARICATA);
    }

    @Test
    void svuotaCestinoLiEliminaTutti() throws Exception {
        Foto a = importa("a.png", Color.RED, "2020-01-01T10:00:00Z", null);
        Foto b = importa("b.png", Color.GREEN, "2020-01-02T10:00:00Z", null);
        Foto resta = importa("resta.png", Color.BLUE, "2020-01-03T10:00:00Z", null);
        service.elimina(a.getId());
        service.elimina(b.getId());

        mvc.perform(post("/api/cestino/svuota").with(csrf())).andExpect(status().isOk()).andExpect(jsonPath("$.fatte").value(2));

        assertThat(repository.findAll()).extracting(Foto::getId).containsExactly(resta.getId());
        assertThat(cestino.elenco(0, 60).totale()).isZero();
    }

    @Test
    void dopoIGiorniDelCestinoLeFotoSiEliminanoDaSole() throws IOException {
        Foto vecchia = importa("vecchia.png", Color.RED, "2019-05-01T10:00:00Z", null);
        Foto recente = importa("recente.png", Color.GREEN, "2019-05-02T10:00:00Z", null);
        Foto archivio = importa("archivio.png", Color.BLUE, "2019-05-03T10:00:00Z", null);
        service.elimina(vecchia.getId());
        service.elimina(recente.getId());
        Path originaleVecchia = service.fileOriginale(vecchia);
        nelCestinoDa(vecchia, Duration.ofDays(31));
        nelCestinoDa(recente, Duration.ofDays(29));

        // Col cloud smontato aspetta: niente si cancella.
        doReturn(false).when(cloud).disponibile();
        assertThat(cestino.pulisci()).isZero();
        assertThat(repository.existsById(vecchia.getId())).isTrue();
        doReturn(true).when(cloud).disponibile();

        assertThat(cestino.pulisci()).isEqualTo(1);
        assertThat(repository.existsById(vecchia.getId())).isFalse();
        assertThat(originaleVecchia).doesNotExist();
        assertThat(repository.findById(recente.getId()).orElseThrow().getEliminataIl()).isNotNull();
        assertThat(cestino.elenco(0, 60).foto()).singleElement()
                .satisfies(v -> assertThat(v.giorniRimasti()).isEqualTo(1));
        assertThat(repository.findById(archivio.getId()).orElseThrow().getEliminataIl()).isNull();
        assertThat(cestino.pulisci()).isZero();
    }

    @Test
    void colCloudSmontatoNonSiRipristinaNeSiEliminaPerSempre() throws Exception {
        Foto foto = importa("ferma.png", Color.YELLOW, "2018-02-02T10:00:00Z", null);
        service.elimina(foto.getId());
        doReturn(false).when(cloud).disponibile();
        doThrow(new ArchivioNonDisponibile()).when(cloud).verifica();

        String corpo = "{\"ids\":[\"" + foto.getId() + "\"]}";
        mvc.perform(post("/api/cestino/ripristina").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/cestino/elimina").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(corpo))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/cestino/svuota").with(csrf())).andExpect(status().isServiceUnavailable());
        // L'elenco si vede lo stesso (solo MongoDB e miniature).
        mvc.perform(get("/api/cestino")).andExpect(status().isOk()).andExpect(jsonPath("$.totale").value(1));
        assertThatThrownBy(() -> service.elimina(foto.getId())).isInstanceOf(ArchivioNonDisponibile.class);
        assertThat(repository.findById(foto.getId()).orElseThrow().getEliminataIl()).isNotNull();
    }

    @Test
    void laStessaFotoReimportataMentreENelCestinoNonRientraEDiceDove() throws IOException {
        Foto foto = importa("IMG_0001.png", Color.RED, "2024-07-01T10:00:00Z", null);
        service.elimina(foto.getId());

        // Caricamento dal browser o dal telefono.
        var caricata = service.importa("dal-telefono.png", immagine(Color.RED), null, null, "anna");
        assertThat(caricata.esito()).isEqualTo(Esito.DUPLICATA);
        assertThat(caricata.messaggio()).isEqualTo(FotoService.NEL_CESTINO);
        assertThat(caricata.foto().getId()).isEqualTo(foto.getId());

        // La cartella automatica (sposta): la copia nell'origine si toglie come per ogni doppione.
        Path telefono = Files.createTempDirectory(disco, "telefono");
        Files.write(telefono.resolve("IMG_9999.png"), immagine(Color.RED));
        var automatica = importazione.importa(telefono, false, true, Avanzamento.NESSUNO, p -> null,
                new Automatica(memoria, p -> false, Instant.now(), Duration.ZERO));
        assertThat(automatica.importate()).isZero();
        assertThat(automatica.duplicate()).isEqualTo(1);
        assertThat(telefono.resolve("IMG_9999.png")).doesNotExist();

        // Sempre una sola scheda, sempre nel cestino: niente si ripristina da solo.
        assertThat(repository.findAll()).singleElement().satisfies(f -> {
            assertThat(f.getId()).isEqualTo(foto.getId());
            assertThat(f.getEliminataIl()).isNotNull();
        });
        assertThat(service.cerca(TUTTO, 0, 10).totale()).isZero();
    }

    @Test
    void indicizzaArchivioNonRiprendeUnaFotoNelCestino() throws IOException {
        Foto foto = importa("IMG_0002.png", Color.GREEN, "2024-07-02T10:00:00Z", null);
        service.elimina(foto.getId());
        // Una copia identica messa nell'archivio da fuori, in un altro giorno.
        Path copia = disco.resolve("archivio/2024/07/03/copia.png");
        Files.createDirectories(copia.getParent());
        Files.write(copia, immagine(Color.GREEN));

        var esito = importazione.indicizza(ImportazioneCartelle.Avanzamento.NESSUNO);

        assertThat(esito.importate()).isZero();
        assertThat(esito.duplicate()).isEqualTo(2);
        assertThat(repository.findAll()).singleElement().satisfies(f -> {
            assertThat(f.getId()).isEqualTo(foto.getId());
            assertThat(f.getEliminataIl()).isNotNull();
        });
        assertThat(service.fileOriginale(foto)).exists();
        assertThat(service.cerca(TUTTO, 0, 10).totale()).isZero();
        Files.delete(copia);
    }

    @Test
    void unLinkCondivisoNonMostraLeFotoNelCestino() throws IOException {
        Foto resta = importa("resta.png", Color.RED, "2017-09-01T10:00:00Z", "Festa");
        Foto via = importa("via.png", Color.GREEN, "2017-09-01T11:00:00Z", "Festa");
        var link = condivisioni.crea(new NuovaCondivisione("Festa", null, "Festa", null, null, null, true, false), "marco");
        service.elimina(via.getId());

        assertThat(condivisioni.foto(link)).extracting(Foto::getId).containsExactly(resta.getId());
        assertThatThrownBy(() -> condivisioni.foto(link, via.getId()))
                .isInstanceOf(CondivisioniService.LinkNonValido.class);
        // Né in un link nuovo dello stesso album.
        var nuovo = condivisioni.crea(new NuovaCondivisione("Festa", null, "Festa", null, null, null, true, false), "marco");
        assertThat(nuovo.getFoto()).containsExactly(resta.getId());

        // Ripristinata, torna anche nel link di prima.
        service.ripristina(via.getId());
        assertThat(condivisioni.foto(link)).extracting(Foto::getId).containsExactly(resta.getId(), via.getId());
    }

    // ------------------------------------------------------------ utilità

    private Foto importa(String nome, Color colore, String data, String album) throws IOException {
        var esito = service.importa(nome, immagine(colore), Instant.parse(data), album);
        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        return esito.foto();
    }

    private void nelCestinoDa(Foto foto, Duration quanto) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(foto.getId())),
                new Update().set(FotoService.CESTINO, Instant.now().minus(quanto).truncatedTo(ChronoUnit.MILLIS)),
                Foto.class);
    }

    private static byte[] immagine(Color colore) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.setColor(Color.WHITE);
        g.fillRect(colore.getRGB() & 0x1f, 3, 7, 5);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }
}
