package it.fototimeline.sicurezza;

/**
 * Evento: un utente è appena entrato (o ha riaperto l'app). Lo ascolta la
 * sincronizzazione dei telefoni, per dare al primo amministratore il telefono
 * migrato senza proprietario.
 */
public record UtenteRegistrato(String username, boolean admin) {
}
