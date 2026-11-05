package it.fototimeline.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * I programmi esterni per HEIC e video, e ImageMagick per le foto che né
 * Java né ffmpeg leggono; nel container ci sono, sul PC forse no.
 *
 * @param imagemagick il programma di ImageMagick; vuoto = {@code magick}
 *                    (ImageMagick 7) se c'è, altrimenti {@code convert} (6)
 */
@ConfigurationProperties("fototimeline.media")
public record MediaProperties(String heifConvert, String ffmpeg, String ffprobe, String imagemagick) {

    public MediaProperties {
        heifConvert = heifConvert == null || heifConvert.isBlank() ? "heif-convert" : heifConvert;
        ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
        ffprobe = ffprobe == null || ffprobe.isBlank() ? "ffprobe" : ffprobe;
        imagemagick = imagemagick == null || imagemagick.isBlank() ? null : imagemagick;
    }
}
