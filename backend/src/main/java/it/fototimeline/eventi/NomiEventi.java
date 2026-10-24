package it.fototimeline.eventi;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Il nome automatico di un evento, in italiano: "{luogo}, {date}" o, senza
 * luogo, solo le date. Le date: "12 maggio 2021" per un giorno solo,
 * "12–14 marzo 2018" fino a {@link #GIORNI_IN_CHIARO} giorni, "agosto 2019"
 * (o "luglio–agosto 2019") oltre.
 */
final class NomiEventi {

    /** Fino a tanti giorni le date si scrivono giorno per giorno; oltre basta il mese. */
    static final int GIORNI_IN_CHIARO = 4;

    private static final String[] MESI = {"gennaio", "febbraio", "marzo", "aprile", "maggio", "giugno", "luglio",
            "agosto", "settembre", "ottobre", "novembre", "dicembre"};

    private NomiEventi() {
    }

    static String nome(RilevamentoEventi.Gruppo gruppo, LuogoFoto casa) {
        String luogo = nomeLuogo(gruppo.luoghi(), casa);
        String date = date(gruppo.primoGiorno(), gruppo.ultimoGiorno());
        return luogo == null ? date : luogo + ", " + date;
    }

    /**
     * Il nome del posto di un evento, dalle sue foto con un luogo; null se
     * non ne ha. L'unico punto che sceglie il nome (con {@link LuogoFoto#di}
     * l'unico che legge i campi della foto), sui livelli strutturati dei
     * luoghi: città, provincia, regione, nazione.
     *
     * <ul>
     * <li>Una città con almeno la metà delle foto dà il nome ("Sperlonga",
     * "Manchester").</li>
     * <li>All'estero (la nazione con più foto non è quella di casa), se no, la
     * nazione ("Regno Unito").</li>
     * <li>In Italia (nella nazione di casa), se no, la regione con almeno la
     * metà delle foto ("Trentino-Alto Adige" per un giro tra Canazei, Moena e
     * Bolzano), altrimenti la città con più foto. La provincia non dà mai il
     * nome: sta dentro la regione, e "Lazio" si legge meglio di "Provincia di
     * Latina".</li>
     * <li>Senza nessuna città (luoghi scelti a mano solo come regione o
     * nazione), il livello più preciso della voce con più foto.</li>
     * </ul>
     */
    static String nomeLuogo(Map<LuogoFoto, Integer> luoghi, LuogoFoto casa) {
        if (luoghi.isEmpty()) {
            return null;
        }
        int totale = luoghi.values().stream().mapToInt(Integer::intValue).sum();
        Map.Entry<String, Integer> nazione = prevalente(luoghi, l -> vuoto(l.codiceNazione()));
        Map<LuogoFoto, Integer> conCitta = new LinkedHashMap<>();
        luoghi.forEach((l, n) -> {
            if (l.luogo() != null) conCitta.put(l, n);
        });
        // La città con più foto (stesso nome e nazione), o null se nessuna foto ne ha una.
        Map.Entry<String, Integer> citta = conCitta.isEmpty() ? null
                : prevalente(conCitta, l -> l.luogo() + "\u0000" + vuoto(l.codiceNazione()));
        boolean estero = casa != null && !nazione.getKey().isEmpty() && !nazione.getKey().equals(casa.codiceNazione());
        if (citta != null && citta.getValue() * 2 >= totale) {
            return nomeCitta(citta.getKey());
        }
        if (estero) {
            return luoghi.keySet().stream()
                    .filter(l -> nazione.getKey().equals(l.codiceNazione()) && l.nazione() != null)
                    .map(LuogoFoto::nazione)
                    .findFirst()
                    .orElseGet(() -> piuPreciso(luoghi, citta));
        }
        Map<LuogoFoto, Integer> conRegione = new LinkedHashMap<>();
        luoghi.forEach((l, n) -> {
            if (l.regione() != null) conRegione.put(l, n);
        });
        Map.Entry<String, Integer> regione = conRegione.isEmpty() ? null : prevalente(conRegione, LuogoFoto::regione);
        if (regione != null && regione.getValue() * 2 >= totale) {
            return regione.getKey();
        }
        return piuPreciso(luoghi, citta);
    }

    /** La città con più foto o, se nessuna ne ha una, il livello più preciso della voce con più foto. */
    private static String piuPreciso(Map<LuogoFoto, Integer> luoghi, Map.Entry<String, Integer> citta) {
        if (citta != null) {
            return nomeCitta(citta.getKey());
        }
        return prevalente(luoghi, Function.identity()).getKey().nomePiuPreciso();
    }

    private static String nomeCitta(String chiave) {
        return chiave.substring(0, chiave.indexOf('\u0000'));
    }

    private static String vuoto(String s) {
        return s == null ? "" : s;
    }

    /** La voce con più foto (a parità la prima vista, cioè la più vecchia). */
    private static <K> Map.Entry<K, Integer> prevalente(Map<LuogoFoto, Integer> luoghi, Function<LuogoFoto, K> chiave) {
        Map<K, Integer> conti = new LinkedHashMap<>();
        luoghi.forEach((l, n) -> conti.merge(chiave.apply(l), n, Integer::sum));
        return conti.entrySet().stream()
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .orElseThrow();
    }

    /** "12 maggio 2021", "12–14 marzo 2018", "30 luglio – 2 agosto 2019", "agosto 2019", "luglio–agosto 2019". */
    static String date(LocalDate da, LocalDate a) {
        if (da.equals(a)) {
            return giorno(da);
        }
        if (ChronoUnit.DAYS.between(da, a) + 1 <= GIORNI_IN_CHIARO) {
            if (da.getYear() != a.getYear()) {
                return giorno(da) + " – " + giorno(a);
            }
            if (da.getMonth() != a.getMonth()) {
                return da.getDayOfMonth() + " " + mese(da) + " – " + giorno(a);
            }
            return da.getDayOfMonth() + "–" + giorno(a);
        }
        if (da.getYear() != a.getYear()) {
            return mese(da) + " " + da.getYear() + " – " + mese(a) + " " + a.getYear();
        }
        if (da.getMonth() != a.getMonth()) {
            return mese(da) + "–" + mese(a) + " " + a.getYear();
        }
        return mese(a) + " " + a.getYear();
    }

    private static String giorno(LocalDate d) {
        return d.getDayOfMonth() + " " + mese(d) + " " + d.getYear();
    }

    private static String mese(LocalDate d) {
        return MESI[d.getMonthValue() - 1];
    }
}
