package it.fototimeline.cloud;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Il cloud (LifetimeCloud, pCloud, ...) montato da un container rclone, che
 * l'app comanda con l'API di controllo di rclone ({@code rclone rcd}).
 *
 * @param rcUrl          indirizzo dell'API di rclone; vuoto = nessun cloud da
 *                       gestire, l'archivio è un disco sempre disponibile (il PC)
 * @param rcUtente       utente dell'API
 * @param rcPassword     password dell'API
 * @param remoto         il remote di rclone, per esempio {@code lifetime:}
 * @param puntoMontaggio dove montarlo, nel container di rclone e nell'app
 * @param montaAllAvvio  se true l'app monta appena parte; se false resta smontato
 *                       finché un amministratore non lo monta
 * @param uid            proprietario dei file nel montaggio (e gruppo): l'utente
 *                       dell'app, che così può scriverci
 */
@ConfigurationProperties("fototimeline.cloud")
public record CloudProperties(String rcUrl, String rcUtente, String rcPassword, String remoto,
        String puntoMontaggio, boolean montaAllAvvio, int uid) {

    public CloudProperties {
        if (uid <= 0) {
            uid = 1000;
        }
    }

    public boolean gestito() {
        return rcUrl != null && !rcUrl.isBlank();
    }
}
