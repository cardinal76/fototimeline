package it.fototimeline.luoghi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import it.fototimeline.dominio.Foto;
import it.fototimeline.luoghi.IndiceLuoghi.Trovato;

/**
 * Dal GPS al nome del luogo, senza servizi esterni: i centri abitati di
 * GeoNames (CC BY 4.0) letti all'avvio da {@code fototimeline.luoghi.cartella}.
 * Nel container la cartella la riempie il Dockerfile; senza cartella l'app
 * funziona lo stesso, solo senza luoghi.
 *
 * <p>File letti (formato del dump di GeoNames):
 * <ul>
 * <li>{@code cities500.txt} (o 1000, 5000, 15000): i punti;</li>
 * <li>{@code admin1CodesASCII.txt}: i nomi delle regioni;</li>
 * <li>{@code countryInfo.txt}, facoltativo: i nomi delle nazioni che Java non
 *     sa dire in italiano;</li>
 * <li>{@code alternateNames-it.txt}, facoltativo: le righe in italiano di
 *     {@code alternateNamesV2.txt} (Roma invece di Rome, Parigi, Baviera).</li>
 * </ul>
 */
@Service
public class GeocodificaInversa {

    private static final Logger log = LoggerFactory.getLogger(GeocodificaInversa.class);

    static final String[] CITTA = {"cities500.txt", "cities1000.txt", "cities5000.txt", "cities15000.txt"};
    static final String REGIONI = "admin1CodesASCII.txt";
    static final String NAZIONI = "countryInfo.txt";
    static final String NOMI_ITALIANI = "alternateNames-it.txt";
    /** Quartieri (PPLX) e posti storici, abbandonati o distrutti: un quartiere di Roma non è un luogo a sé. */
    private static final Set<String> SALTATI = Set.of("PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH");

    private final LuoghiProperties proprieta;
    private final ResourceLoader risorse;
    private volatile IndiceLuoghi indice;

    public GeocodificaInversa(LuoghiProperties proprieta, ResourceLoader risorse) {
        this.proprieta = proprieta;
        this.risorse = risorse;
        carica();
    }

    /** True se il dataset c'è ed è stato letto. */
    public boolean disponibile() {
        return indice != null && indice.dimensione() > 0;
    }

    public int punti() {
        return indice == null ? 0 : indice.dimensione();
    }

