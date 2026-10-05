package it.fototimeline.luoghi;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cartella           dove stanno i file di GeoNames (cartella su disco
 *                           o {@code classpath:...}); vuota = niente luoghi
 * @param distanzaMassimaKm  oltre questa distanza dal centro abitato più vicino
 *                           il luogo è sconosciuto (mare aperto)
 */
@ConfigurationProperties("fototimeline.luoghi")
public record LuoghiProperties(String cartella, double distanzaMassimaKm) {

    public LuoghiProperties {
        if (distanzaMassimaKm <= 0) {
            distanzaMassimaKm = 30;
        }
    }
}
