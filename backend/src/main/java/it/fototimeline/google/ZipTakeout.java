package it.fototimeline.google;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Uno zip di Google Takeout già visto: il registro che dice cosa è fatto. La
 * chiave è nome + dimensione + data di modifica su Drive: lo stesso zip non si
 * rifà, uno nuovo con lo stesso nome (l'esportazione di due mesi dopo) sì.
 *
 * @param sorgente     la sorgente da cui è stato preso ({@code gdrive:Takeout}), per toglierlo da Drive
 * @param nome         relativo alla sorgente ({@code takeout-20260101T000000Z-001.zip})
 * @param file         foto e video trovati nello zip (0 finché non è aperto)
 * @param fatti        quanti di quelli, nell'ordine dello zip: dopo un riavvio si riparte da qui
 * @param senzaJson    importati senza il JSON di Google (data e luogo solo dall'EXIF)
 * @param saltati      file che l'archivio non accetta (formato), non errori
 * @param cancellatoIl quando è stato tolto da Drive (se impostato), null se c'è ancora
 */
@Document("takeout_zip")
@CompoundIndex(name = "zip", def = "{'nome': 1, 'dimensione': 1, 'modificatoIl': 1}", unique = true)
public record ZipTakeout(@Id String id, String sorgente, String nome, long dimensione, Instant modificatoIl,
        StatoZip stato, Instant iniziatoIl, Instant finitoIl, int file, int fatti, int nuove, int giaPresenti,
        int senzaJson, int saltati, int errori, List<String> messaggi, String errore, Instant cancellatoIl) {

    public enum StatoZip {
        /** Si sta scaricando o importando (o lo si stava facendo prima di un riavvio). */
        IN_CORSO,
        /** Tutti i file fatti, nessun errore. */
        FATTO,
        /** Tutti i file visti, ma qualcuno non è entrato: non si rifà da solo ("Riprova"). */
        CON_ERRORI,
        /** Non scaricato o non aperto: si riprova al prossimo giro. */
        FALLITO
    }

    public ZipTakeout {
        messaggi = messaggi == null ? List.of() : List.copyOf(messaggi);
    }
}
