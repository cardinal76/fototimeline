package it.fototimeline.luoghi;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * I luoghi di GeoNames in memoria, per trovare il più vicino a una posizione.
 * Con 150-250 mila punti e un heap limitato niente oggetti per punto: solo
 * array di primitivi, e i nomi tutti in un unico {@code byte[]} UTF-8.
 *
 * <p>La ricerca è un k-d tree implicito sui punti come vettori unitari in 3D
 * (x, y, z sulla sfera): la distanza in linea retta cresce con quella sulla
 * superficie, e non ci sono problemi al meridiano 180. L'albero non ha nodi:
 * gli array sono ordinati in modo che il punto a metà di ogni intervallo sia
 * il nodo che lo divide, sull'asse {@code profondità % 3}.
 */
public final class IndiceLuoghi {

    /** Raggio medio della Terra, in km. */
    static final double RAGGIO_KM = 6371.0088;

    private final int n;
    private final float[] x;
    private final float[] y;
    private final float[] z;
    /** Il nome del punto i sta in nomi[inizioNome[i] .. inizioNome[i + 1]). */
    private final int[] inizioNome;
    private final byte[] nomi;
    /** Indice in {@link #regioni}, -1 se non si sa. */
    private final int[] regione;
    /** Indice in {@link #province}, -1 se non c'è (fuori dall'Italia). */
    private final short[] provincia;
    /** Indice in {@link #nazioni}. */
    private final short[] nazione;
    /** Il geonameid: la chiave stabile di un luogo scelto a mano ("citta:2643123"). */
    private final int[] geonameid;
    /** Abitanti: nella ricerca per nome il più grande viene prima (Manchester inglese prima di quella americana). */
    private final int[] popolazione;
    private final String[] regioni;
    private final String[] province;
    private final Nazione[] nazioni;

    /** Codice ISO (IT) e nome da mostrare (Italia). */
    public record Nazione(String codice, String nome) {
    }

    /**
     * Il luogo trovato, con la distanza dal punto chiesto. {@code provincia}
     * c'è solo in Italia ("Provincia di Trento").
     */
    public record Trovato(String nome, String provincia, String regione, String nazione, String codiceNazione,
            double distanzaKm) {
    }

    /** Un punto dell'indice, per la ricerca per nome e per i luoghi scelti a mano. */
    public record Punto(int geonameid, String nome, String provincia, String regione, String nazione,
            String codiceNazione, double latitudine, double longitudine, int popolazione) {
    }

    private IndiceLuoghi(Costruttore c) {
        this.n = c.n;
        this.regioni = c.regioni.toArray(String[]::new);
        this.province = c.province.toArray(String[]::new);
        this.nazioni = c.nazioni.toArray(Nazione[]::new);
        float[] lat = c.lat;
        float[] lon = c.lon;
        float[] cx = new float[n];
        float[] cy = new float[n];
        float[] cz = new float[n];
        for (int i = 0; i < n; i++) {
            double fi = Math.toRadians(lat[i]);
            double lambda = Math.toRadians(lon[i]);
            cx[i] = (float) (Math.cos(fi) * Math.cos(lambda));
            cy[i] = (float) (Math.cos(fi) * Math.sin(lambda));
            cz[i] = (float) Math.sin(fi);
        }
        int[] ordine = new int[n];
        for (int i = 0; i < n; i++) {
            ordine[i] = i;
        }
        dividi(ordine, new float[][] {cx, cy, cz}, 0, n, 0);

        // Tutto riordinato secondo l'albero.
        x = new float[n];
        y = new float[n];
        z = new float[n];
        regione = new int[n];
        provincia = new short[n];
        nazione = new short[n];
        geonameid = new int[n];
        popolazione = new int[n];
        inizioNome = new int[n + 1];
        nomi = new byte[c.lunghezzaNomi];
        int scritti = 0;
        for (int i = 0; i < n; i++) {
            int o = ordine[i];
            x[i] = cx[o];
            y[i] = cy[o];
            z[i] = cz[o];
            regione[i] = c.regione[o];
            provincia[i] = c.provincia[o];
            nazione[i] = c.nazione[o];
            geonameid[i] = c.geonameid[o];
            popolazione[i] = c.popolazione[o];
            int da = c.inizioNome[o];
            int lunghezza = c.lunghezzaNome[o];
            inizioNome[i] = scritti;
            System.arraycopy(c.nomi, da, nomi, scritti, lunghezza);
            scritti += lunghezza;
        }
        inizioNome[n] = scritti;
    }

