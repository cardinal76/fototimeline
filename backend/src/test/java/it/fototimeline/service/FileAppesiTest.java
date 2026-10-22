package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import it.fototimeline.cloud.Cloud;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.service.LavoriImportazione.Stato;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;

/**
 * I lavori che in produzione restavano fermi per ore: un file che il mount
 * non dà più (qui una lettura che aspetta un latch che non scatta mai), Annulla
 * con un file appeso, un errore inatteso in un thread.
 */
class FileAppesiTest {

    @TempDir
    Path disco;

    /** Le letture "appese" aspettano questo: si sblocca solo alla fine del test. */
    private final CountDownLatch maiPiu = new CountDownLatch(1);

    @AfterEach
    void sblocca() {
        maiPiu.countDown();
    }

    @Test
    void unFileCheNonTornaSiAbbandonaEIlLavoroVaAvanti() throws Exception {
        Path foto = archivioCon(6);
        FotoService fotoService = Mockito.mock(FotoService.class);
        CountDownLatch tornato = new CountDownLatch(1);
        Mockito.when(fotoService.indicizza(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            Path p = inv.getArgument(0);
            String nome = p.getFileName().toString();
            if (nome.equals("f002.jpg")) {
                try {
                    // La lettura dal mount che non torna.
                    maiPiu.await();
                } finally {
                    tornato.countDown();
                }
            }
            return new Caricamento(nome, Esito.CARICATA, null, null);
        });
        var importazione = importazione(fotoService, foto, new IndicizzazioneProperties(2, Duration.ofMillis(1),
                Duration.ofMillis(300), Duration.ZERO, Duration.ofHours(1)));
        AtomicInteger fatte = new AtomicInteger();
        List<String> errori = new ArrayList<>();
        var avanzamento = new ImportazioneCartelle.Avanzamento() {
            @Override
            public synchronized void fatta(Path file, Caricamento esito, boolean rimossa) {
                fatte.incrementAndGet();
                if (esito.esito() == Esito.ERRORE) {
                    errori.add(esito.messaggio());
                }
            }
        };

        var esito = CompletableFuture.supplyAsync(() -> indicizza(importazione, avanzamento)).get(20, TimeUnit.SECONDS);

        // Gli altri cinque fatti, quello appeso contato come errore di lettura (si riprova al giro dopo).
        assertThat(esito.importate()).isEqualTo(5);
        assertThat(esito.errori()).isEqualTo(1);
        assertThat(fatte).hasValue(6);
        assertThat(errori).singleElement().satisfies(m -> {
            assertThat(m).contains("oltre il tempo massimo").contains("in partenza");
            assertThat(FotoService.erroreDiLettura(m)).isTrue();
            assertThat(FotoService.erroreDelFile(m)).isFalse();
        });
        assertThat(esito.messaggi()).singleElement().asString().startsWith("FotoTimeline/2015/11/01/f002.jpg: File illeggibile (");

        // Se poi la lettura torna, quel thread non conta niente.
        maiPiu.countDown();
        assertThat(tornato.await(10, TimeUnit.SECONDS)).isTrue();
        aspetta(() -> Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.getName().endsWith("f002.jpg")),
                "il thread del file appeso finito");
        assertThat(fatte).hasValue(6);
    }

    @Test
    void annullaConUnFileAppesoFinisceLostesso() throws Exception {
        Path foto = archivioCon(10);
        FotoService fotoService = Mockito.mock(FotoService.class);
        CountDownLatch cominciati = new CountDownLatch(3);
        Mockito.when(fotoService.indicizza(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            cominciati.countDown();
            // Appesi tutti, e sordi ad Annulla: come una read() sul mount.
            maiPiu.await();
            return new Caricamento("x", Esito.CARICATA, null, null);
        });
        var importazione = importazione(fotoService, foto, new IndicizzazioneProperties(3, Duration.ofMillis(1),
                Duration.ofHours(1), Duration.ZERO, Duration.ofMillis(300)));
        AtomicBoolean annullata = new AtomicBoolean();
        List<String> errori = new ArrayList<>();
        var avanzamento = new ImportazioneCartelle.Avanzamento() {
            @Override
            public synchronized void fatta(Path file, Caricamento esito, boolean rimossa) {
                errori.add(esito.messaggio());
            }

            @Override
            public boolean annullata() {
                return annullata.get();
            }
        };

        var lavoro = CompletableFuture.supplyAsync(() -> indicizza(importazione, avanzamento));
        assertThat(cominciati.await(10, TimeUnit.SECONDS)).isTrue();
        annullata.set(true);
        // Prima restava qui per sempre: "Annullo… finiscono i file già cominciati".
        var esito = lavoro.get(20, TimeUnit.SECONDS);

        assertThat(esito.errori()).isEqualTo(3);
        assertThat(errori).hasSize(3).allSatisfy(m -> assertThat(m).contains("dopo Annulla").startsWith("File illeggibile ("));
        // Nessun file nuovo dopo Annulla.
        Mockito.verify(fotoService, Mockito.times(3)).indicizza(Mockito.any(), Mockito.any());
    }

    @Test
    void unErroreInattesoChiudeIlLavoroComeFallito() throws Exception {
        Path foto = archivioCon(5);
        FotoService fotoService = Mockito.mock(FotoService.class);
        Mockito.when(fotoService.indicizza(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            if (inv.<Path>getArgument(0).getFileName().toString().equals("f001.jpg")) {
                // Un Error, non una Exception: prima il lavoro restava "in corso" per sempre, senza thread.
                throw new OutOfMemoryError("Java heap space (prova)");
            }
            return new Caricamento("x", Esito.CARICATA, null, null);
        });
        LavoriImportazione lavori = lavori(importazione(fotoService, foto, new IndicizzazioneProperties(2,
                Duration.ofMillis(1))));

        lavori.avviaIndicizzazione();
        StatoLavoro fine = aspettaFine(lavori);

        assertThat(fine.stato()).isEqualTo(Stato.FALLITA);
        assertThat(fine.errore()).contains("OutOfMemoryError").contains("Java heap space (prova)");
        assertThat(fine.inLavorazione()).isEmpty();
        // Il posto è libero: si può rilanciare.
        assertThat(lavori.avviaIndicizzazione().stato()).isEqualTo(Stato.IN_CORSO);
        aspettaFine(lavori);
    }

    @Test
    void loStatoDiceQualeFileSiStaLavorandoInQualeFaseEDaQuanto() throws Exception {
        Path foto = archivioCon(1);
        FotoService fotoService = Mockito.mock(FotoService.class);
        CountDownLatch dentro = new CountDownLatch(1);
        CountDownLatch via = new CountDownLatch(1);
        Mockito.when(fotoService.indicizza(Mockito.any(), Mockito.any())).thenAnswer(inv -> {
            ControlloFile controllo = inv.getArgument(1);
            controllo.fase(ControlloFile.Fase.METADATI);
            dentro.countDown();
            assertThat(via.await(20, TimeUnit.SECONDS)).isTrue();
            return new Caricamento("f000.jpg", Esito.CARICATA, null, null);
        });
        LavoriImportazione lavori = lavori(importazione(fotoService, foto, IndicizzazioneProperties.PREDEFINITE));

        Instant prima = Instant.now();
        lavori.avviaIndicizzazione();
        assertThat(dentro.await(10, TimeUnit.SECONDS)).isTrue();
        StatoLavoro stato = lavori.corrente().orElseThrow();
        assertThat(stato.inLavorazione()).singleElement().satisfies(f -> {
            assertThat(f.file()).isEqualTo("FotoTimeline/2015/11/01/f000.jpg");
            assertThat(f.fase()).isEqualTo("metadati");
            assertThat(f.secondi()).isBetween(0L, Duration.between(prima, Instant.now()).toSeconds() + 1);
            assertThat(f.secondiFase()).isLessThanOrEqualTo(f.secondi());
        });

        via.countDown();
        StatoLavoro fine = aspettaFine(lavori);
        assertThat(fine.stato()).isEqualTo(Stato.FINITA);
        assertThat(fine.importate()).isEqualTo(1);
        assertThat(fine.inLavorazione()).isEmpty();
    }

    @Test
    void unLavoroAnnullatoMaAppesoFuoriDaiFileLoChiudeIlGuardiano() throws Exception {
        Path foto = archivioCon(0);
        ImportazioneCartelle importazione = Mockito.mock(ImportazioneCartelle.class);
        CountDownLatch dentro = new CountDownLatch(1);
        // Come un Files.walk sul mount che non torna: Annulla non lo sveglia.
        Mockito.when(importazione.indicizza(Mockito.any())).thenAnswer(inv -> {
            dentro.countDown();
            maiPiu.await();
            return null;
        });
        Mockito.when(importazione.relativo(Mockito.any())).thenAnswer(inv -> inv.getArgument(0).toString());
        LavoriImportazione lavori = new LavoriImportazione(importazione, new ImportazioneAutomaticaProperties(null, null,
                null), Mockito.mock(MemoriaImportazione.class), archivio(foto), List.of(), Mockito.mock(LuoghiService.class),
                new IndicizzazioneProperties(1, Duration.ofMillis(1), null, null, Duration.ofMillis(200)), Optional.empty());

        lavori.avviaIndicizzazione();
        assertThat(dentro.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(lavori.annulla()).isTrue();
        StatoLavoro fine = aspettaFine(lavori);

        assertThat(fine.stato()).isEqualTo(Stato.ANNULLATA);
        assertThat(fine.errore()).contains("non rispondeva");
        // Il posto si è liberato anche se quel thread è ancora appeso.
        CountDownLatch secondo = new CountDownLatch(1);
        Mockito.doAnswer(inv -> {
            secondo.countDown();
            return null;
        }).when(importazione).indicizza(Mockito.any());
        lavori.avviaIndicizzazione();
        assertThat(secondo.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(aspettaFine(lavori).stato()).isEqualTo(Stato.FINITA);
    }

    private static Risultati.Importazione indicizza(ImportazioneCartelle importazione,
            ImportazioneCartelle.Avanzamento avanzamento) {
        try {
            return importazione.indicizza(avanzamento);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private ImportazioneCartelle importazione(FotoService fotoService, Path foto, IndicizzazioneProperties tempi) {
        return new ImportazioneCartelle(fotoService, archivio(foto),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, disco.resolve("cloud")), tempi);
    }

    private LavoriImportazione lavori(ImportazioneCartelle importazione) {
        return new LavoriImportazione(importazione, new ImportazioneAutomaticaProperties(null, null, null),
                Mockito.mock(MemoriaImportazione.class), archivio(disco.resolve("cloud/FotoTimeline")), List.of(),
                Mockito.mock(LuoghiService.class), IndicizzazioneProperties.PREDEFINITE, Optional.empty());
    }

    private ArchivioFile archivio(Path foto) {
        Cloud cloud = Mockito.mock(Cloud.class);
        Mockito.when(cloud.disponibile()).thenReturn(true);
        return new ArchivioFile(new ArchivioProperties(foto, disco.resolve("miniature"), 0, disco.resolve("cloud")), cloud);
    }

    /** Un archivio con {@code quanti} file finti in 2015/11/01 (f000.jpg, f001.jpg, ...). */
    private Path archivioCon(int quanti) throws IOException {
        Path foto = Files.createDirectories(disco.resolve("cloud/FotoTimeline"));
        Path cartella = Files.createDirectories(foto.resolve("2015/11/01"));
        for (int i = 0; i < quanti; i++) {
            Files.write(cartella.resolve("f%03d.jpg".formatted(i)), new byte[] {1});
        }
        return foto;
    }

    private static StatoLavoro aspettaFine(LavoriImportazione lavori) throws InterruptedException {
        aspetta(() -> lavori.corrente().map(s -> s.stato() != Stato.IN_CORSO).orElse(false), "lavoro finito");
        return lavori.corrente().orElseThrow();
    }

    /** Aspetta che {@code condizione} diventi vera, al massimo 20 secondi. */
    private static void aspetta(BooleanSupplier condizione, String cosa) throws InterruptedException {
        Instant limite = Instant.now().plus(Duration.ofSeconds(20));
        while (!condizione.getAsBoolean()) {
            if (Instant.now().isAfter(limite)) {
                throw new AssertionError("Dopo 20 secondi ancora no: " + cosa);
            }
            Thread.sleep(20);
        }
    }
}
