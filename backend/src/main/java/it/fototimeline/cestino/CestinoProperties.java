package it.fototimeline.cestino;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Il cestino delle foto eliminate.
 *
 * @param giorni    dopo quanti giorni una foto nel cestino si elimina per
 *                  sempre da sola ({@code FOTOTIMELINE_CESTINO_GIORNI}); 0 = mai
 * @param controllo ogni quanto guardare se ce n'è qualcuna da eliminare
 */
@ConfigurationProperties("fototimeline.cestino")
public record CestinoProperties(Integer giorni, Duration controllo) {

    public CestinoProperties {
        giorni = giorni == null ? 30 : Math.max(giorni, 0);
        controllo = controllo == null ? Duration.ofHours(1) : controllo;
    }
}
