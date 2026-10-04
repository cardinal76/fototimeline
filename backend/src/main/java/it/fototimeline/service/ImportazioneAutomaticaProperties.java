package it.fototimeline.service;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param cartella   la cartella da svuotare nell'archivio (per esempio dove il
 *                   telefono carica le foto nel cloud); null = spenta
 * @param intervallo ogni quanto controllarla
 */
@ConfigurationProperties("fototimeline.importazione-automatica")
public record ImportazioneAutomaticaProperties(Path cartella, Duration intervallo) {
}
