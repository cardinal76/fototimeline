package it.fototimeline.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;
import it.fototimeline.google.ImportazioneTakeoutTest.Orologio;
import it.fototimeline.google.SceltaGoogleFoto.Fase;
import it.fototimeline.google.SceltaGoogleFoto.StatoOAuth;
import it.fototimeline.google.SceltaGoogleFoto.StatoScelta;
import it.fototimeline.repository.FotoRepository;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;

/**
 * Collegamento a Google, sessione del Picker, attesa della scelta, pagine,
 * download e importazione, con Google simulato (MockRestServiceServer).
 */
@SpringBootTest(properties = "de.flapdoodle.mongodb.embedded.version=7.0.14")
class SceltaGoogleFotoTest {

    /** Finti, costruiti qui: non devono uscire da messaggi e Mongo. */
    private static final String SEGRETO = "segreto-" + "di-prova-GOCSPX";
    private static final String REFRESH = "1//refresh-" + "di-prova";
    private static final String REDIRECT = "https://foto.example/api/google/callback";
    private static final GoogleProperties GOOGLE = new GoogleProperties("id-client.apps.googleusercontent.com", SEGRETO,
            null, null, "https://accounts.invalid/auth", "https://oauth.invalid/token", "https://oauth.invalid/revoke",
            "https://picker.invalid/v1");

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    MongoTemplate mongo;
    @Autowired
    FotoService fotoService;
    @Autowired
    FotoRepository repository;
    @Autowired
    ArchivioFile archivioFile;

    private MockRestServiceServer google;
    private Orologio orologio;
    private CifraturaToken cifratura;
    private SceltaGoogleFoto scelta;

    @BeforeEach
    void prepara() {
        mongo.remove(new Query(), TokenGoogle.class);
        repository.findAll().forEach(f -> fotoService.elimina(f.getId()));
        var builder = RestClient.builder();
        google = MockRestServiceServer.bindTo(builder).build();
        orologio = new Orologio(Instant.parse("2026-05-01T10:00:00Z"));
        cifratura = new CifraturaToken(GOOGLE);
        scelta = new SceltaGoogleFoto(GOOGLE, new ClientGoogle(GOOGLE, builder), cifratura, fotoService, archivioFile,
                mongo, orologio);
    }

