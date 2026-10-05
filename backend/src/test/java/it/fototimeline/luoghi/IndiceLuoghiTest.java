package it.fototimeline.luoghi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import it.fototimeline.luoghi.IndiceLuoghi.Trovato;

/** La ricerca del luogo più vicino, senza Spring: il mini dataset di prova e punti sintetici. */
class IndiceLuoghiTest {

    private static GeocodificaInversa miniDataset() {
        return new GeocodificaInversa(new LuoghiProperties("classpath:geonames", 30), new DefaultResourceLoader());
    }

    @Test
    void puntiNotiConINomiInItaliano() {
        GeocodificaInversa geo = miniDataset();
        assertThat(geo.disponibile()).isTrue();
        // Trastevere è un quartiere (PPLX): non conta, resta Roma.
        assertThat(geo.punti()).isEqualTo(8);

        Trovato roma = geo.trova(41.9, 12.5).orElseThrow();
        assertThat(roma.nome()).isEqualTo("Roma");
        assertThat(roma.regione()).isEqualTo("Lazio");
        assertThat(roma.nazione()).isEqualTo("Italia");
        assertThat(roma.codiceNazione()).isEqualTo("IT");
        assertThat(geo.trova(41.8833, 12.4667)).get().extracting(Trovato::nome).isEqualTo("Roma");

        // Sulla spiaggia di Sperlonga, Fondi è a 11 km.
        Trovato sperlonga = geo.trova(41.2545, 13.4300).orElseThrow();
        assertThat(sperlonga.nome()).isEqualTo("Sperlonga");
        assertThat(sperlonga.distanzaKm()).isLessThan(1);
        assertThat(geo.trova(41.2140, 13.5700)).get().extracting(Trovato::nome).isEqualTo("Gaeta");

        Trovato parigi = geo.trova(48.8584, 2.2945).orElseThrow(); // Torre Eiffel
        assertThat(parigi.nome()).isEqualTo("Parigi");
        assertThat(parigi.regione()).isEqualTo("Île-de-France");
        assertThat(parigi.nazione()).isEqualTo("Francia");

        Trovato monaco = geo.trova(48.14, 11.58).orElseThrow();
        assertThat(monaco.nome()).isEqualTo("Monaco di Baviera");
        assertThat(monaco.regione()).isEqualTo("Baviera");
        assertThat(monaco.nazione()).isEqualTo("Germania");
    }

    @Test
    void inMareApertoNessunLuogo() {
        GeocodificaInversa geo = miniDataset();
        // Tirreno, a metà tra Lazio e Sardegna; e a 50 km dalla costa di Roma.
        assertThat(geo.trova(40.5, 11.0)).isEmpty();
        assertThat(geo.trova(41.75, 11.9)).isEmpty();
        assertThat(geo.trova(null, 12.5)).isEmpty();
        // Con la distanza massima più larga lo stesso punto trova Roma.
        var largo = new GeocodificaInversa(new LuoghiProperties("classpath:geonames", 100), new DefaultResourceLoader());
        assertThat(largo.trova(41.75, 11.9)).get().extracting(Trovato::nome).isEqualTo("Roma");
    }

    @Test
    void senzaDatasetSpento() {
        var geo = new GeocodificaInversa(new LuoghiProperties("", 0), new DefaultResourceLoader());
        assertThat(geo.disponibile()).isFalse();
        assertThat(geo.trova(41.9, 12.5)).isEmpty();
        var mancante = new GeocodificaInversa(new LuoghiProperties("classpath:non-c-e", 0), new DefaultResourceLoader());
        assertThat(mancante.disponibile()).isFalse();
    }

