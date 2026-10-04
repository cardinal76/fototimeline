package it.fototimeline.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Il login acceso, come su server2, senza un Keycloak vero: gli indirizzi non vengono mai chiamati. */
@SpringBootTest(properties = {
        "de.flapdoodle.mongodb.embedded.version=7.0.14",
        "fototimeline.login.attivo=true",
        "server.address=0.0.0.0",
        "spring.security.oauth2.client.registration.keycloak.client-id=fototimeline",
        "spring.security.oauth2.client.registration.keycloak.client-secret=segreto",
        "spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.keycloak.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.keycloak.scope=openid",
        "spring.security.oauth2.client.provider.keycloak.authorization-uri=https://kc.invalid/auth",
        "spring.security.oauth2.client.provider.keycloak.token-uri=https://kc.invalid/token",
        "spring.security.oauth2.client.provider.keycloak.jwk-set-uri=https://kc.invalid/certs",
        "spring.security.oauth2.client.provider.keycloak.user-name-attribute=sub",
})
@AutoConfigureMockMvc
class SicurezzaTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @Autowired
    MockMvc mvc;

    @Test
    void senzaLoginLeApiRispondono401ELePagineMandanoAKeycloak() throws Exception {
        mvc.perform(get("/api/foto")).andExpect(status().isUnauthorized());
        mvc.perform(get("/")).andExpect(status().isFound())
                .andExpect(redirectedUrl("http://localhost/oauth2/authorization/keycloak"));
        mvc.perform(get("/salute")).andExpect(status().isOk());
        // I file della PWA non chiedono il login: 200 se il frontend è compilato in static/, 404 se no
        // (in CI), mai 401 o il redirect a Keycloak.
        for (String pubblico : new String[] {"/manifest.webmanifest", "/sw.js", "/icone/icona-192.png"}) {
            mvc.perform(get(pubblico)).andExpect(r -> assertThat(r.getResponse().getStatus()).as(pubblico).isIn(200, 404));
        }
    }

    @Test
    void unUtenteDelRealmVedeLeFotoMaNonMontaIlCloud() throws Exception {
        mvc.perform(get("/api/foto").with(oidcLogin())).andExpect(status().isOk());
        mvc.perform(get("/api/io").with(oidcLogin()))
                .andExpect(jsonPath("$.login").value(true))
                .andExpect(jsonPath("$.admin").value(false));
        mvc.perform(post("/api/cloud/smonta").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/backup").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/backup").with(oidcLogin())).andExpect(status().isOk());
    }

    @Test
    void lAmministratoreMontaMaServeIlTokenCsrf() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(get("/api/io").with(admin)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(post("/api/cloud/smonta").with(admin)).andExpect(status().isForbidden());
        // Passa la sicurezza; qui non c'è rclone, quindi 409.
        mvc.perform(post("/api/cloud/smonta").with(admin).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void ruoliDiRealmDallAccessToken() {
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"realm_access\":{\"roles\":[\"fototimeline-admin\",\"offline_access\"]}}".getBytes());
        assertThat(RuoliKeycloak.ruoliDiRealm("h." + payload + ".firma"))
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("ROLE_fototimeline-admin", "ROLE_offline_access");
        assertThat(RuoliKeycloak.ruoliDiRealm("non-un-jwt")).isEmpty();
    }

    @Test
    void senzaLoginSoloSuQuestoPc() {
        ConfigurazioneSicurezza.controllaSoloLocale("127.0.0.1");
        assertThatThrownBy(() -> ConfigurazioneSicurezza.controllaSoloLocale("0.0.0.0"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ConfigurazioneSicurezza.controllaSoloLocale(""))
                .isInstanceOf(IllegalStateException.class);
    }
}
