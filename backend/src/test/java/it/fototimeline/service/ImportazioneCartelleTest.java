package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.cloud.Cloud;

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