    public int dimensione() {
        return n;
    }

    /** Quanta memoria occupano gli array dell'indice, più o meno, in byte. */
    public long byteOccupati() {
        long stringhe = 0;
        for (String r : regioni) {
            stringhe += 48 + 2L * r.length();
        }
        for (String p : province) {
            stringhe += 48 + 2L * p.length();
        }
        // Coordinate, inizio del nome, nomi, regione, provincia, nazione, geonameid, popolazione.
        return 3L * 4 * n + 4L * (n + 1) + nomi.length + 4L * n + 2L * n + 2L * n + 4L * n + 4L * n + stringhe
                + 100L * nazioni.length;
    }

    /**
     * Il luogo più vicino entro {@code distanzaMassimaKm}, o null (mare
     * aperto, deserto, indice vuoto).
     */
    public Trovato piuVicino(double latitudine, double longitudine, double distanzaMassimaKm) {
        if (n == 0 || Double.isNaN(latitudine) || Double.isNaN(longitudine)) {
            return null;
        }
        double fi = Math.toRadians(latitudine);
        double lambda = Math.toRadians(longitudine);
        double[] q = {Math.cos(fi) * Math.cos(lambda), Math.cos(fi) * Math.sin(lambda), Math.sin(fi)};
        // Corda che corrisponde alla distanza massima sulla superficie.
        double corda = 2 * Math.sin(Math.min(distanzaMassimaKm / RAGGIO_KM, Math.PI) / 2);
        Ricerca r = new Ricerca(q, corda * corda);
        cerca(r, 0, n, 0);
        if (r.migliore < 0) {
            return null;
        }
        int i = r.migliore;
        double km = 2 * RAGGIO_KM * Math.asin(Math.min(1, Math.sqrt(r.distanza2) / 2));
        Nazione naz = nazioni[nazione[i]];
        return new Trovato(nome(i), provincia(i), regione[i] < 0 ? null : regioni[regione[i]], naz.nome(), naz.codice(),
                km);
    }

    private String nome(int i) {
        return new String(nomi, inizioNome[i], inizioNome[i + 1] - inizioNome[i], StandardCharsets.UTF_8);
    }

    private String provincia(int i) {
        return provincia[i] < 0 ? null : province[provincia[i]];
    }

    private Punto punto(int i) {
        Nazione naz = nazioni[nazione[i]];
        // Le coordinate tornano dal vettore unitario: in float sono giuste al metro.
        double lat = Math.toDegrees(Math.asin(Math.max(-1, Math.min(1, z[i]))));
        double lon = Math.toDegrees(Math.atan2(y[i], x[i]));
        return new Punto(geonameid[i], nome(i), provincia(i), regione[i] < 0 ? null : regioni[regione[i]], naz.nome(),
                naz.codice(), lat, lon, popolazione[i]);
    }

    /** Il punto con questo geonameid, se c'è (una scansione: si fa solo quando si sceglie un luogo a mano). */
    public Punto perGeonameid(int id) {
        for (int i = 0; i < n; i++) {
            if (geonameid[i] == id) {
                return punto(i);
            }
        }
        return null;
    }

