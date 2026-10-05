package it.fototimeline.contenuto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import it.fototimeline.contenuto.IndiceContenuto.Stato;
import it.fototimeline.contenuto.IndiceContenuto.StatoLavoro;
import it.fototimeline.contenuto.Visione.EsitoBlocco;
import it.fototimeline.contenuto.Visione.Somiglianza;
import it.fototimeline.dominio.Foto;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.Modifica;

/** La ricerca per contenuto con un servizio visione finto (un mock al posto del client HTTP). */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.visione.url=http://visione.invalid:8000",
        "fototimeline.visione.segreto=prova",
        "fototimeline.visione.blocco=2",
        "fototimeline.visione.attesa=PT0S",
})
class IndiceContenutoTest {

    private static final FiltroFoto NESSUNO = new FiltroFoto(null, null, null, null, null, null);

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @MockitoBean
    Visione visione;

    @Autowired
    IndiceContenuto indice;

    @Autowired
    FotoService service;

    @Autowired
    FotoRepository repository;

    @Autowired
    MongoTemplate mongo;

    @BeforeEach
    void svuota() {
        aspettaFine();
        repository.findAll().forEach(f -> service.elimina(f.getId()));
        mongo.remove(Query.query(Criteria.where("_id").is(IndiceContenuto.ID)), "impostazioni");
        // Visione prende tutto quello che riceve.
        when(visione.mettiBlocco(anyMap())).thenAnswer(i -> new EsitoBlocco(
                new ArrayList<>(i.<Map<String, Path>>getArgument(0).keySet()), Map.of()));
    }

    @Test
    void unaFotoImportataVaAVisioneInSottofondo() throws IOException {
        Foto foto = importa("mare.png", Color.BLUE);

        // L'importazione è già tornata: la miniatura arriva a visione dal thread "contenuto".
        verify(visione, timeout(5000)).metti(eq(foto.getId()), any(Path.class));
        aspetta(() -> repository.findById(foto.getId()).orElseThrow().getContenutoIndicizzato() != null);
        ArgumentCaptor<Path> miniatura = ArgumentCaptor.forClass(Path.class);
        verify(visione).metti(eq(foto.getId()), miniatura.capture());
        assertThat(miniatura.getValue().toString()).contains(".miniature").endsWith(foto.getId() + ".jpg");
        assertThat(indice.stato().mancanti()).isZero();
    }

    @Test
    void seVisioneNonRispondeLImportazioneVaAvantiELaFotoRestaDaFare() throws IOException {
        doThrow(new VisioneNonRisponde("spento")).when(visione).metti(any(), any());

        Foto foto = importa("neve.png", Color.WHITE);

        verify(visione, timeout(5000)).metti(eq(foto.getId()), any(Path.class));
        assertThat(repository.findById(foto.getId()).orElseThrow().getContenutoIndicizzato()).isNull();
        assertThat(indice.stato().mancanti()).isEqualTo(1);
    }

    @Test
    void ilLavoroMandaLeMancantiABlocchiEDueVolteNonRifaNiente() throws IOException {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(importa("foto" + i + ".png", new Color(i * 50, 100, 100)).getId());
        }
        verify(visione, timeout(5000).times(5)).metti(any(), any());
        nessunaSegnata();
        assertThat(indice.stato().mancanti()).isEqualTo(5);

        StatoLavoro partito = indice.avvia(false);
        assertThat(partito.stato()).isEqualTo(Stato.IN_CORSO);
        StatoLavoro fine = aspettaFine();

        assertThat(fine.stato()).isEqualTo(Stato.FINITO);
        assertThat(fine.daFare()).isEqualTo(5);
        assertThat(fine.fatte()).isEqualTo(5);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Path>> blocchi = ArgumentCaptor.forClass(Map.class);
        verify(visione, times(3)).mettiBlocco(blocchi.capture());
        assertThat(blocchi.getAllValues()).allSatisfy(b -> assertThat(b).hasSizeLessThanOrEqualTo(2));
        assertThat(blocchi.getAllValues().stream().flatMap(b -> b.keySet().stream())).containsExactlyInAnyOrderElementsOf(ids);
        assertThat(indice.stato().mancanti()).isZero();

        // Idempotente: rilanciato non manda niente.
        indice.avvia(false);
        assertThat(aspettaFine().fatte()).isZero();
        verify(visione, times(3)).mettiBlocco(anyMap());

