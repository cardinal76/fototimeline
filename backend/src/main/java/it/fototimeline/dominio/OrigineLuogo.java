package it.fototimeline.dominio;

/** Da dove viene il luogo di una foto (città, provincia, regione, nazione). */
public enum OrigineLuogo {
    /** Dalle coordinate GPS, con GeoNames: lo rifà "Calcola luoghi". */
    GPS,
    /**
     * Scelto a mano (anche "nessun luogo"): il calcolo automatico non lo
     * sovrascrive mai, finché non si torna al GPS.
     */
    MANUALE
}