    /** Il luogo più vicino, se c'è entro la distanza massima. */
    public Optional<Trovato> trova(Double latitudine, Double longitudine) {
        IndiceLuoghi i = indice;
        if (i == null || latitudine == null || longitudine == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(i.piuVicino(latitudine, longitudine, proprieta.distanzaMassimaKm()));
    }

    /**
     * Scrive nella foto luogo, regione e nazione delle sue coordinate (o li
     * toglie, se non ne ha o sono in mare aperto). Senza dataset non tocca
     * niente: "Calcola luoghi" ci ripasserà quando ci sarà.
     */
    public void applica(Foto foto) {
        if (!disponibile()) {
            return;
        }
        Trovato t = trova(foto.getLatitudine(), foto.getLongitudine()).orElse(null);
        foto.setLuogo(t == null ? null : t.nome());
        foto.setRegione(t == null ? null : t.regione());
        foto.setNazione(t == null ? null : t.nazione());
        foto.setCodiceNazione(t == null ? null : t.codiceNazione());
        foto.setLuogoCalcolato(true);
    }

    // ------------------------------------------------------------ lettura dei file

    private void carica() {
        if (!StringUtils.hasText(proprieta.cartella())) {
            log.info("Luoghi spenti: manca fototimeline.luoghi.cartella (FOTOTIMELINE_LUOGHI)");
            return;
        }
        long inizio = System.nanoTime();
        try {
            Resource citta = null;
            for (String nome : CITTA) {
                Resource r = risorsa(nome);
                if (r.exists()) {
                    citta = r;
                    break;
                }
            }
            if (citta == null) {
                log.warn("Luoghi spenti: in {} non c'è nessuno di {}", proprieta.cartella(), String.join(", ", CITTA));
                return;
            }
            Map<Integer, String> italiani = nomiItaliani();
            Map<String, String> regioni = regioni(italiani);
            Map<String, String> nazioni = nazioni();
            IndiceLuoghi.Costruttore c = new IndiceLuoghi.Costruttore();
            Map<String, String> nomiNazioni = new HashMap<>();
            String[] campi = new String[11];
            try (BufferedReader in = leggi(citta)) {
                String riga;
                while ((riga = in.readLine()) != null) {
                    if (dividi(riga, campi) < 11 || SALTATI.contains(campi[7]) || campi[8].isEmpty()) {
                        continue;
                    }
                    double lat;
                    double lon;
                    int id;
                    try {
                        id = Integer.parseInt(campi[0]);
                        lat = Double.parseDouble(campi[4]);
                        lon = Double.parseDouble(campi[5]);
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    String codice = campi[8];
                    String nome = italiani.getOrDefault(id, campi[1]);
                    String nazione = nomiNazioni.computeIfAbsent(codice, k -> nomeNazione(k, nazioni));
                    c.aggiungi(lat, lon, nome, regioni.get(codice + "." + campi[10]), codice, nazione);
                }
            }
            IndiceLuoghi nuovo = c.costruisci();
            indice = nuovo;
            log.info("Luoghi: {} punti da {} in {} ms, circa {} MB", nuovo.dimensione(), citta.getFilename(),
                    (System.nanoTime() - inizio) / 1_000_000, nuovo.byteOccupati() / (1024 * 1024));
        } catch (IOException | RuntimeException e) {
            log.warn("Luoghi spenti: non riesco a leggere il dataset in {}: {}", proprieta.cartella(), e.toString());
        }
    }

    /** I nomi in italiano per geonameid: prima quello preferito, poi quello breve, poi il primo. */
    private Map<Integer, String> nomiItaliani() throws IOException {
        Map<Integer, String> nomi = new HashMap<>();
        Resource r = risorsa(NOMI_ITALIANI);
        if (!r.exists()) {
            return nomi;
        }
        Map<Integer, Integer> priorita = new HashMap<>();
        String[] campi = new String[8];
        try (BufferedReader in = leggi(r)) {
            String riga;
            while ((riga = in.readLine()) != null) {
                int letti = dividi(riga, campi);
                if (letti < 4 || !"it".equals(campi[2]) || campi[3].isEmpty()
                        || (letti > 6 && "1".equals(campi[6])) || (letti > 7 && "1".equals(campi[7]))) {
                    continue; // colloquiale o storico
                }
                int id;
                try {
                    id = Integer.parseInt(campi[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                int p = letti > 4 && "1".equals(campi[4]) ? 3 : letti > 5 && "1".equals(campi[5]) ? 2 : 1;
                if (p > priorita.getOrDefault(id, 0)) {
                    priorita.put(id, p);
                    nomi.put(id, campi[3]);
                }
            }
        }
        return nomi;
    }

    /** "IT.07" → "Lazio". */
    private Map<String, String> regioni(Map<Integer, String> italiani) throws IOException {
        Map<String, String> regioni = new HashMap<>();
        Resource r = risorsa(REGIONI);
        if (!r.exists()) {
            log.warn("Luoghi senza regioni: manca {}", REGIONI);
            return regioni;
        }
        String[] campi = new String[4];
        try (BufferedReader in = leggi(r)) {
            String riga;
            while ((riga = in.readLine()) != null) {
                int letti = dividi(riga, campi);
                if (letti < 2) {
                    continue;
                }
                String nome = campi[1];
                if (letti == 4) {
                    try {
                        nome = italiani.getOrDefault(Integer.parseInt(campi[3].trim()), nome);
                    } catch (NumberFormatException e) {
                        // senza geonameid resta il nome inglese
                    }
                }
                regioni.put(campi[0], nome);
            }
        }
        return regioni;
    }

    /** "IT" → "Italy", da countryInfo.txt: solo la riserva per quello che Java non conosce. */
    private Map<String, String> nazioni() throws IOException {
        Map<String, String> nazioni = new HashMap<>();
        Resource r = risorsa(NAZIONI);
        if (!r.exists()) {
            return nazioni;
        }
        String[] campi = new String[5];
        try (BufferedReader in = leggi(r)) {
            String riga;
            while ((riga = in.readLine()) != null) {
                if (!riga.startsWith("#") && dividi(riga, campi) == 5) {
                    nazioni.put(campi[0], campi[4]);
                }
            }
        }
        return nazioni;
    }

    /** In italiano da Java (Francia, Germania); se non lo conosce, da countryInfo; se no il codice. */
    static String nomeNazione(String codice, Map<String, String> riserva) {
        String nome = Locale.of("", codice).getDisplayCountry(Locale.ITALIAN);
        if (nome.isEmpty() || nome.equalsIgnoreCase(codice)) {
            return riserva.getOrDefault(codice, codice);
        }
        return nome;
    }

    private Resource risorsa(String nome) {
        String cartella = proprieta.cartella().trim();
        if (cartella.startsWith("classpath:") || cartella.startsWith("file:")) {
            return risorse.getResource(cartella.endsWith("/") ? cartella + nome : cartella + "/" + nome);
        }
        return risorse.getResource(Path.of(cartella).resolve(nome).toUri().toString());
    }

    private static BufferedReader leggi(Resource r) throws IOException {
        return new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8), 1 << 16);
    }

    /**
     * Divide una riga separata da tabulazioni nei primi {@code campi.length}
     * campi, senza regex e senza creare quelli che non servono; restituisce
     * quanti ne ha riempiti.
     */
    static int dividi(String riga, String[] campi) {
        int n = 0;
        int da = 0;
        while (n < campi.length) {
            int tab = riga.indexOf('\t', da);
            if (tab < 0) {
                campi[n++] = riga.substring(da);
                break;
            }
            campi[n++] = riga.substring(da, tab);
            da = tab + 1;
        }
        return n;
    }
}
