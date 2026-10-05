package it.fototimeline.contenuto;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Il servizio "visione" (cartella {@code visione/}, container a parte sulla
 * rete interna): CLIP multilingue e l'indice dei vettori delle miniature.
 *
 * @param url     indirizzo del servizio; vuoto = ricerca per contenuto spenta
 *                (il PC senza visione: l'app funziona come prima)
 * @param segreto segreto condiviso, mandato nell'header {@code X-Visione-Segreto}
 * @param soglia  punteggio minimo (coseno tra testo e immagine) perché una foto
 *                sia un risultato: con CLIP ViT-B/32 le foto giuste stanno
 *                sopra 0,25, quelle a caso intorno a 0,20
 * @param margine si tengono solo le foto entro questo distacco dalla più simile
 * @param massimo quanti risultati chiedere al più a visione
 * @param blocco  quante miniature per richiesta nel lavoro "Indicizza contenuto"
 * @param attesa  quanto aspettare prima di riprovare quando visione non risponde
 *                (per esempio mentre riparte e carica il modello)
 */
@ConfigurationProperties("fototimeline.visione")
public record VisioneProperties(String url, String segreto, double soglia, double margine, int massimo, int blocco,
        Duration attesa) {

    public VisioneProperties {
        if (soglia <= 0) {
            soglia = 0.24;
        }
        if (margine <= 0) {
            margine = 0.06;
        }
        if (massimo <= 0) {
            massimo = 500;
        }
        if (blocco <= 0) {
            blocco = 16;
        }
        if (attesa == null || attesa.isNegative()) {
            attesa = Duration.ofSeconds(20);
        }
    }

    public boolean attiva() {
        return url != null && !url.isBlank();
    }
}
