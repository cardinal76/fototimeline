package it.fototimeline.service;

import java.time.LocalDate;

/**
 * Filtri della timeline; i campi null non filtrano. {@code nazione} è il
 * codice ISO (IT), {@code regione} e {@code luogo} i nomi come in /api/luoghi;
 * {@code caricataDa} è uno username; {@code evento} l'id di un evento
 * (pacchetto {@code eventi}).
 */
public record FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
        String nazione, String regione, String luogo, String caricataDa, String evento) {

    /** Senza filtro per evento. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
            String nazione, String regione, String luogo, String caricataDa) {
        this(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, null);
    }

    /** Lo stesso filtro, solo con le foto di un evento (null = di tutti). */
    public FiltroFoto conEvento(String evento) {
        return new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, evento);
    }

    /** Senza filtro per luogo né per chi l'ha caricata. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al) {
        this(q, tag, album, preferite, dal, al, null, null, null, null);
    }

    /** Solo per chi l'ha caricata, senza filtro per luogo. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
            String caricataDa) {
        this(q, tag, album, preferite, dal, al, null, null, null, caricataDa);
    }

    /** Solo per luogo, senza filtro per chi l'ha caricata. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
            String nazione, String regione, String luogo) {
        this(q, tag, album, preferite, dal, al, nazione, regione, luogo, null);
    }

    public FiltroFoto senzaDate() {
        return new FiltroFoto(q, tag, album, preferite, null, null, nazione, regione, luogo, caricataDa, evento);
    }
}
