package it.fototimeline.google;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "Scegli da Google Foto" (Google Photos Picker API) con OAuth 2.0. Senza
 * client ID e secret la funzione è nascosta.
 *
 * @param clientId     del progetto Google Cloud ({@code FOTOTIMELINE_GOOGLE_CLIENT_ID})
 * @param clientSecret idem ({@code FOTOTIMELINE_GOOGLE_CLIENT_SECRET}): mai nei log né nelle risposte
 * @param chiave       per cifrare i refresh token in Mongo ({@code FOTOTIMELINE_GOOGLE_CHIAVE});
 *                     vuota = derivata dal client secret
 * @param redirectUri  dove Google torna dopo il consenso; vuoto = {@code <indirizzo dell'app>/api/google/callback}
 * @param urlConsenso  pagina di consenso di Google
 * @param urlToken     scambio del codice e rinnovo del token
 * @param urlRevoca    revoca del token ("Scollega Google")
 * @param urlPicker    API Picker ({@code https://photospicker.googleapis.com/v1})
 */
@ConfigurationProperties("fototimeline.google")
public record GoogleProperties(String clientId, String clientSecret, String chiave, String redirectUri,
        String urlConsenso, String urlToken, String urlRevoca, String urlPicker) {

    /** Il solo permesso chiesto: leggere le foto che l'utente sceglie nel Picker. */
    public static final String SCOPE = "https://www.googleapis.com/auth/photospicker.mediaitems.readonly";

    public GoogleProperties {
        clientId = vuoto(clientId);
        clientSecret = vuoto(clientSecret);
        chiave = vuoto(chiave);
        redirectUri = vuoto(redirectUri);
        urlConsenso = urlConsenso == null || urlConsenso.isBlank() ? "https://accounts.google.com/o/oauth2/v2/auth" : urlConsenso;
        urlToken = urlToken == null || urlToken.isBlank() ? "https://oauth2.googleapis.com/token" : urlToken;
        urlRevoca = urlRevoca == null || urlRevoca.isBlank() ? "https://oauth2.googleapis.com/revoke" : urlRevoca;
        urlPicker = urlPicker == null || urlPicker.isBlank() ? "https://photospicker.googleapis.com/v1" : urlPicker;
    }

    /** True se client ID e secret ci sono: solo allora la funzione si vede. */
    public boolean configurato() {
        return clientId != null && clientSecret != null;
    }

    private static String vuoto(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Senza segreti: un record li stamperebbe tutti. */
    @Override
    public String toString() {
        return "GoogleProperties[clientId=" + clientId + ", configurato=" + configurato() + "]";
    }
}
