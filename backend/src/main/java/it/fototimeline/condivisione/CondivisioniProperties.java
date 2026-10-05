package it.fototimeline.condivisione;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Link pubblici alle foto.
 *
 * @param richiesteAlMinuto      richieste ai path pubblici per indirizzo IP in un minuto
 *                               (una galleria grande chiede una miniatura per foto)
 * @param tokenSbagliati         link sconosciuti (o foto fuori dal link) per IP prima del blocco
 * @param finestraTokenSbagliati per quanto si contano, e per quanto dura il blocco
 * @param massimoFoto            foto al massimo in una condivisione
 * @param latoVista              lato massimo, in pixel, delle foto mostrate a chi ha il link
 */
@ConfigurationProperties("fototimeline.condivisioni")
public record CondivisioniProperties(int richiesteAlMinuto, int tokenSbagliati, Duration finestraTokenSbagliati,
        int massimoFoto, int latoVista) {

    public CondivisioniProperties {
        if (richiesteAlMinuto <= 0) {
            richiesteAlMinuto = 1200;
        }
        if (tokenSbagliati <= 0) {
            tokenSbagliati = 20;
        }
        if (finestraTokenSbagliati == null || finestraTokenSbagliati.isNegative() || finestraTokenSbagliati.isZero()) {
            finestraTokenSbagliati = Duration.ofMinutes(15);
        }
        if (massimoFoto <= 0) {
            massimoFoto = 2000;
        }
        if (latoVista <= 0) {
            latoVista = 2048;
        }
    }
}
