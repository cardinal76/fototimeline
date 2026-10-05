package it.fototimeline.salute;

import java.util.List;

/**
 * Una riga della pagina "Salute".
 *
 * @param chiave    fissa, per ricordare lo stato tra un controllo e l'altro
 *                  (avvisi Telegram)
 * @param titolo    per le persone: "Backup dei metadati"
 * @param messaggio una frase su com'è
 * @param dettagli  righe in più, anche vuote
 */
public record VoceSalute(String chiave, String titolo, StatoSalute stato, String messaggio, List<String> dettagli) {

    public VoceSalute {
        dettagli = dettagli == null ? List.of() : List.copyOf(dettagli);
    }

    static VoceSalute ok(String chiave, String titolo, String messaggio, List<String> dettagli) {
        return new VoceSalute(chiave, titolo, StatoSalute.OK, messaggio, dettagli);
    }

    static VoceSalute attenzione(String chiave, String titolo, String messaggio, List<String> dettagli) {
        return new VoceSalute(chiave, titolo, StatoSalute.ATTENZIONE, messaggio, dettagli);
    }

    static VoceSalute errore(String chiave, String titolo, String messaggio, List<String> dettagli) {
        return new VoceSalute(chiave, titolo, StatoSalute.ERRORE, messaggio, dettagli);
    }
}
