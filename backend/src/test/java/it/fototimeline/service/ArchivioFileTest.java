package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import it.fototimeline.cloud.Cloud;

class ArchivioFileTest {

    @Test
    void cartellaPerAnnoMeseGiorno() {
        assertThat(ArchivioFile.cartella(LocalDate.of(2024, 8, 5))).isEqualTo("2024/08/05");
        assertThat(ArchivioFile.alSuoPosto("2024/08/05/a.jpg", LocalDate.of(2024, 8, 5))).isTrue();
        assertThat(ArchivioFile.alSuoPosto("2024/08/05/x/a.jpg", LocalDate.of(2024, 8, 5))).isFalse();
        assertThat(ArchivioFile.alSuoPosto("originali/2024/08/a.jpg", LocalDate.of(2024, 8, 5))).isFalse();
    }

    @Test
    void giornoDallaCartella() {
        assertThat(ArchivioFile.giornoDellaCartella("2024/05/03/a.jpg")).isEqualTo(LocalDate.of(2024, 5, 3));
        assertThat(ArchivioFile.giornoDellaCartella("2024/02/30/a.jpg")).isNull();
        assertThat(ArchivioFile.giornoDellaCartella("2024/05/03/altro/a.jpg")).isNull();
        assertThat(ArchivioFile.giornoDellaCartella("Vacanze/a.jpg")).isNull();
    }

    @Test
    void nomeSicuroTogliePercorsiECaratteriVietati() {
        assertThat(ArchivioFile.nomeSicuro("IMG_0001.JPG", "id", "jpg")).isEqualTo("IMG_0001.jpg");
        assertThat(ArchivioFile.nomeSicuro("../../etc/x.png", "id", "png")).isEqualTo("x.png");
        assertThat(ArchivioFile.nomeSicuro("C:\\Foto\\a:b?.jpg", "id", "jpg")).isEqualTo("a_b_.jpg");
        assertThat(ArchivioFile.nomeSicuro(".jpg", "id", "jpg")).isEqualTo("id.jpg");
        assertThat(ArchivioFile.nomeSicuro(null, "id", "png")).isEqualTo("id.png");
    }

    @Test
    void numeraIDoppioni() {
        assertThat(ArchivioFile.conNumero("IMG.jpg", 1)).isEqualTo("IMG.jpg");
        assertThat(ArchivioFile.conNumero("IMG.jpg", 3)).isEqualTo("IMG (3).jpg");
    }

    @TempDir
    Path disco;

