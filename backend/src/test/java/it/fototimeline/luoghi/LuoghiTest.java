package it.fototimeline.luoghi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati.Esito;

/** Luoghi dal GPS con il mini dataset di GeoNames in src/test/resources/geonames. */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.luoghi.cartella=classpath:geonames",
})
@AutoConfigureMockMvc
class LuoghiTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    LuoghiService luoghi;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        // Le schede di prova non hanno file: basta Mongo.
        repository.deleteAll();
    }

    @Test
    void ilLuogoSiCalcolaAllImportazione() throws Exception {
        byte[] jpg = getClass().getResourceAsStream("/con-exif.jpg").readAllBytes();

        var esito = service.importa("IMG_0001.JPG", jpg, Instant.parse("2025-01-01T00:00:00Z"), null);

        assertThat(esito.esito()).isEqualTo(Esito.CARICATA);
        Foto foto = repository.findById(esito.foto().getId()).orElseThrow();
        assertThat(foto.getLuogo()).isEqualTo("Roma");
        assertThat(foto.getRegione()).isEqualTo("Lazio");
        assertThat(foto.getNazione()).isEqualTo("Italia");
        assertThat(foto.getCodiceNazione()).isEqualTo("IT");
        assertThat(foto.isLuogoCalcolato()).isTrue();
        assertThat(service.mappa(new FiltroFoto(null, null, null, null, null, null)))
                .singleElement().satisfies(p -> assertThat(p.luogo()).isEqualTo("Roma"));
        service.elimina(foto.getId());
    }

    @Test
    void calcolaLuoghiEIdempotente() throws Exception {
        Foto roma = scheda(41.9, 12.5);
        Foto sperlonga = scheda(41.2545, 13.43);
        Foto mare = scheda(40.5, 11.0);
        Foto senzaGps = scheda(null, null);

        StatoLavoro partito = lavori.avviaLuoghi(false);
        assertThat(partito.origine()).isEqualTo(Origine.LUOGHI);
        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.trovate()).isEqualTo(3);
        assertThat(fine.fatte()).isEqualTo(3);
        assertThat(fine.importate()).isEqualTo(2);
        assertThat(fine.duplicate()).isEqualTo(1);

        assertThat(repository.findById(roma.getId()).orElseThrow().getLuogo()).isEqualTo("Roma");
        Foto s = repository.findById(sperlonga.getId()).orElseThrow();
        assertThat(s.getLuogo()).isEqualTo("Sperlonga");
        assertThat(s.getRegione()).isEqualTo("Lazio");
        Foto m = repository.findById(mare.getId()).orElseThrow();
        assertThat(m.getLuogo()).isNull();
        assertThat(m.isLuogoCalcolato()).isTrue();
        assertThat(repository.findById(senzaGps.getId()).orElseThrow().isLuogoCalcolato()).isFalse();

        // Di nuovo: non resta niente da fare.
        lavori.avviaLuoghi(false);
        assertThat(aspettaFine().trovate()).isZero();
        assertThat(luoghi.elenco().daCalcolare()).isZero();

        // "tutte": rifà le tre con GPS, con lo stesso risultato.
        lavori.avviaLuoghi(true);
        StatoLavoro tutte = aspettaFine();
        assertThat(tutte.trovate()).isEqualTo(3);
        assertThat(tutte.importate()).isEqualTo(2);
        assertThat(repository.findById(sperlonga.getId()).orElseThrow().getLuogo()).isEqualTo("Sperlonga");
    }

    @Test
    void conMoltiDocumentiVaAGruppi() throws Exception {
        var schede = new java.util.ArrayList<Foto>();
        for (int i = 0; i < LuoghiService.GRUPPO * 2 + 7; i++) {
            schede.add(nuova(48.85 + i * 1e-5, 2.35));
        }
        repository.insert(schede);
        var calcolo = luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);
        assertThat(calcolo.foto()).isEqualTo(LuoghiService.GRUPPO * 2 + 7);
        assertThat(calcolo.conLuogo()).isEqualTo(calcolo.foto());
        assertThat(service.cerca(new FiltroFoto(null, null, null, null, null, null, "FR", null, "Parigi"), 0, 1).totale())
                .isEqualTo(LuoghiService.GRUPPO * 2 + 7);
    }

    @Test
    void filtroPerLuogoEElencoConIConteggi() throws Exception {
        scheda(41.9, 12.5);
        scheda(41.89, 12.49);
        scheda(41.2545, 13.43);
        scheda(48.8584, 2.2945);
        scheda(40.5, 11.0);
        luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);

        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "IT", null, null))).isEqualTo(3);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "it", "Lazio", "Roma"))).isEqualTo(2);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, null, null, "Sperlonga"))).isEqualTo(1);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "FR", null, null))).isEqualTo(1);
        // La ricerca libera trova anche i luoghi.
        assertThat(cerca(new FiltroFoto("parigi", null, null, null, null, null))).isEqualTo(1);
        assertThat(service.timeline(new FiltroFoto(null, null, null, null, null, null, "IT", "Lazio", "Roma")))
                .singleElement().satisfies(m -> assertThat(m.conteggio()).isEqualTo(2));

        mvc.perform(get("/api/foto").param("nazione", "IT").param("luogo", "Sperlonga"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(1))
                .andExpect(jsonPath("$.foto[0].luogo").value("Sperlonga"))
                .andExpect(jsonPath("$.foto[0].nazione").value("Italia"));
        mvc.perform(get("/api/luoghi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(true))
                .andExpect(jsonPath("$.daCalcolare").value(0))
                .andExpect(jsonPath("$.nazioni.length()").value(2))
                .andExpect(jsonPath("$.nazioni[0].codice").value("IT"))
                .andExpect(jsonPath("$.nazioni[0].nome").value("Italia"))
                .andExpect(jsonPath("$.nazioni[0].conteggio").value(3))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].nome").value("Lazio"))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].luoghi[0].nome").value("Roma"))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].luoghi[0].conteggio").value(2))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].luoghi[1].nome").value("Sperlonga"))
                .andExpect(jsonPath("$.nazioni[1].nome").value("Francia"))
                .andExpect(jsonPath("$.nazioni[1].regioni[0].luoghi[0].nome").value("Parigi"));
        mvc.perform(post("/api/archivio/luoghi").with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.origine").value("LUOGHI"));
    }

    @Test
    void unSecondoLavoroInsiemeNonParte() throws Exception {
        var schede = new java.util.ArrayList<Foto>();
        for (int i = 0; i < 1500; i++) {
            schede.add(nuova(41.9, 12.5));
        }
        repository.insert(schede);
        lavori.avviaLuoghi(false);
        // Può essere già finito: se è ancora in corso, il secondo viene rifiutato.
        if (lavori.corrente().orElseThrow().stato() == Stato.IN_CORSO) {
            assertThatThrownBy(() -> lavori.avviaLuoghi(false)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(aspettaFine().stato()).isEqualTo(Stato.FINITA);
    }

    private long cerca(FiltroFoto filtro) {
        return service.cerca(filtro, 0, 10).totale();
    }

    /** Una scheda senza file, solo con la posizione. */
    private Foto scheda(Double lat, Double lon) {
        return repository.insert(nuova(lat, lon));
    }

    private static Foto nuova(Double lat, Double lon) {
        Foto f = new Foto();
        f.setId(UUID.randomUUID().toString());
        f.setNomeOriginale("prova.jpg");
        f.setContentType("image/jpeg");
        f.setHash(UUID.randomUUID().toString());
        f.setScattataIl(LocalDateTime.of(2024, 6, 1, 12, 0));
        f.setOrigineData(OrigineData.EXIF);
        f.setCaricataIl(Instant.now());
        f.setPercorso("2024/06/01/" + f.getId() + ".jpg");
        f.setLatitudine(lat);
        f.setLongitudine(lon);
        return f;
    }

    private StatoLavoro aspettaFine() throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(limite)) {
            var stato = lavori.corrente();
            if (stato.isEmpty() || stato.get().stato() != Stato.IN_CORSO) {
                return stato.orElse(null);
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Calcolo dei luoghi ancora in corso dopo 30 secondi");
    }
}
