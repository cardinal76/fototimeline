package it.fototimeline.quasiuguali;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.fototimeline.dominio.Foto;
import it.fototimeline.quasiuguali.CalcoloImpronte.Stato;
import it.fototimeline.quasiuguali.QuasiUguali.FotoSimile;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.service.Risultati.Esito;

@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class QuasiUgualiTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    FotoService fotoService;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    QuasiUguali quasiUguali;

    @Autowired
    CalcoloImpronte calcolo;

    @BeforeEach
    void svuota() {
        repository.findAll().forEach(f -> fotoService.elimina(f.getId()));
        mongo.dropCollection(QuasiUguali.IGNORATI);
        quasiUguali.invalida();
    }

    /** La stessa scena in alta risoluzione e "passata da WhatsApp", più una foto diversa. */
    private Foto[] importaTre() throws IOException {
        var scena = Immagini.scena(1, 1200, 900);
        Foto grande = importa("grande.png", Immagini.png(scena), "2024-05-03T10:00:00Z");
        Foto piccola = importa("IMG-20240504-WA0001.jpg",
                Immagini.jpeg(Immagini.ridimensiona(scena, 600, 450), 0.3f), "2024-05-04T18:00:00Z");
        Foto diversa = importa("diversa.png", Immagini.png(Immagini.scena(2, 1200, 900)), "2024-05-03T10:00:00Z");
        return new Foto[] {grande, piccola, diversa};
    }

    private Foto importa(String nome, byte[] dati, String data) {
        var esito = fotoService.importa(nome, dati, Instant.parse(data), null);
        assertThat(esito.esito()).as(nome).isEqualTo(Esito.CARICATA);
        return esito.foto();
    }

    private static Map<String, FotoSimile> perId(List<FotoSimile> foto) {
        return foto.stream().collect(Collectors.toMap(FotoSimile::id, Function.identity()));
    }

    @Test
    void lImprontaSiCalcolaAllImportazioneEIGruppiSuggerisconoLaPiuGrande() throws IOException {
        Foto[] f = importaTre();
        assertThat(repository.findAll()).allSatisfy(foto -> assertThat(foto.getImpronta()).isNotNull());

        var pagina = quasiUguali.gruppi(0, 20);
        assertThat(pagina.totale()).isEqualTo(1);
        List<FotoSimile> gruppo = pagina.gruppi().getFirst().foto();
        assertThat(gruppo).extracting(FotoSimile::id).containsExactly(f[0].getId(), f[1].getId());
        var foto = perId(gruppo);
        assertThat(foto.get(f[0].getId()).suggerita()).isTrue();
        assertThat(foto.get(f[0].getId()).tieni()).isTrue();
        assertThat(foto.get(f[0].getId()).miniatura()).isEqualTo("/api/foto/" + f[0].getId() + "/miniatura");
        assertThat(foto.get(f[1].getId()).suggerita()).isFalse();
        assertThat(foto.get(f[1].getId()).tieni()).isFalse();
        assertThat(foto.get(f[1].getId()).larghezza()).isEqualTo(600);
    }

    @Test
    void aParitaDiRisoluzioneVinceIlFilePiuGrandePoiLExifPoiLaDataPiuVecchia() {
        Foto a = foto("a", 100, 100, 1000, null, "2024-01-02T00:00:00");
        Foto b = foto("b", 100, 100, 2000, null, "2024-01-02T00:00:00");
        Foto c = foto("c", 100, 100, 2000, "Apple iPhone 13", "2024-01-03T00:00:00");
        Foto d = foto("d", 100, 100, 2000, "Apple iPhone 13", "2024-01-01T00:00:00");
        Foto e = foto("e", 200, 100, 10, null, "2024-01-05T00:00:00");
        assertThat(List.of(a, b, c, d, e).stream().sorted(QuasiUguali.DA_TENERE).map(Foto::getId))
                .containsExactly("e", "d", "c", "b", "a");
    }

    private static Foto foto(String id, int larghezza, int altezza, long dimensione, String fotocamera, String data) {
        Foto f = new Foto();
        f.setId(id);
        f.setLarghezza(larghezza);
        f.setAltezza(altezza);
        f.setDimensione(dimensione);
        f.setFotocamera(fotocamera);
        f.setScattataIl(java.time.LocalDateTime.parse(data));
        return f;
    }

    @Test
    void unaPreferitaNonSiProponeMaiDaTogliere() throws IOException {
        Foto[] f = importaTre();
        fotoService.modifica(f[1].getId(), new Modifica(null, null, List.of(), null, true, null));

        var foto = perId(quasiUguali.gruppi(0, 20).gruppi().getFirst().foto());
        assertThat(foto.get(f[1].getId()).suggerita()).isFalse();
        assertThat(foto.get(f[1].getId()).preferita()).isTrue();
        assertThat(foto.get(f[1].getId()).tieni()).isTrue();
        // E non si toglie nemmeno chiedendolo.
        assertThatThrownBy(() -> quasiUguali.risolvi(List.of(f[0].getId()), List.of(f[1].getId())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("preferite");
        assertThat(repository.existsById(f[1].getId())).isTrue();
    }

    @Test
    void risolviTogliSoloLeFotoDaTogliere() throws IOException {
        Foto[] f = importaTre();
        Path originalePiccola = fotoService.fileOriginale(f[1]);
        Path miniaturaPiccola = fotoService.fileMiniatura(f[1]);

        var esito = quasiUguali.risolvi(List.of(f[0].getId()), List.of(f[1].getId()));

        assertThat(esito.eliminate()).isEqualTo(1);
        assertThat(repository.findAll()).extracting(Foto::getId).containsExactlyInAnyOrder(f[0].getId(), f[2].getId());
        assertThat(originalePiccola).doesNotExist();
        assertThat(miniaturaPiccola).doesNotExist();
        assertThat(fotoService.fileOriginale(f[0])).exists();
        assertThat(quasiUguali.gruppi(0, 20).totale()).isZero();
        assertThatThrownBy(() -> quasiUguali.risolvi(List.of(), List.of(f[0].getId())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nonSonoDoppioniNonRicompare() throws IOException {
        Foto[] f = importaTre();
        quasiUguali.ignora(List.of(f[0].getId(), f[1].getId()));
        assertThat(quasiUguali.gruppi(0, 20).totale()).isZero();
        // Anche rifacendo i gruppi da capo.
        quasiUguali.invalida();
        assertThat(quasiUguali.gruppi(0, 20).totale()).isZero();
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void tenendoneDueQuelleDueNonSonoPiuDoppioni() throws IOException {
        Foto[] f = importaTre();
        var scena = Immagini.scena(1, 1200, 900);
        Foto media = importa("media.jpg", Immagini.jpeg(Immagini.ridimensiona(scena, 900, 675), 0.6f),
                "2024-05-05T10:00:00Z");
        assertThat(quasiUguali.gruppi(0, 20).gruppi().getFirst().foto()).hasSize(3);

        quasiUguali.risolvi(List.of(f[0].getId(), media.getId()), List.of(f[1].getId()));

        quasiUguali.invalida();
        assertThat(quasiUguali.gruppi(0, 20).totale()).isZero();
        assertThat(repository.count()).isEqualTo(3);
    }

    @Test
    void calcolaImprontePerLeFotoVecchieEDueVolteNonRifaNiente() throws Exception {
        importaTre();
        Map<String, Long> prima = repository.findAll().stream().collect(Collectors.toMap(Foto::getId, Foto::getImpronta));
        mongo.updateMulti(new Query(), new Update().unset("impronta"), Foto.class);
        assertThat(calcolo.stato().senzaImpronta()).isEqualTo(3);
        quasiUguali.invalida();
        assertThat(quasiUguali.gruppi(0, 20).totale()).isZero();

        var fine = aspetta(calcolo.avvia());
        assertThat(fine.stato()).isEqualTo(Stato.FINITO);
        assertThat(fine.totale()).isEqualTo(3);
        assertThat(fine.calcolate()).isEqualTo(3);
        assertThat(fine.errori()).isZero();
        assertThat(fine.senzaImpronta()).isZero();
        assertThat(repository.findAll().stream().collect(Collectors.toMap(Foto::getId, Foto::getImpronta)))
                .isEqualTo(prima);
        assertThat(quasiUguali.gruppi(0, 20).totale()).isEqualTo(1);

        var ancora = aspetta(calcolo.avvia());
        assertThat(ancora.totale()).isZero();
        assertThat(ancora.calcolate()).isZero();
    }

    private CalcoloImpronte.StatoCalcolo aspetta(CalcoloImpronte.StatoCalcolo avviato) throws InterruptedException {
        assertThat(avviato.stato()).isEqualTo(Stato.IN_CORSO);
        long limite = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        var stato = calcolo.stato();
        while (stato.stato() == Stato.IN_CORSO && System.nanoTime() < limite) {
            Thread.sleep(50);
            stato = calcolo.stato();
        }
        return stato;
    }
}
