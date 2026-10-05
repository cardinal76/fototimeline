package it.fototimeline.dominio;

/**
 * Dove sta un video nella coda della versione compatibile (H.264/AAC in MP4).
 * Assente sulla scheda: niente da fare (originale già compatibile, o
 * conversione mai chiesta o annullata).
 */
public enum StatoConversione {
    /** Aspetta il suo turno (anche solo per capire con ffprobe se serve). */
    IN_CODA,
    /** Lo sta convertendo il thread della coda; dopo un riavvio torna IN_CODA. */
    IN_CORSO,
    /** La versione compatibile c'è: il browser riceve quella. */
    FATTA,
    /** Non riuscita: il motivo è in {@code erroreConversione}; "Converti video" ci riprova. */
    ERRORE
}