    /**
     * I centri abitati il cui nome comincia con {@code cercato} (o ha una
     * parola che comincia così: "haag" trova "Den Haag"), senza maiuscole né
     * accenti: prima il nome uguale, poi chi comincia così, poi gli altri; a
     * parità il più popoloso. Una scansione di tutti i nomi, senza indici in
     * più: con 225 mila punti qualche decina di millisecondi.
     *
     * @param cercato già passato da {@link Nomi#piega}
     */
    public List<Punto> cerca(String cercato, int quanti) {
        if (cercato == null || cercato.isEmpty() || quanti <= 0) {
            return List.of();
        }
        List<long[]> trovati = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int livello = Nomi.corrisponde(Nomi.piega(nome(i)), cercato);
            if (livello >= 0) {
                trovati.add(new long[] {livello, -popolazione[i], i});
            }
        }
        trovati.sort(Comparator.<long[]>comparingLong(t -> t[0]).thenComparingLong(t -> t[1]));
        List<Punto> punti = new ArrayList<>();
        for (int k = 0; k < Math.min(quanti, trovati.size()); k++) {
            punti.add(punto((int) trovati.get(k)[2]));
        }
        return punti;
    }

    private static final class Ricerca {
        final double[] q;
        double distanza2;
        int migliore = -1;

        Ricerca(double[] q, double distanzaMassima2) {
            this.q = q;
            this.distanza2 = distanzaMassima2;
        }
    }

    private void cerca(Ricerca r, int da, int a, int profondita) {
        while (da < a) {
            int m = (da + a) >>> 1;
            double dx = x[m] - r.q[0];
            double dy = y[m] - r.q[1];
            double dz = z[m] - r.q[2];
            double d2 = dx * dx + dy * dy + dz * dz;
            if (d2 < r.distanza2) {
                r.distanza2 = d2;
                r.migliore = m;
            }
            double diff = switch (profondita % 3) {
                case 0 -> r.q[0] - x[m];
                case 1 -> r.q[1] - y[m];
                default -> r.q[2] - z[m];
            };
            // Prima il lato dove sta il punto; l'altro solo se il piano è più vicino del migliore.
            int vicinoDa = diff < 0 ? da : m + 1;
            int vicinoA = diff < 0 ? m : a;
            int lontanoDa = diff < 0 ? m + 1 : da;
            int lontanoA = diff < 0 ? a : m;
            cerca(r, vicinoDa, vicinoA, profondita + 1);
            if (diff * diff >= r.distanza2) {
                return;
            }
            da = lontanoDa;
            a = lontanoA;
            profondita++;
        }
    }

    /** Ordina {@code ordine[da..a)} come un k-d tree: mediana a metà, poi i due lati. */
    private static void dividi(int[] ordine, float[][] assi, int da, int a, int profondita) {
        while (a - da > 1) {
            int m = (da + a) >>> 1;
            seleziona(ordine, assi[profondita % 3], da, a - 1, m);
            dividi(ordine, assi, da, m, profondita + 1);
            da = m + 1;
            profondita++;
        }
    }

    /** Quickselect: mette in {@code k} l'elemento che ci starebbe a ordinare, i minori prima, i maggiori dopo. */
    private static void seleziona(int[] ordine, float[] chiave, int sx, int dx, int k) {
        while (dx > sx) {
            // Pivot: mediana di tre, contro i casi già ordinati.
            int m = (sx + dx) >>> 1;
            if (chiave[ordine[m]] < chiave[ordine[sx]]) scambia(ordine, m, sx);
            if (chiave[ordine[dx]] < chiave[ordine[sx]]) scambia(ordine, dx, sx);
            if (chiave[ordine[dx]] < chiave[ordine[m]]) scambia(ordine, dx, m);
            float pivot = chiave[ordine[m]];
            int i = sx;
            int j = dx;
            while (i <= j) {
                while (chiave[ordine[i]] < pivot) i++;
                while (chiave[ordine[j]] > pivot) j--;
                if (i <= j) {
                    scambia(ordine, i, j);
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                dx = j;
            } else if (k >= i) {
                sx = i;
            } else {
                return;
            }
        }
    }

    private static void scambia(int[] v, int i, int j) {
        int t = v[i];
        v[i] = v[j];
        v[j] = t;
    }

    /** Raccoglie i punti (array che crescono, niente oggetti per punto) e costruisce l'indice. */
    public static final class Costruttore {
        private int n;
        private float[] lat = new float[1024];
        private float[] lon = new float[1024];
        private int[] inizioNome = new int[1024];
        private short[] lunghezzaNome = new short[1024];
        private int[] regione = new int[1024];
        private short[] provincia = new short[1024];
        private short[] nazione = new short[1024];
        private int[] geonameid = new int[1024];
        private int[] popolazione = new int[1024];
        private byte[] nomi = new byte[16 * 1024];
        private int lunghezzaNomi;
        private final List<String> regioni = new java.util.ArrayList<>();
        private final Map<String, Integer> indiceRegioni = new HashMap<>();
        private final List<String> province = new java.util.ArrayList<>();
        private final Map<String, Short> indiceProvince = new HashMap<>();
        private final List<Nazione> nazioni = new java.util.ArrayList<>();
        private final Map<String, Short> indiceNazioni = new HashMap<>();

        /**
         * @param regione       nome della regione, null se non si sa
         * @param codiceNazione ISO a due lettere
         * @param nomeNazione   come mostrarla (la prima volta che compare il codice)
         */
        public Costruttore aggiungi(double latitudine, double longitudine, String nome, String regione,
                String codiceNazione, String nomeNazione) {
            return aggiungi(0, latitudine, longitudine, nome, null, regione, codiceNazione, nomeNazione, 0);
        }

        /**
         * @param geonameid   l'id di GeoNames (0 se non c'è)
         * @param provincia   nome della provincia, null fuori dall'Italia
         * @param popolazione abitanti, per l'ordine della ricerca per nome
         */
        public Costruttore aggiungi(int geonameid, double latitudine, double longitudine, String nome,
                String provincia, String regione, String codiceNazione, String nomeNazione, int popolazione) {
            if (n == lat.length) {
                int nuova = n + (n >> 1);
                lat = Arrays.copyOf(lat, nuova);
                lon = Arrays.copyOf(lon, nuova);
                inizioNome = Arrays.copyOf(inizioNome, nuova);
                lunghezzaNome = Arrays.copyOf(lunghezzaNome, nuova);
                this.regione = Arrays.copyOf(this.regione, nuova);
                this.provincia = Arrays.copyOf(this.provincia, nuova);
                nazione = Arrays.copyOf(nazione, nuova);
                this.geonameid = Arrays.copyOf(this.geonameid, nuova);
                this.popolazione = Arrays.copyOf(this.popolazione, nuova);
            }
            byte[] b = nome.getBytes(StandardCharsets.UTF_8);
            int lunghezza = Math.min(b.length, Short.MAX_VALUE);
            if (lunghezzaNomi + lunghezza > nomi.length) {
                nomi = Arrays.copyOf(nomi, Math.max(nomi.length + (nomi.length >> 1), lunghezzaNomi + lunghezza));
            }
            System.arraycopy(b, 0, nomi, lunghezzaNomi, lunghezza);
            lat[n] = (float) latitudine;
            lon[n] = (float) longitudine;
            inizioNome[n] = lunghezzaNomi;
            lunghezzaNome[n] = (short) lunghezza;
            lunghezzaNomi += lunghezza;
            this.regione[n] = regione == null ? -1 : indiceRegioni.computeIfAbsent(regione, r -> {
                regioni.add(r);
                return regioni.size() - 1;
            });
            this.provincia[n] = provincia == null ? -1 : indiceProvince.computeIfAbsent(provincia, p -> {
                province.add(p);
                return (short) (province.size() - 1);
            });
            this.geonameid[n] = geonameid;
            this.popolazione[n] = popolazione;
            nazione[n] = indiceNazioni.computeIfAbsent(codiceNazione, c -> {
                nazioni.add(new Nazione(c, nomeNazione != null ? nomeNazione : c));
                return (short) (nazioni.size() - 1);
            });
            n++;
            return this;
        }

        public IndiceLuoghi costruisci() {
            return new IndiceLuoghi(this);
        }
    }
}
