package it.fototimeline.sicurezza;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import org.springframework.http.MediaType;
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
        // Token finto: non deve uscire da /api/salute. Nessun test qui manda davvero.
        "fototimeline.telegram.token=123456:token-segreto-di-prova",
        "fototimeline.telegram.chat=42",
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
        mvc.perform(get("/salute")).andExpect(status().isOk()).andExpect(content().string("ok"));
        mvc.perform(get("/api/salute")).andExpect(status().isUnauthorized());
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
        mvc.perform(post("/api/archivio/indicizza").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/video/converti").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/video/annulla").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(get("/api/backup").with(oidcLogin())).andExpect(status().isOk());
        // La sincronizzazione del telefono non si vede nemmeno.
        mvc.perform(get("/api/telefono").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(put("/api/telefono").with(oidcLogin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attiva\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/telefono/sincronizza").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        // Nemmeno la pagina "Salute" e la prova di Telegram.
        mvc.perform(get("/api/salute").with(oidcLogin())).andExpect(status().isForbidden());
        mvc.perform(post("/api/salute/prova").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void lAmministratoreVedeLaSaluteMaNonIlToken() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        String corpo = mvc.perform(get("/api/salute").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stato").isNotEmpty())
                .andExpect(jsonPath("$.controllatoIl").isNotEmpty())
                .andExpect(jsonPath("$.avvisiTelegram").value(true))
                .andExpect(jsonPath("$.voci[?(@.chiave == 'archivio')].stato").value("OK"))
                .andExpect(jsonPath("$.voci[?(@.chiave == 'backup')]").exists())
                .andReturn().getResponse().getContentAsString();
        assertThat(corpo).doesNotContain("token-segreto").doesNotContain("123456");
        // Senza token CSRF la prova non parte (e qui non deve partire davvero).
        mvc.perform(post("/api/salute/prova").with(admin)).andExpect(status().isForbidden());
    }

    @Test
    void leQuasiUgualiLeRisolveChiunqueMaLeImprontePartonoSoloDallAmministratore() throws Exception {
        mvc.perform(get("/api/quasi-uguali").with(oidcLogin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.totale").value(0));
        mvc.perform(get("/api/quasi-uguali/calcola").with(oidcLogin())).andExpect(status().isOk())
                .andExpect(jsonPath("$.senzaImpronta").value(0));
        // Come l'eliminazione: basta essere del realm (qui le foto non ci sono, quindi 0).
        mvc.perform(post("/api/quasi-uguali/risolvi").with(oidcLogin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[\"a\"],\"togli\":[\"b\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eliminate").value(0));
        mvc.perform(post("/api/quasi-uguali/risolvi").with(oidcLogin()).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"tieni\":[],\"togli\":[\"b\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/quasi-uguali/calcola").with(oidcLogin()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/calcola/annulla").with(oidcLogin()).with(csrf()))
                .andExpect(status().isForbidden());

        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/quasi-uguali/calcola").with(admin).with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.stato").value("IN_CORSO"));
    }

    @Test
    void lAmministratoreVedeLaSincronizzazioneDelTelefono() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        // Qui non c'è rclone: si vede, ma non si attiva e non parte.
        mvc.perform(get("/api/telefono").with(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(false))
                .andExpect(jsonPath("$.motivo").isNotEmpty())
                .andExpect(jsonPath("$.inCorso").value(false))
                .andExpect(jsonPath("$.intervalloOre").value(6));
        mvc.perform(put("/api/telefono").with(admin)
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":12}"))
                .andExpect(status().isForbidden());
        mvc.perform(put("/api/telefono").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("ore")));
        mvc.perform(put("/api/telefono").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attiva\":true}"))
                .andExpect(status().isBadRequest());
        mvc.perform(put("/api/telefono").with(admin).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"intervalloOre\":12,\"giorniPrimaDiCancellare\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intervalloOre").value(12))
                .andExpect(jsonPath("$.giorniPrimaDiCancellare").value(0));
        mvc.perform(post("/api/telefono/sincronizza").with(admin).with(csrf())).andExpect(status().isConflict());
    }

    @Test
    void lAmministratoreMontaMaServeIlTokenCsrf() throws Exception {
        var admin = oidcLogin().authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
        mvc.perform(get("/api/io").with(admin)).andExpect(jsonPath("$.admin").value(true));
        mvc.perform(post("/api/cloud/smonta").with(admin)).andExpect(status().isForbidden());
        // Passa la sicurezza; qui non c'è rclone, quindi 409.
        mvc.perform(post("/api/cloud/smonta").with(admin).with(csrf())).andExpect(status().isConflict());
        // L'archivio qui è un disco, sempre disponibile: l'indicizzazione parte.
        mvc.perform(post("/api/archivio/indicizza").with(admin)).andExpect(status().isForbidden());
        mvc.perform(post("/api/archivio/indicizza").with(admin).with(csrf())).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.origine").value("INDICIZZAZIONE"));
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
