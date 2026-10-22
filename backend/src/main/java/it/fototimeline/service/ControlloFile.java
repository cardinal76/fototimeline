package it.fototimeline.service;

/**
 * Quello che {@link FotoService} chiede a chi lo fa lavorare su un file di
 * un'importazione: se deve fermarsi (lavoro annullato, o file abbandonato
 * perché ha superato il tempo massimo) e a che punto è arrivato, per lo stato
 * del lavoro. L'implementazione vera è {@link FileInLavorazione}; per chi non
 * si annulla (caricamenti, Takeout) {@link #NESSUNO}.
 */
@FunctionalInterface
public interface ControlloFile {

    /** Per chi non si ferma mai: aspetta il turno senza guardare e non riferisce le fasi. */
    ControlloFile NESSUNO = () -> false;

    /** A che punto è un file: si vede nel riquadro dell'importazione. */
    enum Fase {
        INIZIO("in partenza"),
        LETTURA("lettura e impronta"),
        METADATI("metadati"),
        ATTESA_TURNO("in attesa del turno per la miniatura"),
        CONVERSIONE_HEIC("conversione HEIC"),
        FOTOGRAMMA("fotogramma del video"),
        MINIATURA("miniatura"),
        SPOSTAMENTO("spostamento o copia"),
        SALVATAGGIO("salvataggio della scheda"),
        RIPROVA("attesa prima di riprovare"),
        FINE("ultimi controlli");

        private final String etichetta;

        Fase(String etichetta) {
            this.etichetta = etichetta;
        }

        public String etichetta() {
            return etichetta;
        }
    }

    /** True se il file non va più toccato: si lancia {@link FotoService.Annullata} alla prima occasione. */
    boolean fermato();

    default void fase(Fase fase) {
    }

    /**
     * Subito prima di una scrittura che non si disfa (la scheda in Mongo): se
     * il file è stato fermato, {@link FotoService.Annullata} e niente scrittura.
     */
    default void primaDiScrivere() {
        if (fermato()) {
            throw new FotoService.Annullata();
        }
    }

    /**
     * Il file ha preso una risorsa condivisa (il turno per le parti pesanti):
     * se lo si abbandona, {@code rilascio} la restituisce agli altri anche se
     * il suo thread è ancora appeso. False se il file è già fermato: allora la
     * risorsa la restituisce subito chi l'ha presa. {@code rilascio} deve
     * valere una volta sola anche se chiamato due volte.
     */
    default boolean tieni(Runnable rilascio) {
        return true;
    }

    /** La risorsa di {@link #tieni} è stata restituita da chi l'aveva presa. */
    default void lascia(Runnable rilascio) {
    }
}
