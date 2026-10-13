package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
