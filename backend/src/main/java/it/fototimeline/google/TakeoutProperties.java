package it.fototimeline.google;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * "Importa da Google Takeout".
 *
 * @param sorgente       dove Takeout mette gli zip, come remote di rclone; il
 *                       valore iniziale, poi si cambia dall'app
 * @param cartella       dove l'app trova lo zip scaricato (e dove estrae un file
 *                       alla volta): sul disco del server, non nel cloud
 * @param cartellaRclone la stessa cartella vista dal container di rclone, che
 *                       ci scrive; vuota = uguale a {@code cartella}
 * @param riserva        spazio da lasciare libero oltre allo zip (il file più
 *                       grande estratto, la cache di rclone)
 * @param attesaCloud    col cloud smontato, ogni quanto riguardare
 * @param controllo      ogni quanto cercare gli zip da togliere da Drive
 */
@ConfigurationProperties("fototimeline.takeout")
public record TakeoutProperties(String sorgente, Path cartella, String cartellaRclone, DataSize riserva,
        Duration attesaCloud, Duration controllo) {

    public TakeoutProperties {
        if (sorgente == null || sorgente.isBlank()) {
            sorgente = "gdrive:Takeout";
        }
        if (cartella == null) {
            cartella = Path.of(System.getProperty("java.io.tmpdir"), "fototimeline-takeout");
        }
        if (cartellaRclone == null || cartellaRclone.isBlank()) {
            cartellaRclone = cartella.toString();
        }
        if (riserva == null) {
            riserva = DataSize.ofGigabytes(2);
        }
        if (attesaCloud == null || attesaCloud.isNegative() || attesaCloud.isZero()) {
            attesaCloud = Duration.ofMinutes(1);
        }
        if (controllo == null || controllo.isNegative() || controllo.isZero()) {
            controllo = Duration.ofHours(6);
        }
    }
}
