package it.fototimeline.luoghi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineLuogo;
import it.fototimeline.luoghi.IndiceLuoghi.Punto;
import it.fototimeline.luoghi.IndiceLuoghi.Trovato;
import it.fototimeline.luoghi.Luogo.Livello;

/**
 * Dal GPS al nome del luogo, senza servizi esterni: i centri abitati di
 * GeoNames (CC BY 4.0) letti all'avvio da {@code fototimeline.luoghi.cartella}.
 * Nel container la cartella la riempie il Dockerfile; senza cartella l'app
 * funziona lo stesso, solo senza luoghi.
 *
 * <p>I livelli: città o comune ({@code luogo}), provincia (solo in Italia,
 * {@code admin2}), regione (in Italia la regione, all'estero il primo livello
 * di GeoNames: Inghilterra, Baviera, Olanda Settentrionale), nazione.
 *
 * <p>File letti (formato del dump di GeoNames):
 * <ul>
 * <li>{@code cities500.txt} (o 1000, 5000, 15000): i punti;</li>
 * <li>{@code admin1CodesASCII.txt}: i nomi delle regioni;</li>
 * <li>{@code admin2Codes.txt}, facoltativo: le province italiane (le righe
 *     degli altri paesi si saltano; il Dockerfile tiene solo quelle "IT.");</li>
 * <li>{@code countryInfo.txt}, facoltativo: i nomi delle nazioni che Java non
 *     sa dire in italiano, e i loro geonameid;</li>
 * <li>{@code alternateNames-it.txt}, facoltativo: le righe in italiano di
 *     {@code alternateNamesV2.txt} (Roma invece di Rome, Parigi, Baviera;
 *     per le nazioni gli altri nomi, come Olanda per i Paesi Bassi).</li>
 * </ul>
 */
@Service
public class GeocodificaInversa {

    private static final Logger log = LoggerFactory.getLogger(GeocodificaInversa.class);

    static final String[] CITTA = {"cities500.txt", "cities1000.txt", "cities5000.txt", "cities15000.txt"};
    static final String REGIONI = "admin1CodesASCII.txt";
    static final String PROVINCE = "admin2Codes.txt";
    static final String NAZIONI = "countryInfo.txt";
    static final String NOMI_ITALIANI = "alternateNames-it.txt";
    /**
     * La versione del calcolo dal GPS, scritta su ogni foto calcolata: quando
     * cambia (2: con la provincia) "Calcola luoghi" rifà le foto di prima.
     */
    public static final int VERSIONE = 2;
    /** Quartieri (PPLX) e posti storici, abbandonati o distrutti: un quartiere di Roma non è un luogo a sé. */
    private static final Set<String> SALTATI = Set.of("PPLX", "PPLH", "PPLQ", "PPLW", "PPLCH");

    private final LuoghiProperties proprieta;
    private final ResourceLoader risorse;
    private volatile Dati dati;

    /** Tutto quello che si legge all'avvio, sostituito in un colpo solo. */
    /** @param abitanti di regioni, province e nazioni (somma delle città), per chiave: l'ordine della ricerca */
    private record Dati(IndiceLuoghi indice, Map<String, String> regioni, Map<String, String> province,
            Map<String, NomeNazione> nazioni, Map<String, Long> abitanti) {
    }

    /** Il nome da mostrare e gli altri nomi in italiano (Paesi Bassi: Olanda), per la ricerca. */
    private record NomeNazione(String nome, List<String> altri) {
    }

    /** Un nome in italiano con la sua priorità: 3 preferito, 2 breve, 1 gli altri. */
    private record NomeItaliano(String nome, int priorita) {
    }

    public GeocodificaInversa(LuoghiProperties proprieta, ResourceLoader risorse) {
        this.proprieta = proprieta;
        this.risorse = risorse;
        carica();
    }

    /** True se il dataset c'è ed è stato letto. */
    public boolean disponibile() {
        Dati d = dati;
        return d != null && d.indice().dimensione() > 0;
    }

