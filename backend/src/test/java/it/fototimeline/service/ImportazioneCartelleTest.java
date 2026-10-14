package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.Cloud;
import it.fototimeline.service.Risultati.Caricamento;
import it.fototimeline.service.Risultati.Esito;

class ImportazioneCartelleTest {

    @TempDir
    Path disco;

    @Test
    void accettaSoloCartelleSottoLaRadice() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        var importazione = new ImportazioneCartelle(null, archivio(cloudMontato(true)),
                new ArchivioProperties(radice.resolve("FotoTimeline"), disco.resolve("miniature"), 0, radice));

        assertThat(importazione.consentita(radice.resolve("Telefono"))).isEqualTo(radice.resolve("Telefono"));
        assertThatThrownBy(() -> importazione.consentita(radice.resolve("../etc")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> importazione.consentita(Path.of("/etc")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ilNavigatoreNascondeArchivioECartelleNascoste() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        Files.createDirectories(radice.resolve("FotoTimeline/2024"));
        Files.createDirectories(radice.resolve("Telefono"));
        Files.createDirectories(radice.resolve(".cestino"));
        Files.write(radice.resolve("a.jpg"), new byte[] {1});
        var importazione = new ImportazioneCartelle(null, archivio(cloudMontato(true)),
                new ArchivioProperties(radice.resolve("FotoTimeline"), disco.resolve("miniature"), 0, radice));

        var cima = importazione.elenca(null);
        assertThat(cima.sottocartelle()).containsExactly("Telefono");
        assertThat(cima.immagini()).isEqualTo(1);
        assertThat(cima.padre()).isNull();
        assertThat(importazione.elenca(radice.resolve("Telefono")).padre()).isEqualTo(radice.toString());
    }

    @Test
    void conIlCloudSmontatoNonLeggeNiente() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        var archivio = archivio(cloudMontato(false));
        var importazione = new ImportazioneCartelle(null, archivio,
                new ArchivioProperties(radice.resolve("FotoTimeline"), disco.resolve("miniature"), 0, radice));

        assertThatThrownBy(() -> importazione.elenca(null)).isInstanceOf(ArchivioNonDisponibile.class);
        assertThatThrownBy(() -> archivio.originale("2024/01/01/a.jpg")).isInstanceOf(ArchivioNonDisponibile.class);
        assertThatThrownBy(() -> archivio.salvaOriginale("a.jpg", "id", "jpg", java.time.LocalDate.now(), disco.resolve("a.jpg")))
                .isInstanceOf(ArchivioNonDisponibile.class);
        // Niente cartella dell'archivio creata sul disco al posto del cloud.
        assertThat(radice.resolve("FotoTimeline")).doesNotExist();
    }

    @Test
    void indicizzaContaIFileMentreLiCercaEPoiComincia() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        Path foto = radice.resolve("FotoTimeline");
        for (String p : List.of("2015/11/03/a.jpg", "2015/11/03/b.jpg", "2015/11/04/c.png", ".backup/d.jpg")) {
            Files.createDirectories(foto.resolve(p).getParent());
            Files.write(foto.resolve(p), new byte[] {1});
        }
        var importazione = new ImportazioneCartelle(null, archivio(cloudMontato(true)),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, radice));
        // Si ferma a "inizio": senza FotoService non si legge niente.
        Registro registro = new Registro(Integer.MAX_VALUE, true);

        var esito = importazione.indicizza(registro);

