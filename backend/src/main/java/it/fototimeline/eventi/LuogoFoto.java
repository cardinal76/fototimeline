package it.fototimeline.eventi;

import java.util.List;
import java.util.Objects;

import it.fototimeline.dominio.Foto;

/**
 * Il posto di una foto come lo vedono gli eventi: luogo, regione, nazione
 * (nome e codice ISO). Viene dai campi di {@link Foto} scritti da GeoNames;
 * {@link #di(Foto)} è l'unico punto che li legge, e {@link NomiEventi#nomeLuogo}
 * l'unico che sceglie il nome da mettere nell'evento: per usare altri campi
 * (luoghi strutturati) basta cambiare quei due.
 */
public record LuogoFoto(String luogo, String regione, String nazione, String codiceNazione) {

    /** I campi di {@link Foto} da leggere (proiezione delle query). */
    static final List<String> CAMPI = List.of("luogo", "regione", "nazione", "codiceNazione");

    /** Il posto della foto, o null se non ha un luogo (niente GPS, mare aperto, luoghi non calcolati). */
    public static LuogoFoto di(Foto foto) {
        if (foto.getLuogo() == null || foto.getLuogo().isBlank()) {
            return null;
        }
        return new LuogoFoto(foto.getLuogo(), foto.getRegione(), foto.getNazione(), foto.getCodiceNazione());
    }

    boolean stessoLuogo(LuogoFoto altro) {
        return altro != null && luogo.equals(altro.luogo) && Objects.equals(codiceNazione, altro.codiceNazione);
    }

    boolean stessaRegione(LuogoFoto altro) {
        return altro != null && regione != null && regione.equals(altro.regione)
                && Objects.equals(codiceNazione, altro.codiceNazione);
    }

    boolean stessaNazione(LuogoFoto altro) {
        return altro != null && codiceNazione != null && codiceNazione.equals(altro.codiceNazione);
    }
}
