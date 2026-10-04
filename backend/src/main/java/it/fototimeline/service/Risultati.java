package it.fototimeline.service;

import java.util.List;

import it.fototimeline.dominio.Foto;

public final class Risultati {

    private Risultati() {
    }

    public record PaginaFoto(List<Foto> foto, long totale, int pagina, boolean altre) {
    }

    /** Un mese della timeline con quante foto contiene. */
    public record VoceMese(int anno, int mese, long conteggio) {
    }

    public enum Esito { CARICATA, DUPLICATA, ERRORE }

    public record Caricamento(String nome, Esito esito, Foto foto, String messaggio) {
    }

    public record Importazione(int trovate, int importate, int duplicate, int errori, List<String> messaggi) {
    }
}
