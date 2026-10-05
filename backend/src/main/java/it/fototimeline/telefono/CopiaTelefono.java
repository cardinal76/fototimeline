package it.fototimeline.telefono;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un file del telefono già copiato nella cartella automatica. Un file nel
 * registro non si ricopia più, anche quando l'importazione l'ha già spostato
 * nell'archivio: è così che si riconosce "già fatto". La chiave è la cartella
 * ({@code sorgente}), non il telefono: un telefono tolto e rimesso con la
 * stessa cartella non ricopia niente.
 *
 * <p>All'importazione si cerca qui la {@code destinazione} di un file per
 * sapere di chi è ({@link SincronizzazioneTelefono#caricataDa}).
 *
 * @param sorgente     la sorgente al momento della copia ({@code pcloud:Automatic Upload})
 * @param idSorgente   il telefono ({@link SorgenteTelefono}) che l'ha copiato
 * @param proprietario il suo proprietario al momento della copia, se il telefono poi sparisce
 * @param percorso     relativo alla sorgente, con {@code /}
 * @param modificatoIl data di modifica nella sorgente, se rclone la dà
 * @param destinazione relativo al remote del cloud ({@code telefono/Pixel - IMG_0001.jpg})
 * @param cancellatoIl quando è stato tolto dalla sorgente (o trovato già tolto)
 */
@Document("copie_telefono")
@CompoundIndexes({
        @CompoundIndex(name = "file", def = "{'sorgente': 1, 'percorso': 1, 'dimensione': 1}", unique = true),
        @CompoundIndex(name = "destinazione", def = "{'destinazione': 1, 'copiatoIl': -1}")
})
public record CopiaTelefono(@Id String id, String sorgente, String idSorgente, String proprietario, String percorso,
        long dimensione, Instant modificatoIl, String destinazione, Instant copiatoIl, Instant cancellatoIl) {
}
