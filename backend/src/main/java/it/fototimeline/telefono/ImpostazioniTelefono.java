package it.fototimeline.telefono;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Il documento di quando il telefono era uno solo
 * ({@code impostazioni/sincronizzazione-telefono}): serve solo a
 * {@link SincronizzazioneTelefono#migra()}, che lo trasforma nel primo
 * {@link SorgenteTelefono} e poi lo cancella.
 *
 * @param copieInParallelo null nei documenti salvati prima che ci fosse
 */
@Document("impostazioni")
record ImpostazioniTelefono(@Id String id, boolean attiva, String sorgente, int intervalloOre,
        int giorniPrimaDiCancellare, Integer copieInParallelo, SorgenteTelefono.Giro ultimoGiro) {

    static final String ID = "sincronizzazione-telefono";
}
