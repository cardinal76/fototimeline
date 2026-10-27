package it.fototimeline.eventi;

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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.eventi.EventiService.Avanzamento;
import it.fototimeline.eventi.EventiService.Calcolo;
import it.fototimeline.eventi.EventiService.VoceEvento;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.EventiFoto.FotoAggiunta;
import it.fototimeline.service.EventiFoto.FotoDataCambiata;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.Periodo;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

/**
 * Gli eventi su un archivio di schede senza file (basta Mongo): casa a Roma,
 * un viaggio a Sperlonga di due giorni, una festa di una sera.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.eventi.minimo-foto=5",
        "fototimeline.eventi.ritardo-iniziale=PT1H",
})
@AutoConfigureMockMvc
class EventiTest {

    static final LuogoFoto ROMA = new LuogoFoto("Roma", "Provincia di Roma", "Lazio", "Italia", "IT");
    static final LuogoFoto SPERLONGA = new LuogoFoto("Sperlonga", "Provincia di Latina", "Lazio", "Italia", "IT");
    static final LuogoFoto GAETA = new LuogoFoto("Gaeta", "Provincia di Latina", "Lazio", "Italia", "IT");

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    EventiService eventi;

    @Autowired
    FotoService fotoService;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    ApplicationEventPublisher pubblica;

    @Autowired
    MockMvc mvc;

    /** Tre giorni a casa con poche foto. */
    List<Foto> casa;
    /** 10-11 agosto 2019, 4 + 4 foto. */
    List<Foto> viaggio;
    /** 12 maggio 2021 sera, 6 foto. */
    List<Foto> festa;

    @BeforeEach
    void prepara() throws Exception {
        aspettaFine();
        repository.deleteAll();
        mongo.dropCollection(Evento.class);
        mongo.dropCollection(EventiService.DA_RIFARE);
        mongo.remove(Query.query(Criteria.where("_id").is(EventiService.ID_STATO)), "impostazioni");
        casa = new ArrayList<>();
        for (int g = 1; g <= 3; g++) {
            casa.addAll(schede(LocalDateTime.of(2019, 3, g, 18, 0), 3, ROMA));
        }
        // Senza GPS anche una foto del viaggio: resta dentro per vicinanza.
        viaggio = new ArrayList<>(schede(LocalDateTime.of(2019, 8, 10, 10, 0), 3, SPERLONGA));
        viaggio.addAll(schede(LocalDateTime.of(2019, 8, 10, 10, 30), 1, null));
        viaggio.addAll(schede(LocalDateTime.of(2019, 8, 11, 9, 0), 4, SPERLONGA));
        festa = schede(LocalDateTime.of(2021, 5, 12, 21, 0), 6, ROMA);
        casa.addAll(schede(LocalDateTime.of(2021, 5, 13, 18, 0), 3, ROMA));
        eventi.dimenticaCasa();
    }

    @Test
    void ilCalcoloFaGliEventiSopraLaSoglia() throws Exception {
        Calcolo c = eventi.calcola(true, Avanzamento.NESSUNO);

        assertThat(c.completo()).isTrue();
        assertThat(c.foto()).isEqualTo(26);
        assertThat(c.inEventi()).isEqualTo(14);
        assertThat(c.eventi()).isEqualTo(2);
        List<VoceEvento> elenco = eventi.elenco(false).eventi();
        assertThat(elenco).extracting(VoceEvento::nome)
                .containsExactly("Roma, 12 maggio 2021", "Sperlonga, 10–11 agosto 2019");
        VoceEvento sperlonga = elenco.get(1);
        assertThat(sperlonga.conteggio()).isEqualTo(8);
        assertThat(sperlonga.copertina()).isEqualTo(viaggio.getFirst().getId());
        assertThat(sperlonga.luogo()).isEqualTo("Sperlonga");
        // I giorni a casa con poche foto non sono eventi.
        assertThat(casa).allSatisfy(f -> assertThat(evento(f)).isNull());
        assertThat(viaggio).allSatisfy(f -> assertThat(evento(f)).isEqualTo(sperlonga.id()));

        // Il filtro per evento, combinabile con gli altri.
        FiltroFoto tutte = new FiltroFoto(null, null, null, null, null, null);
        assertThat(fotoService.cerca(tutte.conEvento(sperlonga.id()), 0, 50).totale()).isEqualTo(8);
        assertThat(fotoService.cerca(new FiltroFoto(null, null, null, null, null, null, "IT", null, "Sperlonga")
                .conEvento(sperlonga.id()), 0, 50).totale()).isEqualTo(7);
        assertThat(fotoService.timeline(tutte.conEvento(sperlonga.id()))).singleElement()
                .satisfies(m -> assertThat(m.conteggio()).isEqualTo(8));
        mvc.perform(get("/api/foto").param("evento", sperlonga.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(8))
                .andExpect(jsonPath("$.foto[0].evento").value(sperlonga.id()));
        mvc.perform(get("/api/eventi"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventi.length()").value(2))
                .andExpect(jsonPath("$.calcolo.interrotto").value(false))
                .andExpect(jsonPath("$.minimoFoto").value(5));

        // Rifarlo non cambia niente.
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.elenco(false).eventi()).extracting(VoceEvento::id)
                .containsExactlyElementsOf(elenco.stream().map(VoceEvento::id).toList());
    }

    @Test
    void rinominaUnioneEDivisioneSopravvivonoAlRicalcolo() throws Exception {
        eventi.calcola(true, Avanzamento.NESSUNO);
        String sperlonga = evento(viaggio.getFirst());
        String sera = evento(festa.getFirst());

        eventi.rinomina(sperlonga, "Vacanze a Sperlonga");
        List<VoceEvento> due = eventi.dividi(sera, festa.get(3).getId());
        assertThat(due).extracting(VoceEvento::conteggio).containsExactly(3, 3);
        String secondaParte = due.get(1).id();
        eventi.calcola(true, Avanzamento.NESSUNO);

        assertThat(eventi.evento(sperlonga).orElseThrow().nome()).isEqualTo("Vacanze a Sperlonga");
        // Sotto la soglia, ma fatti a mano: restano.
        assertThat(eventi.evento(sera).orElseThrow().conteggio()).isEqualTo(3);
        assertThat(eventi.evento(secondaParte).orElseThrow().conteggio()).isEqualTo(3);
        assertThat(festa.subList(3, 6)).allSatisfy(f -> assertThat(evento(f)).isEqualTo(secondaParte));
        assertThat(eventi.elenco(false).eventi()).hasSize(3);

        VoceEvento unito = eventi.unisci(List.of(secondaParte, sera));
        assertThat(unito.id()).isEqualTo(sera);
        assertThat(unito.conteggio()).isEqualTo(6);
        assertThat(unito.nome()).isEqualTo("Roma, 12 maggio 2021");
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.elenco(false).eventi()).extracting(VoceEvento::id).containsExactly(sera, sperlonga);
        assertThat(festa).allSatisfy(f -> assertThat(evento(f)).isEqualTo(sera));

        // Una foto tolta resta fuori; una aggiunta resta dentro.
        eventi.togli(List.of(viaggio.getLast().getId()));
        eventi.aggiungi(sperlonga, List.of(casa.getFirst().getId()));
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(evento(viaggio.getLast())).isNull();
        assertThat(evento(casa.getFirst())).isEqualTo(sperlonga);
        assertThat(eventi.evento(sperlonga).orElseThrow().conteggio()).isEqualTo(8);

        // Nascosto: fuori dall'elenco (e dai nomi), le foto restano.
        eventi.nascondi(sperlonga, true);
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.elenco(false).eventi()).extracting(VoceEvento::id).containsExactly(sera);
        assertThat(eventi.elenco(true).eventi()).hasSize(2);
        assertThat(eventi.nomi(List.of(sperlonga, sera))).containsOnlyKeys(sera);

        // "Torna automatico": il ricalcolo lo rifà da sé (senza la foto tolta a mano).
        assertThat(eventi.sciogli(sperlonga)).isTrue();
        eventi.calcola(true, Avanzamento.NESSUNO);
        String rifatto = evento(viaggio.getFirst());
        assertThat(rifatto).isNotNull();
        assertThat(eventi.evento(rifatto).orElseThrow().manuale()).isFalse();
        assertThat(eventi.evento(rifatto).orElseThrow().conteggio()).isEqualTo(7);
        assertThat(evento(casa.getFirst())).isNull();
    }

    @Test
    void unEventoNuovoDaUnaSelezione() throws Exception {
        String ids = casa.subList(0, 3).stream().map(f -> "\"" + f.getId() + "\"").collect(Collectors.joining(","));
        String risposta = mvc.perform(post("/api/eventi").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Compleanno di Anna\",\"ids\":[" + ids + "]}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Compleanno di Anna"))
                .andExpect(jsonPath("$.conteggio").value(3))
                .andExpect(jsonPath("$.manuale").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = risposta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(casa.subList(0, 3)).allSatisfy(f -> assertThat(evento(f)).isEqualTo(id));

        mvc.perform(put("/api/eventi/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Roma, 1 marzo 2019"));
        mvc.perform(post("/api/eventi/unisci").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isBadRequest());
        assertThatThrownBy(() -> eventi.dividi(id, casa.getFirst().getId())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unaFotoNuovaEntraNellEventoGiusto() throws Exception {
        eventi.calcola(true, Avanzamento.NESSUNO);
        String sperlonga = evento(viaggio.getFirst());
        String sera = evento(festa.getFirst());
        eventi.rinomina(sera, "Festa");
        // Le foto nuove sono caricate dopo l'ultima modifica a mano.
        Thread.sleep(5);

        Foto nelViaggio = scheda(LocalDateTime.of(2019, 8, 10, 15, 0), SPERLONGA);
        Foto allaFesta = scheda(LocalDateTime.of(2021, 5, 12, 21, 5), null);
        List<Foto> gita = schede(LocalDateTime.of(2022, 6, 2, 10, 0), 5, GAETA);
        for (Foto f : List.of(nelViaggio, allaFesta)) {
            pubblica.publishEvent(new FotoAggiunta(f.getId()));
        }
        gita.forEach(f -> pubblica.publishEvent(new FotoAggiunta(f.getId())));
        assertThat(eventi.elenco(false).giorniDaRifare()).isEqualTo(3);

        assertThat(eventi.ricalcolaGiorniToccati()).isEqualTo(3);

        assertThat(evento(nelViaggio)).isEqualTo(sperlonga);
        assertThat(eventi.evento(sperlonga).orElseThrow().conteggio()).isEqualTo(9);
        // Nella festa fatta a mano entra, e resta fissa.
        assertThat(evento(allaFesta)).isEqualTo(sera);
        assertThat(repository.findById(allaFesta.getId()).orElseThrow().isEventoFisso()).isTrue();
        assertThat(eventi.evento(sera).orElseThrow().conteggio()).isEqualTo(7);
        // Un giorno nuovo con abbastanza foto è un evento nuovo.
        String nuovo = evento(gita.getFirst());
        assertThat(nuovo).isNotNull();
        assertThat(eventi.evento(nuovo).orElseThrow().nome()).isEqualTo("Gaeta, 2 giugno 2022");
        assertThat(eventi.elenco(false).giorniDaRifare()).isZero();
    }

    @Test
    void leFotoNelCestinoNonContanoERipristinateTornano() throws Exception {
        eventi.calcola(true, Avanzamento.NESSUNO);
        String sera = evento(festa.getFirst());
        for (Foto f : festa.subList(0, 4)) {
            assertThat(fotoService.elimina(f.getId())).isTrue();
        }
        assertThat(eventi.evento(sera).orElseThrow().conteggio()).isEqualTo(2);

        eventi.ricalcolaGiorniToccati();
        // Restano 2 foto: sotto la soglia, niente evento.
        assertThat(eventi.evento(sera)).isEmpty();
        assertThat(evento(festa.get(5))).isNull();
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.elenco(false).eventi()).hasSize(1);

        for (Foto f : festa.subList(0, 4)) {
            assertThat(fotoService.ripristina(f.getId())).isTrue();
        }
        eventi.ricalcolaGiorniToccati();
        assertThat(eventi.evento(sera).orElseThrow().conteggio()).isEqualTo(6);
        assertThat(festa).allSatisfy(f -> assertThat(evento(f)).isEqualTo(sera));
    }

    @Test
    void ilCalcoloInterrottoRiprendeDaDoveEraArrivato() throws Exception {
        for (int g = 1; g <= 6; g++) {
            schede(LocalDateTime.of(2023, 9, g * 2, 20, 0), 5, ROMA);
        }
        eventi.calcola(true, Avanzamento.NESSUNO);
        Set<String> attesi = istantanea();
        mongo.dropCollection(Evento.class);
        mongo.updateMulti(new Query(), new org.springframework.data.mongodb.core.query.Update().unset("evento"), Foto.class);

        // Fermato dopo i primi gruppi, come un Annulla (o un riavvio).
        int[] gruppi = {0};
        Calcolo fermato = eventi.calcola(true, new Avanzamento() {
            @Override
            public void raggruppate(int quante, int inEventi) {
                gruppi[0]++;
            }

            @Override
            public boolean annullata() {
                return gruppi[0] >= 3;
            }
        });
        assertThat(fermato.completo()).isFalse();
        assertThat(eventi.stato().interrotto()).isTrue();
        assertThat(eventi.stato().fattoFinoA()).isNotNull();

        int[] totale = {0};
        Calcolo ripreso = eventi.calcola(false, new Avanzamento() {
            @Override
            public void inizio(int quante) {
                totale[0] = quante;
            }
        });
        assertThat(ripreso.ripreso()).isTrue();
        assertThat(ripreso.completo()).isTrue();
        assertThat(totale[0]).isPositive().isLessThan(56);
        assertThat(eventi.stato().interrotto()).isFalse();
        assertThat(istantanea()).isEqualTo(attesi);
    }

    @Test
    void calcolaEventiEUnLavoroConLaBarra() throws Exception {
        mvc.perform(post("/api/archivio/eventi").with(csrf()).param("daCapo", "true"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.origine").value("EVENTI"));
        StatoLavoro fine = aspettaFine();
        assertThat(fine.origine()).isEqualTo(Origine.EVENTI);
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.trovate()).isEqualTo(26);
        assertThat(fine.fatte()).isEqualTo(26);
        assertThat(fine.importate()).isEqualTo(14);
        assertThat(fine.duplicate()).isEqualTo(12);
        assertThat(eventi.elenco(false).eventi()).hasSize(2);
    }

    // ------------------------------------------------------------ utilità

    @Test
    void unEventoPerDatePrendeTutteLeFotoDeiSuoiGiorni() throws Exception {
        eventi.calcola(true, Avanzamento.NESSUNO);
        String sperlonga = evento(viaggio.getFirst());
        List<Foto> primoGiorno = viaggio.subList(0, 4);
        List<Foto> secondoGiorno = viaggio.subList(4, 8);

        String risposta = mvc.perform(post("/api/eventi/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Vacanze al mare\",\"dal\":\"2019-08-11\",\"al\":\"2019-08-15\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nome").value("Vacanze al mare"))
                .andExpect(jsonPath("$.conteggio").value(4))
                .andExpect(jsonPath("$.manuale").value(true))
                .andExpect(jsonPath("$.dal").value("2019-08-11"))
                .andExpect(jsonPath("$.al").value("2019-08-15"))
                .andReturn().getResponse().getContentAsString();
        String id = risposta.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        // Vince sull'automatico: le foto dell'11 sono sue, fisse; l'automatico tiene il 10.
        assertThat(secondoGiorno).allSatisfy(f -> {
            assertThat(evento(f)).isEqualTo(id);
            assertThat(repository.findById(f.getId()).orElseThrow().isEventoFisso()).isTrue();
        });
        assertThat(eventi.evento(sperlonga).orElseThrow().conteggio()).isEqualTo(4);

        // Il calcolo (anche da capo) non lo tocca: il 10 da solo è sotto la soglia.
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.evento(id).orElseThrow().conteggio()).isEqualTo(4);
        assertThat(secondoGiorno).allSatisfy(f -> assertThat(evento(f)).isEqualTo(id));
        assertThat(primoGiorno).allSatisfy(f -> assertThat(evento(f)).isNull());

        // Una foto nuova nei suoi giorni entra, come una a cui si cambia la data a mano.
        Foto nuova = scheda(LocalDateTime.of(2019, 8, 13, 12, 0), null);
        pubblica.publishEvent(new FotoAggiunta(nuova.getId()));
        eventi.ricalcolaGiorniToccati();
        assertThat(evento(nuova)).isEqualTo(id);
        Foto spostata = casa.getFirst();
        spostata.setScattataIl(LocalDateTime.of(2019, 8, 14, 9, 0));
        repository.save(spostata);
        pubblica.publishEvent(new FotoDataCambiata(spostata.getId()));
        assertThat(evento(spostata)).isEqualTo(id);
        assertThat(eventi.evento(id).orElseThrow().conteggio()).isEqualTo(6);

        // Il filtro per evento della timeline vale anche per lui.
        FiltroFoto tutte = new FiltroFoto(null, null, null, null, null, null);
        assertThat(fotoService.cerca(tutte.conEvento(id), 0, 100).totale()).isEqualTo(6);

        // Due eventi per date non si sovrappongono.
        mvc.perform(post("/api/eventi/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Altro\",\"dal\":\"2019-08-01\",\"al\":\"2019-08-11\"}"))
                .andExpect(status().isBadRequest());

        // Date nuove: escono quelle fuori, entrano quelle dentro (anche dal 10, che era fuori da ogni evento).
        mvc.perform(put("/api/eventi/" + id + "/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Sperlonga in famiglia\",\"dal\":\"2019-08-10\",\"al\":\"2019-08-10\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nome").value("Sperlonga in famiglia"))
                .andExpect(jsonPath("$.conteggio").value(4));
        assertThat(primoGiorno).allSatisfy(f -> assertThat(evento(f)).isEqualTo(id));
        for (Foto f : List.of(secondoGiorno.getFirst(), nuova, spostata)) {
            Foto letta = repository.findById(f.getId()).orElseThrow();
            assertThat(letta.getEvento()).isNull();
            assertThat(letta.isEventoFisso()).isFalse();
        }

        // Le date le ha solo un evento per date; unire e dividere non valgono per lui.
        String festaId = evento(festa.getFirst());
        mvc.perform(put("/api/eventi/" + festaId + "/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Festa\",\"dal\":\"2021-05-12\",\"al\":\"2021-05-12\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(delete("/api/eventi/" + festaId + "/per-date").with(csrf())).andExpect(status().isBadRequest());
        assertThatThrownBy(() -> eventi.unisci(List.of(id, festaId))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> eventi.dividi(id, primoGiorno.get(2).getId()))
                .isInstanceOf(IllegalArgumentException.class);
        mvc.perform(put("/api/eventi/" + id).with(csrf()).contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"\"}"))
                .andExpect(status().isBadRequest());

        // Eliminato: le foto tornano al calcolo automatico.
        mvc.perform(delete("/api/eventi/" + id + "/per-date").with(csrf())).andExpect(status().isNoContent());
        assertThat(eventi.evento(id)).isEmpty();
        eventi.ricalcolaGiorniToccati();
        String rifatto = evento(viaggio.getFirst());
        assertThat(rifatto).isNotNull().isNotEqualTo(id);
        assertThat(eventi.evento(rifatto).orElseThrow().manuale()).isFalse();
        assertThat(primoGiorno).allSatisfy(f -> assertThat(evento(f)).isEqualTo(rifatto));
        mvc.perform(delete("/api/eventi/" + id + "/per-date").with(csrf())).andExpect(status().isNotFound());
    }

    @Test
    void unEventoPerDateRestaAncheSenzaFoto() throws Exception {
        VoceEvento futuro = eventi.creaPerDate(" Viaggio in Giappone ", java.time.LocalDate.of(2030, 4, 1),
                java.time.LocalDate.of(2030, 4, 10));
        assertThat(futuro.nome()).isEqualTo("Viaggio in Giappone");
        assertThat(futuro.conteggio()).isZero();
        assertThat(futuro.primoGiorno()).isEqualTo("2030-04-01");
        assertThat(eventi.elenco(false).eventi()).extracting(VoceEvento::id).contains(futuro.id());
        eventi.calcola(true, Avanzamento.NESSUNO);
        assertThat(eventi.evento(futuro.id())).isPresent();

        Foto arrivata = scheda(LocalDateTime.of(2030, 4, 3, 8, 0), null);
        pubblica.publishEvent(new FotoAggiunta(arrivata.getId()));
        eventi.ricalcolaGiorniToccati();
        assertThat(evento(arrivata)).isEqualTo(futuro.id());

        mvc.perform(post("/api/eventi/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\" \",\"dal\":\"2031-01-01\",\"al\":\"2031-01-02\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/eventi/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Al contrario\",\"dal\":\"2031-01-05\",\"al\":\"2031-01-02\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/eventi/per-date").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nome\":\"Senza fine\",\"dal\":\"2031-01-05\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ilFiltroPerDataSiCombinaConGliAltri() throws Exception {
        // Anno: tutte le foto del 2019 (casa a marzo, viaggio ad agosto).
        mvc.perform(get("/api/foto").param("periodo", "2019")).andExpect(jsonPath("$.totale").value(17));
        // Mese, con un altro filtro.
        mvc.perform(get("/api/foto").param("periodo", "2019-08")).andExpect(jsonPath("$.totale").value(8));
        mvc.perform(get("/api/foto").param("periodo", "2019-08").param("luogo", "Sperlonga"))
                .andExpect(jsonPath("$.totale").value(7));
        // Due giorni, compresi; un lato vuoto non ha limite.
        mvc.perform(get("/api/foto").param("periodo", "2019-03-02..2019-08-10")).andExpect(jsonPath("$.totale").value(10));
        mvc.perform(get("/api/foto").param("periodo", "2021-05-13..")).andExpect(jsonPath("$.totale").value(3));
        mvc.perform(get("/api/foto").param("periodo", "..2019-03-01")).andExpect(jsonPath("$.totale").value(3));
        // Col salto della timeline (al) valgono tutti e due.
        mvc.perform(get("/api/foto").param("periodo", "2019").param("al", "2019-03-31"))
                .andExpect(jsonPath("$.totale").value(9));
        // La colonna dei mesi segue il periodo (il salto no).
        mvc.perform(get("/api/timeline").param("periodo", "2019"))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].mese").value(8))
                .andExpect(jsonPath("$[1].mese").value(3));
        mvc.perform(get("/api/foto").param("periodo", "2019-13")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/foto").param("periodo", "2019-09-01..2019-08-01")).andExpect(status().isBadRequest());

        // Con gli eventi: le foto di un evento in un periodo.
        eventi.calcola(true, Avanzamento.NESSUNO);
        String sperlonga = evento(viaggio.getFirst());
        FiltroFoto tutte = new FiltroFoto(null, null, null, null, null, null);
        assertThat(fotoService.cerca(tutte.conEvento(sperlonga).conPeriodo(Periodo.leggi("2019-08-11")), 0, 10)
                .totale()).isEqualTo(4);
        assertThat(fotoService.cerca(tutte.conEvento(sperlonga).conPeriodo(Periodo.leggi("2020")), 0, 10)
                .totale()).isZero();
    }

    /** Ogni evento con le sue foto, per confrontare due calcoli. */
    private Set<String> istantanea() {
        return repository.findAll().stream()
                .map(f -> f.getId() + "→" + (f.getEvento() == null ? "-"
                        : f.getEvento() + " " + eventi.evento(f.getEvento()).orElseThrow().nome()))
                .collect(Collectors.toSet());
    }

    private String evento(Foto f) {
        return repository.findById(f.getId()).orElseThrow().getEvento();
    }

    /** {@code quante} schede ogni 10 minuti da {@code inizio}. */
    private List<Foto> schede(LocalDateTime inizio, int quante, LuogoFoto luogo) {
        List<Foto> foto = new ArrayList<>();
        for (int i = 0; i < quante; i++) {
            foto.add(scheda(inizio.plusMinutes(10L * i), luogo));
        }
        return foto;
    }

    /** Una scheda senza file. */
    private Foto scheda(LocalDateTime quando, LuogoFoto luogo) {
        Foto f = new Foto();
        f.setId(UUID.randomUUID().toString());
        f.setNomeOriginale("prova.jpg");
        f.setContentType("image/jpeg");
        f.setHash(UUID.randomUUID().toString());
        f.setScattataIl(quando);
        f.setOrigineData(OrigineData.EXIF);
        f.setCaricataIl(Instant.now());
        f.setPercorso(quando.toLocalDate().toString().replace('-', '/') + "/" + f.getId() + ".jpg");
        if (luogo != null) {
            f.setLuogo(luogo.luogo());
            f.setProvincia(luogo.provincia());
            f.setRegione(luogo.regione());
            f.setNazione(luogo.nazione());
            f.setCodiceNazione(luogo.codiceNazione());
            f.setLuogoCalcolato(true);
        }
        return repository.insert(f);
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
        throw new AssertionError("Lavoro ancora in corso dopo 30 secondi");
    }
}
