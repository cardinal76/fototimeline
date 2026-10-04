package it.fototimeline.media;

import java.time.LocalDateTime;

/**
 * Quello che ffprobe dice di un video.
 *
 * @param larghezza   già scambiata con l'altezza se il video è ruotato di 90°
 * @param ripresoIl   ora locale di ripresa, se il file la dice
 */
public record InfoVideo(Integer larghezza, Integer altezza, Double durata, LocalDateTime ripresoIl,
        String fotocamera, Double latitudine, Double longitudine) {
}
