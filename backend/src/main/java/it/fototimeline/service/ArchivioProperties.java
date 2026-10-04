package it.fototimeline.service;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fototimeline")
public record ArchivioProperties(Path archivio, int miniaturaLato) {

    public ArchivioProperties {
        if (miniaturaLato <= 0) {
            miniaturaLato = 480;
        }
    }
}
