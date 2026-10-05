package it.fototimeline.service;

import java.time.LocalDate;

/**
 * Filtri della timeline; i campi null non filtrano. {@code nazione} è il
 * codice ISO (IT), {@code regione} e {@code luogo} i nomi come in /api/luoghi.
 */
public record FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
        String nazione, String regione, String luogo) {

    /** Senza filtro per luogo. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al) {
        this(q, tag, album, preferite, dal, al, null, null, null);
    }

    public FiltroFoto senzaDate() {
        return new FiltroFoto(q, tag, album, preferite, null, null, nazione, regione, luogo);
    }
}