    @Test
    void comeLaRicercaATappetoAncheAl180() {
        Random caso = new Random(7);
        int n = 20_000;
        double[] lat = new double[n];
        double[] lon = new double[n];
        var c = new IndiceLuoghi.Costruttore();
        for (int i = 0; i < n; i++) {
            lat[i] = Math.toDegrees(Math.asin(2 * caso.nextDouble() - 1));
            lon[i] = caso.nextDouble() * 360 - 180;
            c.aggiungi(lat[i], lon[i], "p" + i, null, "XX", "Prova");
        }
        IndiceLuoghi indice = c.costruisci();
        for (int k = 0; k < 2_000; k++) {
            double qLat = Math.toDegrees(Math.asin(2 * caso.nextDouble() - 1));
            // Metà delle prove a cavallo del meridiano 180.
            double qLon = k % 2 == 0 ? caso.nextDouble() * 360 - 180 : (caso.nextBoolean() ? 179.9 : -179.9);
            int migliore = -1;
            double minima = Double.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                double d = haversine(qLat, qLon, lat[i], lon[i]);
                if (d < minima) {
                    minima = d;
                    migliore = i;
                }
            }
            Trovato t = indice.piuVicino(qLat, qLon, 20_000);
            // Con i float due punti quasi alla pari possono scambiarsi: si confronta la distanza.
            assertThat(t.distanzaKm()).as("punto %d", k).isCloseTo(minima, org.assertj.core.data.Offset.offset(0.05));
            if (t.nome().equals("p" + migliore)) {
                continue;
            }
            assertThat(haversine(qLat, qLon, lat[Integer.parseInt(t.nome().substring(1))],
                    lon[Integer.parseInt(t.nome().substring(1))])).isCloseTo(minima, org.assertj.core.data.Offset.offset(0.05));
        }
    }

    /** Come cities1000/cities500: ~150-250 mila punti. Deve essere veloce e stare in pochi MB. */
    @Test
    void centocinquantamilaPuntiVelociECompatti() {
        Random caso = new Random(42);
        int n = 150_000;
        long prima = usata();
        long inizio = System.nanoTime();
        var c = new IndiceLuoghi.Costruttore();
        for (int i = 0; i < n; i++) {
            // Più fitti in Europa, come i dati veri.
            double lat = i % 3 == 0 ? 36 + caso.nextDouble() * 24 : Math.toDegrees(Math.asin(2 * caso.nextDouble() - 1));
            double lon = i % 3 == 0 ? -10 + caso.nextDouble() * 40 : caso.nextDouble() * 360 - 180;
            c.aggiungi(lat, lon, "Località " + i, "Regione " + (i % 3000), "C" + (i % 250), null);
        }
        IndiceLuoghi indice = c.costruisci();
        long costruzioneMs = (System.nanoTime() - inizio) / 1_000_000;

        inizio = System.nanoTime();
        int trovati = 0;
        for (int k = 0; k < 100_000; k++) {
            if (indice.piuVicino(36 + caso.nextDouble() * 24, -10 + caso.nextDouble() * 40, 30) != null) {
                trovati++;
            }
        }
        long ricercaMs = (System.nanoTime() - inizio) / 1_000_000;
        long dopo = usata();

        System.out.printf("Indice luoghi: %d punti costruiti in %d ms, 100000 ricerche in %d ms, %d KB stimati, %d KB di heap%n",
                indice.dimensione(), costruzioneMs, ricercaMs, indice.byteOccupati() / 1024, (dopo - prima) / 1024);
        assertThat(indice.dimensione()).isEqualTo(n);
        assertThat(trovati).isGreaterThan(90_000);
        assertThat(costruzioneMs).isLessThan(5_000);
        assertThat(ricercaMs).isLessThan(5_000);
        // 12 byte di coordinate, 4+4+2 di indici e ~15 di nome per punto: sotto i 10 MB.
        assertThat(indice.byteOccupati()).isLessThan(10L * 1024 * 1024);
        assertThat(dopo - prima).isLessThan(32L * 1024 * 1024);
    }

    private static long usata() {
        Runtime r = Runtime.getRuntime();
        for (int i = 0; i < 3; i++) {
            System.gc();
        }
        return r.totalMemory() - r.freeMemory();
    }

    private static double haversine(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * IndiceLuoghi.RAGGIO_KM * Math.asin(Math.sqrt(a));
    }
}
