package it.fototimeline.service;

/**
 * Quello che succede a una foto e che interessa ad altri (la ricerca per
 * contenuto): li pubblica {@link FotoService}, chi ascolta non deve rallentarlo.
 */
public final class EventiFoto {

    private EventiFoto() {
    }

    /** Una foto (o un video) è entrata in archivio, con la sua miniatura già su disco. */
    public record FotoAggiunta(String id) {
    }

    /** Una foto è finita nel cestino: scheda e file restano, sparisce dagli elenchi. */
    public record FotoNelCestino(String id) {
    }

    /** Una foto è tornata dal cestino nell'archivio. */
    public record FotoRipristinata(String id) {
    }

    /** Una foto è stata eliminata per sempre, scheda e file. */
    public record FotoEliminata(String id) {
    }
}
