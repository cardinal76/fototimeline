package it.fototimeline.backup;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cartella  dove scrivere i backup; null = {@code <archivio>/.backup},
 *                  cioè nel cloud accanto alle foto
 * @param intervallo ogni quanto farne uno nuovo (se il cloud è montato)
 * @param tenere    quanti backup tenere; i più vecchi si cancellano
 */
@ConfigurationProperties("fototimeline.backup")
public record BackupProperties(Path cartella, Duration intervallo, int tenere) {

    public BackupProperties {
        if (intervallo == null || intervallo.isNegative() || intervallo.isZero()) {
            intervallo = Duration.ofDays(1);
        }
        if (tenere <= 0) {
            tenere = 30;
        }
    }
}
