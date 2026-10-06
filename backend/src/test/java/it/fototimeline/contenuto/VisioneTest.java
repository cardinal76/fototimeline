package it.fototimeline.contenuto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import it.fototimeline.contenuto.Visione.Somiglianza;

/** Le chiamate HTTP al servizio visione, con un server finto. */
class VisioneTest {

    private static final String SEGRETO = "segreto-di-prova";

    @TempDir
    Path cartella;

    private MockRestServiceServer server;
    private Visione visione;

    static VisioneProperties properties(String url) {
        return new VisioneProperties(url, SEGRETO, 0, 0, 0, 0, Duration.ZERO);
    }

    @BeforeEach
    void prepara() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        visione = new Visione(properties("http://visione:8000"), builder);
    }

    @Test
    void mandaLaMiniaturaColSegreto() throws Exception {
        Path miniatura = Files.write(cartella.resolve("m.jpg"), new byte[] {1, 2, 3});
        server.expect(requestTo("http://visione:8000/clip/indice/abc"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(header(Visione.SEGRETO, SEGRETO))
                .andExpect(header("Content-Type", "image/jpeg"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}))
                .andRespond(withSuccess("{\"id\":\"abc\",\"totale\":1}", MediaType.APPLICATION_JSON));

        visione.metti("abc", miniatura);
        server.verify();
    }

    @Test
    void unBloccoInUnaRichiestaMultipart() throws Exception {
        Map<String, Path> miniature = new LinkedHashMap<>();
        miniature.put("uno", Files.write(cartella.resolve("1.jpg"), new byte[] {1}));
        miniature.put("due", Files.write(cartella.resolve("2.jpg"), new byte[] {2}));
        server.expect(requestTo("http://visione:8000/clip/indice"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Content-Type", Matchers.startsWith("multipart/form-data")))
                .andExpect(content().string(Matchers.allOf(
                        Matchers.containsString("filename=\"uno\""), Matchers.containsString("filename=\"due\""))))
                .andRespond(withSuccess("""
                        {"aggiunte":["uno"],"errori":{"due":"Immagine illeggibile"},"totale":1}""",
                        MediaType.APPLICATION_JSON));

        var esito = visione.mettiBlocco(miniature);

        assertThat(esito.aggiunte()).containsExactly("uno");
        assertThat(esito.errori()).containsEntry("due", "Immagine illeggibile");
    }

    @Test
    void cercaRestituisceIdEPunteggiNellOrdine() {
        server.expect(requestTo(Matchers.startsWith("http://visione:8000/clip/cerca?q=torta%20di%20compleanno&k=50")))
                .andExpect(header(Visione.SEGRETO, SEGRETO))
                .andRespond(withSuccess("""
                        {"modello":"x","risultati":[{"id":"b","punteggio":0.31},{"id":"a","punteggio":0.27}]}""",
                        MediaType.APPLICATION_JSON));

        assertThat(visione.cerca("torta di compleanno", 50, 0.2))
                .containsExactly(new Somiglianza("b", 0.31), new Somiglianza("a", 0.27));
    }

    @Test
    void togliereUnIdCheNonCeVaBene() {
        server.expect(requestTo("http://visione:8000/clip/indice/abc")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        visione.togli("abc");
        server.verify();
    }

    @Test
    void seVisioneRifiutaLoDiceChiaro() {
        server.expect(requestTo("http://visione:8000/clip/indice"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).body("{\"detail\":\"Segreto mancante o sbagliato\"}"));
        assertThatThrownBy(visione::totale).isInstanceOf(VisioneNonRisponde.class).hasMessageContaining("401");
    }

    @Test
    void senzaUrlLaFunzioneESpenta() {
        var spenta = new Visione(properties(""), RestClient.builder());
        assertThat(properties("").attiva()).isFalse();
        assertThatThrownBy(() -> spenta.cerca("cane", 10, 0.2)).isInstanceOf(IllegalStateException.class);
    }
}
