package it.fototimeline.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** I programmi esterni per HEIC e video; nel container ci sono, sul PC forse no. */
@ConfigurationProperties("fototimeline.media")
public record MediaProperties(String heifConvert, String ffmpeg, String ffprobe) {

    public MediaProperties {
        heifConvert = heifConvert == null || heifConvert.isBlank() ? "heif-convert" : heifConvert;
        ffmpeg = ffmpeg == null || ffmpeg.isBlank() ? "ffmpeg" : ffmpeg;
        ffprobe = ffprobe == null || ffprobe.isBlank() ? "ffprobe" : ffprobe;
    }
}
