package it.fototimeline.service;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quanti file insieme per "Indicizza archivio", "Importa cartella" e la
 * cartella automatica ({@link ImportazioneCartelle}).
 *
 * @param paralleli     i file letti nello stesso momento: dal cloud montato
 *                      quasi tutto il tempo è attesa di rete, e con più file
 *                      insieme le attese si sovrappongono. Le parti pesanti
 *                      (decodifica, miniatura, heif-convert, ffmpeg) restano una
 *                      alla volta ({@link FotoService}). 0 o meno = 3; al
 *                      massimo {@link #MASSIMO}.
 * @param attesaRiprova dopo un errore di lettura (il cloud che non risponde)
 *                      il file si riprova una volta dopo questa attesa (null = 3 s)
 */
@ConfigurationProperties("fototimeline.indicizzazione")
public record IndicizzazioneProperties(int paralleli, Duration attesaRiprova) {

    public static final int PARALLELI = 3;
    public static final int MASSIMO = 16;
    public static final Duration ATTESA_RIPROVA = Duration.ofSeconds(3);
    public static final IndicizzazioneProperties PREDEFINITE = new IndicizzazioneProperties(PARALLELI, ATTESA_RIPROVA);

    public IndicizzazioneProperties {
        if (paralleli <= 0) {
            paralleli = PARALLELI;
        }
        paralleli = Math.min(paralleli, MASSIMO);
        if (attesaRiprova == null || attesaRiprova.isNegative()) {
            attesaRiprova = ATTESA_RIPROVA;
        }
    }
}
