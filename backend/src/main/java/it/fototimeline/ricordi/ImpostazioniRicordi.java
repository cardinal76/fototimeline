package it.fototimeline.ricordi;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Impostazioni e stato dei ricordi su Telegram, in {@code impostazioni/ricordi-telegram}.
 * Le impostazioni null valgono quelle di {@link RicordiTelegramProperties};
 * impostazioni e stato si scrivono con update separati.
 *
 * @param ultimoGiorno il giorno ("yyyy-MM-dd", nel fuso dei ricordi) già
 *                     fatto: mandato, o senza niente da mandare. Mai due
 *                     invii lo stesso giorno, anche dopo un riavvio.
 * @param ultimoEsito  com'è andata l'ultima volta (per l'app), senza token
 */
@Document("impostazioni")
record ImpostazioniRicordi(@Id String id, Boolean attivo, String ora, Integer foto, Boolean nessunRicordo,
        String ultimoGiorno, Instant ultimoInvioIl, String ultimoEsito) {

    static final String ID = "ricordi-telegram";
}
