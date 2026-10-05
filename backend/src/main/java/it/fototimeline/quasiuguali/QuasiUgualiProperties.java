package it.fototimeline.quasiuguali;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param soglia          bit di differenza massimi tra due impronte (dHash a
 *                        64 bit) perché due foto siano "quasi uguali"
 * @param finestraRaffica foto con data EXIF scattate entro questo tempo
 *                        possono essere una raffica; zero = niente raffiche
 * @param sogliaRaffica   bit di differenza massimi dentro una raffica
 */
@ConfigurationProperties("fototimeline.quasi-uguali")
public record QuasiUgualiProperties(Integer soglia, Duration finestraRaffica, Integer sogliaRaffica) {

    public QuasiUgualiProperties {
        soglia = soglia == null ? 6 : Math.clamp(soglia, 0, 15);
        finestraRaffica = finestraRaffica == null ? Duration.ofSeconds(10) : finestraRaffica;
        sogliaRaffica = sogliaRaffica == null ? 12 : Math.clamp(sogliaRaffica, 0, 32);
    }
}