        assertThat(registro.inizio).isEqualTo(3);
        assertThat(registro.eventi).last().isEqualTo("inizio 3");
        assertThat(registro.trovati).isEqualTo(3);
        assertThat(registro.eventi).anyMatch(e -> e.endsWith(" in " + foto.resolve("2015/11/03")));
        assertThat(registro.eventi).anyMatch(e -> e.endsWith(" in " + foto.resolve("2015/11/04")));
        assertThat(registro.eventi).noneMatch(e -> e.contains(".backup"));
        assertThat(esito.trovate()).isEqualTo(3);
        assertThat(esito.importate()).isZero();
    }

    @Test
    void annullareMentreSiCercanoIFileFermaIlCammino() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        Path foto = radice.resolve("FotoTimeline");
        for (int g = 1; g <= 5; g++) {
            Path cartella = Files.createDirectories(foto.resolve("2015/11/0" + g));
            Files.write(cartella.resolve("a.jpg"), new byte[] {1});
            Files.write(cartella.resolve("b.jpg"), new byte[] {1});
        }
        var importazione = new ImportazioneCartelle(null, archivio(cloudMontato(true)),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, radice));
        Registro registro = new Registro(3, false);

        var esito = importazione.indicizza(registro);

        // Al terzo file trovato si annulla: il cammino si ferma lì, niente si legge.
        assertThat(registro.trovati).isEqualTo(3);
        assertThat(esito.trovate()).isEqualTo(3);
        assertThat(esito.importate() + esito.duplicate() + esito.errori()).isZero();
    }

    @Test
    void importaCartellaContaIFileMentreLiCercaESiPuoAnnullare() throws IOException {
        Path radice = Files.createDirectories(disco.resolve("cloud"));
        Path telefono = Files.createDirectories(radice.resolve("telefono"));
        for (int i = 0; i < 6; i++) {
            Path cartella = Files.createDirectories(telefono.resolve("album" + i));
            Files.write(cartella.resolve("a.jpg"), new byte[] {1});
        }
        Files.writeString(telefono.resolve("note.txt"), "non è una foto");
        var importazione = new ImportazioneCartelle(null, archivio(cloudMontato(true)),
                new ArchivioProperties(radice.resolve("FotoTimeline"), disco.resolve("miniature"), 0, radice));

        Registro tutto = new Registro(Integer.MAX_VALUE, true);
        importazione.importa(telefono, true, true, tutto);
        assertThat(tutto.trovati).isEqualTo(6);
        assertThat(tutto.inizio).isEqualTo(6);
        assertThat(tutto.eventi).anyMatch(e -> e.startsWith("1 in " + telefono.resolve("album")));

        Registro annullato = new Registro(2, false);
        var esito = importazione.importa(telefono, true, true, annullato);
        assertThat(annullato.trovati).isEqualTo(2);
        // Annullata durante l'elenco: non si comincia nemmeno, i file restano.
        assertThat(annullato.inizio).isEqualTo(-1);
        assertThat(esito.trovate()).isEqualTo(2);
        assertThat(telefono.resolve("album0/a.jpg")).exists();
    }

    @Test
    void annullareAMetaNonCominciaFileNuoviEAspettaQuelliInCorso() throws Exception {
        Path foto = archivioCon(20);
        FotoService fotoService = Mockito.mock(FotoService.class);
        CountDownLatch cominciati = new CountDownLatch(3);
        CountDownLatch via = new CountDownLatch(1);
        AtomicInteger letti = new AtomicInteger();
        Mockito.when(fotoService.indicizza(Mockito.any())).thenAnswer(inv -> {
            letti.incrementAndGet();
            cominciati.countDown();
            assertThat(via.await(10, TimeUnit.SECONDS)).isTrue();
            return new Caricamento(inv.<Path>getArgument(0).getFileName().toString(), Esito.CARICATA, null, null);
        });
        var importazione = new ImportazioneCartelle(fotoService, archivio(cloudMontato(true)),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, disco.resolve("cloud")),
                new IndicizzazioneProperties(3, Duration.ofMillis(1)));
        AtomicBoolean annullata = new AtomicBoolean();
        AtomicInteger fatte = new AtomicInteger();
        var avanzamento = new ImportazioneCartelle.Avanzamento() {
            @Override
            public void fatta(Path file, Caricamento esito, boolean rimossa) {
                fatte.incrementAndGet();
            }

            @Override
            public boolean annullata() {
                return annullata.get();
            }
        };

        var lavoro = CompletableFuture.supplyAsync(() -> {
            try {
                return importazione.indicizza(avanzamento);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
        // Tre file insieme, fermi dentro la lettura.
        assertThat(cominciati.await(10, TimeUnit.SECONDS)).isTrue();
        annullata.set(true);
        // Annullata, ma i tre in corso non sono finiti: si aspettano.
        assertThat(lavoro).isNotDone();
        via.countDown();
        var esito = lavoro.get(10, TimeUnit.SECONDS);

        assertThat(letti).hasValue(3);
        assertThat(fatte).hasValue(3);
        assertThat(esito.trovate()).isEqualTo(20);
        assertThat(esito.importate()).isEqualTo(3);
    }

    @Test
    void inParalleloContaTuttoGiusto() throws Exception {
        Path foto = archivioCon(300);
        FotoService fotoService = Mockito.mock(FotoService.class);
        Map<Path, AtomicInteger> letture = new ConcurrentHashMap<>();
        AtomicInteger insieme = new AtomicInteger();
        AtomicInteger massimo = new AtomicInteger();
        Mockito.when(fotoService.indicizza(Mockito.any())).thenAnswer(inv -> {
            Path p = inv.getArgument(0);
            letture.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet();
            massimo.accumulateAndGet(insieme.incrementAndGet(), Math::max);
            try {
                int i = Integer.parseInt(p.getFileName().toString().replaceAll("\\D", ""));
                Esito e = i % 3 == 0 ? Esito.CARICATA : i % 3 == 1 ? Esito.DUPLICATA : Esito.ERRORE;
                return new Caricamento(p.getFileName().toString(), e, null, e == Esito.ERRORE ? "Formato non supportato" : null);
            } finally {
                insieme.decrementAndGet();
            }
        });
        var importazione = new ImportazioneCartelle(fotoService, archivio(cloudMontato(true)),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, disco.resolve("cloud")),
                new IndicizzazioneProperties(3, Duration.ofMillis(1)));
        AtomicInteger fatte = new AtomicInteger();
        var avanzamento = new ImportazioneCartelle.Avanzamento() {
            @Override
            public void fatta(Path file, Caricamento esito, boolean rimossa) {
                fatte.incrementAndGet();
            }
        };

        var esito = importazione.indicizza(avanzamento);

        assertThat(esito.trovate()).isEqualTo(300);
        assertThat(esito.importate()).isEqualTo(100);
        assertThat(esito.duplicate()).isEqualTo(100);
        assertThat(esito.errori()).isEqualTo(100);
        assertThat(esito.messaggi()).hasSize(50).isSorted();
        assertThat(fatte).hasValue(300);
        // Ogni file letto una volta sola, mai più di tre insieme.
        assertThat(letture).hasSize(300).allSatisfy((p, n) -> assertThat(n).hasValue(1));
        assertThat(massimo.get()).isLessThanOrEqualTo(3);
    }

    @Test
    void unErroreDiLetturaSiRiprovaUnaVoltaQuelliDelFileNo() throws Exception {
        Path foto = archivioCon(0);
        Files.createDirectories(foto.resolve("2015/11/01"));
        for (String n : List.of("torna.jpg", "rotta.jpg", "sparita.jpg")) {
            Files.write(foto.resolve("2015/11/01/" + n), new byte[] {1});
        }
        FotoService fotoService = Mockito.mock(FotoService.class);
        Map<String, AtomicInteger> letture = new ConcurrentHashMap<>();
        Mockito.when(fotoService.indicizza(Mockito.any())).thenAnswer(inv -> {
            String nome = inv.<Path>getArgument(0).getFileName().toString();
            int volta = letture.computeIfAbsent(nome, k -> new AtomicInteger()).incrementAndGet();
            return switch (nome) {
                // Il cloud non risponde la prima volta, poi sì.
                case "torna.jpg" -> volta == 1
                        ? new Caricamento(nome, Esito.ERRORE, null, "File illeggibile (Input/output error)")
                        : new Caricamento(nome, Esito.CARICATA, null, null);
                case "rotta.jpg" -> new Caricamento(nome, Esito.ERRORE, null, "Immagine illeggibile (troncata)");
                default -> new Caricamento(nome, Esito.ERRORE, null, "File illeggibile (Input/output error)");
            };
        });
        var importazione = new ImportazioneCartelle(fotoService, archivio(cloudMontato(true)),
                new ArchivioProperties(foto, disco.resolve("miniature"), 0, disco.resolve("cloud")),
                new IndicizzazioneProperties(3, Duration.ofMillis(1)));

        var esito = importazione.indicizza(ImportazioneCartelle.Avanzamento.NESSUNO);

        assertThat(letture.get("torna.jpg")).hasValue(2);
        assertThat(letture.get("rotta.jpg")).hasValue(1);
        // Una volta sola: alla seconda resta un errore.
        assertThat(letture.get("sparita.jpg")).hasValue(2);
        assertThat(esito.importate()).isEqualTo(1);
        assertThat(esito.errori()).isEqualTo(2);
    }

    @Test
    void gliErroriDiLetturaNonSonoErroriDelFile() {
        String io = FotoService.illeggibile(new IOException("Input/output error"));
        assertThat(io).isEqualTo("File illeggibile (Input/output error)");
        assertThat(FotoService.erroreDiLettura(io)).isTrue();
        // La cartella automatica non lo ricorda: al giro dopo si rilegge.
        assertThat(FotoService.erroreDelFile(io)).isFalse();
        assertThat(FotoService.erroreDiLettura("Immagine illeggibile (troncata)")).isFalse();
        assertThat(FotoService.erroreDiLettura(FotoService.CIFRATO_LIFETIME)).isFalse();

        // ImageIO avvolge l'errore del mount: è di lettura lo stesso.
        assertThat(FotoService.letturaNonRiuscita(new IIOException("Error reading", new IOException("Input/output error"))))
                .isTrue();
        assertThat(FotoService.letturaNonRiuscita(new java.nio.file.FileSystemException("/cloud/a.jpg"))).isTrue();
        assertThat(FotoService.letturaNonRiuscita(new IIOException("Unsupported Image Type"))).isFalse();
        assertThat(FotoService.letturaNonRiuscita(new IOException("Nessun lettore per questa immagine"))).isFalse();
    }

    /** Un archivio con {@code quanti} file finti in 2015/11/01 (f000.jpg, f001.jpg, ...). */
    private Path archivioCon(int quanti) throws IOException {
        Path foto = Files.createDirectories(disco.resolve("cloud/FotoTimeline"));
        if (quanti > 0) {
            Path cartella = Files.createDirectories(foto.resolve("2015/11/01"));
            for (int i = 0; i < quanti; i++) {
                Files.write(cartella.resolve("f%03d.jpg".formatted(i)), new byte[] {1});
            }
        }
        return foto;
    }

    /** Segna quello che riferisce la ricerca; si annulla al file numero {@code annullaA} o a {@code inizio}. */
    private static final class Registro implements ImportazioneCartelle.Avanzamento {
        final List<String> eventi = new ArrayList<>();
        final int annullaA;
        final boolean annullaAllInizio;
        int trovati;
        int inizio = -1;

        Registro(int annullaA, boolean annullaAllInizio) {
            this.annullaA = annullaA;
            this.annullaAllInizio = annullaAllInizio;
        }

        @Override
        public void cercando(int trovati, Path cartella) {
            assertThat(inizio).as("la ricerca viene prima di inizio").isEqualTo(-1);
            assertThat(trovati).as("i trovati non calano").isGreaterThanOrEqualTo(this.trovati);
            this.trovati = trovati;
            eventi.add(trovati + " in " + cartella);
        }

        @Override
        public void inizio(int totale) {
            inizio = totale;
            eventi.add("inizio " + totale);
        }

        @Override
        public boolean annullata() {
            return trovati >= annullaA || (annullaAllInizio && inizio >= 0);
        }
    }

    private ArchivioFile archivio(Cloud cloud) {
        Path radice = disco.resolve("cloud");
        return new ArchivioFile(new ArchivioProperties(radice.resolve("FotoTimeline"), disco.resolve("miniature"), 0, radice), cloud);
    }

    private static Cloud cloudMontato(boolean montato) {
        Cloud cloud = Mockito.mock(Cloud.class);
        Mockito.when(cloud.disponibile()).thenReturn(montato);
        if (!montato) {
            Mockito.doThrow(new ArchivioNonDisponibile()).when(cloud).verifica();
        }
        return cloud;
    }
}
