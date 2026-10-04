package it.fototimeline.service;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param archivio      radice degli originali
 * @param miniature     cartella delle miniature; null = {@code <archivio>/.miniature}
 * @param miniaturaLato lato massimo delle miniature, in pixel
 * @param importazione  radice sotto cui "Importa cartella" può leggere; null = ovunque
 */
@ConfigurationProperties("fototimeline")
public record ArchivioProperties(Path archivio, Path miniature, int miniaturaLato, Path importazione) {

    public ArchivioProperties {
        if (miniaturaLato <= 0) {
            miniaturaLato = 480;
        }
    }
}
