package it.fototimeline.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/** Come si scrive il filtro per data: anno, mese, due giorni. */
class PeriodoTest {

    @Test
    void annoMeseEGiorni() {
        assertThat(Periodo.leggi("2019")).isEqualTo(new Periodo(LocalDate.of(2019, 1, 1), LocalDate.of(2019, 12, 31)));
        assertThat(Periodo.leggi("2024-02")).isEqualTo(new Periodo(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29)));
        assertThat(Periodo.leggi(" 2019-08-01..2019-09-15 "))
                .isEqualTo(new Periodo(LocalDate.of(2019, 8, 1), LocalDate.of(2019, 9, 15)));
        assertThat(Periodo.leggi("2019-08-01..")).isEqualTo(new Periodo(LocalDate.of(2019, 8, 1), null));
        assertThat(Periodo.leggi("..2019-09-15")).isEqualTo(new Periodo(null, LocalDate.of(2019, 9, 15)));
        assertThat(Periodo.leggi("2019-08-05")).isEqualTo(new Periodo(LocalDate.of(2019, 8, 5), LocalDate.of(2019, 8, 5)));
    }

    @Test
    void vuotoNonFiltra() {
        assertThat(Periodo.leggi(null)).isNull();
        assertThat(Periodo.leggi(" ")).isNull();
        assertThat(Periodo.leggi("..")).isNull();
    }

    @Test
    void sbagliatoERifiutato() {
        assertThatThrownBy(() -> Periodo.leggi("2019-13")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Periodo.leggi("ieri")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Periodo.leggi("2019-09-01..2019-08-01")).isInstanceOf(IllegalArgumentException.class);
    }
}
