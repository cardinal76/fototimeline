package it.fototimeline.volti;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Riconoscimento dei volti, nel servizio visione (YuNet + SFace): serve anche
 * {@code fototimeline.visione.url}.
 *
 * @param attiva          false = spento del tutto ({@code FOTOTIMELINE_VOLTI=false}); null = acceso
 * @param latoMinimo      lato corto minimo di un volto, in pixel della miniatura (480): sotto, troppo
 *                        pochi pixel per riconoscerlo (folla, sfondo)
 * @param punteggioMinimo quanto il rilevatore deve essere sicuro che sia un volto (0–1)
 * @param sogliaGruppo    coseno minimo con un vicino per entrare nel suo gruppo senza nome
 * @param sogliaSicura    coseno minimo con un volto di una persona con nome per esserle
 *                        assegnato da solo
 * @param sogliaProposta  da qui a {@code sogliaSicura} il volto resta una proposta per quella persona
 * @param vicini          quanti volti simili guardare per ogni volto nuovo
 * @param blocco          quante miniature per richiesta a visione nel lavoro "Riconosci volti"
 * @param minimoGruppo    i gruppi senza nome con meno volti non si mostrano (sconosciuti di passaggio)
 * @param attesa          quanto aspettare prima di riprovare quando visione non risponde
 */
@ConfigurationProperties("fototimeline.volti")
public record VoltiProperties(Boolean attiva, int latoMinimo, double punteggioMinimo, double sogliaGruppo,
        double sogliaSicura, double sogliaProposta, int vicini, int blocco, int minimoGruppo, Duration attesa) {

    public VoltiProperties {
        if (attiva == null) {
            attiva = true;
        }
        if (latoMinimo <= 0) {
            latoMinimo = 32;
        }
        if (punteggioMinimo <= 0) {
            punteggioMinimo = 0.8;
        }
        if (sogliaGruppo <= 0) {
            sogliaGruppo = 0.5;
        }
        if (sogliaSicura <= 0) {
            sogliaSicura = 0.6;
        }
        if (sogliaProposta <= 0) {
            sogliaProposta = 0.42;
        }
        if (vicini <= 0) {
            vicini = 10;
        }
        if (blocco <= 0) {
            blocco = 8;
        }
        if (minimoGruppo <= 0) {
            minimoGruppo = 2;
        }
        if (attesa == null || attesa.isNegative()) {
            attesa = Duration.ofSeconds(20);
        }
    }
}
