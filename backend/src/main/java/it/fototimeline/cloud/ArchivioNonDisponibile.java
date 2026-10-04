package it.fototimeline.cloud;

/** Il cloud è smontato: originali, caricamenti e modifiche ai file aspettano. */
public class ArchivioNonDisponibile extends RuntimeException {

    public ArchivioNonDisponibile() {
        super("L'archivio nel cloud è smontato: un amministratore deve montarlo.");
    }
}