        // "Da capo": tutte di nuovo.
        indice.avvia(true);
        assertThat(aspettaFine().fatte()).isEqualTo(5);
    }

    @Test
    void gliErroriDiUnaFotoNonFermanoIlLavoro() throws IOException {
        Foto buona = importa("buona.png", Color.GREEN);
        Foto rotta = importa("rotta.png", Color.RED);
        verify(visione, timeout(5000).times(2)).metti(any(), any());
        nessunaSegnata();
        when(visione.mettiBlocco(anyMap())).thenReturn(
                new EsitoBlocco(List.of(buona.getId()), Map.of(rotta.getId(), "Immagine illeggibile")));

        indice.avvia(false);
        StatoLavoro fine = aspettaFine();

        assertThat(fine.stato()).isEqualTo(Stato.FINITO);
        assertThat(fine.fatte()).isEqualTo(1);
        assertThat(fine.errori()).isEqualTo(1);
        assertThat(fine.messaggi()).singleElement().asString().contains("rotta.png", "illeggibile");
        assertThat(indice.stato().mancanti()).isEqualTo(1);
    }

    @Test
    void seVisioneRestaSpentaIlLavoroSiFermaERiprendeAlRiavvio() throws IOException {
        importa("a.png", Color.ORANGE);
        verify(visione, timeout(5000)).metti(any(), any());
        nessunaSegnata();
        when(visione.mettiBlocco(anyMap())).thenThrow(new VisioneNonRisponde("non risponde"));

        indice.avvia(false);
        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FALLITO);
        assertThat(fine.errore()).contains("non risponde");

        // L'app riparte con visione di nuovo accesa: il lavoro riprende da solo.
        doAnswer(i -> new EsitoBlocco(new ArrayList<>(i.<Map<String, Path>>getArgument(0).keySet()), Map.of()))
                .when(visione).mettiBlocco(anyMap());
        indice.riprendi();
        assertThat(aspettaFine().stato()).isEqualTo(Stato.FINITO);
        assertThat(indice.stato().mancanti()).isZero();

        // Finito: al prossimo avvio non riparte.
        indice.riprendi();
        assertThat(indice.corrente().orElseThrow().stato()).isEqualTo(Stato.FINITO);
    }

    @Test
    void cercaRestituisceLeFotoNellOrdineDeiPunteggi() throws IOException {
        Foto cane = importa("cane.png", Color.YELLOW);
        Foto spiaggia = importa("spiaggia.png", Color.CYAN);
        Foto torta = importa("torta.png", Color.PINK);
        Foto lontana = importa("lontana.png", Color.GRAY);
        service.modifica(cane.getId(), new Modifica(null, null, List.of(), "Vacanze", false, null));
        service.modifica(spiaggia.getId(), new Modifica(null, null, List.of(), "Vacanze", false, null));
        when(visione.cerca(eq("cane in spiaggia"), anyInt(), anyDouble())).thenReturn(List.of(
                // Una foto eliminata ma rimasta nell'indice: non conta, nemmeno per il margine.
                new Somiglianza("tolta-dall-archivio", 0.40),
                new Somiglianza(spiaggia.getId(), 0.31),
                new Somiglianza(cane.getId(), 0.29),
                new Somiglianza(torta.getId(), 0.26),
                // Troppo lontana dalla più simile (margine 0,06): non è un risultato.
                new Somiglianza(lontana.getId(), 0.24)));

        var pagina = indice.cerca(" cane in spiaggia ", NESSUNO, 0, 2);
        assertThat(pagina.foto()).extracting(Foto::getId).containsExactly(spiaggia.getId(), cane.getId());
        assertThat(pagina.totale()).isEqualTo(3);
        assertThat(pagina.altre()).isTrue();
        assertThat(indice.cerca("cane in spiaggia", NESSUNO, 1, 2).foto()).extracting(Foto::getId)
                .containsExactly(torta.getId());

        // Con gli altri filtri della timeline.
        var vacanze = indice.cerca("cane in spiaggia", new FiltroFoto(null, null, "Vacanze", null, null, null), 0, 10);
        assertThat(vacanze.foto()).extracting(Foto::getId).containsExactly(spiaggia.getId(), cane.getId());
        verify(visione, times(3)).cerca("cane in spiaggia", 500, 0.24);
    }

    @Test
    void lEliminazioneTogliePureDallIndice() throws IOException {
        Foto foto = importa("via.png", Color.MAGENTA);
        verify(visione, timeout(5000)).metti(eq(foto.getId()), any());

        service.elimina(foto.getId());

        verify(visione, timeout(5000)).togli(foto.getId());
    }

    @Test
    void loStatoDiceQuanteNeMancano() throws IOException {
        importa("uno.png", Color.BLACK);
        verify(visione, timeout(5000)).metti(any(), any());
        when(visione.totale()).thenReturn(1L);
        aspetta(() -> indice.stato().mancanti() == 0);

        var stato = indice.stato();
        assertThat(stato.attiva()).isTrue();
        assertThat(stato.foto()).isEqualTo(1);
        assertThat(stato.indicizzate()).isEqualTo(1);
        assertThat(stato.nellIndice()).isEqualTo(1);
        verify(visione, never()).mettiBlocco(anyMap());
    }

    // ------------------------------------------------------------ aiuti

    private Foto importa(String nome, Color colore) throws IOException {
        var esito = service.importa(nome, immagine(colore), Instant.parse("2024-06-01T10:00:00Z"), null);
        assertThat(esito.foto()).as(esito.messaggio()).isNotNull();
        assertThat(Files.exists(archivio.resolve(".miniature").resolve(esito.foto().getId() + ".jpg"))).isTrue();
        return esito.foto();
    }

    /** Come un archivio di prima della ricerca per contenuto, dopo che le foto appena importate sono passate. */
    private void nessunaSegnata() {
        aspetta(() -> indice.stato().mancanti() == 0);
        mongo.updateMulti(new Query(), new Update().unset(IndiceContenuto.CAMPO), Foto.class);
    }

    private StatoLavoro aspettaFine() {
        aspetta(() -> indice.corrente().map(l -> l.stato() != Stato.IN_CORSO).orElse(true));
        return indice.corrente().orElse(null);
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