    @Test
    void copieESpostamentiInsiemeVersoLoStessoNomeNonSiSovrascrivono() throws Exception {
        Path radice = disco.resolve("archivio");
        Cloud cloud = Mockito.mock(Cloud.class);
        Mockito.when(cloud.disponibile()).thenReturn(true);
        var archivio = new ArchivioFile(new ArchivioProperties(radice, disco.resolve("miniature"), 0, null), cloud);
        LocalDate giorno = LocalDate.of(2024, 5, 3);
        int quanti = 12;
        // Metà si copiano da fuori, metà si spostano da un'altra cartella dell'archivio: tutti "x.jpg" nello stesso giorno.
        List<Callable<String>> lavori = new ArrayList<>();
        CyclicBarrier via = new CyclicBarrier(2 * quanti);
        for (int i = 0; i < quanti; i++) {
            Path fuori = Files.writeString(disco.resolve("fuori" + i + ".jpg"), "copiata " + i);
            Path dentro = Files.createDirectories(radice.resolve("2020/01/%02d".formatted(i + 1))).resolve("x.jpg");
            Files.writeString(dentro, "spostata " + i);
            String percorso = radice.relativize(dentro).toString();
            lavori.add(() -> {
                via.await(10, TimeUnit.SECONDS);
                return archivio.salvaOriginale("x.jpg", "id", "jpg", giorno, fuori);
            });
            lavori.add(() -> {
                via.await(10, TimeUnit.SECONDS);
                return archivio.sposta(percorso, giorno, "x.jpg");
            });
        }
        ExecutorService esecutore = Executors.newFixedThreadPool(2 * quanti);
        Set<String> percorsi;
        try {
            List<String> fatti = new ArrayList<>();
            for (Future<String> f : esecutore.invokeAll(lavori)) {
                fatti.add(f.get());
            }
            percorsi = Set.copyOf(fatti);
            assertThat(fatti).hasSize(2 * quanti);
        } finally {
            esecutore.shutdownNow();
        }

        assertThat(percorsi).hasSize(2 * quanti).contains("2024/05/03/x.jpg", "2024/05/03/x (24).jpg");
        Set<String> contenuti;
        try (Stream<Path> s = Files.list(radice.resolve("2024/05/03"))) {
            contenuti = s.map(p -> {
                try {
                    return Files.readString(p);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }).collect(Collectors.toSet());
        }
        // Nessun file perso: ognuno col suo contenuto.
        assertThat(contenuti).hasSize(2 * quanti);
        // Le cartelle svuotate dagli spostamenti sono sparite.
        assertThat(radice.resolve("2020")).doesNotExist();
    }

    @Test
    void sulloStessoDiscoSiSpostaSenzaCopiareEMaiSopraUnAltroFile() throws Exception {
        Path radice = disco.resolve("cloud/archivio");
        var archivio = new ArchivioFile(new ArchivioProperties(radice, disco.resolve("miniature"), 0, null), montato());
        LocalDate giorno = LocalDate.of(2024, 5, 3);
        Path giaLi = Files.createDirectories(radice.resolve("2024/05/03")).resolve("IMG_1.jpg");
        Files.writeString(giaLi, "un'altra foto");
        Path origine = Files.writeString(Files.createDirectories(disco.resolve("cloud/telefono")).resolve("IMG_1.jpg"),
                "dal telefono");
        Object inode = Files.getAttribute(origine, "unix:ino");

        var salvato = archivio.salvaOriginale("IMG_1.jpg", "id", "jpg", giorno, origine, true);

        assertThat(salvato.spostato()).isTrue();
        assertThat(salvato.percorso()).isEqualTo("2024/05/03/IMG_1 (2).jpg");
        assertThat(origine).doesNotExist();
        Path destinazione = radice.resolve(salvato.percorso());
        assertThat(destinazione).hasContent("dal telefono");
        // Rinominato, non copiato: è lo stesso file.
        assertThat(Files.getAttribute(destinazione, "unix:ino")).isEqualTo(inode);
        assertThat(giaLi).hasContent("un'altra foto");

        // La scheda non entra: torna dov'era.
        archivio.rimetti(salvato.percorso(), origine);
        assertThat(origine).hasContent("dal telefono");
        assertThat(destinazione).doesNotExist();
    }

    @Test
    void suDischiDiversiSiCopiaELOrigineResta() throws Exception {
        Path radice = disco.resolve("cloud/archivio");
        var archivio = new ArchivioFile(new ArchivioProperties(radice, disco.resolve("miniature"), 0, null), montato()) {
            @Override
            boolean stessoDisco(Path a, Path b) {
                return false;
            }
        };
        Path origine = Files.writeString(Files.createDirectories(disco.resolve("altrove")).resolve("IMG_2.jpg"), "foto");
        Object inode = Files.getAttribute(origine, "unix:ino");

        var salvato = archivio.salvaOriginale("IMG_2.jpg", "id", "jpg", LocalDate.of(2024, 5, 3), origine, true);

        assertThat(salvato.spostato()).isFalse();
        assertThat(salvato.percorso()).isEqualTo("2024/05/03/IMG_2.jpg");
        // L'origine la toglie chi chiama, dopo la scheda.
        assertThat(origine).hasContent("foto");
        Path destinazione = radice.resolve(salvato.percorso());
        assertThat(destinazione).hasContent("foto");
        assertThat(Files.getAttribute(destinazione, "unix:ino")).isNotEqualTo(inode);
    }

    @Test
    void stessoDiscoRiconosceLoStessoFileSystem() throws Exception {
        var archivio = new ArchivioFile(new ArchivioProperties(disco.resolve("a"), disco.resolve("miniature"), 0, null),
                montato());
        Path uno = Files.createDirectories(disco.resolve("uno"));
        Path due = Files.createDirectories(disco.resolve("due"));
        assertThat(archivio.stessoDisco(uno, due)).isTrue();
        // /proc è un altro file system.
        assertThat(archivio.stessoDisco(uno, Path.of("/proc/self"))).isFalse();
    }

    private static Cloud montato() {
        Cloud cloud = Mockito.mock(Cloud.class);
        Mockito.when(cloud.disponibile()).thenReturn(true);
        return cloud;
    }
}
