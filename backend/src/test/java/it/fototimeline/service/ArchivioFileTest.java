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
}
