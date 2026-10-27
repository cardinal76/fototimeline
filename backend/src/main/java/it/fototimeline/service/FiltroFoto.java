package it.fototimeline.service;

import java.time.LocalDate;

/**
 * Filtri della timeline; i campi null non filtrano. {@code nazione} è il
 * codice ISO (IT), {@code regione}, {@code provincia} (solo Italia) e
 * {@code luogo} i nomi come in /api/luoghi; {@code caricataDa} è uno username;
 * {@code evento} l'id di un evento (pacchetto {@code eventi}); {@code persona}
 * l'id di una persona o di un gruppo di volti ({@code Foto.persone}).
 *
 * <p>Due filtri per data: {@code dal}/{@code al} è il salto della timeline
 * (la colonna dei mesi: "fino a qui"), che {@code /api/timeline} e la mappa
 * ignorano; {@code periodo} è il filtro per data scelto nei Filtri (anno,
 * mese o due giorni), che vale anche per loro.
 */
public record FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
        String nazione, String regione, String luogo, String caricataDa, String provincia, String evento,
        String persona, Periodo periodo) {

    /** Senza filtro per periodo. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
            String nazione, String regione, String luogo, String caricataDa, String provincia, String evento,
            String persona) {
        this(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, provincia, evento, persona,
                null);
    }

    /** Senza filtro per provincia, evento, persona né periodo. */
    public FiltroFoto(String q, String tag, String album, Boolean preferite, LocalDate dal, LocalDate al,
            String nazione, String regione, String luogo, String caricataDa) {
        this(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, null, null, null, null);
    }

    /** Lo stesso filtro, solo con le foto scattate nel periodo (null = sempre). */
    public FiltroFoto conPeriodo(Periodo periodo) {
        return new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, provincia,
                evento, persona, periodo);
    }

    /** Lo stesso filtro, solo con le foto di un evento (null = di tutti). */
    public FiltroFoto conEvento(String evento) {
        return new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, provincia,
                evento, persona, periodo);
    }

    /** Lo stesso filtro, solo con le foto di una provincia (null = di tutte). */
    public FiltroFoto conProvincia(String provincia) {
        return new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, provincia,
                evento, persona, periodo);
    }

    /** Lo stesso filtro, solo con le foto di una persona o di un gruppo di volti (null = di tutti). */
    public FiltroFoto conPersona(String persona) {
        return new FiltroFoto(q, tag, album, preferite, dal, al, nazione, regione, luogo, caricataDa, provincia,
                evento, persona, periodo);
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

    /** Senza il salto della timeline ({@code dal}/{@code al}); il periodo resta. */
    public FiltroFoto senzaDate() {
        return new FiltroFoto(q, tag, album, preferite, null, null, nazione, regione, luogo, caricataDa, provincia,
                evento, persona, periodo);
    }
}
