package it.fototimeline.notte;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.scheduling.support.CronExpression;

/**
 * Il giro notturno ({@link GiroNotturno}).
 *
 * @param cron          quando parte: cron di Spring a sei campi (secondi compresi), nel {@code fuso};
 *                      vuoto o {@code -} = spento ({@code FOTOTIMELINE_NOTTE_CRON})
 * @param fuso          il fuso del cron, predefinito Europe/Rome: le 2:30 sono quelle italiane
 * @param attesaMassima se all'ora del giro gira già un lavoro chiesto da qualcuno, quanto lo si
 *                      aspetta prima di saltare ({@code FOTOTIMELINE_NOTTE_ATTESA})
 * @param controllo     ogni quanto si guarda se un lavoro del giro è finito (nei test pochi millisecondi)
 */
@ConfigurationProperties("fototimeline.notte")
public record NotteProperties(String cron, String fuso, Duration attesaMassima, Duration controllo) {

    public static final String SPENTO = "-";

    public NotteProperties {
        cron = cron == null ? "" : cron.trim();
        fuso = fuso == null || fuso.isBlank() ? "Europe/Rome" : fuso.trim();
        if (attesaMassima == null || attesaMassima.isNegative()) {
            attesaMassima = Duration.ofHours(2);
        }
        if (controllo == null || controllo.isNegative() || controllo.isZero()) {
            controllo = Duration.ofSeconds(5);
        }
        // Un cron sbagliato ferma l'avvio, con il motivo: meglio che un giro che non parte mai in silenzio.
        if (!cron.isEmpty() && !cron.equals(SPENTO)) {
            try {
                CronExpression.parse(cron);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("FOTOTIMELINE_NOTTE_CRON non valido (\"" + cron
                        + "\"): servono sei campi, per esempio \"0 30 2 * * *\" per le 2:30; \"-\" lo spegne", e);
            }
        }
        ZoneId.of(fuso);
    }

    /** Spento con il cron vuoto o {@code -}. */
    public boolean attivo() {
        return !cron.isEmpty() && !cron.equals(SPENTO);
    }

    /** Il cron; null se spento. */
    public CronExpression espressione() {
        return attivo() ? CronExpression.parse(cron) : null;
    }

    public ZoneId zona() {
        return ZoneId.of(fuso);
    }
}
