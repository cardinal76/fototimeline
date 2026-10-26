package it.fototimeline.sicurezza;

/**
 * Cosa può fare chi è entrato. Su Keycloak sono tre ruoli di realm
 * ({@code fototimeline-admin}, {@code fototimeline-gestore},
 * {@code fototimeline-utilizzatore}, in {@link LoginProperties}); chi ne ha
 * più d'uno vale per il più alto, chi non ne ha nessuno non vede niente.
 * L'elenco degli indirizzi per ruolo è in {@link ConfigurazioneSicurezza}.
 */
public enum Ruolo {

    /** Tutto: anche gli utenti e il menu Strumenti (lavori, cloud, cestino, salute, telefoni...). */
    AMMINISTRATORE,
    /** Le foto: vedere, scaricare, caricare, modificare, eliminare (nel cestino), condividere. */
    GESTORE,
    /** Solo guardare: niente originali da scaricare, niente modifiche, niente Strumenti. */
    UTILIZZATORE;

    public boolean admin() {
        return this == AMMINISTRATORE;
    }

    /** Può cambiare le foto e scaricare gli originali. */
    public boolean puoGestire() {
        return this == AMMINISTRATORE || this == GESTORE;
    }
}
