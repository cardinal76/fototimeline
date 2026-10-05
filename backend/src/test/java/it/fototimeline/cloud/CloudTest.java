package it.fototimeline.cloud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** rclone risponde JSON con Content-Type text/plain: i finti rispondono uguale. */
class CloudTest {

    private static final String NESSUNO = "{\"mountPoints\":[]}";
    private static final String MONTATO = "{\"mountPoints\":[{\"Fs\":\"lifetime\",\"MountPoint\":\"/cloud\"}]}";

    // Credenziali finte: costruite qui, non scritte già codificate.
    private static final String UTENTE = "fototimeline";
    private static final String PASSWORD = "password-di-prova";

    private MockRestServiceServer rclone;
    private Cloud cloud;

    @BeforeEach
    void prepara() {
        var builder = RestClient.builder();
        rclone = MockRestServiceServer.bindTo(builder).build();
        cloud = new Cloud(new CloudProperties("http://rclone:5572", UTENTE, PASSWORD, "lifetime:", "/cloud", false, 0),
                builder, Optional.empty());
    }

    @Test
    void montaSeNonEMontatoEPoiRisultaDisponibile() {
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andExpect(header("Authorization", HttpHeaders.encodeBasicAuth(UTENTE, PASSWORD, null)
                        .transform("Basic "::concat)))
                .andRespond(withSuccess(NESSUNO, MediaType.TEXT_PLAIN));
        rclone.expect(requestTo("http://rclone:5572/mount/mount"))
                .andExpect(content().json("""
                        {"fs":"lifetime:","mountPoint":"/cloud","mountOpt":{"AllowOther":true,"AllowNonEmpty":true},
                         "vfsOpt":{"CacheMode":3,"UID":1000,"GID":1000,"Umask":2}}"""))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(MONTATO, MediaType.TEXT_PLAIN));

        assertThat(cloud.monta().montato()).isTrue();
        // Lo stato appena letto resta valido qualche secondo: nessuna altra chiamata.
        assertThat(cloud.disponibile()).isTrue();
        rclone.verify();
    }

    @Test
    void smontatoNonDaGliOriginali() {
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(NESSUNO, MediaType.TEXT_PLAIN));

        assertThatThrownBy(cloud::verifica).isInstanceOf(ArchivioNonDisponibile.class);
    }

    @Test
    void smontaSoloSeMontato() {
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(MONTATO, MediaType.TEXT_PLAIN));
        rclone.expect(requestTo("http://rclone:5572/mount/unmount"))
                .andExpect(content().json("{\"mountPoint\":\"/cloud\"}"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(NESSUNO, MediaType.TEXT_PLAIN));

        assertThat(cloud.smonta().montato()).isFalse();
        rclone.verify();
    }

    @Test
    void seRcloneRifiutaIlMontaggioLoDiceChiaro() {
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(NESSUNO, MediaType.TEXT_PLAIN));
        rclone.expect(requestTo("http://rclone:5572/mount/mount"))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR).body("{\"error\":\"fusermount: not found\"}"));
        rclone.expect(requestTo("http://rclone:5572/mount/listmounts"))
                .andRespond(withSuccess(NESSUNO, MediaType.TEXT_PLAIN));

        assertThatThrownBy(cloud::monta).isInstanceOf(RcloneNonRiesce.class).hasMessageContaining("fusermount");
    }

    @Test
    void senzaRcloneLArchivioESempreDisponibile() {
        var locale = new Cloud(new CloudProperties("", null, null, null, null, false, 0), RestClient.builder(), Optional.empty());
        assertThat(locale.disponibile()).isTrue();
        assertThat(locale.stato().gestito()).isFalse();
    }
}
