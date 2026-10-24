package it.fototimeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.argThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.ArchivioProperties;
import it.fototimeline.service.EstrattoreMetadati;
import it.fototimeline.service.IndicizzazioneProperties;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.ImportazioneCartelle;
import it.fototimeline.service.ImportazioneCartelle.Avanzamento;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati;
import it.fototimeline.service.Risultati.Esito;
import it.fototimeline.service.Risultati.Importazione;

/** "Indicizza archivio": le foto messe nell'archivio da fuori (lo script che carica nel cloud). */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class IndicizzazioneTest {

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

    /** Spia: nel test dell'annullamento la ricerca si ferma a metà finché il test non ha guardato. */
    @MockitoSpyBean
    ImportazioneCartelle importazione;

    @Autowired
    ArchivioFile archivioFile;

    /** Spia: nel test delle copie identiche le due letture si aspettano prima di salvare. */
    @MockitoSpyBean
    EstrattoreMetadati estrattore;

    @Autowired
    ArchivioProperties proprieta;

    @Autowired
    LavoriImportazione lavori;

    @BeforeEach
    void svuota() throws Exception {
        aspettaFine();
        repository.findAll().forEach(f -> {
            service.elimina(f.getId());
            service.eliminaPerSempre(f.getId());
        });
        // Anche i file rimasti senza scheda; le miniature restano.
        try (Stream<Path> s = Files.list(archivio)) {
            for (Path p : s.filter(p -> !p.getFileName().toString().equals(".miniature")).toList()) {
                cancellaTutto(p);
            }
        }
    }

    @Test
    void unaFotoMessaAManoEntraSenzaMuoversi() throws IOException {
        Path file = scrivi("2024/05/03/IMG_0001.jpg", immagine(Color.RED, "jpg"));

        Importazione esito = indicizza();

        assertThat(esito.trovate()).isEqualTo(1);
        assertThat(esito.importate()).isEqualTo(1);
        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getPercorso()).isEqualTo("2024/05/03/IMG_0001.jpg");
        assertThat(foto.getNomeOriginale()).isEqualTo("IMG_0001.jpg");
        assertThat(foto.getGiorno()).isEqualTo("2024-05-03");
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.CARTELLA);
        assertThat(file).exists();
        assertThat(service.fileOriginale(foto)).isEqualTo(file);
        assertThat(archivio.resolve(".miniature/" + foto.getId() + ".jpg")).exists();

        // Una seconda volta non cambia niente.
        Importazione ancora = indicizza();
        assertThat(ancora.importate()).isZero();
        assertThat(ancora.duplicate()).isEqualTo(1);
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void conLExifDiUnAltroGiornoVaNellaCartellaGiusta() throws IOException {
        Path sbagliata = scrivi("2024/01/01/con-exif.jpg", getClass().getResourceAsStream("/con-exif.jpg").readAllBytes());

        assertThat(indicizza().importate()).isEqualTo(1);

        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.EXIF);
        assertThat(foto.getPercorso()).isEqualTo("2021/07/14/con-exif.jpg");
        assertThat(archivio.resolve("2021/07/14/con-exif.jpg")).exists();
        assertThat(sbagliata).doesNotExist();
        // La cartella rimasta vuota sparisce.
        assertThat(archivio.resolve("2024")).doesNotExist();
    }

    @Test
    void fuoriDalleCartelleDelGiornoUsaLaDataDelFile() throws IOException {
        Path sparsa = scrivi("Da sistemare/sparsa.png", immagine(Color.BLUE, "png"));
        Files.setLastModifiedTime(sparsa, FileTime.from(Instant.parse("2019-03-08T10:00:00Z")));

        assertThat(indicizza().importate()).isEqualTo(1);

        Foto foto = repository.findAll().getFirst();
        assertThat(foto.getOrigineData()).isEqualTo(OrigineData.FILE);
        assertThat(foto.getGiorno()).isEqualTo("2019-03-08");
        assertThat(foto.getPercorso()).isEqualTo("2019/03/08/sparsa.png");
        assertThat(sparsa).doesNotExist();
    }

    @Test
    void saltaLeCartelleColPunto() throws IOException {
        scrivi(".backup/vecchia.jpg", immagine(Color.GREEN, "jpg"));
        scrivi(".miniature/finta.jpg", immagine(Color.YELLOW, "jpg"));
        scrivi("2024/05/03/._IMG_0001.jpg", new byte[] {0, 5, 22, 7});
        scrivi("2024/05/03/note.txt", "non è una foto".getBytes());

        Importazione esito = indicizza();

        assertThat(esito.trovate()).isZero();
        assertThat(repository.count()).isZero();
    }

    @Test
    void unaCopiaIdenticaNonDiventaUnaSecondaScheda() throws IOException {
        byte[] png = immagine(Color.MAGENTA, "png");
        var caricata = service.importa("a.png", png, Instant.parse("2024-05-03T10:00:00Z"), null);
        assertThat(caricata.esito()).isEqualTo(Esito.CARICATA);
        Path copia = scrivi("2023/12/25/copia.png", png);

        Importazione esito = indicizza();

        assertThat(esito.importate()).isZero();
        assertThat(esito.duplicate()).isEqualTo(2);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findAll().getFirst().getPercorso()).isEqualTo(caricata.foto().getPercorso());
        // Il file non si tocca.
        assertThat(copia).exists();
    }

    @Test
    void unFileIllegibileEUnErroreENonFermaIlResto() throws IOException {
        Path rotta = scrivi("2024/05/03/rotta.jpg", "non è un JPEG".getBytes());
        scrivi("2024/05/04/buona.jpg", immagine(Color.ORANGE, "jpg"));

        Importazione esito = indicizza();

        assertThat(esito.errori()).isEqualTo(1);
        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.messaggi()).singleElement().asString().contains("rotta.jpg");
        assertThat(rotta).exists();
    }

    @Test
    void inSottofondoComeUnImportazione() throws Exception {
        scrivi("2024/05/03/a.jpg", immagine(Color.RED, "jpg"));
        scrivi("2024/05/04/b.jpg", immagine(Color.GREEN, "jpg"));

        StatoLavoro partito = lavori.avviaIndicizzazione();
        assertThat(partito.origine()).isEqualTo(Origine.INDICIZZAZIONE);

        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.origine()).isEqualTo(Origine.INDICIZZAZIONE);
        assertThat(fine.ricerca()).isFalse();
        assertThat(fine.trovate()).isEqualTo(2);
        assertThat(fine.importate()).isEqualTo(2);
        assertThat(repository.findAll()).extracting(Foto::getGiorno)
                .containsExactlyInAnyOrder("2024-05-03", "2024-05-04");
    }

    @Test
    void mentreCercaIFileLoStatoLiContaEAnnullaFermaTutto() throws Exception {
        scrivi("2024/05/03/a.jpg", immagine(Color.RED, "jpg"));
        scrivi("2024/05/04/b.jpg", immagine(Color.GREEN, "jpg"));
        scrivi("2024/05/05/c.jpg", immagine(Color.BLUE, "jpg"));
        // La vera ricerca, che si ferma al secondo file trovato finché il lavoro non è annullato.
        var vera = new ImportazioneCartelle(service, archivioFile, proprieta);
        CountDownLatch alSecondo = new CountDownLatch(1);
        int[] totale = {-1};
        doAnswer(inv -> {
            Avanzamento lavoro = inv.getArgument(0);
            return vera.indicizza(new Avanzamento() {
                @Override
                public void cercando(int trovati, Path cartella) {
                    lavoro.cercando(trovati, cartella);
                    if (trovati == 2) {
                        alSecondo.countDown();
                        Instant limite = Instant.now().plusSeconds(10);
                        while (!lavoro.annullata() && Instant.now().isBefore(limite)) {
                            Thread.onSpinWait();
                        }
                    }
                }

                @Override
                public void inizio(int n) {
                    totale[0] = n;
                    lavoro.inizio(n);
                }

                @Override
                public void fatta(Path file, Risultati.Caricamento esito, boolean rimossa) {
                    lavoro.fatta(file, esito, rimossa);
                }

                @Override
                public boolean annullata() {
                    return lavoro.annullata();
                }
            });
        }).when(importazione).indicizza(any());

        StatoLavoro partito = lavori.avviaIndicizzazione();
        assertThat(partito.ricerca()).isTrue();
        assertThat(alSecondo.await(10, TimeUnit.SECONDS)).isTrue();

        StatoLavoro cerca = lavori.corrente().orElseThrow();
        assertThat(cerca.stato()).isEqualTo(Stato.IN_CORSO);
        assertThat(cerca.ricerca()).isTrue();
        assertThat(cerca.trovate()).isEqualTo(2);
        assertThat(cerca.fatte()).isZero();
        assertThat(cerca.cercaIn()).matches("2024/05/0[345]");

        assertThat(lavori.annulla()).isTrue();
        StatoLavoro fine = aspettaFine();
        assertThat(fine.stato()).isEqualTo(Stato.ANNULLATA);
        assertThat(fine.ricerca()).isFalse();
        assertThat(fine.cercaIn()).isNull();
        assertThat(fine.fatte()).isZero();
        // Il cammino si è fermato lì: il terzo file non l'ha nemmeno trovato.
        assertThat(totale[0]).isEqualTo(2);
        assertThat(repository.count()).isZero();
    }

    @Test
    void conTreFileInsiemeGliStessiEsitiDiUnoAllaVolta() throws Exception {
        prepara();
        Importazione uno = indicizza(1);
        Risultato primo = risultato();
        svuota();
        prepara();
        Importazione tre = indicizza(3);
        Risultato secondo = risultato();

        assertThat(uno.trovate()).isEqualTo(48);
        assertThat(uno.importate()).isEqualTo(45);
        assertThat(uno.duplicate()).isEqualTo(2);
        assertThat(uno.errori()).isEqualTo(1);
        assertThat(tre).usingRecursiveComparison().isEqualTo(uno);
        assertThat(secondo).isEqualTo(primo);
        // Tre file diversi con lo stesso nome nello stesso giorno, due arrivati spostandoli: nessuno sovrascritto.
        assertThat(secondo.percorsi()).contains("2019/03/08/x.png", "2019/03/08/x (2).png", "2019/03/08/x (3).png");
        assertThat(secondo.percorsi()).contains("2021/07/14/con-exif.jpg");
        assertThat(archivio.resolve("Da sistemare")).doesNotExist();
    }

    /** Quello che resta dopo un'indicizzazione, senza dipendere da quale copia identica è arrivata prima. */
    record Risultato(List<String> percorsi, List<String> hash, long fileSuDisco, long miniature) {
    }

    /** Le miniature delle schede che ci sono: quelle dei file senza scheda (rotti, perdenti) non devono restare. */
    private long miniature() throws IOException {
        try (Stream<Path> s = Files.list(archivio.resolve(".miniature"))) {
            return s.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".jpg"))
                    .filter(n -> repository.existsById(n.substring(0, n.length() - 4))).count();
        }
    }

    private long miniatureSuDisco() throws IOException {
        try (Stream<Path> s = Files.list(archivio.resolve(".miniature"))) {
            return s.filter(p -> p.getFileName().toString().endsWith(".jpg")).count();
        }
    }

    private Risultato risultato() throws IOException {
        List<Foto> tutte = repository.findAll();
        for (Foto f : tutte) {
            assertThat(service.fileOriginale(f)).exists();
        }
        // Delle copie identiche resta un percorso qualunque: si confrontano gli altri.
        List<String> percorsi = tutte.stream().map(Foto::getPercorso).filter(p -> !p.contains("/uguale")).sorted().toList();
        List<String> hash = tutte.stream().map(Foto::getHash).sorted().toList();
        assertThat(hash).doesNotHaveDuplicates();
        long fileSuDisco;
        try (Stream<Path> s = Files.walk(archivio)) {
            fileSuDisco = s.filter(Files::isRegularFile).filter(p -> !p.startsWith(archivio.resolve(".miniature"))).count();
        }
        return new Risultato(percorsi, hash, fileSuDisco, miniature());
    }

    /**
     * 48 file: 40 foto diverse già al loro posto, tre copie identiche, una
     * rotta, una con l'EXIF di un altro giorno e due "x.png" diverse da
     * spostare nel giorno dove c'è già una terza "x.png".
     */
    private static void prepara() throws IOException {
        for (int i = 0; i < 40; i++) {
            scrivi("2024/05/%02d/foto%02d.png".formatted(1 + i % 28, i), immagine(new Color(i * 6, 255 - i * 6, i * 3), "png"));
        }
        byte[] uguale = immagine(Color.PINK, "png");
        scrivi("2023/01/01/uguale.png", uguale);
        scrivi("2023/01/02/uguale.png", uguale);
        scrivi("2023/01/03/uguale.png", uguale);
        scrivi("2023/01/04/rotta.jpg", "non è un JPEG".getBytes());
        scrivi("2024/01/01/con-exif.jpg", IndicizzazioneTest.class.getResourceAsStream("/con-exif.jpg").readAllBytes());
        Instant marzo = Instant.parse("2019-03-08T10:00:00Z");
        scrivi("2019/03/08/x.png", immagine(Color.CYAN, "png"));
        Files.setLastModifiedTime(scrivi("Da sistemare/a/x.png", immagine(Color.GRAY, "png")), FileTime.from(marzo));
        Files.setLastModifiedTime(scrivi("Da sistemare/b/x.png", immagine(Color.DARK_GRAY, "png")), FileTime.from(marzo));
    }

    @Test
    void dueCopieIdenticheInsiemeDannoUnaSolaScheda() throws Exception {
        byte[] png = immagine(Color.MAGENTA, "png");
        Path prima = scrivi("2024/05/03/copia.png", png);
        Path seconda = scrivi("2024/05/04/copia.png", png);
        // Le due letture si aspettano dopo il controllo dell'hash e prima di salvare:
        // nessuna delle due vede l'altra già in archivio, decide l'indice unico.
        CyclicBarrier insieme = new CyclicBarrier(2);
        doAnswer(inv -> {
            insieme.await(10, TimeUnit.SECONDS);
            return inv.callRealMethod();
        }).when(estrattore).estrai(argThat((Path p) -> p != null && p.getFileName().toString().equals("copia.png")));
        long miniaturePrima = miniatureSuDisco();

        Importazione esito = indicizza(3);

        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.duplicate()).isEqualTo(1);
        assertThat(esito.errori()).isZero();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(prima).exists();
        assertThat(seconda).exists();
        // La miniatura di chi ha perso non resta.
        assertThat(miniatureSuDisco()).isEqualTo(miniaturePrima + 1);
        assertThat(archivio.resolve(".miniature/" + repository.findAll().getFirst().getId() + ".jpg")).exists();
    }

    @Test
    void ilRilancioNonRileggeIFileGiaIndicizzati() throws IOException {
        List<Path> file = List.of(
                scrivi("2024/05/03/a.jpg", immagine(Color.RED, "jpg")),
                scrivi("2024/05/04/b.png", immagine(Color.GREEN, "png")),
                scrivi("2024/05/05/c.png", immagine(Color.BLUE, "png")));
        assertThat(indicizza(3).importate()).isEqualTo(3);
        // Se si rileggessero, questi non sono più immagini: sarebbero errori o schede nuove.
        for (Path p : file) {
            Files.write(p, "non è più una foto".getBytes());
        }
        scrivi("2024/05/06/nuova.png", immagine(Color.YELLOW, "png"));

        Importazione ancora = indicizza(3);

        assertThat(ancora.trovate()).isEqualTo(4);
        assertThat(ancora.duplicate()).isEqualTo(3);
        assertThat(ancora.importate()).isEqualTo(1);
        assertThat(ancora.errori()).isZero();
        assertThat(repository.count()).isEqualTo(4);
    }

    /** L'indicizzazione vera con {@code paralleli} file insieme. */
    private Importazione indicizza(int paralleli) throws IOException {
        return new ImportazioneCartelle(service, archivioFile, proprieta,
                new IndicizzazioneProperties(paralleli, Duration.ofMillis(1))).indicizza(Avanzamento.NESSUNO);
    }

    private Importazione indicizza() throws IOException {
        return importazione.indicizza(Avanzamento.NESSUNO);
    }

    private static Path scrivi(String percorso, byte[] contenuto) throws IOException {
        Path p = archivio.resolve(percorso);
        Files.createDirectories(p.getParent());
        return Files.write(p, contenuto);
    }

    private static void cancellaTutto(Path p) throws IOException {
        try (Stream<Path> s = Files.walk(p)) {
            for (Path q : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(q);
            }
        }
    }

    private StatoLavoro aspettaFine() throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(30));
        while (Instant.now().isBefore(limite)) {
            var stato = lavori.corrente();
            if (stato.isEmpty() || stato.get().stato() != Stato.IN_CORSO) {
                return stato.orElse(null);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Indicizzazione ancora in corso dopo 30 secondi");
    }

    private static byte[] immagine(Color colore, String formato) throws IOException {
        var img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        var out = new ByteArrayOutputStream();
        ImageIO.write(img, formato, out);
        return out.toByteArray();
    }
}
