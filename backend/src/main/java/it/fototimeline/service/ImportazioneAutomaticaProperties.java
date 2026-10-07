package it.fototimeline.service;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cartella   la cartella da svuotare nell'archivio (per esempio dove il
 *                   telefono carica le foto nel cloud); null = spenta
 * @param intervallo ogni quanto controllarla
 * @param attesa     un file modificato da meno di così si lascia al giro
 *                   dopo: può essere ancora in copia (null = 10 minuti)
 */
@ConfigurationProperties("fototimeline.importazione-automatica")
public record ImportazioneAutomaticaProperties(Path cartella, Duration intervallo, Duration attesa) {

    public static final Duration ATTESA = Duration.ofMinutes(10);

    public ImportazioneAutomaticaProperties {
        if (attesa == null || attesa.isNegative()) {
            attesa = ATTESA;
        }
    }
}
