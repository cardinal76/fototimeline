package it.fototimeline.luoghi;

/**
 * Un luogo di GeoNames da scegliere a mano, a uno dei suoi livelli: una città
 * (con le coordinate, per la mappa), una provincia italiana, una regione o una
 * nazione. I campi sotto il livello scelto sono null (una regione non ha né
 * città né provincia).
 *
 * @param chiave come lo si richiede: "citta:2643123", "provincia:IT.17.TN",
 *               "regione:IT.17", "nazione:NL"
 */
public record Luogo(String chiave, Livello livello, String luogo, String provincia, String regione, String nazione,
        String codiceNazione, Double latitudine, Double longitudine) {

    public enum Livello { CITTA, PROVINCIA, REGIONE, NAZIONE }
}
