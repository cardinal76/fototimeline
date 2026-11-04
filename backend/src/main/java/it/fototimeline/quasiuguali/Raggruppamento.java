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

import it.fototimeline.quasiuguali.Confronto.Esito;
import it.fototimeline.quasiuguali.Confronto.Finestre;
import it.fototimeline.quasiuguali.Confronto.Sensibilita;
import it.fototimeline.quasiuguali.Confronto.Tempo;

/**
 * Mette insieme le foto quasi uguali senza confrontare tutte le coppie (con
 * 150.000 foto sarebbero 11 miliardi di confronti).
 *
 * <p><b>Bande</b>: il pHash si divide in {@code soglia + 1} bande di bit. Se
 * due impronte differiscono al più in {@code soglia} bit, per il principio dei
 * cassetti almeno una banda è identica: basta confrontare le foto che hanno
 * una banda uguale (stesso "cassetto"), e nessuna coppia vicina sfugge.
 *
 * <p><b>Nel tempo</b>: due scatti della stessa raffica, o due foto dello
 * stesso momento, possono differire di più (il soggetto si muove), e
 * {@link Confronto} per loro ha soglie più larghe: si cercano scorrendo le foto
 * in ordine di data, solo le vicine. Le ricompressioni passano dalle bande,
 * senza guardare la data (WhatsApp la cambia).
 *
 * <p><b>Gruppi a legame completo</b>: una foto entra in un gruppo solo se è
 * quasi uguale a <em>tutte</em> quelle che ci sono già. Prima era union-find:
 * se A somigliava a B e B a C, A e C finivano insieme anche se diverse, e una
 * catena di foto simili a due a due (una giornata al mare, una raffica lunga)
 * diventava un gruppo grande di foto diverse. Anche le coppie "non sono
 * doppioni" così restano separate davvero: prima si ritrovavano insieme
 * passando da una terza foto.
 */
final class Raggruppamento {

    /** Una foto, solo quello che serve per raggrupparla; larghezza e altezza 0 se non si sanno. */
    record Voce(String id, long impronta, Firma firma, LocalDateTime scattataIl, boolean exif, int larghezza,
            int altezza) {

        /** Senza firma né dimensioni: una foto di prima di "Calcola impronte". */
        Voce(String id, long impronta, LocalDateTime scattataIl, boolean exif) {
            this(id, impronta, null, scattataIl, exif, 0, 0);
        }
    }

    /** Una coppia di foto quasi uguali (indici in {@code voci}, {@code a < b}). */
    record Coppia(int a, int b, Esito esito) {
    }

    /**
     * Il coseno CLIP di più coppie, nello stesso ordine; NaN se visione non ha
     * il vettore di una delle due. Una risposta di lunghezza diversa (visione
     * spenta, o non risponde) vale come nessun contenuto.
     */
    interface Contenuto {
        double[] somiglianze(List<Voce> voci, List<Coppia> coppie);
    }

    /** I gruppi (indici in {@code voci}, almeno due foto) e il coseno CLIP delle coppie che l'hanno. */
    record Risultato(List<List<Integer>> gruppi, Map<Long, Double> contenuto) {
    }

    /** Quante coppie chiedere a visione per volta. */
    static final int COPPIE_PER_RICHIESTA = 2000;

    private Raggruppamento() {
    }

    /**
     * @param separate  true per le coppie da non unire ("non sono doppioni", "non è uguale")
     * @param contenuto null per non usare CLIP
     */
    static Risultato gruppi(List<Voce> voci, Sensibilita sensibilita, Finestre finestre,
            BiPredicate<Voce, Voce> separate, Contenuto contenuto) {
        Map<Long, Coppia> coppie = coppie(voci, sensibilita, finestre, separate);
        Map<Long, Double> coseni = new HashMap<>();
        if (contenuto != null && !coppie.isEmpty()) {
            controllaContenuto(voci, coppie, sensibilita.soglie.contenuto(), contenuto, coseni);
        }
        return new Risultato(legameCompleto(voci.size(), coppie), coseni);
    }

    /** Tutte le coppie quasi uguali (senza CLIP), per {@link #chiave}: dalle bande e dalle date vicine. */
    static Map<Long, Coppia> coppie(List<Voce> voci, Sensibilita sensibilita, Finestre finestre,
            BiPredicate<Voce, Voce> separate) {
        int n = voci.size();
        long[] impronte = voci.stream().mapToLong(Voce::impronta).toArray();
        Map<Long, Coppia> coppie = new HashMap<>();

        int soglia = sensibilita.soglie.forma();
        int bande = Math.clamp(soglia + 1, 1, 64);
        int inizio = 0;
        for (int b = 0; b < bande; b++) {
            int larghezza = 64 / bande + (b < 64 % bande ? 1 : 0);
            for (int[] cassetto : cassetti(impronte, inizio, larghezza)) {
                for (int x = 0; x < cassetto.length; x++) {
                    for (int y = x + 1; y < cassetto.length; y++) {
                        int i = cassetto[x];
                        int j = cassetto[y];
                        if (Impronta.distanza(impronte[i], impronte[j]) <= soglia) {
                            prova(voci, i, j, sensibilita, finestre, separate, coppie);
                        }
                    }
                }
            }
            inizio += larghezza;
        }

        Duration finestra = finestraPiuLarga(finestre);
        if (finestra != null) {
            int[] nelTempo = IntStream.range(0, n)
                    .filter(i -> voci.get(i).scattataIl() != null)
                    .boxed()
                    .sorted(Comparator.comparing(i -> voci.get(i).scattataIl()))
                    .mapToInt(Integer::intValue)
                    .toArray();
            int larga = Math.max(Confronto.formaMassima(sensibilita.soglie, Tempo.RAFFICA),
                    Confronto.formaMassima(sensibilita.soglie, Tempo.VICINE));
            for (int x = 0; x < nelTempo.length; x++) {
                LocalDateTime limite = voci.get(nelTempo[x]).scattataIl().plus(finestra);
                for (int y = x + 1; y < nelTempo.length && !voci.get(nelTempo[y]).scattataIl().isAfter(limite); y++) {
                    int i = nelTempo[x];
                    int j = nelTempo[y];
                    if (Impronta.distanza(impronte[i], impronte[j]) <= larga) {
                        prova(voci, i, j, sensibilita, finestre, separate, coppie);
                    }
                }
            }
        }
        return coppie;
    }

