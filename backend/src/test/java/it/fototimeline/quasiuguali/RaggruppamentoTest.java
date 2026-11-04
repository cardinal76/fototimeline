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

import it.fototimeline.quasiuguali.Confronto.Finestre;
import it.fototimeline.quasiuguali.Confronto.Sensibilita;
import it.fototimeline.quasiuguali.Raggruppamento.Voce;

/** Le coppie e i gruppi, con firme finte: qui conta solo il pHash, gli altri segnali sono uguali. */
class RaggruppamentoTest {

    private static final LocalDateTime ORA = LocalDateTime.of(2024, 8, 15, 10, 0);
    private static final Finestre FINESTRE = new Finestre(Duration.ofSeconds(10), Duration.ofHours(6));
    private static final Finestre SENZA_DATE = new Finestre(null, null);

    @Test
    void conLeBandeNonSfuggeNessunaCoppia() {
        Random r = new Random(42);
        List<Voce> voci = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            voci.add(voce("c" + i, r.nextLong()));
        }
        // Coppie piantate a ogni distanza intorno alle soglie, coi bit cambiati sparsi a caso.
        for (int d = 0; d <= 10; d++) {
            for (int k = 0; k < 20; k++) {
                long base = r.nextLong();
                voci.add(voce("p" + d + "-" + k + "a", base));
                voci.add(voce("p" + d + "-" + k + "b", base ^ bitACaso(r, d)));
            }
        }
        for (Sensibilita s : Sensibilita.values()) {
            Set<Set<String>> trovate = Raggruppamento.coppie(voci, s, SENZA_DATE, (a, b) -> false).values().stream()
                    .map(c -> Set.of(voci.get(c.a()).id(), voci.get(c.b()).id()))
                    .collect(Collectors.toSet());
            assertThat(trovate).as("%s", s).isEqualTo(forzaBruta(voci, s.soglie.forma()));
        }
    }

    @Test
    void unaCatenaDiFotoSimiliADueADueNonDiventaUnGruppo() {
        // a~b e b~c (4 bit), ma a e c a 8 bit: con union-find erano un gruppo solo.
        long a = 0;
        long b = 0xFL;
        long c = 0xFFL;
        List<Voce> voci = List.of(voce("a", a), voce("b", b), voce("c", c));
        var gruppi = insiemi(voci, Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false,
                null).gruppi());
        assertThat(gruppi).hasSize(1);
        assertThat(gruppi).noneMatch(g -> g.containsAll(Set.of("a", "c")));

        // Una catena lunga: ogni foto a 4 bit dalla successiva, ogni gruppo ha tutte le foto entro 6 bit.
        List<Voce> catena = new ArrayList<>();
        long passo = 0;
        for (int i = 0; i < 12; i++) {
            catena.add(voce("k" + i, passo));
            passo ^= 0b11L << (2 * i);
        }
        for (List<Integer> g : Raggruppamento.gruppi(catena, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, null)
                .gruppi()) {
            for (int i : g) {
                for (int j : g) {
                    assertThat(Impronta.distanza(catena.get(i).impronta(), catena.get(j).impronta()))
                            .isLessThanOrEqualTo(Sensibilita.SEVERA.soglie.forma());
                }
            }
        }
    }

    @Test
    void leCoppieSeparateNonSiUnisconoNemmenoPassandoDaUnaTerza() {
        List<Voce> voci = List.of(voce("a", 5L), voce("b", 5L), voce("c", 7L));
        var gruppi = insiemi(voci, Raggruppamento.gruppi(voci, Sensibilita.LARGA, SENZA_DATE,
                (x, y) -> Set.of(x.id(), y.id()).equals(Set.of("a", "b")), null).gruppi());
        // Prima a e b si ritrovavano insieme passando per c, che somiglia a tutte e due.
        assertThat(gruppi).singleElement().satisfies(g -> assertThat(g).hasSize(2).contains("c"));
        List<Voce> due = List.of(voce("a", 5L), voce("b", 5L));
        assertThat(Raggruppamento.gruppi(due, Sensibilita.LARGA, SENZA_DATE, (x, y) -> true, null).gruppi())
                .isEmpty();
    }

    @Test
    void unaRafficaHaUnaSogliaPiuLargaSoloConLaDataExifVicina() {
        long base = 0x0F0F_0F0F_F0F0_F0F0L;
        long dieci = 0x3FFL;
        List<Voce> voci = List.of(
                voce("a", base, ORA, true),
                voce("b", base ^ dieci, ORA.plusSeconds(3), true),
                voce("lontana", base ^ dieci << 20, ORA.plusMinutes(5), true),
                voce("senza-exif", base ^ dieci << 40, ORA.plusSeconds(1), false));
        assertThat(insiemi(voci, Raggruppamento.gruppi(voci, Sensibilita.SEVERA, FINESTRE, (x, y) -> false, null)
                .gruppi())).containsExactly(Set.of("a", "b"));
        assertThat(Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, null).gruppi())
                .isEmpty();
    }

    @Test
    void loStessoMomentoAllargaUnPocoLeDateExifLontaneStringono() {
        long base = 0x0123_4567_89AB_CDEFL;
        long otto = base ^ 0xFFL;
        long sei = base ^ 0x3FL;
        // 8 bit: troppi per la severa, non per due foto della stessa mattina.
        List<Voce> vicine = List.of(voce("a", base, ORA, false), voce("b", otto, ORA.plusHours(2), false));
        assertThat(Raggruppamento.gruppi(vicine, Sensibilita.SEVERA, FINESTRE, (x, y) -> false, null).gruppi())
                .hasSize(1);
        assertThat(Raggruppamento.gruppi(vicine, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, null).gruppi())
                .isEmpty();
        // 6 bit passano senza date, non tra due foto con data EXIF a un anno di distanza.
        List<Voce> lontane = List.of(voce("a", base, ORA, true), voce("b", sei, ORA.plusYears(1), true));
        assertThat(Raggruppamento.gruppi(lontane, Sensibilita.SEVERA, FINESTRE, (x, y) -> false, null).gruppi())
                .isEmpty();
        List<Voce> senzaExif = List.of(voce("a", base, ORA, false), voce("b", sei, ORA.plusYears(1), false));
        assertThat(Raggruppamento.gruppi(senzaExif, Sensibilita.SEVERA, FINESTRE, (x, y) -> false, null).gruppi())
                .hasSize(1);
    }

    @Test
    void ilContenutoScartaLeCoppieSottoLaSogliaESenzaRispostaNonConta() {
        long lontana = 0xFFFF_FFFF_0000_0000L;
        List<Voce> voci = List.of(voce("a", 5L), voce("b", 5L), voce("c", lontana), voce("d", lontana));
        // a-b diverse per CLIP, c-d uguali.
        Raggruppamento.Contenuto clip = (v, coppie) -> coppie.stream()
                .mapToDouble(c -> v.get(c.a()).id().equals("a") ? 0.5 : 0.97).toArray();
        var risultato = Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, clip);
        assertThat(insiemi(voci, risultato.gruppi())).containsExactly(Set.of("c", "d"));
        assertThat(risultato.contenuto()).containsValue(0.97);

        Raggruppamento.Contenuto senzaVettori = (v, coppie) -> coppie.stream().mapToDouble(c -> Double.NaN).toArray();
        assertThat(Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, senzaVettori)
                .gruppi()).hasSize(2);
        Raggruppamento.Contenuto spenta = (v, coppie) -> null;
        assertThat(Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, spenta).gruppi())
                .hasSize(2);
    }

    @Test
    void senzaFirmaServeIlPhashQuasiIdentico() {
        List<Voce> voci = List.of(new Voce("a", 0L, null, false), new Voce("b", 0xFL, null, false),
                new Voce("c", 0xFFFF_0000_0000_0000L, null, false), new Voce("d", 0xFFFF_0000_0000_0001L, null, false));
        assertThat(insiemi(voci, Raggruppamento.gruppi(voci, Sensibilita.SEVERA, SENZA_DATE, (x, y) -> false, null)
                .gruppi())).containsExactly(Set.of("c", "d"));
    }

    @Test
    void cassettiConBandeLargheEStrette() {
        long[] impronte = {0b1010L, 0b1010L, 0b0110L, -1L, -1L};
        assertThat(Raggruppamento.cassetti(impronte, 0, 4)).hasSize(2);
        assertThat(Raggruppamento.cassetti(impronte, 0, 64)).hasSize(2);
        assertThat(Raggruppamento.cassetti(impronte, 0, 2)).hasSize(2);
    }

    /** Una firma qualunque, la stessa per tutte: foto ricca di dettagli, a colori. */
    static final Firma FIRMA = firma();

    private static Firma firma() {
        byte[] colori = new byte[48];
        for (int i = 0; i < colori.length; i++) {
            colori[i] = (byte) (60 + i * 3);
        }
        return new Firma(Impronta.VERSIONE, 0x5555_AAAA_5555_AAAAL, colori, 120, 40, 0);
    }

    private static Voce voce(String id, long impronta) {
        return voce(id, impronta, null, false);
    }

    private static Voce voce(String id, long impronta, LocalDateTime data, boolean exif) {
        return new Voce(id, impronta, FIRMA, data, exif, 4000, 3000);
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

    /** Tutte le coppie entro la soglia, per confronto. */
    private static Set<Set<String>> forzaBruta(List<Voce> voci, int soglia) {
        Set<Set<String>> coppie = new HashSet<>();
        for (int i = 0; i < voci.size(); i++) {
            for (int j = i + 1; j < voci.size(); j++) {
                if (Impronta.distanza(voci.get(i).impronta(), voci.get(j).impronta()) <= soglia) {
                    coppie.add(Set.of(voci.get(i).id(), voci.get(j).id()));
                }
            }
        }
        return coppie;
    }
}
