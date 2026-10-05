package it.fototimeline.media;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La versione compatibile dei video (H.264/AAC in MP4) per quelli che non
 * tutti i browser sanno riprodurre (HEVC dell'iPhone, VP9, ProRes...).
 *
 * @param attiva              false: niente conversioni, si serve sempre l'originale
 * @param risoluzioneMassima  lato corto massimo della versione compatibile (1080 = 1080p);
 *                            i 4K si rimpiccioliscono mantenendo le proporzioni
 * @param thread              thread di ffmpeg: limita la CPU che una conversione può prendere
 * @param destinazione        dove vanno le versioni compatibili; null = {@code <archivio>/.compatibili}
 * @param controllo           ogni quanto la coda guarda se può ripartire (cloud rimontato)
 */
@ConfigurationProperties("fototimeline.video")
public record VideoProperties(boolean attiva, int risoluzioneMassima, int thread, Path destinazione,
        Duration controllo) {

    public VideoProperties {
        if (risoluzioneMassima <= 0) {
            risoluzioneMassima = 1080;
        }
        if (thread <= 0) {
            thread = 2;
        }
        if (controllo == null) {
            controllo = Duration.ofMinutes(1);
        }
    }
}
