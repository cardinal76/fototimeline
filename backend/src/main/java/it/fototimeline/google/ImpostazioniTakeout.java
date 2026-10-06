package it.fototimeline.google;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * {@code impostazioni/google-takeout}: da dove si prendono gli zip e per chi.
 *
 * @param sorgente                remote e cartella di rclone ({@code gdrive:Takeout})
 * @param proprietario            "caricate da" per le foto importate; vuoto = chi lancia
 * @param giorniPrimaDiCancellare dopo quanti giorni togliere da Drive uno zip importato senza errori; 0 = mai
 * @param richiesto               true dal momento in cui parte a quando finisce (o lo si annulla):
 *                                dopo un riavvio, se è true, riparte da solo
 * @param lanciatoDa              chi l'ha fatto partire l'ultima volta
 */
@Document("impostazioni")
record ImpostazioniTakeout(@Id String id, String sorgente, String proprietario, Integer giorniPrimaDiCancellare,
        Boolean richiesto, String lanciatoDa) {

    static final String ID = "google-takeout";

    /** I campi mancano finché nessuno li ha salvati (gli update parziali): valgono 0 e false. */
    ImpostazioniTakeout {
        giorniPrimaDiCancellare = giorniPrimaDiCancellare == null ? 0 : giorniPrimaDiCancellare;
        richiesto = richiesto != null && richiesto;
    }
}
