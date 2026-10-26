package it.fototimeline.utenti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withCreatedEntity;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withNoContent;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import it.fototimeline.sicurezza.LoginProperties;
import it.fototimeline.sicurezza.Ruolo;

/** Le chiamate all'Admin REST API di Keycloak, contro un server finto: indirizzi, metodi e corpi. */
class AdminKeycloakTest {

    private static final String ADMIN = "https://kc.test/auth/admin/realms/foto";
    private static final String BLOCCO = "?briefRepresentation=true&first=0&max=500";

    private MockRestServiceServer server;
    private AdminKeycloak keycloak;

    @BeforeEach
    void preparaServer() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        keycloak = new AdminKeycloak(new UtentiProperties("https://kc.test/auth/realms/foto/", null, "segreto-del-client"),
                new LoginProperties(true, null, null, null), builder, Optional.empty());
    }

    private void token() {
        server.expect(requestTo("https://kc.test/auth/realms/foto/protocol/openid-connect/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(containsString("grant_type=client_credentials")))
                .andExpect(content().string(containsString("client_id=fototimeline-utenti")))
                .andRespond(withSuccess("{\"access_token\":\"tok\",\"expires_in\":300}", MediaType.APPLICATION_JSON));
    }

    @Test
    void indirizziDalLIssuer() {
        var p = new UtentiProperties("https://kc.test/auth/realms/foto", null, "");
        assertThat(p.realm()).isEqualTo("foto");
        assertThat(p.indirizzoAdmin()).isEqualTo(ADMIN);
        assertThat(p.clientId()).isEqualTo("fototimeline-utenti");
        assertThat(p.toString()).doesNotContain("segreto");
        assertThat(new UtentiProperties("", null, "x").realm()).isNull();
        assertThat(keycloak.nonDisponibile()).isNull();
        var senzaSegreto = new AdminKeycloak(p, new LoginProperties(true, null, null, null), RestClient.builder(),
                Optional.empty());
        assertThat(senzaSegreto.nonDisponibile()).contains("FOTOTIMELINE_UTENTI_CLIENT_SECRET");
        var sulPc = new AdminKeycloak(p, new LoginProperties(false, null, null, null), RestClient.builder(),
                Optional.empty());
        assertThat(sulPc.nonDisponibile()).contains("login è spento");
    }

    @Test
    void lElencoHaIlRuoloPiuAltoDiOgnuno() {
        token();
        server.expect(requestTo(ADMIN + "/roles/fototimeline-utilizzatore/users" + BLOCCO))
                .andExpect(header("Authorization", "Bearer tok"))
                .andRespond(withSuccess("[{\"id\":\"u1\"},{\"id\":\"u2\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/roles/fototimeline-gestore/users" + BLOCCO))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(ADMIN + "/roles/fototimeline-admin/users" + BLOCCO))
                .andRespond(withSuccess("[{\"id\":\"u2\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/users" + BLOCCO))
                .andRespond(withSuccess("""
                        [{"id":"u1","username":"nonna","firstName":"Lina","enabled":true},
                         {"id":"u2","username":"marco","email":"m@example.org","enabled":true},
                         {"id":"u3","username":"ospite","enabled":false}]""", MediaType.APPLICATION_JSON));
        var elenco = keycloak.elenco();
        assertThat(elenco).extracting(UtenteKeycloak::username).containsExactly("nonna", "marco", "ospite");
        assertThat(elenco).extracting(UtenteKeycloak::ruolo).containsExactly(Ruolo.UTILIZZATORE, Ruolo.AMMINISTRATORE, null);
        assertThat(elenco.get(0).nome()).isEqualTo("Lina");
        assertThat(elenco.get(2).attivo()).isFalse();
        server.verify();
    }

    @Test
    void creaEDaIlRuoloTogliendoGliAltriDue() {
        token();
        server.expect(requestTo(ADMIN + "/users")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"username\":\"anna\",\"firstName\":\"Anna\",\"enabled\":true}"))
                .andRespond(withCreatedEntity(URI.create(ADMIN + "/users/id-anna")));
        for (String r : new String[] {"fototimeline-admin", "fototimeline-gestore", "fototimeline-utilizzatore"}) {
            server.expect(requestTo(ADMIN + "/roles/" + r)).andExpect(method(HttpMethod.GET))
                    .andRespond(withSuccess("{\"id\":\"id-" + r + "\",\"name\":\"" + r + "\"}", MediaType.APPLICATION_JSON));
        }
        server.expect(requestTo(ADMIN + "/users/id-anna/role-mappings/realm")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("[{\"id\":\"id-fototimeline-utilizzatore\",\"name\":\"fototimeline-utilizzatore\"},"
                        + "{\"id\":\"x\",\"name\":\"offline_access\"}]", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/users/id-anna/role-mappings/realm")).andExpect(method(HttpMethod.POST))
                .andExpect(content().json("[{\"id\":\"id-fototimeline-gestore\",\"name\":\"fototimeline-gestore\"}]"))
                .andRespond(withNoContent());
        // Gli altri ruoli del realm (offline_access) restano.
        server.expect(requestTo(ADMIN + "/users/id-anna/role-mappings/realm")).andExpect(method(HttpMethod.DELETE))
                .andExpect(content().json("[{\"name\":\"fototimeline-utilizzatore\"}]"))
                .andRespond(withNoContent());
        server.expect(requestTo(ADMIN + "/users/id-anna/reset-password")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"type\":\"password\",\"value\":\"lunga-abbastanza\",\"temporary\":true}"))
                .andRespond(withNoContent());
        server.expect(requestTo(ADMIN + "/users/id-anna")).andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("{\"enabled\":false}"))
                .andRespond(withNoContent());
        server.expect(requestTo(ADMIN + "/users/id-anna/logout")).andExpect(method(HttpMethod.POST))
                .andRespond(withNoContent());
        server.expect(requestTo(ADMIN + "/users/id-anna/execute-actions-email?lifespan=259200"))
                .andExpect(method(HttpMethod.PUT))
                .andExpect(content().json("[\"UPDATE_PASSWORD\"]"))
                .andRespond(withNoContent());
        server.expect(requestTo(ADMIN + "/users/id-anna")).andExpect(method(HttpMethod.DELETE))
                .andRespond(withNoContent());

        assertThat(keycloak.crea("anna", "Anna", null, null)).isEqualTo("id-anna");
        keycloak.impostaRuolo("id-anna", Ruolo.GESTORE);
        keycloak.password("id-anna", "lunga-abbastanza", true);
        keycloak.attiva("id-anna", false);
        keycloak.chiudiSessioni("id-anna");
        keycloak.invito("id-anna");
        keycloak.elimina("id-anna");
        server.verify();
    }

    @Test
    void unRuoloCheNonCeNelRealmLoDice() {
        token();
        server.expect(requestTo(ADMIN + "/roles/fototimeline-admin"))
                .andRespond(withSuccess("{\"id\":\"a\",\"name\":\"fototimeline-admin\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ADMIN + "/roles/fototimeline-gestore")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(ADMIN + "/roles/fototimeline-utilizzatore")).andRespond(withStatus(HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> keycloak.impostaRuolo("id", Ruolo.GESTORE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fototimeline-gestore");
    }

    @Test
    void gliErroriDiconoCosaFareMaiIlSegreto() {
        server.expect(requestTo("https://kc.test/auth/realms/foto/protocol/openid-connect/token"))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"unauthorized_client\",\"error_description\":\"Invalid client secret\"}"));
        token();
        server.expect(requestTo(ADMIN + "/users/x")).andRespond(withStatus(HttpStatus.FORBIDDEN));
        // Il token si tiene: la chiamata dopo non lo richiede.
        server.expect(requestTo(ADMIN + "/users/y")).andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> keycloak.elimina("x"))
                .isInstanceOf(KeycloakNonRiesce.class)
                .hasMessageContaining("Invalid client secret")
                .hasMessageNotContaining("segreto-del-client");
        assertThatThrownBy(() -> keycloak.elimina("x"))
                .isInstanceOf(KeycloakNonRiesce.class)
                .hasMessageContaining("manage-users");
        assertThat(keycloak.trova("y")).isEmpty();
        server.verify();
    }
}
