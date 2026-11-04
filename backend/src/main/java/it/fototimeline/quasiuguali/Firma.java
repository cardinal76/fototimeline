package it.fototimeline.quasiuguali;

/**
 * Quello che delle quasi uguali conferma l'impronta (pHash, che resta in
 * {@code Foto.impronta}): con il solo pHash due foto diverse ma con la stessa
 * "forma" (cielo sopra e prato sotto, un foglio bianco, una foto al buio)
 * finivano insieme. Si calcola dalla miniatura insieme al pHash
 * ({@link Impronta#analizza}).
 *
 * @param versione  {@link Impronta#VERSIONE} di quando è stata calcolata: se
 *                  cambia il calcolo, "Calcola impronte" la rifà (il pHash no)
 * @param dhash     dHash a 64 bit (9×8 riquadri, ogni bit dice se un riquadro
 *                  è più chiaro del vicino a destra): guarda i bordi, il pHash
 *                  le grandi masse
 * @param colori    4×4 riquadri di colore medio, R G B per riquadro (48 byte,
 *                  da 0 a 255): il pHash i colori non li vede
 * @param luce      luminosità media, da 0 a 255
 * @param contrasto deviazione standard della luminosità sui 32×32 riquadri del
 *                  pHash, da 0 a 127: vicino a zero la foto è uniforme e il
 *                  pHash non vuol dire niente
 * @param chiari    percentuale di riquadri quasi bianchi (documenti, schermate)
 */
public record Firma(int versione, long dhash, byte[] colori, int luce, int contrasto, int chiari) {

    /** R, G o B del riquadro {@code riquadro} (0..15), da 0 a 255. */
    int colore(int riquadro, int canale) {
        return colori[riquadro * 3 + canale] & 0xff;
    }
}
