package it.fototimeline.volti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import it.fototimeline.contenuto.Visione;
import it.fototimeline.contenuto.VisioneNonRisponde;
import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;
import it.fototimeline.volti.Persone.Tipo;
import it.fototimeline.volti.Persone.VocePersona;
import it.fototimeline.volti.RiconoscimentoVolti.Stato;
import it.fototimeline.volti.RiconoscimentoVolti.StatoLavoro;
import it.fototimeline.volti.VisioneVolti.ImmagineVolti;
import it.fototimeline.volti.VisioneVolti.Rilevamento;
import it.fototimeline.volti.VisioneVolti.Vicino;
import it.fototimeline.volti.VisioneVolti.VoltoRilevato;

/**
 * Volti e persone con un servizio visione finto: nella miniatura il "volto"
 * è la metà sinistra (o destra) non nera dell'immagine, e il suo vettore è
 * il colore medio. Due rossi sono la stessa persona (coseno quasi 1), rosso e
 * verde no (0,17). L'indice dei vicini è una mappa qui nel test.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.visione.url=http://visione.invalid:8000",
        "fototimeline.visione.segreto=prova",
        "fototimeline.volti.blocco=2",
        "fototimeline.volti.attesa=PT0S",
})
@AutoConfigureMockMvc
class VoltiTest {

    private static final Color ROSSO = new Color(240, 20, 20);
    private static final Color ROSSO_2 = new Color(225, 45, 30);
    private static final Color VERDE = new Color(20, 240, 20);
    private static final Color BLU = new Color(20, 20, 240);
    /** Coseno col rosso ~0,52: una proposta (fra 0,42 e 0,6), non abbastanza per darlo da solo. */
    private static final Color VIOLA = new Color(100, 0, 200);
    /** Coseno col rosso ~0,47: sotto la soglia del gruppo (0,5), sopra quella delle proposte. */
    private static final Color VIOLA_SCURO = new Color(90, 0, 210);
    private static final FiltroFoto TUTTE = new FiltroFoto(null, null, null, null, null, null);

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @MockitoBean
    VisioneVolti visione;

    /** La ricerca per contenuto c'è anche lei (stesso visione.url): qui non fa niente. */
    @MockitoBean
    Visione clip;

    @Autowired
    RiconoscimentoVolti riconoscimento;

    @Autowired
    Persone persone;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    MockMvc mvc;

    /** L'indice dei volti di visione: id del volto → vettore. */
    private final Map<String, float[]> indice = new ConcurrentHashMap<>();
    private final AtomicInteger giorno = new AtomicInteger();

    @BeforeEach
    void svuota() {
        aspettaLavoro();
        when(visione.rileva(anyMap(), anyInt(), anyDouble())).thenAnswer(i -> rileva(i.getArgument(0)));
        doAnswer(i -> {
            indice.putAll(i.getArgument(0));
            return null;
        }).when(visione).metti(anyMap());
        doAnswer(i -> {
            i.<Collection<String>>getArgument(0).forEach(indice::remove);
            return null;
        }).when(visione).togli(anyCollection());
        when(visione.vicini(anyList(), anyInt(), anyDouble()))
                .thenAnswer(i -> vicini(i.getArgument(0), i.getArgument(1), i.getArgument(2)));
        when(visione.totale()).thenAnswer(i -> (long) indice.size());
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        aspetta(riconoscimento::codaVuota);
        mongo.remove(new Query(), Volto.class);
        mongo.remove(new Query(), Persona.class);
        mongo.remove(Query.query(Criteria.where("_id").is(RiconoscimentoVolti.ID)), "impostazioni");
        indice.clear();
    }

    // ------------------------------------------------------------ raggruppamento

    @Test
    void leFotoNuoveSiGuardanoDaSoleEIVoltiSimiliFannoUnGruppo() throws Exception {
        Foto a = importa(ROSSO);
        Foto b = importa(ROSSO_2);
        Foto c = importa(ROSSO);
        Foto verde = importa(VERDE);
        Foto vuota = importa(Color.BLACK);

        assertThat(mongo.count(new Query(), Volto.class)).isEqualTo(4);
        assertThat(ricarica(vuota).getVoltiCercati()).isNotNull();
        assertThat(indice).hasSize(4);
        // Il primo rosso da solo non fa gruppo; col secondo sì, il terzo ci entra. Il verde resta da solo.
        var gruppi = persone.elenco(Tipo.GRUPPI, 100).persone();
        assertThat(gruppi).singleElement().satisfies(g -> {
            assertThat(g.nome()).isNull();
            assertThat(g.volti()).isEqualTo(3);
            assertThat(g.copertina()).isNotNull();
        });
        String gruppo = gruppi.getFirst().id();
        assertThat(voltiDi(a).getFirst().getPersonaId()).isEqualTo(gruppo);
        assertThat(voltiDi(verde).getFirst().getPersonaId()).isNull();
        assertThat(ricarica(a).getPersone()).containsExactly(gruppo);
        assertThat(ricarica(verde).getPersone()).isNull();

        // Il filtro "persona" vale anche per un gruppo senza nome, e si combina con gli altri.
        assertThat(cerca(gruppo)).containsExactlyInAnyOrder(a.getId(), b.getId(), c.getId());
        service.modifica(b.getId(), new Modifica(null, null, List.of(), "Mare", false, null));
        assertThat(service.cerca(filtro(gruppo, "Mare"), 0, 10).foto()).extracting(Foto::getId).containsExactly(b.getId());
        assertThat(service.timeline(filtro(gruppo, null))).singleElement().satisfies(m -> assertThat(m.conteggio()).isEqualTo(3));
        mvc.perform(get("/api/foto").param("persona", gruppo)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(3));
        mvc.perform(get("/api/mappa").param("persona", gruppo)).andExpect(status().isOk());
        mvc.perform(get("/api/persone").param("tipo", "GRUPPI")).andExpect(status().isOk())
                .andExpect(jsonPath("$.persone[0].id").value(gruppo))
                .andExpect(jsonPath("$.persone[0].volti").value(3));
    }

    @Test
    void nominaEUnione() throws Exception {
        Foto a = importa(ROSSO);
        importa(ROSSO_2);
        Foto blu = importa(BLU);
        importa(BLU);
        var gruppi = persone.elenco(Tipo.GRUPPI, 100).persone();
        assertThat(gruppi).hasSize(2);
        String rossi = gruppoDi(a);
        String blui = gruppoDi(blu);

        mvc.perform(put("/api/persone/" + rossi).contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"  Anna  \"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Anna"));
        assertThat(persone.elenco(Tipo.NOMINATE, 100).persone()).extracting(VocePersona::nome).containsExactly("Anna");
        assertThat(persone.elenco(Tipo.GRUPPI, 100).persone()).extracting(VocePersona::id).containsExactly(blui);

        // Un'altra "anna" no: si uniscono, e la risposta dice con chi.
        mvc.perform(put("/api/persone/" + blui).contentType(MediaType.APPLICATION_JSON).content("{\"nome\":\"anna\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.persona").value(rossi));

        mvc.perform(post("/api/persone/unisci").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"in\":\"" + rossi + "\",\"da\":[\"" + blui + "\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Anna")).andExpect(jsonPath("$.volti").value(4));
        mvc.perform(get("/api/persone/" + blui)).andExpect(status().isNotFound());
        assertThat(ricarica(blu).getPersone()).containsExactly(rossi);
        assertThat(cerca(rossi)).hasSize(4);

        // Un gruppo senza nome unito a una persona con nome: il nome resta.
        Foto verde = importa(VERDE);
        Foto verde2 = importa(VERDE);
        String verdi = gruppoDi(verde);
        assertThat(persone.unisci(verdi, List.of(rossi)).nome()).isEqualTo("Anna");
        assertThat(ricarica(verde2).getPersone()).containsExactly(verdi);
        assertThat(ricarica(a).getPersone()).containsExactly(verdi);

        // Nome vuoto: torna un gruppo.
        assertThat(persone.nomina(verdi, " ").nome()).isNull();
        assertThatThrownBy(() -> persone.nomina("non-c-e", "X")).hasMessageContaining("non trovata");
    }

    // ------------------------------------------------------------ assegnazione automatica

    @Test
    void iVoltiVicinoAUnaPersonaSiAssegnanoDaSoliSopraLaSogliaSicuraSottoRestanoProposte() throws Exception {
        Foto a = importa(ROSSO);
        importa(ROSSO_2);
        String anna = gruppoDi(a);
        persone.nomina(anna, "Anna");

        Foto sicura = importa(ROSSO);
        assertThat(voltiDi(sicura).getFirst().getPersonaId()).isEqualTo(anna);
        assertThat(voltiDi(sicura).getFirst().isManuale()).isFalse();
        assertThat(ricarica(sicura).getPersone()).containsExactly(anna);

        Foto forse = importa(VIOLA);
        Foto forse2 = importa(VIOLA);
        Volto proposto = voltiDi(forse).getFirst();
        assertThat(proposto.getPersonaId()).isNull();
        assertThat(proposto.getProposta()).isEqualTo(anna);
        assertThat(proposto.getSomiglianzaProposta()).isBetween(0.42, 0.6);
        // Il secondo viola somiglia al primo, ma col nome di mezzo resta una proposta: niente gruppo nuovo.
        assertThat(voltiDi(forse2).getFirst().getProposta()).isEqualTo(anna);
        assertThat(voltiDi(forse2).getFirst().getPersonaId()).isNull();
        assertThat(cerca(anna)).doesNotContain(forse.getId());
        assertThat(persone.persona(anna).proposte()).isEqualTo(2);
        mvc.perform(get("/api/persone/" + anna + "/proposte")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));

        // Una si rifiuta (e non le si ripropone), l'altra si conferma.
        mvc.perform(post("/api/volti/" + voltiDi(forse2).getFirst().getId() + "/rifiuta")).andExpect(status().isNoContent());
        assertThat(voltiDi(forse2).getFirst().getEsclusi()).containsExactly(anna);
        mvc.perform(post("/api/persone/" + anna + "/proposte/conferma")).andExpect(status().isOk())
                .andExpect(jsonPath("$.confermate").value(1));
        assertThat(voltiDi(forse).getFirst().getPersonaId()).isEqualTo(anna);
        assertThat(voltiDi(forse).getFirst().isManuale()).isTrue();
        assertThat(cerca(anna)).contains(forse.getId()).doesNotContain(forse2.getId());
    }

    @Test
    void unVoltoSbagliatoSiTogliEUnoNuovoSiNominaDalVisore() throws Exception {
        Foto a = importa(ROSSO);
        Foto b = importa(ROSSO_2);
        Foto c = importa(ROSSO);
        String anna = gruppoDi(a);
        persone.nomina(anna, "Anna");
        Volto sbagliato = voltiDi(c).getFirst();

        mvc.perform(post("/api/volti/" + sbagliato.getId() + "/togli")).andExpect(status().isNoContent());
        Volto tolto = voltiDi(c).getFirst();
        assertThat(tolto.getPersonaId()).isNull();
        assertThat(tolto.isManuale()).isTrue();
        assertThat(tolto.getEsclusi()).containsExactly(anna);
        assertThat(ricarica(c).getPersone()).isNull();
        assertThat(cerca(anna)).containsExactlyInAnyOrder(a.getId(), b.getId());

        // Un altro rosso nella stessa foto di nessuno: va ad Anna; quello tolto no, ha "non è lei".
        mvc.perform(post("/api/volti/" + sbagliato.getId() + "/assegna").contentType(MediaType.APPLICATION_JSON)
                .content("{\"persona\":\"" + anna + "\"}")).andExpect(status().isNoContent());
        assertThat(voltiDi(c).getFirst().getPersonaId()).isEqualTo(anna);
        assertThat(voltiDi(c).getFirst().getEsclusi()).isEmpty();

        // "Chi è?" su un volto di nessuno: una persona nuova; con un nome che c'è già, va a lei.
        Foto verde = importa(VERDE);
        mvc.perform(post("/api/volti/" + voltiDi(verde).getFirst().getId() + "/nome").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"Bruno\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.nome").value("Bruno"))
                .andExpect(jsonPath("$.volti").value(1));
        Foto blu = importa(BLU);
        mvc.perform(post("/api/volti/" + voltiDi(blu).getFirst().getId() + "/nome").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nome\":\"anna\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(anna));
        assertThat(cerca(anna)).contains(blu.getId());

        // Nel visore: i volti della foto col nome.
        mvc.perform(get("/api/foto/" + verde.getId() + "/volti")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nome").value("Bruno"))
                .andExpect(jsonPath("$[0].riquadro.larghezza").value(0.5));

        // I volti di Anna, il meno somigliante (il blu) per primo.
        mvc.perform(get("/api/persone/" + anna + "/volti")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[0].fotoId").value(blu.getId()));
    }

    @Test
    void unGruppoNascostoNonSiMostraMaRaccoglieISuoiSimili() throws Exception {
        Foto a = importa(BLU);
        importa(BLU);
        String folla = gruppoDi(a);
        mvc.perform(post("/api/persone/" + folla + "/nascondi").contentType(MediaType.APPLICATION_JSON)
                .content("{\"nascosta\":true}")).andExpect(status().isOk()).andExpect(jsonPath("$.nascosta").value(true));
        assertThat(persone.elenco(Tipo.GRUPPI, 100).persone()).isEmpty();
        assertThat(persone.elenco(Tipo.NASCOSTE, 100).persone()).extracting(VocePersona::id).containsExactly(folla);

        Foto altra = importa(BLU);
        assertThat(voltiDi(altra).getFirst().getPersonaId()).isEqualTo(folla);
        assertThat(persone.dellaFoto(altra.getId())).singleElement().satisfies(v -> {
            assertThat(v.nascosta()).isTrue();
            assertThat(v.nome()).isNull();
        });
    }

    @Test
    void gruppiSimiliDaUnire() throws Exception {
        Foto a = importa(ROSSO);
        importa(ROSSO);
        Foto b = importa(VIOLA_SCURO);
        importa(VIOLA_SCURO);
        String rossi = gruppoDi(a);
        String viola = gruppoDi(b);
        assertThat(rossi).isNotEqualTo(viola);

        assertThat(persone.simili(rossi, 10)).singleElement().satisfies(s -> {
            assertThat(s.persona().id()).isEqualTo(viola);
            assertThat(s.persona().volti()).isEqualTo(2);
            assertThat(s.somiglianza()).isBetween(0.42, 0.5);
        });
    }

    // ------------------------------------------------------------ cestino ed eliminazione

    @Test
    void nelCestinoNonContanoRipristinateTornanoEliminatePerSempreSeNeVanno() throws Exception {
        Foto a = importa(ROSSO);
        Foto b = importa(ROSSO);
        Foto c = importa(ROSSO);
        String anna = gruppoDi(a);
        persone.nomina(anna, "Anna");
        String voltoA = voltiDi(a).getFirst().getId();

        service.elimina(a.getId());
        aspetta(riconoscimento::codaVuota);
        assertThat(voltiDi(a).getFirst().isNelCestino()).isTrue();
        assertThat(indice).doesNotContainKey(voltoA);
        assertThat(persone.persona(anna).volti()).isEqualTo(2);
        assertThat(cerca(anna)).containsExactlyInAnyOrder(b.getId(), c.getId());
        assertThat(riconoscimento.stato().volti()).isEqualTo(2);

        service.ripristina(a.getId());
        aspetta(riconoscimento::codaVuota);
        assertThat(voltiDi(a).getFirst().isNelCestino()).isFalse();
        assertThat(indice).containsKey(voltoA);
        assertThat(persone.persona(anna).volti()).isEqualTo(3);
        assertThat(cerca(anna)).hasSize(3);

        service.elimina(a.getId());
        service.eliminaPerSempre(a.getId());
        aspetta(riconoscimento::codaVuota);
        assertThat(mongo.exists(Query.query(Criteria.where("fotoId").is(a.getId())), Volto.class)).isFalse();
        assertThat(indice).doesNotContainKey(voltoA);
        assertThat(persone.persona(anna).volti()).isEqualTo(2);

        // Un gruppo senza nome che resta vuoto se ne va; una persona con nome resta.
        Foto v1 = importa(VERDE);
        Foto v2 = importa(VERDE);
        String verdi = gruppoDi(v1);
        for (Foto f : List.of(v1, v2, b, c)) {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        }
        aspetta(riconoscimento::codaVuota);
        assertThat(mongo.findById(verdi, Persona.class)).isNull();
        assertThat(persone.persona(anna).volti()).isZero();
        assertThat(persone.elenco(Tipo.NOMINATE, 10).persone()).extracting(VocePersona::nome).containsExactly("Anna");
    }

    // ------------------------------------------------------------ il lavoro

    @Test
    void ilLavoroGuardaLeFotoABlocchiENonRifaQuelleFatte() throws Exception {
        List<Foto> foto = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            foto.add(importa(new Color(240, 20 + i * 5, 20)));
        }
        Foto nelCestino = importa(VERDE);
        service.elimina(nelCestino.getId());
        comeUnArchivioDiPrima();
        assertThat(riconoscimento.stato().mancanti()).isEqualTo(5);

        StatoLavoro partito = riconoscimento.avvia(false);
        assertThat(partito.stato()).isEqualTo(Stato.IN_CORSO);
        StatoLavoro fine = aspettaLavoro();
        assertThat(fine.stato()).isEqualTo(Stato.FINITO);
        assertThat(fine.daFare()).isEqualTo(5);
        assertThat(fine.fatte()).isEqualTo(5);
        assertThat(fine.volti()).isEqualTo(5);
        // A blocchi di 2: tre richieste; la foto nel cestino no.
        verify(visione, times(3)).rileva(anyMap(), anyInt(), anyDouble());
        assertThat(ricarica(nelCestino).getVoltiCercati()).isNull();
        assertThat(persone.elenco(Tipo.GRUPPI, 10).persone()).singleElement()
                .satisfies(g -> assertThat(g.volti()).isEqualTo(5));
        assertThat(riconoscimento.stato().mancanti()).isZero();

        // Rilanciato non rifà niente.
        riconoscimento.avvia(false);
        assertThat(aspettaLavoro().fatte()).isZero();
        verify(visione, times(3)).rileva(anyMap(), anyInt(), anyDouble());
    }

    @Test
    void seVisioneRestaSpentaIlLavoroSiFermaERiprendeAlRiavvio() throws Exception {
        importa(ROSSO);
        importa(ROSSO);
        comeUnArchivioDiPrima();
        when(visione.rileva(anyMap(), anyInt(), anyDouble())).thenThrow(new VisioneNonRisponde("non risponde"));

        riconoscimento.avvia(false);
        StatoLavoro fine = aspettaLavoro();
        assertThat(fine.stato()).isEqualTo(Stato.FALLITO);
        assertThat(fine.errore()).contains("non risponde");
        assertThat(riconoscimento.stato().mancanti()).isEqualTo(2);

        // L'app riparte con visione di nuovo accesa: il lavoro riprende da solo.
        doAnswer(i -> rileva(i.getArgument(0))).when(visione).rileva(anyMap(), anyInt(), anyDouble());
        riconoscimento.riprendi();
        assertThat(aspettaLavoro().stato()).isEqualTo(Stato.FINITO);
        assertThat(riconoscimento.stato().mancanti()).isZero();

        // Finito: al prossimo avvio non riparte.
        riconoscimento.riprendi();
        assertThat(riconoscimento.corrente().orElseThrow().stato()).isEqualTo(Stato.FINITO);
    }

    @Test
    void annullatoSiFermaENonRipartePiu() throws Exception {
        for (int i = 0; i < 6; i++) {
            importa(new Color(20, 240, 20 + i * 5));
        }
        comeUnArchivioDiPrima();
        doAnswer(i -> {
            Thread.sleep(300);
            return rileva(i.getArgument(0));
        }).when(visione).rileva(anyMap(), anyInt(), anyDouble());

        riconoscimento.avvia(false);
        assertThat(riconoscimento.annulla()).isTrue();
        StatoLavoro fine = aspettaLavoro();
        assertThat(fine.stato()).isEqualTo(Stato.ANNULLATO);
        assertThat(fine.fatte()).isLessThan(6);
        riconoscimento.riprendi();
        assertThat(riconoscimento.corrente().orElseThrow().stato()).isEqualTo(Stato.ANNULLATO);
        assertThat(riconoscimento.annulla()).isFalse();
    }

    @Test
    void lIndicePersoSiRicostruisceDaMongo() throws Exception {
        importa(ROSSO);
        importa(BLU);
        Foto tre = importa(VERDE);
        service.elimina(tre.getId());
        aspetta(riconoscimento::codaVuota);
        indice.clear();
        assertThat(riconoscimento.stato().nellIndice()).isZero();

        mvc.perform(post("/api/volti/riconosci").param("ricostruisci", "true")).andExpect(status().isAccepted());
        assertThat(aspettaLavoro().stato()).isEqualTo(Stato.FINITO);
        // Quelli delle foto nel cestino no.
        assertThat(indice).hasSize(2);
        assertThat(riconoscimento.stato().nellIndice()).isEqualTo(2);
        mvc.perform(post("/api/volti/riconosci")).andExpect(status().isAccepted());
        aspettaLavoro();
        mvc.perform(get("/api/volti")).andExpect(jsonPath("$.attiva").value(true))
                .andExpect(jsonPath("$.volti").value(2)).andExpect(jsonPath("$.mancanti").value(0));
    }

    @Test
    void ilRitaglioDelVoltoVieneDallaMiniatura() throws Exception {
        Foto a = importa(ROSSO);
        Volto v = voltiDi(a).getFirst();

        byte[] jpeg = mvc.perform(get("/api/volti/" + v.getId() + "/ritaglio")).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage ritaglio = ImageIO.read(new ByteArrayInputStream(jpeg));
        assertThat(ritaglio.getWidth()).isEqualTo(RitaglioVolto.LATO);
        assertThat(ritaglio.getHeight()).isEqualTo(RitaglioVolto.LATO);
        // Il centro è il volto (rosso), non lo sfondo nero.
        Color centro = new Color(ritaglio.getRGB(80, 80));
        assertThat(centro.getRed()).isGreaterThan(150);
        mvc.perform(get("/api/volti/000000000000000000000000/ritaglio")).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ aiuti

    private Foto importa(Color colore) throws IOException {
        // Un giorno diverso per foto: lo stesso contenuto non entra due volte (hash), e l'ordine resta quello di arrivo.
        int n = giorno.incrementAndGet();
        var esito = service.importa("volto" + n + ".png", immagine(colore, n), Instant.parse("2024-06-01T10:00:00Z")
                .plusSeconds(n * 60L), null);
        assertThat(esito.foto()).as(esito.messaggio()).isNotNull();
        String id = esito.foto().getId();
        aspetta(() -> riconoscimento.codaVuota() && ricarica(id).getVoltiCercati() != null);
        return esito.foto();
    }

    /** Come un archivio di prima del riconoscimento: niente volti, niente persone, niente segni. */
    private void comeUnArchivioDiPrima() {
        aspetta(riconoscimento::codaVuota);
        mongo.remove(new Query(), Volto.class);
        mongo.remove(new Query(), Persona.class);
        mongo.updateMulti(new Query(), new Update().unset(RiconoscimentoVolti.CAMPO).unset("persone"), Foto.class);
        indice.clear();
        clearInvocations(visione);
    }

    /** Il CSRF è sempre acceso, anche senza login. */
    private static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).with(csrf());
    }

    private static MockHttpServletRequestBuilder put(String url) {
        return MockMvcRequestBuilders.put(url).with(csrf());
    }

    private Foto ricarica(Foto f) {
        return ricarica(f.getId());
    }

    private Foto ricarica(String id) {
        return repository.findById(id).orElseThrow();
    }

    private List<Volto> voltiDi(Foto f) {
        return mongo.find(Query.query(Criteria.where("fotoId").is(f.getId())), Volto.class);
    }

    private String gruppoDi(Foto f) {
        String id = voltiDi(f).getFirst().getPersonaId();
        assertThat(id).as("gruppo di " + f.getNomeOriginale()).isNotNull();
        return id;
    }

    private List<String> cerca(String persona) {
        return service.cerca(filtro(persona, null), 0, 100).foto().stream().map(Foto::getId).toList();
    }

    private static FiltroFoto filtro(String persona, String album) {
        return new FiltroFoto(null, null, album, null, null, null).conPersona(persona);
    }

    private StatoLavoro aspettaLavoro() {
        aspetta(() -> riconoscimento.corrente().map(l -> l.stato() != Stato.IN_CORSO).orElse(true));
        return riconoscimento.corrente().orElse(null);
    }

    private static void aspetta(BooleanSupplier condizione) {
        long fine = System.currentTimeMillis() + 10_000;
        while (!condizione.getAsBoolean()) {
            if (System.currentTimeMillis() > fine) {
                throw new AssertionError("Non è successo entro 10 secondi");
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** Nera con il "volto" a sinistra; il numero in un pixel dell'angolo cambia l'hash, non il colore medio. */
    private static byte[] immagine(Color sinistra, int n) throws IOException {
        var img = new BufferedImage(80, 40, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 80, 40);
        g.setColor(sinistra);
        g.fillRect(0, 0, 40, 40);
        g.dispose();
        img.setRGB(79, 39, n & 0xFFFFFF);
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    /** Il visione finto: un volto per metà dell'immagine che non è nera, col colore medio come vettore. */
    private static Rilevamento rileva(Map<String, Path> miniature) {
        Map<String, ImmagineVolti> risultati = new LinkedHashMap<>();
        miniature.forEach((id, file) -> {
            try {
                BufferedImage img = ImageIO.read(file.toFile());
                List<VoltoRilevato> volti = new ArrayList<>();
                for (int meta = 0; meta < 2; meta++) {
                    float[] media = media(img, meta * img.getWidth() / 2, img.getWidth() / 2);
                    if (media[0] + media[1] + media[2] > 0.3f) {
                        volti.add(new VoltoRilevato(new Volto.Riquadro(meta * 0.5, 0.25, 0.5, 0.5), 0.95, normalizza(media)));
                    }
                }
                risultati.put(id, new ImmagineVolti(img.getWidth(), img.getHeight(), volti));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new Rilevamento("finto", risultati, Map.of());
    }

    private static float[] media(BufferedImage img, int x0, int larghezza) {
        double r = 0, g = 0, b = 0;
        int n = 0;
        // Lontano dal bordo fra le due metà, che il JPEG sfuma.
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = x0 + larghezza / 8; x < x0 + larghezza * 7 / 8; x++) {
                Color c = new Color(img.getRGB(x, y));
                r += c.getRed();
                g += c.getGreen();
                b += c.getBlue();
                n++;
            }
        }
        return new float[] {(float) (r / n / 255), (float) (g / n / 255), (float) (b / n / 255)};
    }

    private static float[] normalizza(float[] v) {
        double norma = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new float[] {(float) (v[0] / norma), (float) (v[1] / norma), (float) (v[2] / norma)};
    }

    private List<List<Vicino>> vicini(List<float[]> domande, int k, double soglia) {
        List<List<Vicino>> risultati = new ArrayList<>();
        for (float[] q : domande) {
            risultati.add(indice.entrySet().stream()
                    .map(e -> new Vicino(e.getKey(), coseno(q, e.getValue())))
                    .filter(v -> v.punteggio() >= soglia)
                    .sorted(Comparator.comparingDouble(Vicino::punteggio).reversed())
                    .limit(k)
                    .toList());
        }
        return risultati;
    }

    private static double coseno(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
