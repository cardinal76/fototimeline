package it.fototimeline.service;

import java.util.List;

import it.fototimeline.dominio.Foto;

public final class Risultati {

    private Risultati() {
    }

    public record PaginaFoto(List<Foto> foto, long totale, int pagina, boolean altre) {
    }

    /** Una foto sulla mappa: solo quello che serve al marker. */
    public record PuntoMappa(String id, double lat, double lon, String giorno, String titolo) {
    }

    /** "Accadde oggi": le foto di un anno passato nello stesso giorno. */
    public record Ricordo(int anno, int anniFa, List<Foto> foto) {
    }

    /** Un mese della timeline con quante foto contiene. */
    public record VoceMese(int anno, int mese, long conteggio) {
    }

    public enum Esito { CARICATA, DUPLICATA, ERRORE }

    public record Caricamento(String nome, Esito esito, Foto foto, String messaggio) {
    }

    /**
     * @param rimossi originali tolti dalla cartella di origine (importazione con
     *                "sposta": le importate e le duplicate già in archivio)
     */
    public record Importazione(int trovate, int importate, int duplicate, int errori, int rimossi,
            List<String> messaggi) {
    }

    /** Una cartella vista dal navigatore di "Importa cartella". */
    public record Cartella(String percorso, String padre, List<String> sottocartelle, int immagini) {
    }
}
