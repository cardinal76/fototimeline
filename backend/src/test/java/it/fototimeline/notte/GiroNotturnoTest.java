package it.fototimeline.notte;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import it.fototimeline.cloud.Cloud;
import it.fototimeline.contenuto.IndiceContenuto;
import it.fototimeline.contenuto.Visione;
import it.fototimeline.contenuto.Visione.EsitoBlocco;
import it.fototimeline.dominio.Foto;
import it.fototimeline.eventi.EventiService;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.notte.GiroNotturno.EsitoPasso;
import it.fototimeline.notte.GiroNotturno.Esito;
import it.fototimeline.notte.GiroNotturno.Giro;
import it.fototimeline.notte.GiroNotturno.Passo;
import it.fototimeline.notte.GiroNotturno.Risultato;
import it.fototimeline.quasiuguali.CalcoloImpronte;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.salute.Salute;
import it.fototimeline.salute.StatoSalute;
import it.fototimeline.salute.VoceSalute;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.volti.RiconoscimentoVolti;
import it.fototimeline.volti.VisioneVolti;

/**
 * Il giro notturno con i lavori veri: archivio in una cartella, GeoNames di
 * prova, la ricerca per contenuto con un visione finto e i volti spenti
 * ({@code FOTOTIMELINE_VOLTI=false}). Il cron qui è spento
 * (src/test/resources/application.properties): il giro si lancia a mano.
 */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.luoghi.cartella=classpath:geonames",
        "fototimeline.visione.url=http://visione.invalid:8000",
        "fototimeline.visione.segreto=prova",
        "fototimeline.visione.attesa=PT0.01S",
        "fototimeline.volti.attiva=false",
        "fototimeline.notte.controllo=PT0.02S",
        "fototimeline.notte.attesa-massima=PT1S",
})
class GiroNotturnoTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    GiroNotturno giro;

    @Autowired
    LavoriImportazione lavori;

    @Autowired
    FotoRepository repository;

    @Autowired
    FotoService service;

    @Autowired
    MongoTemplate mongo;

    @Autowired
    Salute salute;

    @Autowired
    IndiceContenuto contenuto;

    @Autowired
    LuoghiService luoghi;

    @Autowired
    EventiService eventi;

    @Autowired
    RiconoscimentoVolti volti;

    @Autowired
    CalcoloImpronte impronte;

    @Autowired
    ArchivioFile archivioFile;

    /** Il cloud: montato, salvo nel test che lo smonta. */
    @MockitoSpyBean
    Cloud cloud;

    /** CLIP finto: mette nell'indice tutto quello che riceve; con {@link #trattieni} aspetta prima. */
    @MockitoBean
    Visione clip;

    @MockitoBean
    VisioneVolti visioneVolti;

    private volatile CountDownLatch trattieni;

    @BeforeEach
    void prepara() throws Exception {
        aspetta(() -> !giro.inCorso(), "giro di prima finito");
        aspetta(() -> giro.occupato() == null, "lavori di prima finiti");
        reset(cloud);
        trattieni = null;
        when(clip.mettiBlocco(any())).thenAnswer(inv -> {
            CountDownLatch l = trattieni;
            if (l != null) {
                l.await(10, TimeUnit.SECONDS);
            }
            Map<String, Path> miniature = inv.getArgument(0);
            return new EsitoBlocco(List.copyOf(miniature.keySet()), Map.of());
        });
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        mongo.remove(Query.query(Criteria.where("_id").is(GiroNotturno.ID)), "impostazioni");
    }

    @AfterEach
    void libera() {
        CountDownLatch l = trattieni;
        if (l != null) {
            l.countDown();
        }
    }

    @Test
    void lanciaILavoriInFilaEAggiornaLaSalute() throws Exception {
        scrivi("2024/05/03/IMG_0001.jpg", Color.RED);
        scrivi("2024/05/03/IMG_0002.jpg", Color.BLUE);

        giro.lanciaOra("marco");
        assertThatThrownBy(() -> giro.lanciaOra("marco")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("già in corso");
        Giro fatto = finito();

        assertThat(fatto.esito()).isEqualTo(Esito.FINITO);
        assertThat(fatto.aMano()).isTrue();
        assertThat(fatto.chi()).isEqualTo("marco");
        assertThat(fatto.passi()).extracting(Risultato::passo).containsExactly(Passo.INDICIZZA, Passo.LUOGHI,
                Passo.EVENTI, Passo.CONTENUTO, Passo.VOLTI, Passo.IMPRONTE);
        // Uno dopo l'altro: ognuno parte quando quello di prima è finito.
        for (int i = 1; i < fatto.passi().size(); i++) {
            assertThat(fatto.passi().get(i).iniziatoIl()).isAfterOrEqualTo(fatto.passi().get(i - 1).finitoIl());
        }
        Risultato indicizza = fatto.passi().get(0);
        assertThat(indicizza.esito()).isEqualTo(EsitoPasso.FATTO);
        assertThat(indicizza.quanti()).isEqualTo(2);
        assertThat(fatto.fotoNuove()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(2);
        assertThat(fatto.passi().get(1).esito()).isEqualTo(EsitoPasso.FATTO);
        assertThat(fatto.passi().get(2).esito()).isEqualTo(EsitoPasso.FATTO);
        assertThat(fatto.passi().get(3).esito()).isEqualTo(EsitoPasso.FATTO);
        // FOTOTIMELINE_VOLTI=false: "Riconosci volti" non parte.
        Risultato riconosci = fatto.passi().get(4);
        assertThat(riconosci.esito()).isEqualTo(EsitoPasso.SPENTO);
        assertThat(riconosci.nota()).contains("FOTOTIMELINE_VOLTI=false");
        assertThat(volti.corrente()).isEmpty();
        // L'importazione automatica non resta sospesa dopo il giro (si vede dal lavoro: nessuno appeso).
        assertThat(lavori.corrente()).get().extracting(LavoriImportazione.StatoLavoro::stato)
                .isNotEqualTo(LavoriImportazione.Stato.IN_CORSO);

        // Rimesso in Mongo: dopo un riavvio la Salute lo vede ancora.
        assertThat(mongo.findById(GiroNotturno.ID, Giro.class).esito()).isEqualTo(Esito.FINITO);
        VoceSalute voce = voceNotte();
        assertThat(voce.stato()).isEqualTo(StatoSalute.OK);
        assertThat(voce.messaggio()).contains("finito").contains("2 foto nuove").contains("lanciato a mano");
        assertThat(voce.dettagli()).contains("Indicizza archivio: 2 foto nuove")
                .anyMatch(d -> d.startsWith("Riconosci volti: saltato, spento"))
                .anyMatch(d -> d.startsWith("Spento: si accende con FOTOTIMELINE_NOTTE_CRON"));

        // Un secondo giro non trova niente di nuovo.
        giro.lanciaOra(null);
        assertThat(finito().fotoNuove()).isZero();
    }

    @Test
    void colCloudSmontatoSaltaELoScrive() throws Exception {
        scrivi("2024/05/04/IMG_0003.jpg", Color.GREEN);
        doReturn(false).when(cloud).disponibile();
        String lavoroDiPrima = lavori.corrente().map(LavoriImportazione.StatoLavoro::id).orElse(null);

        // A mano lo dice subito, e non registra niente.
        assertThatThrownBy(() -> giro.lanciaOra("marco")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cloud");
        assertThat(mongo.findById(GiroNotturno.ID, Giro.class)).isNull();

        // Dal cron: il giro risulta saltato, e nessun lavoro è partito.
        giro.parti(false, null);
        Giro saltato = finito();
        assertThat(saltato.esito()).isEqualTo(Esito.SALTATO);
        assertThat(saltato.messaggio()).contains("cloud non era montato");
        assertThat(saltato.passi()).isEmpty();
        assertThat(lavori.corrente().map(LavoriImportazione.StatoLavoro::id).orElse(null)).isEqualTo(lavoroDiPrima);
        assertThat(repository.count()).isZero();
        VoceSalute voce = voceNotte();
        assertThat(voce.stato()).isEqualTo(StatoSalute.ATTENZIONE);
        assertThat(voce.messaggio()).contains("saltato").contains("cloud");
    }

    @Test
    void nonSiSovrapponeAUnLavoroInCorsoLoAspetta() throws Exception {
        segnaContenutoDaRifare(1);
        trattieni = new CountDownLatch(1);
        contenuto.avvia(false);
        aspetta(() -> giro.occupato() != null, "Indicizza contenuto partito");

        giro.parti(false, null);
        Thread.sleep(200);
        // Aspetta: niente è partito sul thread delle importazioni.
        assertThat(giro.stato().ultimo().passo()).isNull();
        assertThat(giro.stato().ultimo().passi()).isEmpty();
        Instant liberato = Instant.now();
        trattieni.countDown();

        Giro fatto = finito();
        assertThat(fatto.esito()).isEqualTo(Esito.FINITO);
        assertThat(fatto.passi().getFirst().iniziatoIl()).isAfterOrEqualTo(liberato.minusMillis(50));
    }

    @Test
    void nonSiSovrapponeEDopoLAttesaMassimaSalta() throws Exception {
        segnaContenutoDaRifare(1);
        trattieni = new CountDownLatch(1);
        contenuto.avvia(false);
        aspetta(() -> giro.occupato() != null, "Indicizza contenuto partito");

        giro.parti(false, null);
        Giro saltato = finito();
        trattieni.countDown();

        assertThat(saltato.esito()).isEqualTo(Esito.SALTATO);
        assertThat(saltato.messaggio()).contains("\"Indicizza contenuto\" era in corso");
        assertThat(saltato.passi()).isEmpty();
        assertThat(voceNotte().stato()).isEqualTo(StatoSalute.ATTENZIONE);
    }

    @Test
    void annullaFermaIlGiro() throws Exception {
        segnaContenutoDaRifare(1);
        trattieni = new CountDownLatch(1);
        giro.lanciaOra("marco");
        aspetta(() -> giro.stato().ultimo().passo() == Passo.CONTENUTO
                && contenuto.corrente().filter(c -> c.stato() == IndiceContenuto.Stato.IN_CORSO).isPresent(),
                "giro arrivato a Indicizza contenuto");

        assertThat(giro.annulla()).isTrue();
        trattieni.countDown();
        Giro annullato = finito();

        assertThat(annullato.esito()).isEqualTo(Esito.ANNULLATO);
        assertThat(annullato.passi()).extracting(Risultato::passo).containsExactly(Passo.INDICIZZA, Passo.LUOGHI,
                Passo.EVENTI, Passo.CONTENUTO);
        assertThat(annullato.passi().getLast().esito()).isEqualTo(EsitoPasso.ANNULLATO);
        assertThat(giro.annulla()).isFalse();
    }

    @Test
    void unLavoroFallitoFaRossaLaSalute() throws Exception {
        segnaContenutoDaRifare(1);
        doThrow(new it.fototimeline.contenuto.VisioneNonRisponde("visione spento")).when(clip).mettiBlocco(any());

        giro.lanciaOra("marco");
        Giro fallito = finito();

        assertThat(fallito.esito()).isEqualTo(Esito.FALLITO);
        assertThat(fallito.messaggio()).contains("Indicizza contenuto");
        // Gli altri lavori sono andati avanti.
        assertThat(fallito.passi()).extracting(Risultato::passo).contains(Passo.VOLTI, Passo.IMPRONTE);
        VoceSalute voce = voceNotte();
        assertThat(voce.stato()).isEqualTo(StatoSalute.ERRORE);
        assertThat(voce.dettagli()).anyMatch(d -> d.startsWith("Indicizza contenuto: non riuscito"));
    }

    @Test
    void dopoUnRiavvioRiprendeDaDoveEraArrivato() throws Exception {
        Instant prima = Instant.now().minusSeconds(60);
        mongo.save(new Giro(GiroNotturno.ID, "giro-interrotto", Esito.IN_CORSO, false, null, prima, null, Passo.EVENTI,
                null, List.of(
                        new Risultato(Passo.INDICIZZA, EsitoPasso.FATTO, 7, "foto nuove", 0, null, prima, prima),
                        new Risultato(Passo.LUOGHI, EsitoPasso.FATTO, 3, "foto calcolate", 0, null, prima, prima))));

        giro.riprendi();
        Giro ripreso = finito();

        assertThat(ripreso.giro()).isEqualTo("giro-interrotto");
        assertThat(ripreso.esito()).isEqualTo(Esito.FINITO);
        assertThat(ripreso.fotoNuove()).isEqualTo(7);
        assertThat(ripreso.passi()).extracting(Risultato::passo).containsExactly(Passo.INDICIZZA, Passo.LUOGHI,
                Passo.EVENTI, Passo.CONTENUTO, Passo.VOLTI, Passo.IMPRONTE);
    }

    @Test
    void colCronVuotoOMenoESpento() {
        // Qui il cron è "-": niente prossimo giro, e il controllo dell'orario non fa partire niente.
        assertThat(giro.stato().attivo()).isFalse();
        assertThat(giro.stato().prossimo()).isNull();
        assertThat(giro.stato().orario()).isNull();
        giro.controllaOrario();
        assertThat(giro.inCorso()).isFalse();
        assertThat(mongo.findById(GiroNotturno.ID, Giro.class)).isNull();
        assertThat(voceNotte().dettagli()).anyMatch(d -> d.startsWith("Spento: si accende con FOTOTIMELINE_NOTTE_CRON"));

        for (String spento : new String[] {"", "-", "  ", null}) {
            var p = new NotteProperties(spento, null, null, null);
            assertThat(p.attivo()).as(String.valueOf(spento)).isFalse();
            assertThat(p.espressione()).isNull();
        }
        assertThatThrownBy(() -> new NotteProperties("30 2 * * *", null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FOTOTIMELINE_NOTTE_CRON");
    }

    @Test
    void parteAllOraDelCronNelFusoItaliano() throws Exception {
        ZoneId roma = ZoneId.of("Europe/Rome");
        var orologio = new Orologio(LocalDateTime.of(2026, 10, 27, 2, 29).atZone(roma).toInstant());
        var notte = new GiroNotturno(new NotteProperties("0 30 2 * * *", null, Duration.ofSeconds(1),
                Duration.ofMillis(20)), lavori, luoghi, eventi, contenuto, volti, impronte, archivioFile, mongo,
                Optional.of(orologio));
        try {
            assertThat(notte.orario()).isEqualTo("ogni notte alle 02:30");
            assertThat(notte.prossimo()).isEqualTo(LocalDateTime.of(2026, 10, 27, 2, 30).atZone(roma).toInstant());
            // Le 2:30 di Roma sono le 1:30 UTC (ora solare dal 25 ottobre).
            assertThat(notte.prossimo()).isEqualTo(Instant.parse("2026-10-27T01:30:00Z"));

            notte.controllaOrario();
            assertThat(notte.inCorso()).isFalse();
            assertThat(mongo.findById(GiroNotturno.ID, Giro.class)).isNull();

            orologio.vai(LocalDateTime.of(2026, 10, 27, 2, 31).atZone(roma).toInstant());
            notte.controllaOrario();
            aspetta(() -> !notte.inCorso(), "giro del cron finito");
            Giro fatto = mongo.findById(GiroNotturno.ID, Giro.class);
            assertThat(fatto.esito()).isEqualTo(Esito.FINITO);
            assertThat(fatto.aMano()).isFalse();
            assertThat(notte.prossimo()).isEqualTo(LocalDateTime.of(2026, 10, 28, 2, 30).atZone(roma).toInstant());
            assertThat(notte.voceSalute().dettagli()).anyMatch(d -> d.startsWith("Prossimo giro: 28 ott 2026, 02:30"));

            // Fermo da più di due giorni con il giro acceso: giallo.
            orologio.vai(orologio.instant().plus(Duration.ofDays(3)));
            assertThat(notte.voceSalute().stato()).isEqualTo(StatoSalute.ATTENZIONE);
        } finally {
            notte.chiudi();
        }
    }

    // ------------------------------------------------------------ utilità

    private Giro finito() throws InterruptedException {
        aspetta(() -> !giro.inCorso(), "giro finito");
        return giro.stato().ultimo();
    }

    private VoceSalute voceNotte() {
        return salute.controlla().voci().stream().filter(v -> v.chiave().equals("notte")).findFirst().orElseThrow();
    }

    /** Foto già in archivio (anche col giro di prima), ma da rimandare a visione. */
    private void segnaContenutoDaRifare(int almeno) throws Exception {
        if (repository.count() < almeno) {
            scrivi("2024/06/01/IMG_" + System.nanoTime() + ".jpg", new Color((int) (Math.random() * 0xffffff)));
            lavori.avviaIndicizzazione();
            aspetta(() -> lavori.corrente().map(l -> l.stato() != LavoriImportazione.Stato.IN_CORSO).orElse(true),
                    "indicizzazione finita");
        }
        // Le foto nuove si indicizzano anche da sole (thread "contenuto"): si aspetta e si toglie il segno.
        aspetta(() -> mongo.count(Query.query(Criteria.where("contenutoIndicizzato").is(null)), Foto.class) == 0,
                "contenuto delle foto nuove");
        mongo.updateMulti(new Query(), new Update().unset("contenutoIndicizzato"), Foto.class);
    }

    private static void scrivi(String percorso, Color colore) throws IOException {
        Path file = archivio.resolve(percorso);
        Files.createDirectories(file.getParent());
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 64, 48);
        g.setColor(Color.WHITE);
        g.fillRect(5, 5, 10 + (colore.getRGB() & 0x1f), 10);
        g.dispose();
        ImageIO.write(img, "jpg", file.toFile());
    }

    private static void aspetta(BooleanSupplier condizione, String cosa) throws InterruptedException {
        long fine = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!condizione.getAsBoolean()) {
            if (System.nanoTime() > fine) {
                throw new AssertionError("Non arriva: " + cosa);
            }
            Thread.sleep(20);
        }
    }

    /** Un orologio che si sposta a mano. */
    static final class Orologio extends Clock {
        private volatile Instant adesso;

        Orologio(Instant adesso) {
            this.adesso = adesso;
        }

        void vai(Instant quando) {
            adesso = quando;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zona) {
            return this;
        }

        @Override
        public Instant instant() {
            return adesso;
        }
    }
}
