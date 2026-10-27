package it.fototimeline.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/**
 * Il filtro per data della timeline ({@code periodo} nelle richieste): un
 * anno ("2019"), un mese ("2019-08") o due giorni ("2019-08-01..2019-09-15",
 * compresi; un lato vuoto non ha limite: "..2019-09-15", "2019-08-01..").
 * Vale sul giorno di scatto: le foto senza data non ci sono mai.
 *
 * @param dal il primo giorno, null senza limite
 * @param al  l'ultimo giorno (compreso), null senza limite
 */
public record Periodo(LocalDate dal, LocalDate al) {

    private static final Pattern ANNO = Pattern.compile("\\d{4}");
    private static final Pattern MESE = Pattern.compile("\\d{4}-\\d{2}");

    public Periodo {
        if (dal != null && al != null && dal.isAfter(al)) {
            throw new IllegalArgumentException("Periodo: la prima data viene dopo la seconda");
        }
    }

    /** Il periodo scritto nella richiesta; null o vuoto = nessun filtro (null). */
    public static Periodo leggi(String testo) {
        if (testo == null || testo.isBlank()) {
            return null;
        }
        String t = testo.trim();
        try {
            if (ANNO.matcher(t).matches()) {
                int anno = Integer.parseInt(t);
                return new Periodo(LocalDate.of(anno, 1, 1), LocalDate.of(anno, 12, 31));
            }
            if (MESE.matcher(t).matches()) {
                YearMonth mese = YearMonth.parse(t);
                return new Periodo(mese.atDay(1), mese.atEndOfMonth());
            }
            int punti = t.indexOf("..");
            if (punti >= 0) {
                LocalDate dal = giorno(t.substring(0, punti));
                LocalDate al = giorno(t.substring(punti + 2));
                return dal == null && al == null ? null : new Periodo(dal, al);
            }
            LocalDate giorno = LocalDate.parse(t);
            return new Periodo(giorno, giorno);
        } catch (DateTimeParseException | NumberFormatException e) {
            throw new IllegalArgumentException("Periodo non valido: " + t
                    + " (scrivi un anno 2019, un mese 2019-08 o due giorni 2019-08-01..2019-09-15)");
        }
    }

    private static LocalDate giorno(String s) {
        return s.isBlank() ? null : LocalDate.parse(s.trim());
    }
}