    private static Duration finestraPiuLarga(Finestre f) {
        if (f.raffica() == null) {
            return f.vicine();
        }
        return f.vicine() == null || f.raffica().compareTo(f.vicine()) > 0 ? f.raffica() : f.vicine();
    }

    private static void prova(List<Voce> voci, int x, int y, Sensibilita sensibilita, Finestre finestre,
            BiPredicate<Voce, Voce> separate, Map<Long, Coppia> coppie) {
        int i = Math.min(x, y);
        int j = Math.max(x, y);
        long chiave = chiave(i, j, voci.size());
        if (coppie.containsKey(chiave)) {
            return;
        }
        Esito esito = Confronto.valuta(voci.get(i), voci.get(j), sensibilita, finestre);
        if (esito.uguali() && !separate.test(voci.get(i), voci.get(j))) {
            coppie.put(chiave, new Coppia(i, j, esito));
        }
    }

    /** CLIP come veto: le coppie sotto la soglia escono. Senza risposta da visione restano tutte. */
    private static void controllaContenuto(List<Voce> voci, Map<Long, Coppia> coppie, double soglia,
            Contenuto contenuto, Map<Long, Double> coseni) {
        int n = voci.size();
        List<Coppia> tutte = new ArrayList<>(coppie.values());
        for (int da = 0; da < tutte.size(); da += COPPIE_PER_RICHIESTA) {
            List<Coppia> blocco = tutte.subList(da, Math.min(da + COPPIE_PER_RICHIESTA, tutte.size()));
            double[] punteggi = contenuto.somiglianze(voci, blocco);
            if (punteggi == null || punteggi.length != blocco.size()) {
                return;
            }
            for (int k = 0; k < blocco.size(); k++) {
                if (Double.isNaN(punteggi[k])) {
                    continue;
                }
                Coppia c = blocco.get(k);
                long chiave = chiave(c.a(), c.b(), n);
                if (punteggi[k] < soglia) {
                    coppie.remove(chiave);
                } else {
                    coseni.put(chiave, punteggi[k]);
                }
            }
        }
    }

    /**
     * Dalle coppie più simili: due foto libere fanno un gruppo; una libera
     * entra in un gruppo se è quasi uguale a tutte quelle del gruppo; due
     * gruppi si uniscono se ogni foto dell'uno lo è a ogni foto dell'altro.
     */
    static List<List<Integer>> legameCompleto(int n, Map<Long, Coppia> coppie) {
        List<Coppia> ordinate = new ArrayList<>(coppie.values());
        ordinate.sort(Comparator.comparingDouble((Coppia c) -> c.esito().costo())
                .thenComparingInt(Coppia::a).thenComparingInt(Coppia::b));
        int[] gruppoDi = new int[n];
        Arrays.fill(gruppoDi, -1);
        List<List<Integer>> gruppi = new ArrayList<>();
        for (Coppia c : ordinate) {
            int ga = gruppoDi[c.a()];
            int gb = gruppoDi[c.b()];
            if (ga >= 0 && ga == gb) {
                continue;
            }
            List<Integer> primi = ga >= 0 ? gruppi.get(ga) : List.of(c.a());
            List<Integer> secondi = gb >= 0 ? gruppi.get(gb) : List.of(c.b());
            if (!tutteVicine(primi, secondi, coppie, n)) {
                continue;
            }
            if (ga < 0 && gb < 0) {
                gruppi.add(new ArrayList<>(List.of(c.a(), c.b())));
                gruppoDi[c.a()] = gruppi.size() - 1;
                gruppoDi[c.b()] = gruppi.size() - 1;
            } else if (ga < 0) {
                gruppi.get(gb).add(c.a());
                gruppoDi[c.a()] = gb;
            } else if (gb < 0) {
                gruppi.get(ga).add(c.b());
                gruppoDi[c.b()] = ga;
            } else {
                // Il gruppo di b confluisce in quello di a e resta vuoto.
                for (int i : gruppi.get(gb)) {
                    gruppoDi[i] = ga;
                }
                gruppi.get(ga).addAll(gruppi.get(gb));
                gruppi.set(gb, new ArrayList<>());
            }
        }
        return gruppi.stream().filter(g -> g.size() > 1).map(List::copyOf).toList();
    }

    private static boolean tutteVicine(List<Integer> primi, List<Integer> secondi, Map<Long, Coppia> coppie, int n) {
        for (int i : primi) {
            for (int j : secondi) {
                if (!coppie.containsKey(chiave(i, j, n))) {
                    return false;
                }
            }
        }
        return true;
    }

    static long chiave(int i, int j, int n) {
        return (long) Math.min(i, j) * n + Math.max(i, j);
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
}
