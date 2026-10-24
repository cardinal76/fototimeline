package it.fototimeline.eventi;

import java.util.List;
import java.util.Objects;

import it.fototimeline.dominio.Foto;

/**
 * Il posto di una foto come lo vedono gli eventi, sui livelli di
 * {@code luoghi}: città o comune, provincia (solo in Italia), regione (o il
 * primo livello di GeoNames all'estero), nazione (nome e codice ISO). Viene
 * dai campi di {@link Foto}, dal GPS o scelti a mano; {@link #di(Foto)} è
 * l'unico punto che li legge e {@link NomiEventi#nomeLuogo} l'unico che
 * sceglie il nome da mettere nell'evento. {@code luogo} manca se a mano si è
 * scelta solo una provincia, una regione o una nazione.
 */
public record LuogoFoto(String luogo, String provincia, String regione, String nazione, String codiceNazione) {

    /** I campi di {@link Foto} da leggere (proiezione delle query). */
    static final List<String> CAMPI = List.of("luogo", "provincia", "regione", "nazione", "codiceNazione");

    /** Il posto della foto, o null se non ne ha nessun livello (niente GPS, mare aperto, luoghi non calcolati). */
    public static LuogoFoto di(Foto foto) {
        String luogo = pulito(foto.getLuogo());
        String provincia = pulito(foto.getProvincia());
        String regione = pulito(foto.getRegione());
        String nazione = pulito(foto.getNazione());
        String codice = pulito(foto.getCodiceNazione());
        if (luogo == null && provincia == null && regione == null && nazione == null && codice == null) {
            return null;
        }
        return new LuogoFoto(luogo, provincia, regione, nazione, codice);
    }

    /** Il livello più preciso che c'è: città, provincia, regione o nazione. */
    String nomePiuPreciso() {
        if (luogo != null) return luogo;
        if (provincia != null) return provincia;
        if (regione != null) return regione;
        return nazione;
    }

    boolean stessoLuogo(LuogoFoto altro) {
        return altro != null && luogo != null && luogo.equals(altro.luogo)
                && Objects.equals(codiceNazione, altro.codiceNazione);
    }

    boolean stessaRegione(LuogoFoto altro) {
        return altro != null && regione != null && regione.equals(altro.regione)
                && Objects.equals(codiceNazione, altro.codiceNazione);
    }

    boolean stessaNazione(LuogoFoto altro) {
        return altro != null && codiceNazione != null && codiceNazione.equals(altro.codiceNazione);
    }

    private static String pulito(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
