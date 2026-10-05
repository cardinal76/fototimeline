package it.fototimeline.quasiuguali;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import it.fototimeline.quasiuguali.Raggruppamento.Voce;

class RaggruppamentoTest {

    private static final LocalDateTime ORA = LocalDateTime.of(2024, 8, 15, 10, 0);
    private static final Duration FINESTRA = Duration.ofSeconds(10);

    @Test
    void conLeBandeNonSfuggeNessunaCoppia() {
        Random r = new Random(42);
        List<Voce> voci = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            voci.add(voce("c" + i, r.nextLong()));
        }
        // Coppie piantate a ogni distanza intorno alla soglia, coi bit cambiati sparsi a caso.
        for (int d = 0; d <= 10; d++) {
            for (int k = 0; k < 20; k++) {
                long base = r.nextLong();
                voci.add(voce("p" + d + "-" + k + "a", base));
                voci.add(voce("p" + d + "-" + k + "b", base ^ bitACaso(r, d)));
            }
        }
        for (int soglia : new int[] {4, 6, 8}) {
            assertThat(insiemi(voci, Raggruppamento.gruppi(voci, soglia, null, 0, (a, b) -> false)))
                    .as("soglia %d", soglia)
                    .isEqualTo(forzaBruta(voci, soglia));
        }
    }

    @Test
    void allaSogliaSiUnisconoUnBitDopoNo() {
        // 6 bit cambiati, uno per ognuna delle prime 6 bande su 7: resta uguale solo l'ultima.
        long base = 0x0123_4567_89AB_CDEFL;
        long sei = base ^ (1L | 1L << 10 | 1L << 20 | 1L << 30 | 1L << 39 | 1L << 48);
        long altra = ~base;
        long sette = altra ^ (1L | 1L << 10 | 1L << 20 | 1L << 30 | 1L << 39 | 1L << 48 | 1L << 60);
        List<Voce> voci = List.of(voce("a", base), voce("b", sei), voce("c", altra), voce("d", sette));
        assertThat(insiemi(voci, Raggruppamento.gruppi(voci, 6, null, 0, (x, y) -> false)))
                .containsExactly(Set.of("a", "b"));
    }

    @Test
    void unaRafficaHaUnaSogliaPiuLargaSoloConLaDataExifVicina() {
        long base = 0x0F0F_0F0F_F0F0_F0F0L;
        // Tutte a 10 bit da "a", e a 20 tra loro.
        List<Voce> voci = List.of(
                new Voce("a", base, ORA, true),
                new Voce("b", base ^ 0x3FFL, ORA.plusSeconds(3), true),
                new Voce("lontana", base ^ 0x3FFL << 20, ORA.plusMinutes(5), true),
                new Voce("senza-exif", base ^ 0x3FFL << 40, ORA.plusSeconds(1), false));
        assertThat(insiemi(voci, Raggruppamento.gruppi(voci, 6, FINESTRA, 12, (x, y) -> false)))
                .containsExactly(Set.of("a", "b"));
        assertThat(Raggruppamento.gruppi(voci, 6, null, 12, (x, y) -> false)).isEmpty();
    }

    @Test
    void leCoppieSeparateNonSiUniscono() {
        List<Voce> voci = List.of(voce("a", 5L), voce("b", 5L), voce("c", 7L));
        assertThat(insiemi(voci, Raggruppamento.gruppi(voci, 6, null, 0,
                (x, y) -> Set.of(x.id(), y.id()).equals(Set.of("a", "b")))))
                // a e b si ritrovano insieme solo passando per c, che somiglia a tutte e due.
                .containsExactly(Set.of("a", "b", "c"));
        List<Voce> due = List.of(voce("a", 5L), voce("b", 5L));
        assertThat(Raggruppamento.gruppi(due, 6, null, 0, (x, y) -> true)).isEmpty();
    }

    @Test
    void cassettiConBandeLargheEStrette() {
        long[] impronte = {0b1010L, 0b1010L, 0b0110L, -1L, -1L};
        assertThat(Raggruppamento.cassetti(impronte, 0, 4)).hasSize(2);
        assertThat(Raggruppamento.cassetti(impronte, 0, 64)).hasSize(2);
        assertThat(Raggruppamento.cassetti(impronte, 0, 2)).hasSize(2);
    }

    private static Voce voce(String id, long impronta) {
        return new Voce(id, impronta, null, false);
    }

    private static long bitACaso(Random r, int quanti) {
        long bit = 0;
        while (Long.bitCount(bit) < quanti) {
            bit |= 1L << r.nextInt(64);
        }
        return bit;
    }

    private static Set<Set<String>> insiemi(List<Voce> voci, List<List<Integer>> gruppi) {
        return gruppi.stream()
                .map(g -> g.stream().map(i -> voci.get(i).id()).collect(Collectors.toSet()))
                .collect(Collectors.toSet());
    }

    /** Tutte le coppie, per confronto. */
    private static Set<Set<String>> forzaBruta(List<Voce> voci, int soglia) {
        int n = voci.size();
        int[] gruppo = new int[n];
        for (int i = 0; i < n; i++) {
            gruppo[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Impronta.distanza(voci.get(i).impronta(), voci.get(j).impronta()) <= soglia) {
                    int vecchio = gruppo[j];
                    int nuovo = gruppo[i];
                    for (int k = 0; k < n; k++) {
                        if (gruppo[k] == vecchio) {
                            gruppo[k] = nuovo;
                        }
                    }
                }
            }
        }
        var perGruppo = new java.util.HashMap<Integer, Set<String>>();
        for (int i = 0; i < n; i++) {
            perGruppo.computeIfAbsent(gruppo[i], k -> new HashSet<>()).add(voci.get(i).id());
        }
        return perGruppo.values().stream().filter(s -> s.size() > 1).collect(Collectors.toSet());
    }
}
