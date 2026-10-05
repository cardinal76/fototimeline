package it.fototimeline.service;

import java.time.LocalDate;

/** Filtri della timeline; i campi null non filtrano. {@code caricataDa} è uno username. */
public record FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
        String caricataDa) {

    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al) {
        this(q, tag, album, preferite, dal, al, null);
    }

    public FiltroFoto senzaDate() {
        return new FiltroFoto(q, tag, album, preferite, null, null, caricataDa);
    }
}
