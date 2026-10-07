package it.fototimeline.service;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un file della cartella automatica che l'importazione non è riuscita a
 * prendere per colpa del file stesso (cifrato, illeggibile): non si rilegge
 * a ogni giro finché dimensione e data di modifica restano quelle.
 *
 * @param percorso     il file, assoluto
 * @param modificatoIl la data di modifica del file quando è fallito
 * @param motivo       l'errore, come lo mostra l'app
 * @param quando       la prima volta che è fallito così
 */
@Document("importazione_scartati")
public record FileScartato(@Id String percorso, long dimensione, Instant modificatoIl, String motivo,
        Instant quando) {

    /** True se il file è ancora quello che era fallito. */
    public boolean uguale(long dimensione, Instant modificatoIl) {
        return this.dimensione == dimensione && modificatoIl != null
                && modificatoIl.toEpochMilli() == (this.modificatoIl == null ? Long.MIN_VALUE
                        : this.modificatoIl.toEpochMilli());
    }
}
