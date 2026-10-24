package it.fototimeline.luoghi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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
import it.fototimeline.dominio.OrigineLuogo;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Operazione;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati.PuntoMappa;

/**
 * I luoghi su più livelli (città, provincia, regione, nazione) come "tag"
 * automatici dal GPS, e il luogo scelto a mano: una foto, una selezione, il
 * ritorno al GPS, la mappa, il cestino e il ricalcolo delle foto di prima.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.luoghi.cartella=classpath:geonames",
})
@AutoConfigureMockMvc
class LuoghiManualiTest {

    private static final String MANCHESTER = "citta:2643123";
    private static final String MANCHESTER_USA = "citta:5089178";
    private static final String AMSTERDAM = "citta:2759794";
    private static final String TRENTO = "citta:3165243";

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
    LuoghiService luoghi;

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.deleteAll();
    }

    @Test
    void ricercaEFiltroPerRegioneProvinciaENazione() throws Exception {
        scheda(46.0700, 11.1200); // Trento
        scheda(45.8904, 11.0405); // Rovereto
        scheda(52.3731, 4.8926); // Amsterdam
        scheda(53.4794, -2.2453); // Manchester
        scheda(41.9, 12.5); // Roma
        luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);

        // La ricerca libera: "Trentino" trova tutto il Trentino, "Olanda" i Paesi Bassi.
        assertThat(cerca(new FiltroFoto("Trentino", null, null, null, null, null))).isEqualTo(2);
        assertThat(cerca(new FiltroFoto("provincia di trento", null, null, null, null, null))).isEqualTo(2);
        assertThat(cerca(new FiltroFoto("olanda", null, null, null, null, null))).isEqualTo(1);
        assertThat(cerca(new FiltroFoto("Paesi Bassi", null, null, null, null, null))).isEqualTo(1);
        assertThat(cerca(new FiltroFoto("inghilterra", null, null, null, null, null))).isEqualTo(1);
        assertThat(cerca(new FiltroFoto("Regno Unito", null, null, null, null, null))).isEqualTo(1);
        // Il filtro: nazione, regione, provincia.
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "IT", "Trentino-Alto Adige", null)))
                .isEqualTo(2);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "IT", "Trentino-Alto Adige", null)
                .conProvincia("Provincia di Trento"))).isEqualTo(2);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "NL", null, null))).isEqualTo(1);
        assertThat(cerca(new FiltroFoto(null, null, null, null, null, null, "GB", "Inghilterra", "Manchester")))
                .isEqualTo(1);

        mvc.perform(get("/api/foto").param("nazione", "IT").param("provincia", "Provincia di Trento"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(2));
        mvc.perform(get("/api/foto").param("q", "Olanda"))
                .andExpect(jsonPath("$.totale").value(1))
                .andExpect(jsonPath("$.foto[0].luogo").value("Amsterdam"))
                .andExpect(jsonPath("$.foto[0].regione").value("Olanda Settentrionale"))
                .andExpect(jsonPath("$.foto[0].nazione").value("Paesi Bassi"))
                .andExpect(jsonPath("$.foto[0].origineLuogo").value("GPS"));
        // L'elenco raggruppato: nazione → regione (→ provincia in Italia) → città.
        mvc.perform(get("/api/luoghi"))
                .andExpect(jsonPath("$.nazioni[0].codice").value("IT"))
                .andExpect(jsonPath("$.nazioni[0].conteggio").value(3))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].nome").value("Trentino-Alto Adige"))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].conteggio").value(2))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].province[0].nome").value("Provincia di Trento"))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].province[0].conteggio").value(2))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].luoghi.length()").value(2))
                .andExpect(jsonPath("$.nazioni[0].regioni[0].luoghi[0].provincia").value("Provincia di Trento"))
                .andExpect(jsonPath("$.nazioni[?(@.codice == 'NL')].altriNomi[0]").value("Olanda"))
                .andExpect(jsonPath("$.nazioni[?(@.codice == 'GB')].regioni[0].nome").value("Inghilterra"));
        mvc.perform(get("/api/luoghi/cerca").param("q", "Manch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].chiave").value(MANCHESTER))
                .andExpect(jsonPath("$[0].livello").value("CITTA"))
                .andExpect(jsonPath("$[0].regione").value("Inghilterra"))
                .andExpect(jsonPath("$[0].nazione").value("Regno Unito"));
    }

    @Test
    void unLuogoManualeNonLoSovrascriveIlRicalcolo() throws Exception {
        Foto foto = scheda(53.4794, -2.2453);
        luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);
        assertThat(rileggi(foto).getLuogo()).isEqualTo("Manchester");

        // Il GPS dice Manchester, ma la foto è di Amsterdam.
        mvc.perform(put("/api/foto/" + foto.getId() + "/luogo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chiave\":\"" + AMSTERDAM + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.luogo").value("Amsterdam"))
                .andExpect(jsonPath("$.nazione").value("Paesi Bassi"))
                .andExpect(jsonPath("$.origineLuogo").value("MANUALE"));

        luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);
        lavori.avviaLuoghi(true);
        assertThat(aspettaFine().trovate()).isZero();
        Foto dopo = rileggi(foto);
        assertThat(dopo.getLuogo()).isEqualTo("Amsterdam");
        assertThat(dopo.getRegione()).isEqualTo("Olanda Settentrionale");
        assertThat(dopo.getOrigineLuogo()).isEqualTo(OrigineLuogo.MANUALE);
        assertThat(luoghi.elenco().daCalcolare()).isZero();

        // "Nessun luogo" a mano: resta senza anche dopo il ricalcolo.
        mvc.perform(put("/api/foto/" + foto.getId() + "/luogo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chiave\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.luogo").doesNotExist())
                .andExpect(jsonPath("$.origineLuogo").value("MANUALE"));
        luoghi.calcola(true, LuoghiService.Avanzamento.NESSUNO);
        assertThat(rileggi(foto).getLuogo()).isNull();
        assertThat(rileggi(foto).getNazione()).isNull();

        // Una chiave che non è un luogo: 400, la foto resta com'è.
        mvc.perform(put("/api/foto/" + foto.getId() + "/luogo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chiave\":\"citta:1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/foto/sconosciuta/luogo").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"chiave\":\"" + AMSTERDAM + "\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void siTornaAlGps() throws Exception {
        Foto foto = scheda(53.4794, -2.2453);
        Foto senzaGps = scheda(null, null);
        luoghi.imposta(List.of(foto.getId(), senzaGps.getId()), "nazione:NL");
        assertThat(rileggi(foto).getNazione()).isEqualTo("Paesi Bassi");
        assertThat(rileggi(foto).getLuogo()).isNull();

        mvc.perform(delete("/api/foto/" + foto.getId() + "/luogo").with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.luogo").value("Manchester"))
                .andExpect(jsonPath("$.regione").value("Inghilterra"))
                .andExpect(jsonPath("$.origineLuogo").value("GPS"))
                .andExpect(jsonPath("$.latitudineLuogo").doesNotExist());
        // Senza GPS, "usa il GPS" vuol dire nessun luogo, e il calcolo non la prende.
        service.operazioneMultipla(List.of(senzaGps.getId()), Operazione.LUOGO_DA_GPS, null);
        Foto s = rileggi(senzaGps);
        assertThat(s.getNazione()).isNull();
        assertThat(s.getOrigineLuogo()).isNull();
        assertThat(luoghi.elenco().nazioni()).extracting(LuoghiService.VoceNazione::codice).containsExactly("GB");
    }

    @Test
    void luogoSullaSelezioneMultipla() {
        Foto conGps = scheda(41.9, 12.5);
        Foto senzaGps = scheda(null, null);
        Foto nelCestino = scheda(null, null);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(nelCestino.getId())),
                new Update().set(FotoService.CESTINO, Instant.now()), Foto.class);

        long cambiate = service.operazioneMultipla(List.of(conGps.getId(), senzaGps.getId(), nelCestino.getId()),
                Operazione.IMPOSTA_LUOGO, "provincia:IT.17.TN");
        assertThat(cambiate).isEqualTo(2);
        for (Foto f : List.of(conGps, senzaGps)) {
            Foto letta = rileggi(f);
            assertThat(letta.getLuogo()).isNull();
            assertThat(letta.getProvincia()).isEqualTo("Provincia di Trento");
            assertThat(letta.getRegione()).isEqualTo("Trentino-Alto Adige");
            assertThat(letta.getNazione()).isEqualTo("Italia");
            assertThat(letta.getOrigineLuogo()).isEqualTo(OrigineLuogo.MANUALE);
        }
        assertThat(rileggi(nelCestino).getProvincia()).isNull();
        assertThat(cerca(new FiltroFoto("trentino", null, null, null, null, null))).isEqualTo(2);
        var italia = luoghi.elenco().nazioni().getFirst();
        assertThat(italia.conteggio()).isEqualTo(2);
        assertThat(italia.regioni().getFirst().province()).singleElement()
                .satisfies(p -> assertThat(p.conteggio()).isEqualTo(2));
        // Una regione o una provincia non è una città: nell'elenco non c'è nessuna città.
        assertThat(italia.regioni().getFirst().luoghi()).isEmpty();

        // "Nessun luogo" su tutte, poi di nuovo il GPS.
        service.operazioneMultipla(List.of(conGps.getId(), senzaGps.getId()), Operazione.IMPOSTA_LUOGO, "");
        assertThat(luoghi.elenco().nazioni()).isEmpty();
        service.operazioneMultipla(List.of(conGps.getId(), senzaGps.getId()), Operazione.LUOGO_DA_GPS, null);
        assertThat(rileggi(conGps).getLuogo()).isEqualTo("Roma");
        assertThat(rileggi(senzaGps).getLuogo()).isNull();
        assertThatThrownBy(() -> service.operazioneMultipla(List.of(conGps.getId()), Operazione.IMPOSTA_LUOGO, "boh"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void senzaGpsSullaMappaAlLuogoSceltoApprossimativa() {
        Foto senzaGps = scheda(null, null);
        Foto gpsSbagliato = scheda(53.4794, -2.2453); // a Manchester, ma era nel New Hampshire
        Foto gpsVicino = scheda(45.8904, 11.0405); // Rovereto, segnata come Trento: 20 km, resta il GPS
        Foto soloRegione = scheda(null, null);
        luoghi.imposta(List.of(senzaGps.getId()), MANCHESTER);
        luoghi.imposta(List.of(gpsSbagliato.getId()), MANCHESTER_USA);
        luoghi.imposta(List.of(gpsVicino.getId()), TRENTO);
        luoghi.imposta(List.of(soloRegione.getId()), "regione:GB.ENG");

        List<PuntoMappa> punti = service.mappa(new FiltroFoto(null, null, null, null, null, null));
        assertThat(punti).extracting(PuntoMappa::id)
                .containsExactlyInAnyOrder(senzaGps.getId(), gpsSbagliato.getId(), gpsVicino.getId());
        PuntoMappa p = punto(punti, senzaGps);
        assertThat(p.approssimativa()).isTrue();
        assertThat(p.lat()).isCloseTo(53.481, org.assertj.core.data.Offset.offset(0.001));
        assertThat(p.lon()).isCloseTo(-2.237, org.assertj.core.data.Offset.offset(0.001));
        assertThat(p.luogo()).isEqualTo("Manchester");
        PuntoMappa lontano = punto(punti, gpsSbagliato);
        assertThat(lontano.approssimativa()).isTrue();
        assertThat(lontano.lat()).isCloseTo(42.996, org.assertj.core.data.Offset.offset(0.001));
        PuntoMappa vicino = punto(punti, gpsVicino);
        assertThat(vicino.approssimativa()).isFalse();
        assertThat(vicino.lat()).isEqualTo(45.8904);
        assertThat(vicino.luogo()).isEqualTo("Trento");
        // Il filtro per luogo vale anche per la mappa.
        assertThat(service.mappa(new FiltroFoto(null, null, null, null, null, null, "GB", null, null)))
                .extracting(PuntoMappa::id).containsExactly(senzaGps.getId());
    }

    @Test
    void ilCestinoNonContaNeiLuoghi() {
        Foto a = scheda(null, null);
        Foto b = scheda(52.3731, 4.8926);
        luoghi.imposta(List.of(a.getId()), AMSTERDAM);
        luoghi.calcola(false, LuoghiService.Avanzamento.NESSUNO);
        assertThat(luoghi.elenco().nazioni()).singleElement().satisfies(n -> assertThat(n.conteggio()).isEqualTo(2));
        for (Foto f : List.of(a, b)) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is(f.getId())),
                    new Update().set(FotoService.CESTINO, Instant.now()), Foto.class);
        }
        assertThat(luoghi.elenco().nazioni()).isEmpty();
        assertThat(service.mappa(new FiltroFoto(null, null, null, null, null, null))).isEmpty();
        assertThat(cerca(new FiltroFoto("olanda", null, null, null, null, null))).isZero();
        // Nel cestino non si cambia il luogo, né da solo né dalla selezione.
        assertThat(luoghi.imposta(List.of(a.getId()), MANCHESTER)).isZero();
        assertThat(luoghi.usaGps(List.of(a.getId()))).isZero();
        assertThat(rileggi(a).getLuogo()).isEqualTo("Amsterdam");
    }

    @Test
    void ilLavoroRifaLeFotoDiPrimaERiprendeDaDoveEraRimasto() throws Exception {
        // Le foto calcolate prima della provincia: luogo e regione sì, versione no.
        List<Foto> vecchie = new ArrayList<>();
        for (int i = 0; i < LuoghiService.GRUPPO + 10; i++) {
            Foto f = nuova(45.8904 + i * 1e-6, 11.0405);
            f.setLuogo("Rovereto");
            f.setRegione("Trentino-Alto Adige");
            f.setNazione("Italia");
            f.setCodiceNazione("IT");
            f.setLuogoCalcolato(true);
            vecchie.add(f);
        }
        repository.insert(vecchie);
        Foto manuale = scheda(45.8904, 11.0405);
        luoghi.imposta(List.of(manuale.getId()), AMSTERDAM);
        assertThat(luoghi.elenco().daCalcolare()).isEqualTo(LuoghiService.GRUPPO + 10);

        // Interrotto dopo il primo gruppo (annullato, o l'app riavviata).
        AtomicInteger fatte = new AtomicInteger();
        var calcolo = luoghi.calcola(false, new LuoghiService.Avanzamento() {
            @Override
            public void calcolate(int quante, int conLuogo) {
                fatte.addAndGet(quante);
            }

            @Override
            public boolean annullata() {
                return fatte.get() >= LuoghiService.GRUPPO;
            }
        });
        assertThat(calcolo.foto()).isEqualTo(LuoghiService.GRUPPO);
        assertThat(luoghi.elenco().daCalcolare()).isEqualTo(10);

        // Il giro dopo fa solo le rimaste.
        StatoLavoro partito = lavori.avviaLuoghi(false);
        assertThat(partito.stato()).isIn(Stato.IN_CORSO, Stato.FINITA);
        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.trovate()).isEqualTo(10);
        assertThat(luoghi.elenco().daCalcolare()).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("provincia").is("Provincia di Trento")), Foto.class))
                .isEqualTo(LuoghiService.GRUPPO + 10);
        assertThat(rileggi(manuale).getLuogo()).isEqualTo("Amsterdam");

        // Le foto nuove lo prendono all'importazione, già con la versione.
        byte[] jpg = getClass().getResourceAsStream("/con-exif.jpg").readAllBytes();
        Foto importata = service.importa("IMG_0001.JPG", jpg, Instant.parse("2025-01-01T00:00:00Z"), null).foto();
        Foto letta = rileggi(importata);
        assertThat(letta.getProvincia()).isEqualTo("Provincia di Roma");
        assertThat(letta.getOrigineLuogo()).isEqualTo(OrigineLuogo.GPS);
        assertThat(letta.getVersioneLuoghi()).isEqualTo(GeocodificaInversa.VERSIONE);
        service.elimina(importata.getId());
        service.eliminaPerSempre(importata.getId());
        mvc.perform(post("/api/archivio/luoghi").with(csrf())).andExpect(status().isAccepted());
        aspettaFine();
    }

    private static PuntoMappa punto(List<PuntoMappa> punti, Foto f) {
        return punti.stream().filter(p -> p.id().equals(f.getId())).findFirst().orElseThrow();
    }

    private long cerca(FiltroFoto filtro) {
        return service.cerca(filtro, 0, 10).totale();
    }

    private Foto rileggi(Foto f) {
        return repository.findById(f.getId()).orElseThrow();
    }

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
