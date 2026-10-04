package it.fototimeline.service;

import java.time.LocalDateTime;

/** Quello che si riesce a leggere dall'EXIF; ogni campo può mancare. */
public record MetadatiFoto(
        LocalDateTime scattataIl,
        String fotocamera,
        Double latitudine,
        Double longitudine,
        int orientamento) {

    static final MetadatiFoto VUOTI = new MetadatiFoto(null, null, null, null, 1);

    /** Orientamenti EXIF 5-8: l'immagine va ruotata di 90°, larghezza e altezza si scambiano. */
    public boolean ruotata90() {
        return orientamento >= 5 && orientamento <= 8;
    }
}
