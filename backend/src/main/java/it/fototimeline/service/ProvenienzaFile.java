package it.fototimeline.service;

import java.nio.file.Path;

/**
 * Chi ha portato un file che sta nella cartella automatica: la
 * sincronizzazione dei telefoni lo sa dal suo registro delle copie. Così la
 * cartella resta piatta (le sottocartelle diventerebbero album) e ogni foto
 * sa lo stesso di chi è.
 */
@FunctionalInterface
public interface ProvenienzaFile {

    /** Lo username di chi l'ha portato, o null se non si sa (per esempio messo lì a mano). */
    String caricataDa(Path file);
}
