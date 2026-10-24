package it.fototimeline.telefono;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un video del telefono troppo grande per il cloud ({@link VideoGrandi}): la
 * sua ricompressione, dall'entrata in coda allo spostamento dell'originale
 * nella cartella dedicata su pCloud. La chiave è la stessa del registro delle
 * copie (cartella, percorso, dimensione): un file nuovo con lo stesso nome ma
 * un'altra dimensione è un altro video.
 *
 * @param sorgente              la cartella del telefono ({@code pcloud:Automatic Upload})
 * @param idSorgente            il telefono che l'ha trovato
 * @param proprietario          il suo proprietario di allora (va nel registro delle copie)
 * @param percorso              relativo alla sorgente, con {@code /}
 * @param modificatoIl          la data di modifica su pCloud, se rclone la dà
 * @param cartella              la cartella automatica nel remote del cloud ({@code telefono})
 * @param motivo                perché si ricomprime: la soglia o l'errore del cloud
 * @param riprovaIl             per un video rimandato, quando riprovare
 * @param tentativi             quante volte è stato rimandato di fila
 * @param crf                   il CRF del ricompresso (o dell'ultima prova)
 * @param dimensioneRicompresso la dimensione del ricompresso
 * @param hash                  lo SHA-256 del ricompresso: la scheda che lo ha deve
 *                              esistere prima di spostare l'originale
 * @param destinazione          il ricompresso nella cartella automatica, relativo al remote
 * @param originaleIn           dove è finito l'originale ({@code pcloud:Video originali troppo grandi/x.mp4})
 * @param messaggio             l'ultimo errore o il perché di un rimando
 */
@Document("video_grandi_telefono")
@CompoundIndexes({
        @CompoundIndex(name = "file", def = "{'sorgente': 1, 'percorso': 1, 'dimensione': 1}", unique = true),
        @CompoundIndex(name = "coda", def = "{'stato': 1, 'creatoIl': 1}")
})
public record VideoGrande(@Id String id, String sorgente, String idSorgente, String proprietario, String percorso,
        long dimensione, Instant modificatoIl, String cartella, String motivo, Stato stato, Instant creatoIl,
        Instant aggiornatoIl, Instant riprovaIl, int tentativi, Integer crf, Long dimensioneRicompresso, String hash,
        String destinazione, Instant caricatoIl, String originaleIn, Instant finitoIl, String messaggio) {

    public enum Stato {
        /** Aspetta il suo turno. */
        IN_CODA,
        /** Si scarica, si ricomprime o si carica adesso. */
        IN_CORSO,
        /** Non si è potuto (spazio sul disco, rclone): si riprova da solo dopo {@code riprovaIl}. */
        RIMANDATO,
        /** Il ricompresso è nella cartella automatica: aspetta l'importazione. */
        CARICATO,
        /** Importato e originale spostato nella cartella dedicata (o già sparito da pCloud). */
        FATTO,
        /** Non riuscito per un motivo che non passa da solo: si rifà con "Riprova". */
        ERRORE,
        /** Fermato a mano: si rifà con "Riprova". */
        ANNULLATO
    }

    /** Il nome del file, senza le cartelle. */
    public String nome() {
        return percorso.substring(percorso.lastIndexOf('/') + 1);
    }
}
