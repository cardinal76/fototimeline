package it.fototimeline.quasiuguali;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.stream.IntStream;

/**
 * Mette insieme le foto quasi uguali senza confrontare tutte le coppie (con
 * 150.000 foto sarebbero 11 miliardi di confronti).
 *
 * <p><b>Bande</b>: l'impronta si divide in {@code soglia + 1} bande di bit. Se
 * due impronte differiscono al più in {@code soglia} bit, per il principio dei
 * cassetti almeno una banda è identica: basta confrontare le foto che hanno
 * una banda uguale (stesso "cassetto"), e nessuna coppia vicina sfugge.
 *
 * <p><b>Raffiche</b>: due scatti della stessa raffica differiscono di più di
 * una ricompressione (il soggetto si muove). Per le foto con data dall'EXIF
 * scattate a pochi secondi l'una dall'altra vale una soglia più larga: si
 * confrontano solo le vicine nel tempo, ordinate per data. La data non serve
 * invece per le ricompressioni (WhatsApp cambia la data), che passano dalle bande.
 *
 * <p>I gruppi si formano con union-find: se A somiglia a B e B a C, A, B e C
 * stanno nello stesso gruppo.
 */
final class Raggruppamento {

    /** Una foto, solo quello che serve per raggrupparla. */
    record Voce(String id, long impronta, LocalDateTime scattataIl, boolean exif) {
    }

    private Raggruppamento() {
    }

    /**
     * @param soglia        distanza massima tra due foto qualunque
     * @param finestra      per le raffiche: distanza massima tra le date di scatto (null = niente raffiche)
     * @param sogliaRaffica distanza massima tra due foto della stessa raffica
     * @param separate      true per le coppie da non unire ("non sono doppioni")
     * @return i gruppi di almeno due foto, come indici in {@code voci}
     */
    static List<List<Integer>> gruppi(List<Voce> voci, int soglia, Duration finestra, int sogliaRaffica,
            BiPredicate<Voce, Voce> separate) {
        int n = voci.size();
        long[] impronte = voci.stream().mapToLong(Voce::impronta).toArray();
        UnioneTrova uf = new UnioneTrova(n);

        int bande = Math.clamp(soglia + 1, 1, 64);
        int inizio = 0;
        for (int b = 0; b < bande; b++) {
            int larghezza = 64 / bande + (b < 64 % bande ? 1 : 0);
            for (int[] cassetto : cassetti(impronte, inizio, larghezza)) {
                for (int x = 0; x < cassetto.length; x++) {
                    int i = cassetto[x];
                    for (int y = x + 1; y < cassetto.length; y++) {
                        int j = cassetto[y];
                        if (Impronta.distanza(impronte[i], impronte[j]) <= soglia && uf.trova(i) != uf.trova(j)
                                && !separate.test(voci.get(i), voci.get(j))) {
                            uf.unisci(i, j);
                        }
                    }
                }
            }
            inizio += larghezza;
        }

        if (finestra != null && sogliaRaffica > soglia) {
            int[] nelTempo = IntStream.range(0, n)
                    .filter(i -> voci.get(i).exif() && voci.get(i).scattataIl() != null)
                    .boxed()
                    .sorted(Comparator.comparing(i -> voci.get(i).scattataIl()))
                    .mapToInt(Integer::intValue)
                    .toArray();
            for (int x = 0; x < nelTempo.length; x++) {
                LocalDateTime limite = voci.get(nelTempo[x]).scattataIl().plus(finestra);
                for (int y = x + 1; y < nelTempo.length && !voci.get(nelTempo[y]).scattataIl().isAfter(limite); y++) {
                    int i = nelTempo[x];
                    int j = nelTempo[y];
                    if (uf.trova(i) != uf.trova(j) && Impronta.distanza(impronte[i], impronte[j]) <= sogliaRaffica
                            && !separate.test(voci.get(i), voci.get(j))) {
                        uf.unisci(i, j);
                    }
                }
            }
        }

        Map<Integer, List<Integer>> perRadice = new HashMap<>();
        for (int i = 0; i < n; i++) {
            perRadice.computeIfAbsent(uf.trova(i), k -> new ArrayList<>()).add(i);
        }
        return perRadice.values().stream().filter(g -> g.size() > 1).toList();
    }

    /**
     * Le foto (indici) con gli stessi bit da {@code inizio} per {@code larghezza}:
     * solo i cassetti con almeno due foto. Con bande strette (il caso normale)
     * un ordinamento per conteggio, senza oggetti per ogni foto.
     */
    static List<int[]> cassetti(long[] impronte, int inizio, int larghezza) {
        int n = impronte.length;
        List<int[]> risultato = new ArrayList<>();
        if (larghezza > 20) {
            long maschera = larghezza == 64 ? -1L : (1L << larghezza) - 1;
            Map<Long, List<Integer>> perChiave = new HashMap<>();
            for (int i = 0; i < n; i++) {
                perChiave.computeIfAbsent((impronte[i] >>> inizio) & maschera, k -> new ArrayList<>()).add(i);
            }
            perChiave.values().stream().filter(c -> c.size() > 1)
                    .forEach(c -> risultato.add(c.stream().mapToInt(Integer::intValue).toArray()));
            return risultato;
        }
        int chiavi = 1 << larghezza;
        int maschera = chiavi - 1;
        int[] partenza = new int[chiavi + 1];
        for (long impronta : impronte) {
            partenza[((int) (impronta >>> inizio) & maschera) + 1]++;
        }
        for (int k = 0; k < chiavi; k++) {
            partenza[k + 1] += partenza[k];
        }
        int[] ordinati = new int[n];
        int[] posto = Arrays.copyOf(partenza, chiavi);
        for (int i = 0; i < n; i++) {
            ordinati[posto[(int) (impronte[i] >>> inizio) & maschera]++] = i;
        }
        for (int k = 0; k < chiavi; k++) {
            if (partenza[k + 1] - partenza[k] > 1) {
                risultato.add(Arrays.copyOfRange(ordinati, partenza[k], partenza[k + 1]));
            }
        }
        return risultato;
    }

    /** Union-find con compressione dei cammini. */
    private static final class UnioneTrova {
        private final int[] padre;

        UnioneTrova(int n) {
            padre = IntStream.range(0, n).toArray();
        }

        int trova(int i) {
            while (padre[i] != i) {
                padre[i] = padre[padre[i]];
                i = padre[i];
            }
            return i;
        }

        void unisci(int a, int b) {
            padre[trova(a)] = trova(b);
        }
    }
}
