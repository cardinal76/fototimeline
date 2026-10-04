package it.fototimeline.service;

import java.time.LocalDate;

/** Filtri della timeline; i campi null non filtrano. */
public record FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al) {

    public FiltroFoto senzaDate() {
        return new FiltroFoto(q, tag, album, preferite, null, null);
    }
}
