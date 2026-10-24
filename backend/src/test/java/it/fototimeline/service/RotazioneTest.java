package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;

import it.fototimeline.condivisione.Condivisione;
import it.fototimeline.condivisione.CondivisioniService;
import it.fototimeline.condivisione.CondivisioniService.NuovaCondivisione;
import it.fototimeline.dominio.Foto;
import it.fototimeline.media.JpegRuotato;
import it.fototimeline.media.Riduzione;
import it.fototimeline.media.StrumentiMedia;
import it.fototimeline.quasiuguali.Impronta;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.Risultati.Esito;

/**
 * "Ruota": la rotazione sta nella scheda e vale per miniature, visore,
 * download e link condivisi; l'originale nell'archivio resta identico.
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
@AutoConfigureMockMvc
class RotazioneTest {

    @TempDir
    static Path disco;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> disco.resolve("archivio").toString());
    }

    @Autowired
    FotoService service;

    @Autowired
    Rotazioni rotazioni;

    @Autowired
    FotoRepository repository;

    @Autowired
    CondivisioniService condivisioni;

    @Autowired
    StrumentiMedia media;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
    }

    @Test
    void laRotazioneSiSalvaESiSomma() throws IOException {
        Foto foto = importa("a.png", metaRossaMetaBlu(64, 32, "png"));
        assertThat(foto.getRotazione()).isZero();

        Foto girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();
        assertThat(girata.getRotazione()).isEqualTo(90);
        assertThat(girata.getLarghezza()).isEqualTo(32);
        assertThat(girata.getAltezza()).isEqualTo(64);

        // 90 + 90 = 180: lati di nuovo com'erano.
        girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();
        assertThat(girata.getRotazione()).isEqualTo(180);
        assertThat(girata.getLarghezza()).isEqualTo(64);
        assertThat(repository.findById(foto.getId()).orElseThrow().getRotazione()).isEqualTo(180);

        // 180 + 90 = 270, 270 + 90 = 0.
        assertThat(rotazioni.ruota(foto.getId(), 90).orElseThrow().getRotazione()).isEqualTo(270);
        girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();
        assertThat(girata.getRotazione()).isZero();
        assertThat(girata.getLarghezza()).isEqualTo(64);
        assertThat(girata.getAltezza()).isEqualTo(32);

        // A sinistra da dritta: 270.
        assertThat(rotazioni.ruota(foto.getId(), -90).orElseThrow().getRotazione()).isEqualTo(270);
    }

    @Test
    void dalVisoreConLApiEConGradiNonValidi() throws Exception {
        Foto foto = importa("a.png", metaRossaMetaBlu(64, 32, "png"));

        mvc.perform(post("/api/foto/{id}/ruota", foto.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\": -90}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rotazione").value(270))
                .andExpect(jsonPath("$.larghezza").value(32));
        mvc.perform(post("/api/foto/{id}/ruota", foto.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\": 45}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/foto/{id}/ruota", "non-esiste").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\": 90}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void laMiniaturaSiRifaGirataConILatiScambiati() throws Exception {
        Foto foto = importa("a.png", metaRossaMetaBlu(64, 32, "png"));
        BufferedImage prima = ImageIO.read(service.fileMiniatura(foto).toFile());
        assertThat(prima.getWidth()).isGreaterThan(prima.getHeight());

        Foto girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();

        BufferedImage dopo = ImageIO.read(service.fileMiniatura(girata).toFile());
        assertThat(dopo.getWidth()).isEqualTo(prima.getHeight());
        assertThat(dopo.getHeight()).isEqualTo(prima.getWidth());
        // A destra di 90°: la metà rossa (sinistra) va in alto, la blu in basso.
        assertThat(colore(dopo, 0.5, 0.2)).isEqualTo(Color.RED);
        assertThat(colore(dopo, 0.5, 0.8)).isEqualTo(Color.BLUE);
        // La dritta resta (i volti stanno su di lei), e /miniatura dà la girata.
        assertThat(ImageIO.read(service.fileMiniaturaDritta(girata).toFile()).getWidth()).isEqualTo(prima.getWidth());
        byte[] servita = mvc.perform(get("/api/foto/{id}/miniatura", foto.getId()).param("r", "90"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(ImageIO.read(new ByteArrayInputStream(servita)).getHeight()).isEqualTo(prima.getWidth());

        // Tornata dritta: la girata di prima si toglie.
        Path vecchia = service.fileMiniatura(girata);
        rotazioni.ruota(foto.getId(), 270);
        assertThat(vecchia).doesNotExist();
    }

    @Test
    void lOriginaleNellArchivioNonCambia() throws Exception {
        Foto foto = importa("IMG_0001.jpg", getClass().getResourceAsStream("/con-exif.jpg").readAllBytes());
        Path originale = service.fileOriginale(foto);
        byte[] prima = Files.readAllBytes(originale);

        rotazioni.ruota(foto.getId(), 90);
        rotazioni.ruota(foto.getId(), 180);
        // Anche un download girato non scrive niente nell'archivio.
        mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true")).andExpect(status().isOk());

        Foto dopo = repository.findById(foto.getId()).orElseThrow();
        assertThat(dopo.getRotazione()).isEqualTo(270);
        assertThat(Files.readAllBytes(originale)).isEqualTo(prima);
        assertThat(sha256(Files.readAllBytes(originale))).isEqualTo(foto.getHash()).isEqualTo(dopo.getHash());
        // E ricaricarlo dice "già presente", non una foto nuova.
        assertThat(service.importa("copia.jpg", prima, null, null).esito()).isEqualTo(Esito.DUPLICATA);
    }

    @Test
    void ilDownloadDiUnJpegConOrientamentoEGiratoSenzaPerdita() throws Exception {
        // con-exif.jpg: 64×32 con Orientation 6 (girata di 90°), cioè in verticale.
        byte[] jpg = getClass().getResourceAsStream("/con-exif.jpg").readAllBytes();
        Foto foto = importa("IMG_0001.jpg", jpg);
        assertThat(foto.getLarghezza()).isEqualTo(32);

        Foto girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();
        assertThat(girata.getLarghezza()).isEqualTo(64);

        byte[] scaricata = mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true").param("r", "90"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        // 6 + 90° = 3 (180°): stessi pixel compressi, solo il tag diverso.
        assertThat(orientamento(scaricata)).isEqualTo(3);
        assertThat(scaricata).hasSameSizeAs(jpg);
        assertThat(pixel(scaricata)).isEqualTo(pixel(jpg));
        assertThat(sha256(scaricata)).isNotEqualTo(foto.getHash());

        // "Scarica l'originale": i byte dell'archivio.
        byte[] originale = mvc.perform(get("/api/foto/{id}/file", foto.getId())
                        .param("scarica", "true").param("originale", "true"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(originale).isEqualTo(jpg);
    }

    @Test
    void visoreEDownloadSiVedonoGirati() throws Exception {
        // Un JPEG metà rosso e metà blu che è già girato di 90° con l'EXIF (Orientation 6).
        Path dritto = disco.resolve("dritto.jpg");
        Files.write(dritto, metaRossaMetaBlu(64, 32, "jpg"));
        var testa = JpegRuotato.prepara(dritto, 90);
        var conOrientamento = new ByteArrayOutputStream();
        JpegRuotato.scrivi(dritto, testa, conOrientamento);
        Foto foto = importa("girato.jpg", conOrientamento.toByteArray());
        // Com'è: girata di 90°, il rosso in alto.
        assertThat(colore(ImageIO.read(service.fileMiniatura(foto).toFile()), 0.5, 0.2)).isEqualTo(Color.RED);

        Foto girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();

        // Altri 90°: 180 in tutto, il rosso a destra. Miniatura, vista e download dicono lo stesso.
        assertThat(colore(ImageIO.read(service.fileMiniatura(girata).toFile()), 0.8, 0.5)).isEqualTo(Color.RED);
        byte[] vista = mvc.perform(get("/api/foto/{id}/vista", foto.getId()).param("r", "180"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(colore(comeSiVede(vista), 0.8, 0.5)).isEqualTo(Color.RED);
        byte[] scaricata = mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(orientamento(scaricata)).isEqualTo(3);
        assertThat(colore(comeSiVede(scaricata), 0.8, 0.5)).isEqualTo(Color.RED);
    }

    @Test
    void unPngSiVedeGiratoMaSiScaricaComE() throws Exception {
        byte[] png = metaRossaMetaBlu(64, 32, "png");
        Foto foto = importa("a.png", png);
        rotazioni.ruota(foto.getId(), 90);

        var risposta = mvc.perform(get("/api/foto/{id}/vista", foto.getId()).param("r", "90"))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(risposta.getContentType()).isEqualTo("image/jpeg");
        BufferedImage vista = ImageIO.read(new ByteArrayInputStream(risposta.getContentAsByteArray()));
        assertThat(vista.getWidth()).isEqualTo(32);
        assertThat(vista.getHeight()).isEqualTo(64);
        assertThat(colore(vista, 0.5, 0.2)).isEqualTo(Color.RED);

        byte[] scaricato = mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(scaricato).isEqualTo(png);
    }

    @Test
    void laSelezioneMultiplaLeGiraTutteTranneQuelleNelCestino() throws Exception {
        Foto a = importa("a.png", metaRossaMetaBlu(64, 32, "png"));
        Foto b = importa("b.png", metaRossaMetaBlu(40, 30, "png"));
        Foto via = importa("c.png", metaRossaMetaBlu(50, 20, "png"));
        service.elimina(via.getId());

        mvc.perform(post("/api/foto/ruota").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\": [\"%s\", \"%s\", \"%s\"], \"gradi\": 90}".formatted(a.getId(), b.getId(), via.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modificate").value(2));

        assertThat(repository.findById(a.getId()).orElseThrow().getRotazione()).isEqualTo(90);
        assertThat(repository.findById(b.getId()).orElseThrow().getLarghezza()).isEqualTo(30);
        assertThat(repository.findById(via.getId()).orElseThrow().getRotazione()).isZero();
    }

    @Test
    void unaFotoNelCestinoNonSiGira() throws Exception {
        Foto foto = importa("a.png", metaRossaMetaBlu(64, 32, "png"));
        service.elimina(foto.getId());

        assertThat(rotazioni.ruota(foto.getId(), 90)).isEmpty();
        mvc.perform(post("/api/foto/{id}/ruota", foto.getId()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"gradi\": 90}"))
                .andExpect(status().isNotFound());
        Foto dopo = repository.findById(foto.getId()).orElseThrow();
        assertThat(dopo.getRotazione()).isZero();
        assertThat(dopo.getLarghezza()).isEqualTo(64);
    }

    @Test
    void ilLinkCondivisoMostraLaFotoGirata() throws Exception {
        Path dritto = disco.resolve("per-link.jpg");
        Files.write(dritto, metaRossaMetaBlu(64, 32, "jpg"));
        Foto foto = importa("festa.jpg", Files.readAllBytes(dritto));
        rotazioni.ruota(foto.getId(), 90);
        Condivisione c = condivisioni.crea(
                new NuovaCondivisione("Festa", List.of(foto.getId()), null, null, null, null, true, false), "marco");
        String base = "/api/condivise/" + c.getToken();

        mvc.perform(get(base))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.foto[0].rotazione").value(90))
                .andExpect(jsonPath("$.foto[0].larghezza").value(32))
                .andExpect(jsonPath("$.foto[0].altezza").value(64));
        // La vista ridotta e senza EXIF: i pixel sono già girati.
        BufferedImage vista = ImageIO.read(new ByteArrayInputStream(
                mvc.perform(get(base + "/foto/{id}/vista", foto.getId()).param("r", "90"))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        assertThat(vista.getHeight()).isGreaterThan(vista.getWidth());
        assertThat(colore(vista, 0.5, 0.2)).isEqualTo(Color.RED);
        BufferedImage miniatura = ImageIO.read(new ByteArrayInputStream(
                mvc.perform(get(base + "/foto/{id}/miniatura", foto.getId()))
                        .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray()));
        assertThat(colore(miniatura, 0.5, 0.2)).isEqualTo(Color.RED);
        // Originale e zip: girati col tag, gli stessi pixel.
        byte[] originale = mvc.perform(get(base + "/foto/{id}/originale", foto.getId()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(orientamento(originale)).isEqualTo(6);
        assertThat(pixel(originale)).isEqualTo(pixel(Files.readAllBytes(dritto)));
        byte[] zip = mvc.perform(get(base + "/zip")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        try (var in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            assertThat(in.getNextEntry()).isNotNull();
            assertThat(orientamento(in.readAllBytes())).isEqualTo(6);
        }
    }

    @Test
    void lImprontaSiRifaDaComeSiVede() throws IOException {
        // La stessa foto: una dritta, l'altra coricata (girata nei pixel, senza EXIF).
        BufferedImage dritta = disegno(96, 64);
        Foto a = importa("dritta.png", png(dritta));
        Foto b = importa("coricata.png", png(gira90(gira90(gira90(dritta)))));
        int prima = Impronta.distanza(a.getImpronta(), b.getImpronta());

        // Raddrizzata a mano: torna quasi uguale alla dritta.
        Foto raddrizzata = rotazioni.ruota(b.getId(), 90).orElseThrow();

        int dopo = Impronta.distanza(a.getImpronta(), raddrizzata.getImpronta());
        assertThat(dopo).isLessThanOrEqualTo(6).isLessThan(prima);
        assertThat(raddrizzata.getImpronta()).isEqualTo(Impronta.calcola(service.fileMiniatura(raddrizzata)));
        assertThat(raddrizzata.getContenutoIndicizzato()).isNull();
    }

    @Test
    void unHeicSiVedeGirato() throws Exception {
        assumeTrue(media.heicDisponibile(), "heif-convert non installato");
        Foto foto = importa("foto.heic", getClass().getResourceAsStream("/foto.heic").readAllBytes());
        int larghezza = foto.getLarghezza();
        int altezza = foto.getAltezza();

        Foto girata = rotazioni.ruota(foto.getId(), 90).orElseThrow();

        assertThat(girata.getLarghezza()).isEqualTo(altezza);
        byte[] vista = mvc.perform(get("/api/foto/{id}/vista", foto.getId()).param("r", "90"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(orientamento(vista)).isEqualTo(6);
        BufferedImage vede = comeSiVede(vista);
        assertThat(vede.getWidth()).isEqualTo(altezza);
        assertThat(vede.getHeight()).isEqualTo(larghezza);
        BufferedImage miniatura = ImageIO.read(service.fileMiniatura(girata).toFile());
        BufferedImage dritta = ImageIO.read(service.fileMiniaturaDritta(girata).toFile());
        assertThat(miniatura.getWidth()).isEqualTo(dritta.getHeight());
        assertThat(miniatura.getHeight()).isEqualTo(dritta.getWidth());
        // Il download resta l'HEIC dell'archivio.
        byte[] scaricato = mvc.perform(get("/api/foto/{id}/file", foto.getId()).param("scarica", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(scaricato)).isEqualTo(foto.getHash());
    }

    @Test
    void unVideoSiGiraSoloAVederlo() throws Exception {
        assumeTrue(media.videoDisponibile(), "ffmpeg/ffprobe non installati");
        Foto video = importa("video.mp4", getClass().getResourceAsStream("/video.mp4").readAllBytes());

        Foto girato = rotazioni.ruota(video.getId(), 90).orElseThrow();

        assertThat(girato.getRotazione()).isEqualTo(90);
        assertThat(girato.getLarghezza()).isEqualTo(video.getAltezza());
        BufferedImage miniatura = ImageIO.read(service.fileMiniatura(girato).toFile());
        BufferedImage dritta = ImageIO.read(service.fileMiniaturaDritta(girato).toFile());
        assertThat(miniatura.getWidth()).isEqualTo(dritta.getHeight());
        // Il file è quello di sempre.
        byte[] scaricato = mvc.perform(get("/api/foto/{id}/file", video.getId()).param("scarica", "true"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(sha256(scaricato)).isEqualTo(video.getHash());
    }

    // ---------------------------------------------------------------- aiuti

    private Foto importa(String nome, byte[] contenuto) {
        var esito = service.importa(nome, contenuto, null, null);
        assertThat(esito.esito()).as(esito.messaggio()).isEqualTo(Esito.CARICATA);
        return esito.foto();
    }

    /** Come la mostra un browser: con l'orientamento EXIF applicato. */
    private static BufferedImage comeSiVede(byte[] jpeg) throws IOException {
        Path sorgente = Files.createTempFile(disco, "vista-", ".jpg");
        Path dritta = Files.createTempFile(disco, "dritta-", ".jpg");
        Files.write(sorgente, jpeg);
        Riduzione.jpeg(sorgente, dritta, 4096, true, 0.95, false);
        return ImageIO.read(dritta.toFile());
    }

    private static Integer orientamento(byte[] jpeg) throws Exception {
        var ifd0 = ImageMetadataReader.readMetadata(new ByteArrayInputStream(jpeg))
                .getFirstDirectoryOfType(ExifIFD0Directory.class);
        return ifd0 == null ? null : ifd0.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
    }

    /** I pixel decodificati, senza orientamento: per dire "senza perdita". */
    private static int[] pixel(byte[] jpeg) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(jpeg));
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    /** Il colore più vicino fra rosso e blu, al punto (frazioni di larghezza e altezza). */
    private static Color colore(BufferedImage img, double x, double y) {
        Color c = new Color(img.getRGB((int) (img.getWidth() * x), (int) (img.getHeight() * y)));
        return c.getRed() > c.getBlue() ? Color.RED : Color.BLUE;
    }

    private static byte[] metaRossaMetaBlu(int l, int a, String formato) throws IOException {
        BufferedImage img = new BufferedImage(l, a, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, l / 2, a);
        g.setColor(Color.BLUE);
        g.fillRect(l / 2, 0, l - l / 2, a);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }

    /** Qualcosa con un verso: sfumatura, un cerchio in alto a sinistra e una barra in basso. */
    private static BufferedImage disegno(int l, int a) {
        BufferedImage img = new BufferedImage(l, a, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int x = 0; x < l; x++) {
            g.setColor(new Color(x * 255 / l, 80, 255 - x * 255 / l));
            g.drawLine(x, 0, x, a);
        }
        g.setColor(Color.WHITE);
        g.fillOval(l / 10, a / 10, l / 3, a / 3);
        g.setColor(Color.BLACK);
        g.fillRect(l / 2, a * 3 / 4, l / 2, a / 6);
        g.dispose();
        return img;
    }

    /** 90° in senso orario, nei pixel. */
    private static BufferedImage gira90(BufferedImage img) {
        BufferedImage g = new BufferedImage(img.getHeight(), img.getWidth(), BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                g.setRGB(img.getHeight() - 1 - y, x, img.getRGB(x, y));
            }
        }
        return g;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static String sha256(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
