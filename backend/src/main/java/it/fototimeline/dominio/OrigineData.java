package it.fototimeline.dominio;

/** Da dove viene la data con cui la foto sta nella timeline. */
public enum OrigineData {
    /** DateTimeOriginal (o DateTime) dell'EXIF. */
    EXIF,
    /** Data di ultima modifica del file importato da una cartella. */
    FILE,
    /** Cartella AAAA/MM/GG in cui il file era già nell'archivio ("Indicizza archivio"). */
    CARTELLA,
    /** Momento del caricamento: la foto non diceva niente di meglio. */
    CARICAMENTO,
    /** Scelta a mano. */
    MANUALE
}
