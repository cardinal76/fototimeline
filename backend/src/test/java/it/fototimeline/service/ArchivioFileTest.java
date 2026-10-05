package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

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
}
