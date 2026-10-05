package it.fototimeline.telefono;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un telefono da sincronizzare: una cartella di pCloud (o di un altro remote
 * di rclone) da cui copiare le foto nuove nella cartella automatica, con
 * l'esito del suo ultimo giro. Impostazioni e giro si scrivono con
 * aggiornamenti separati: salvare dalla pagina mentre un giro finisce non
 * perde niente.
 *
 * @param nome                    per esempio "Telefono di Anna"
 * @param proprietario            lo username di chi ha il telefono: diventa
 *                                {@code caricataDa} delle sue foto; null solo per
 *                                quello migrato, finché non entra un admin
 * @param attiva                  se false i giri partono solo a mano
 * @param sorgente                remote di rclone e cartella, per esempio
 *                                {@code pcloud:Automatic Upload}: è anche la chiave
 *                                del registro delle copie
 * @param intervalloOre           ogni quante ore un giro
 * @param giorniPrimaDiCancellare dopo quanti giorni dalla copia togliere il file
 *                                dalla sorgente; 0 = mai
 * @param copieInParallelo        quanti file copiare insieme (null = il predefinito)
 */
@Document("sorgenti_telefono")
public record SorgenteTelefono(@Id String id, String nome, String proprietario, boolean attiva, String sorgente,
        int intervalloOre, int giorniPrimaDiCancellare, Integer copieInParallelo, Instant creataIl, Giro ultimoGiro) {

    public static final String NOME = "Telefono";
    public static final String SORGENTE = "pcloud:Automatic Upload";
    public static final int INTERVALLO_ORE = 6;
    public static final int GIORNI_PRIMA_DI_CANCELLARE = 7;
    public static final int COPIE_IN_PARALLELO = 6;
    public static final int MAX_COPIE_IN_PARALLELO = 32;

    /** Le copie insieme, col predefinito per i documenti vecchi. */
    public int copie() {
        return copieInParallelo == null || copieInParallelo < 1 ? COPIE_IN_PARALLELO : copieInParallelo;
    }

    public enum Esito { OK, ERRORE }

    /**
     * Com'è andato un giro.
     *
     * @param trovati   i file della sorgente nei formati dell'archivio
     * @param copiati   copiati in questo giro
     * @param giaCopiati già copiati in un giro precedente (nel registro)
     * @param cancellati tolti dalla sorgente perché importati
     * @param messaggiErrori i primi errori, file per file
     */
    public record Giro(Instant iniziatoIl, Instant finitoIl, Esito esito, String messaggio, int trovati,
            int copiati, int giaCopiati, int cancellati, int errori, List<String> messaggiErrori) {
    }
}