    public int punti() {
        Dati d = dati;
        return d == null ? 0 : d.indice().dimensione();
    }

    public double distanzaMassimaKm() {
        return proprieta.distanzaMassimaKm();
    }

    /** Il luogo più vicino, se c'è entro la distanza massima. */
    public Optional<Trovato> trova(Double latitudine, Double longitudine) {
        Dati d = dati;
        if (d == null || latitudine == null || longitudine == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(d.indice().piuVicino(latitudine, longitudine, proprieta.distanzaMassimaKm()));
    }

    /**
     * Scrive nella foto città, provincia, regione e nazione delle sue
     * coordinate (o li toglie, se non ne ha o sono in mare aperto). Senza
     * dataset non tocca niente: "Calcola luoghi" ci ripasserà quando ci sarà.
     * Un luogo scelto a mano resta com'è.
     */
    public void applica(Foto foto) {
        if (!disponibile() || foto.getOrigineLuogo() == OrigineLuogo.MANUALE) {
            return;
        }
        Trovato t = trova(foto.getLatitudine(), foto.getLongitudine()).orElse(null);
        foto.setLuogo(t == null ? null : t.nome());
        foto.setProvincia(t == null ? null : t.provincia());
        foto.setRegione(t == null ? null : t.regione());
        foto.setNazione(t == null ? null : t.nazione());
        foto.setCodiceNazione(t == null ? null : t.codiceNazione());
        foto.setOrigineLuogo(t == null ? null : OrigineLuogo.GPS);
        foto.setLatitudineLuogo(null);
        foto.setLongitudineLuogo(null);
        foto.setLuogoCalcolato(true);
        foto.setVersioneLuoghi(VERSIONE);
    }

    // ------------------------------------------------------------ luoghi per nome

    /**
     * Città, province, regioni e nazioni che si chiamano così, per scegliere
     * il luogo a mano ("Manch" → Manchester, Inghilterra, Regno Unito): prima
     * i nomi uguali, poi quelli che cominciano così, poi le parole dentro il
     * nome; a parità il più popoloso (per regioni, province e nazioni la somma
     * delle loro città): Manchester inglese prima della parrocchia giamaicana.
     * Due voci che si leggerebbero uguali diventano una.
     */
    public List<Luogo> cerca(String testo, int quanti) {
        Dati d = dati;
        String cercato = Nomi.piega(testo == null ? "" : testo.trim());
        if (d == null || cercato.length() < 2 || quanti <= 0) {
            return List.of();
        }
        record Candidato(int somiglianza, int livello, long popolazione, Luogo luogo) {
        }
        List<Candidato> candidati = new ArrayList<>();
        d.nazioni().forEach((codice, n) -> {
            int migliore = Nomi.corrisponde(Nomi.piega(n.nome()), cercato);
            for (String altro : n.altri()) {
                int s = Nomi.corrisponde(Nomi.piega(altro), cercato);
                if (s >= 0 && (migliore < 0 || s < migliore)) {
                    migliore = s;
                }
            }
            if (migliore >= 0) {
                candidati.add(new Candidato(migliore, 0, d.abitanti().getOrDefault("nazione:" + codice, 0L),
                        nazione(d, codice)));
            }
        });
        d.regioni().forEach((codice, nome) -> {
            int s = Nomi.corrisponde(Nomi.piega(nome), cercato);
            if (s >= 0) {
                candidati.add(new Candidato(s, 1, d.abitanti().getOrDefault("regione:" + codice, 0L),
                        regione(d, codice)));
            }
        });
        d.province().forEach((codice, nome) -> {
            int s = Nomi.corrisponde(Nomi.piega(nome), cercato);
            if (s >= 0) {
                candidati.add(new Candidato(s, 2, d.abitanti().getOrDefault("provincia:" + codice, 0L),
                        provincia(d, codice)));
            }
        });
        // Qualche città in più: alcune si leggono uguali (City of London e London sono tutte e due "Londra").
        for (Punto p : d.indice().cerca(cercato, quanti * 2)) {
            candidati.add(new Candidato(Nomi.corrisponde(Nomi.piega(p.nome()), cercato), 3, p.popolazione(), citta(p)));
        }
        Set<List<Object>> visti = new java.util.HashSet<>();
        return candidati.stream()
                .sorted(Comparator.comparingInt(Candidato::somiglianza)
                        .thenComparing(Comparator.comparingLong(Candidato::popolazione).reversed())
                        .thenComparingInt(Candidato::livello))
                .map(Candidato::luogo)
                .filter(l -> visti.add(java.util.Arrays.asList(l.livello(), l.luogo(), l.provincia(), l.regione(),
                        l.codiceNazione())))
                .limit(quanti)
                .toList();
    }

    /**
     * Il luogo di una chiave di {@link #cerca} ("citta:2643123",
     * "provincia:IT.17.TN", "regione:IT.17", "nazione:NL"), vuoto se non c'è.
     */
    public Optional<Luogo> luogo(String chiave) {
        Dati d = dati;
        if (d == null || chiave == null) {
            return Optional.empty();
        }
        int duePunti = chiave.indexOf(':');
        if (duePunti < 0) {
            return Optional.empty();
        }
        String codice = chiave.substring(duePunti + 1).trim();
        return Optional.ofNullable(switch (chiave.substring(0, duePunti)) {
            case "citta" -> {
                Punto p;
                try {
                    p = d.indice().perGeonameid(Integer.parseInt(codice));
                } catch (NumberFormatException e) {
                    p = null;
                }
                yield p == null ? null : citta(p);
            }
            case "provincia" -> d.province().containsKey(codice) ? provincia(d, codice) : null;
            case "regione" -> d.regioni().containsKey(codice) ? regione(d, codice) : null;
            case "nazione" -> d.nazioni().containsKey(codice.toUpperCase(Locale.ROOT))
                    ? nazione(d, codice.toUpperCase(Locale.ROOT)) : null;
            default -> null;
        });
    }

    /** Gli altri nomi in italiano di una nazione ("Olanda" per NL), per la ricerca nel filtro. */
    public List<String> altriNomi(String codice) {
        Dati d = dati;
        NomeNazione n = d == null || codice == null ? null : d.nazioni().get(codice);
        return n == null ? List.of() : n.altri();
    }

    /**
     * I codici delle nazioni che si chiamano anche così, per la ricerca
     * libera: "Olanda" trova le foto dei Paesi Bassi.
     */
    public Set<String> nazioniPerNome(String testo) {
        Dati d = dati;
        String cercato = Nomi.piega(testo == null ? "" : testo.trim());
        if (d == null || cercato.length() < 2) {
            return Set.of();
        }
        Set<String> codici = new TreeSet<>();
        d.nazioni().forEach((codice, n) -> {
            if (Nomi.corrisponde(Nomi.piega(n.nome()), cercato) >= 0
                    || n.altri().stream().anyMatch(a -> Nomi.corrisponde(Nomi.piega(a), cercato) >= 0)) {
                codici.add(codice);
            }
        });
        return codici;
    }

    private static Luogo citta(Punto p) {
        return new Luogo("citta:" + p.geonameid(), Livello.CITTA, p.nome(), p.provincia(), p.regione(), p.nazione(),
                p.codiceNazione(), p.latitudine(), p.longitudine());
    }

    private static Luogo provincia(Dati d, String codice) {
        String regione = codice.substring(0, codice.lastIndexOf('.'));
        String nazione = codice.substring(0, codice.indexOf('.'));
        return new Luogo("provincia:" + codice, Livello.PROVINCIA, null, d.province().get(codice),
                d.regioni().get(regione), nomeNazione(d, nazione), nazione, null, null);
    }

    private static Luogo regione(Dati d, String codice) {
        String nazione = codice.substring(0, Math.max(0, codice.indexOf('.')));
        return new Luogo("regione:" + codice, Livello.REGIONE, null, null, d.regioni().get(codice),
                nomeNazione(d, nazione), nazione, null, null);
    }

    private static Luogo nazione(Dati d, String codice) {
        return new Luogo("nazione:" + codice, Livello.NAZIONE, null, null, null, nomeNazione(d, codice), codice, null,
                null);
    }

    private static String nomeNazione(Dati d, String codice) {
        NomeNazione n = d.nazioni().get(codice);
        return n != null ? n.nome() : nomeNazione(codice, Map.of());
    }

    /** Distanza sulla superficie, in km (haversine). */
    public static double distanzaKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * IndiceLuoghi.RAGGIO_KM * Math.asin(Math.min(1, Math.sqrt(a)));
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
            Map<Integer, List<NomeItaliano>> italiani = nomiItaliani();
            Map<String, String> regioni = regioni(italiani);
            Map<String, String> province = province(italiani);
            Map<String, NomeNazione> nazioni = nazioni(italiani);
            IndiceLuoghi.Costruttore c = new IndiceLuoghi.Costruttore();
            Map<String, Long> abitanti = new HashMap<>();
            String[] campi = new String[15];
            try (BufferedReader in = leggi(citta)) {
                String riga;
                while ((riga = in.readLine()) != null) {
                    int letti = dividi(riga, campi);
                    if (letti < 11 || SALTATI.contains(campi[7]) || campi[8].isEmpty()) {
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
                    int popolazione = 0;
                    if (letti > 14 && !campi[14].isEmpty()) {
                        try {
                            popolazione = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(campi[14]));
                        } catch (NumberFormatException e) {
                            // senza abitanti: in fondo alla ricerca per nome
                        }
                    }
                    String codice = campi[8];
                    String nome = migliore(italiani.get(id), campi[1]);
                    String nazione = nazioni.computeIfAbsent(codice, k -> new NomeNazione(nomeNazione(k, Map.of()),
                            List.of())).nome();
                    String regione = codice + "." + campi[10];
                    String provincia = letti > 11 && !campi[11].isEmpty() ? province.get(regione + "." + campi[11]) : null;
                    c.aggiungi(id, lat, lon, nome, provincia, regioni.get(regione), codice, nazione, popolazione);
                    abitanti.merge("nazione:" + codice, (long) popolazione, Long::sum);
                    abitanti.merge("regione:" + regione, (long) popolazione, Long::sum);
                    if (provincia != null) {
                        abitanti.merge("provincia:" + regione + "." + campi[11], (long) popolazione, Long::sum);
                    }
                }
            }
            IndiceLuoghi nuovo = c.costruisci();
            dati = new Dati(nuovo, Map.copyOf(regioni), Map.copyOf(province), Map.copyOf(nazioni), Map.copyOf(abitanti));
            log.info("Luoghi: {} punti da {} ({} province) in {} ms, circa {} MB", nuovo.dimensione(),
                    citta.getFilename(), province.size(), (System.nanoTime() - inizio) / 1_000_000,
                    nuovo.byteOccupati() / (1024 * 1024));
        } catch (IOException | RuntimeException e) {
            log.warn("Luoghi spenti: non riesco a leggere il dataset in {}: {}", proprieta.cartella(), e.toString());
        }
    }

    /**
     * I nomi in italiano per geonameid, col preferito (o breve) segnato;
     * senza quelli colloquiali o storici.
     */
    private Map<Integer, List<NomeItaliano>> nomiItaliani() throws IOException {
        Map<Integer, List<NomeItaliano>> nomi = new HashMap<>();
        Resource r = risorsa(NOMI_ITALIANI);
        if (!r.exists()) {
            return nomi;
        }
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
                nomi.computeIfAbsent(id, k -> new ArrayList<>(2)).add(new NomeItaliano(campi[3], p));
            }
        }
        return nomi;
    }

    /** Il nome in italiano: prima quello preferito, poi quello breve, poi il primo; se non c'è, {@code altrimenti}. */
    private static String migliore(List<NomeItaliano> nomi, String altrimenti) {
        if (nomi == null) {
            return altrimenti;
        }
        NomeItaliano scelto = null;
        for (NomeItaliano n : nomi) {
            if (scelto == null || n.priorita() > scelto.priorita()) {
                scelto = n;
            }
        }
        return scelto == null ? altrimenti : scelto.nome();
    }

    private static Integer geonameid(String campo) {
        try {
            return Integer.parseInt(campo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** "IT.07" → "Lazio". */
    private Map<String, String> regioni(Map<Integer, List<NomeItaliano>> italiani) throws IOException {
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
                Integer id = letti == 4 ? geonameid(campi[3]) : null;
                regioni.put(campi[0], id == null ? campi[1] : migliore(italiani.get(id), campi[1]));
            }
        }
        return regioni;
    }

    /**
     * "IT.17.TN" → "Provincia di Trento": solo l'Italia. Fra i nomi in
     * italiano quello che dice "Provincia" (GeoNames ha anche "Trento", che
     * si confonderebbe con la città); se non c'è, quello di GeoNames, con
     * "Provincia di" davanti se non dice già cos'è.
     */
    private Map<String, String> province(Map<Integer, List<NomeItaliano>> italiani) throws IOException {
        Map<String, String> province = new HashMap<>();
        Resource r = risorsa(PROVINCE);
        if (!r.exists()) {
            return province;
        }
        String[] campi = new String[4];
        try (BufferedReader in = leggi(r)) {
            String riga;
            while ((riga = in.readLine()) != null) {
                int letti = riga.startsWith("IT.") ? dividi(riga, campi) : 0;
                if (letti < 2) {
                    continue;
                }
                Integer id = letti > 3 ? geonameid(campi[3]) : null;
                List<NomeItaliano> nomi = id == null ? null : italiani.get(id);
                List<NomeItaliano> conProvincia = nomi == null ? null
                        : nomi.stream().filter(n -> n.nome().startsWith("Provincia")).toList();
                String nome = migliore(conProvincia == null || conProvincia.isEmpty() ? null : conProvincia, campi[1]);
                if (!nome.startsWith("Provincia") && !nome.startsWith("Città") && !nome.startsWith("Libero")) {
                    nome = "Provincia di " + nome;
                }
                province.put(campi[0], nome);
            }
        }
        return province;
    }

    /**
     * "NL" → "Paesi Bassi" (da Java, in italiano), con gli altri nomi in
     * italiano di GeoNames ("Olanda"); per i codici che Java non conosce il
     * nome inglese di countryInfo.
     */
    private Map<String, NomeNazione> nazioni(Map<Integer, List<NomeItaliano>> italiani) throws IOException {
        Map<String, NomeNazione> nazioni = new HashMap<>();
        Resource r = risorsa(NAZIONI);
        if (!r.exists()) {
            return nazioni;
        }
        String[] campi = new String[17];
        try (BufferedReader in = leggi(r)) {
            String riga;
            while ((riga = in.readLine()) != null) {
                int letti = dividi(riga, campi);
                if (riga.startsWith("#") || letti < 5) {
                    continue;
                }
                String nome = nomeNazione(campi[0], Map.of(campi[0], campi[4]));
                Integer id = letti > 16 ? geonameid(campi[16]) : null;
                List<String> altri = new ArrayList<>();
                if (id != null && italiani.containsKey(id)) {
                    for (NomeItaliano n : italiani.get(id)) {
                        if (!n.nome().equalsIgnoreCase(nome) && !altri.contains(n.nome())) {
                            altri.add(n.nome());
                        }
                    }
                }
                nazioni.put(campi[0], new NomeNazione(nome, List.copyOf(altri)));
            }
        }
        return nazioni;
    }

    /** In italiano da Java (Francia, Germania, Paesi Bassi); se non lo conosce, da countryInfo; se no il codice. */
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
