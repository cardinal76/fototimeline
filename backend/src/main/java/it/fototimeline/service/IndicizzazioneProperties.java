package it.fototimeline.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Quanti file insieme per "Indicizza archivio", "Importa cartella" e la
 * cartella automatica ({@link ImportazioneCartelle}), e quanto può durare
 * ognuno.
 *
 * @param paralleli          i file letti nello stesso momento: dal cloud montato
 *                           quasi tutto il tempo è attesa di rete, e con più file
 *                           insieme le attese si sovrappongono. Le parti pesanti
 *                           (decodifica, miniatura, heif-convert, ffmpeg) restano una
 *                           alla volta ({@link FotoService}). 0 o meno = 3; al
 *                           massimo {@link #MASSIMO}.
 * @param attesaRiprova      dopo un errore di lettura (il cloud che non risponde)
 *                           il file si riprova una volta dopo questa attesa (null = 3 s)
 * @param tempoMassimoFile   quanto può durare un file, più {@code tempoMassimoPerGb} per
 *                           ogni GB: oltre si abbandona ({@link FileInLavorazione})
 *                           e il lavoro va avanti con gli altri. Così una lettura
 *                           dal mount di rclone che non torna più non ferma tutto
 *                           (null o zero = {@link #TEMPO_MASSIMO_FILE})
 * @param tempoMassimoPerGb  il tempo in più per GB (null = {@link #TEMPO_MASSIMO_PER_GB})
 * @param attesaAnnullamento dopo Annulla, quanto si aspettano i file già
 *                           cominciati prima di abbandonarli (null o zero =
 *                           {@link #ATTESA_ANNULLAMENTO})
 */
@ConfigurationProperties("fototimeline.indicizzazione")
public record IndicizzazioneProperties(int paralleli, Duration attesaRiprova, Duration tempoMassimoFile,
        Duration tempoMassimoPerGb, Duration attesaAnnullamento) {

    public static final int PARALLELI = 3;
    public static final int MASSIMO = 16;
    public static final Duration ATTESA_RIPROVA = Duration.ofSeconds(3);
    public static final Duration TEMPO_MASSIMO_FILE = Duration.ofMinutes(5);
    public static final Duration TEMPO_MASSIMO_PER_GB = Duration.ofMinutes(5);
    public static final Duration ATTESA_ANNULLAMENTO = Duration.ofMinutes(1);
    public static final IndicizzazioneProperties PREDEFINITE = new IndicizzazioneProperties(PARALLELI, ATTESA_RIPROVA);

    private static final long GB = 1024L * 1024 * 1024;

    @ConstructorBinding
    public IndicizzazioneProperties {
        if (paralleli <= 0) {
            paralleli = PARALLELI;
        }
        paralleli = Math.min(paralleli, MASSIMO);
        if (attesaRiprova == null || attesaRiprova.isNegative()) {
            attesaRiprova = ATTESA_RIPROVA;
        }
        if (tempoMassimoFile == null || tempoMassimoFile.isNegative() || tempoMassimoFile.isZero()) {
            tempoMassimoFile = TEMPO_MASSIMO_FILE;
        }
        if (tempoMassimoPerGb == null || tempoMassimoPerGb.isNegative()) {
            tempoMassimoPerGb = TEMPO_MASSIMO_PER_GB;
        }
        if (attesaAnnullamento == null || attesaAnnullamento.isNegative() || attesaAnnullamento.isZero()) {
            attesaAnnullamento = ATTESA_ANNULLAMENTO;
        }
    }

    /** Coi tempi predefiniti. */
    public IndicizzazioneProperties(int paralleli, Duration attesaRiprova) {
        this(paralleli, attesaRiprova, null, null, null);
    }

    /** Il tempo massimo per un file di {@code dimensione} byte (0 se non si sa ancora), in proporzione anche ai pezzi di GB. */
    public Duration tempoMassimo(long dimensione) {
        long d = Math.max(0, dimensione);
        return tempoMassimoFile.plus(tempoMassimoPerGb.multipliedBy(d / GB))
                .plus(tempoMassimoPerGb.multipliedBy(d % GB).dividedBy(GB));
    }
}
