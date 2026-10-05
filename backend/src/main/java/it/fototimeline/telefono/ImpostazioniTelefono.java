package it.fototimeline.telefono;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Le impostazioni della sincronizzazione del telefono, in un solo documento
 * ({@link SincronizzazioneTelefono#ID}), con l'esito dell'ultimo giro.
 * Impostazioni e giro si scrivono con aggiornamenti separati: salvare dalla
 * pagina mentre un giro finisce non perde niente.
 *
 * @param attiva                  se false i giri partono solo a mano
 * @param sorgente                remote di rclone e cartella, per esempio
 *                                {@code pcloud:Automatic Upload}
 * @param intervalloOre           ogni quante ore un giro
 * @param giorniPrimaDiCancellare dopo quanti giorni dalla copia togliere il file
 *                                dalla sorgente; 0 = mai
 * @param copieInParallelo        quanti file copiare insieme (null nei documenti
 *                                salvati prima che ci fosse: vale il predefinito)
 */
@Document("impostazioni")
public record ImpostazioniTelefono(@Id String id, boolean attiva, String sorgente, int intervalloOre,
        int giorniPrimaDiCancellare, Integer copieInParallelo, Giro ultimoGiro) {

    public static final String SORGENTE = "pcloud:Automatic Upload";
    public static final int INTERVALLO_ORE = 6;
    public static final int GIORNI_PRIMA_DI_CANCELLARE = 7;
    public static final int COPIE_IN_PARALLELO = 6;
    public static final int MAX_COPIE_IN_PARALLELO = 32;

    static ImpostazioniTelefono predefinite() {
        return new ImpostazioniTelefono(SincronizzazioneTelefono.ID, false, SORGENTE, INTERVALLO_ORE,
                GIORNI_PRIMA_DI_CANCELLARE, COPIE_IN_PARALLELO, null);
    }

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