    private void collega(String utente) {
        MultiValueMap<String, String> atteso = new LinkedMultiValueMap<>();
        atteso.add("code", "codice-1");
        atteso.add("client_id", GOOGLE.clientId());
        atteso.add("client_secret", SEGRETO);
        atteso.add("redirect_uri", REDIRECT);
        atteso.add("grant_type", "authorization_code");
        google.expect(requestTo("https://oauth.invalid/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(atteso))
                .andRespond(withSuccess("""
                        {"access_token": "accesso-1", "expires_in": 3599, "refresh_token": "%s",
                         "scope": "%s", "token_type": "Bearer"}
                        """.formatted(REFRESH, GoogleProperties.SCOPE), MediaType.APPLICATION_JSON));
        scelta.collega(utente, "codice-1", REDIRECT);
        google.verify();
        google.reset();
    }

    @Test
    void consensoConSoloLoScopeDelPickerEStateControllato() {
        StatoOAuth stato = scelta.nuovoStato("anna");
        String url = scelta.urlConsenso(stato, REDIRECT);
        assertThat(url).startsWith("https://accounts.invalid/auth?")
                .contains("client_id=id-client.apps.googleusercontent.com")
                .contains("scope=https://www.googleapis.com/auth/photospicker.mediaitems.readonly")
                .contains("access_type=offline")
                .contains("state=" + stato.valore())
                .contains("redirect_uri=https://foto.example/api/google/callback")
                .doesNotContain(SEGRETO);
        assertThat(scelta.statoValido(stato, stato.valore(), "anna")).isTrue();
        assertThat(scelta.statoValido(stato, stato.valore() + "x", "anna")).isFalse();
        assertThat(scelta.statoValido(stato, stato.valore(), "marco")).isFalse();
        assertThat(scelta.statoValido(null, stato.valore(), "anna")).isFalse();
        assertThat(scelta.statoValido(stato, null, "anna")).isFalse();
        orologio.avanti(SceltaGoogleFoto.VALIDITA_STATO.plusSeconds(1));
        assertThat(scelta.statoValido(stato, stato.valore(), "anna")).isFalse();
    }

    @Test
    void ilRefreshTokenStaCifratoELoScollegaLoRevoca() {
        collega("anna");
        TokenGoogle salvato = mongo.findById("anna", TokenGoogle.class);
        assertThat(salvato.refreshCifrato()).doesNotContain(REFRESH).doesNotContain("refresh");
        assertThat(cifratura.decifra(salvato.refreshCifrato(), "anna")).isEqualTo(REFRESH);
        // Copiato su un altro utente non si decifra.
        assertThatThrownBy(() -> cifratura.decifra(salvato.refreshCifrato(), "marco"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(salvato.toString()).doesNotContain(salvato.refreshCifrato());
        assertThat(GOOGLE.toString()).doesNotContain(SEGRETO);
        assertThat(scelta.collegamento("anna").collegato()).isTrue();
        assertThat(scelta.collegamento("marco").collegato()).isFalse();

        google.expect(requestTo("https://oauth.invalid/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of("token", java.util.List.of(REFRESH)))))
                .andRespond(withSuccess());
        assertThat(scelta.scollega("anna")).isTrue();
        google.verify();
        assertThat(mongo.findById("anna", TokenGoogle.class)).isNull();
        assertThat(scelta.scollega("anna")).isFalse();
    }

    @Test
    void sceltaCompletaConAttesaPagineDownloadEdErrori() throws Exception {
        collega("anna");
        byte[] uno = jpg(Color.RED);
        byte[] due = jpg(Color.BLUE);
        String bearer = "Bearer accesso-1";
        google.expect(requestTo("https://picker.invalid/v1/sessions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", bearer))
                .andRespond(withSuccess("""
                        {"id": "sessione-1", "pickerUri": "https://photos.google.com/picker/sessione-1",
                         "pollingConfig": {"pollInterval": "0.010s", "timeoutIn": "600s"},
                         "expireTime": "2099-01-01T00:00:00Z", "mediaItemsSet": false}
                        """, MediaType.APPLICATION_JSON));
        // Due giri d'attesa: l'utente sta ancora scegliendo, poi ha finito.
        google.expect(requestTo("https://picker.invalid/v1/sessions/sessione-1")).andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", bearer))
                .andRespond(withSuccess("""
                        {"id": "sessione-1", "pollingConfig": {"pollInterval": "0.010s", "timeoutIn": "590s"},
                         "mediaItemsSet": false}
                        """, MediaType.APPLICATION_JSON));
        google.expect(requestTo("https://picker.invalid/v1/sessions/sessione-1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"id\": \"sessione-1\", \"mediaItemsSet\": true}", MediaType.APPLICATION_JSON));
        google.expect(requestTo("https://picker.invalid/v1/mediaItems?sessionId=sessione-1&pageSize=100"))
                .andExpect(header("Authorization", bearer))
                .andRespond(withSuccess("""
                        {"mediaItems": [
                          {"id": "a", "createTime": "2021-06-01T08:30:00Z", "type": "PHOTO",
                           "mediaFile": {"baseUrl": "https://lh3.invalid/a", "mimeType": "image/jpeg", "filename": "mare.jpg"}},
                          {"id": "b", "createTime": "2021-06-02T09:00:00Z", "type": "PHOTO",
                           "mediaFile": {"baseUrl": "https://lh3.invalid/b", "mimeType": "image/jpeg", "filename": "monti.jpg"}}],
                         "nextPageToken": "pagina-2"}
                        """, MediaType.APPLICATION_JSON));
        google.expect(requestTo("https://picker.invalid/v1/mediaItems?sessionId=sessione-1&pageSize=100&pageToken=pagina-2"))
                .andRespond(withSuccess("""
                        {"mediaItems": [
                          {"id": "c", "createTime": "2021-06-01T08:30:00Z", "type": "PHOTO",
                           "mediaFile": {"baseUrl": "https://lh3.invalid/c", "mimeType": "image/jpeg", "filename": "copia.jpg"}},
                          {"id": "d", "createTime": "2021-06-03T09:00:00Z", "type": "PHOTO",
                           "mediaFile": {"baseUrl": "https://lh3.invalid/d", "mimeType": "image/jpeg", "filename": "sparita.jpg"}}]}
                        """, MediaType.APPLICATION_JSON));
        google.expect(requestTo("https://lh3.invalid/a=d")).andExpect(header("Authorization", bearer))
                .andRespond(withSuccess(uno, MediaType.IMAGE_JPEG));
        google.expect(requestTo("https://lh3.invalid/b=d")).andRespond(withSuccess(due, MediaType.IMAGE_JPEG));
        google.expect(requestTo("https://lh3.invalid/c=d")).andRespond(withSuccess(uno, MediaType.IMAGE_JPEG));
        google.expect(requestTo("https://lh3.invalid/d=d")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        google.expect(requestTo("https://picker.invalid/v1/sessions/sessione-1")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        StatoScelta partita = scelta.nuova("anna");
        assertThat(partita.pickerUri()).isEqualTo("https://photos.google.com/picker/sessione-1/autoclose");
        assertThat(partita.fase()).isEqualTo(Fase.ATTESA_SCELTA);
        assertThatThrownBy(() -> scelta.nuova("anna")).isInstanceOf(IllegalStateException.class);

        StatoScelta fine = aspetta("anna");
        assertThat(fine.fase()).isEqualTo(Fase.FINITA);
        assertThat(fine.totali()).isEqualTo(4);
        assertThat(fine.fatte()).isEqualTo(4);
        assertThat(fine.nuove()).isEqualTo(2);
        assertThat(fine.giaPresenti()).isEqualTo(1);
        assertThat(fine.errori()).isEqualTo(1);
        assertThat(fine.messaggi()).singleElement().asString().contains("sparita.jpg").contains("404");
        google.verify();

        Foto mare = repository.findAll().stream().filter(f -> f.getNomeOriginale().equals("mare.jpg")).findFirst()
                .orElseThrow();
        assertThat(mare.getCaricataDa()).isEqualTo("anna");
        assertThat(mare.getScattataIl()).isEqualTo(LocalDateTime.of(2021, 6, 1, 8, 30));
        assertThat(mare.getOrigineData()).isEqualTo(OrigineData.GOOGLE);
        assertThat(repository.count()).isEqualTo(2);
        assertThat(scelta.collegamento("anna").scelta().fase()).isEqualTo(Fase.FINITA);
        scelta.dimentica("anna");
        assertThat(scelta.scelta("anna")).isEmpty();
    }

    @Test
    void accessoScadutoSiRinnovaERevocatoChiedeDiRicollegare() {
        collega("anna");
        orologio.avanti(Duration.ofHours(2));
        google.expect(requestTo("https://oauth.invalid/token"))
                .andExpect(content().formData(new LinkedMultiValueMap<>(Map.of(
                        "refresh_token", java.util.List.of(REFRESH), "client_id", java.util.List.of(GOOGLE.clientId()),
                        "client_secret", java.util.List.of(SEGRETO), "grant_type", java.util.List.of("refresh_token")))))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}"));

        assertThatThrownBy(() -> scelta.nuova("anna"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ricollega")
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(SEGRETO).doesNotContain(REFRESH));
        assertThat(mongo.findById("anna", TokenGoogle.class)).isNull();
        assertThatThrownBy(() -> scelta.nuova("anna")).hasMessageContaining("Collega Google");
    }

    @Test
    void senzaClientLaFunzioneNonCe() {
        var spento = new GoogleProperties(null, " ", null, null, null, null, null, null);
        var s = new SceltaGoogleFoto(spento, new ClientGoogle(spento, RestClient.builder()), new CifraturaToken(spento),
                fotoService, archivioFile, mongo, orologio);
        assertThat(s.collegamento("anna").configurato()).isFalse();
        assertThatThrownBy(() -> s.nuova("anna")).isInstanceOf(SceltaGoogleFoto.NonConfigurato.class);
    }

    private StatoScelta aspetta(String utente) throws InterruptedException {
        long limite = System.currentTimeMillis() + 15_000;
        StatoScelta s = scelta.scelta(utente).orElseThrow();
        while (s.inCorso() && System.currentTimeMillis() < limite) {
            Thread.sleep(20);
            s = scelta.scelta(utente).orElseThrow();
        }
        return s;
    }

    private static byte[] jpg(Color colore) throws IOException {
        BufferedImage img = new BufferedImage(40, 30, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colore);
        g.fillRect(0, 0, 40, 30);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }
}
