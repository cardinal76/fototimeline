package it.fototimeline.utenti;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import it.fototimeline.sicurezza.RegistroUtenti;
import it.fototimeline.sicurezza.Ruolo;
import it.fototimeline.sicurezza.Utente;

/** La pagina Utenti col login acceso e un Keycloak finto ({@link KeycloakFinto}) al posto dell'Admin API. */
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
        "fototimeline.ricordi-telegram.attivo=false",
})
@AutoConfigureMockMvc
class GestioneUtentiTest {

    @TempDir
    static Path archivio;

    @DynamicPropertySource
    static void proprieta(DynamicPropertyRegistry r) {
        r.add("fototimeline.archivio", () -> archivio.toString());
    }

    @TestConfiguration
    static class ConKeycloakFinto {
        @Bean
        @Primary
        KeycloakFinto keycloakFinto() {
            return new KeycloakFinto();
        }
    }

    /** Chi usa la pagina: non è fra gli utenti che cambia (le sue sessioni si chiuderebbero). */
    private static final OidcLoginRequestPostProcessor ADMIN = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "operatore"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-admin"));
    private static final OidcLoginRequestPostProcessor GESTORE = oidcLogin()
            .idToken(t -> t.claim("preferred_username", "gestore"))
            .authorities(new SimpleGrantedAuthority("ROLE_fototimeline-gestore"));

    @Autowired
    MockMvc mvc;

    @Autowired
    KeycloakFinto keycloak;

    @Autowired
    RegistroUtenti registro;

    @Autowired
    MongoTemplate mongo;

    private String capo;

    @BeforeEach
    void unAmministratore() {
        keycloak.svuota();
        mongo.remove(new Query(), Utente.class);
        capo = keycloak.aggiungi("capo", Ruolo.AMMINISTRATORE, true);
    }

    private ResultActions crea(String json) throws Exception {
        return mvc.perform(post("/api/gestione-utenti").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private ResultActions ruolo(String id, String ruolo) throws Exception {
        return mvc.perform(put("/api/gestione-utenti/" + id + "/ruolo").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"ruolo\":\"" + ruolo + "\"}"));
    }

    private ResultActions attivo(String id, boolean attivo) throws Exception {
        return mvc.perform(put("/api/gestione-utenti/" + id + "/attivo").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"attivo\":" + attivo + "}"));
    }

    @Test
    void soloLAmministratore() throws Exception {
        mvc.perform(get("/api/gestione-utenti")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/gestione-utenti").with(GESTORE)).andExpect(status().isForbidden());
        mvc.perform(post("/api/gestione-utenti").with(GESTORE).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"x\",\"ruolo\":\"AMMINISTRATORE\",\"password\":\"lunga-abbastanza\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/gestione-utenti/" + capo).with(GESTORE).with(csrf())).andExpect(status().isForbidden());
        // Senza CSRF nemmeno l'amministratore.
        mvc.perform(delete("/api/gestione-utenti/" + capo).with(ADMIN)).andExpect(status().isForbidden());
        assertThat(keycloak.utenti).hasSize(1);
    }

    @Test
    void creaUnUtenteConPasswordTemporaneaEIlSuoRuolo() throws Exception {
        crea("""
                {"username":" Anna.Rossi ","nome":"Anna","cognome":"Rossi","email":"anna@example.org",
                 "ruolo":"GESTORE","password":"lunga-abbastanza"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.username").value("anna.rossi"))
                .andExpect(jsonPath("$.ruolo").value("GESTORE"))
                .andExpect(jsonPath("$.attivo").value(true))
                .andExpect(jsonPath("$.password").doesNotExist());
        var anna = keycloak.perUsername("anna.rossi").orElseThrow();
        assertThat(anna.ruolo()).isEqualTo(Ruolo.GESTORE);
        assertThat(keycloak.password.get(anna.id())).isEqualTo("lunga-abbastanza (temporanea)");
        // Il nome per "Caricata da" c'è già, prima del primo accesso.
        assertThat(registro.nomi()).containsEntry("anna.rossi", "Anna Rossi");

        mvc.perform(get("/api/gestione-utenti").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.disponibile").value(true))
                .andExpect(jsonPath("$.utenti[*].username").value(Matchers.contains("anna.rossi", "capo")))
                .andExpect(jsonPath("$.utenti[0].ultimoAccesso").doesNotExist());

        // Lo stesso username: Keycloak dice 409.
        crea("{\"username\":\"anna.rossi\",\"ruolo\":\"UTILIZZATORE\",\"password\":\"lunga-abbastanza\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("username")));
    }

    @Test
    void datiNonValidi() throws Exception {
        crea("{\"username\":\"anna\",\"ruolo\":\"GESTORE\",\"password\":\"corta\"}").andExpect(status().isBadRequest());
        crea("{\"username\":\"a b\",\"ruolo\":\"GESTORE\",\"password\":\"lunga-abbastanza\"}").andExpect(status().isBadRequest());
        crea("{\"username\":\"anna\",\"password\":\"lunga-abbastanza\"}").andExpect(status().isBadRequest());
        crea("{\"username\":\"anna\",\"ruolo\":\"GESTORE\",\"invito\":true}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("email")));
        crea("{\"username\":\"anna\",\"ruolo\":\"CAPO\",\"password\":\"lunga-abbastanza\"}").andExpect(status().isBadRequest());
        assertThat(keycloak.utenti).hasSize(1);
    }

    @Test
    void conLInvitoKeycloakMandaLEmailEUnInvitoNonPartitoNonLasciaUtentiAMeta() throws Exception {
        crea("{\"username\":\"bruno\",\"email\":\"bruno@example.org\",\"ruolo\":\"UTILIZZATORE\",\"invito\":true}")
                .andExpect(status().isCreated());
        String bruno = keycloak.perUsername("bruno").orElseThrow().id();
        assertThat(keycloak.invitati).containsExactly(bruno);
        assertThat(keycloak.password).doesNotContainKey(bruno);

        keycloak.invitoRotto = true;
        crea("{\"username\":\"carla\",\"email\":\"carla@example.org\",\"ruolo\":\"UTILIZZATORE\",\"invito\":true}")
                .andExpect(status().isBadGateway());
        assertThat(keycloak.perUsername("carla")).isEmpty();
    }

    @Test
    void cambiareRuoloDisattivareEReimpostareChiudonoLeSessioni() throws Exception {
        String anna = keycloak.aggiungi("anna", Ruolo.GESTORE, true);
        ruolo(anna, "UTILIZZATORE").andExpect(status().isOk()).andExpect(jsonPath("$.ruolo").value("UTILIZZATORE"));
        assertThat(keycloak.sessioniChiuse).containsExactly(anna);
        assertThat(registro.sessioniChiuseIl("anna")).isNotNull();
        assertThat(registro.trova("anna").orElseThrow().ruolo()).isEqualTo(Ruolo.UTILIZZATORE);

        keycloak.sessioniChiuse.clear();
        attivo(anna, false).andExpect(status().isOk()).andExpect(jsonPath("$.attivo").value(false));
        assertThat(keycloak.sessioniChiuse).containsExactly(anna);
        attivo(anna, true).andExpect(status().isOk()).andExpect(jsonPath("$.attivo").value(true));

        keycloak.sessioniChiuse.clear();
        mvc.perform(post("/api/gestione-utenti/" + anna + "/password").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"un-altra-lunga\"}"))
                .andExpect(status().isNoContent());
        assertThat(keycloak.password.get(anna)).isEqualTo("un-altra-lunga (temporanea)");
        assertThat(keycloak.sessioniChiuse).containsExactly(anna);
        // Senza email l'invito non si può mandare.
        mvc.perform(post("/api/gestione-utenti/" + anna + "/password").with(ADMIN).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"invito\":true}"))
                .andExpect(status().isBadRequest());

        ruolo("non-c-e", "GESTORE").andExpect(status().isNotFound());
    }

    @Test
    void lUltimoAmministratoreNonPerdeIlRuoloNonSiDisattivaENonSiElimina() throws Exception {
        ruolo(capo, "GESTORE").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("ultimo amministratore")));
        attivo(capo, false).andExpect(status().isConflict());
        mvc.perform(delete("/api/gestione-utenti/" + capo).with(ADMIN).with(csrf())).andExpect(status().isConflict());
        // Un amministratore disattivato non conta.
        String marco = keycloak.aggiungi("marco", Ruolo.AMMINISTRATORE, false);
        ruolo(capo, "GESTORE").andExpect(status().isConflict());
        // Riattivato sì: adesso capo può smettere di esserlo.
        attivo(marco, true).andExpect(status().isOk());
        ruolo(capo, "GESTORE").andExpect(status().isOk());
        // E marco è rimasto l'ultimo.
        attivo(marco, false).andExpect(status().isConflict());
        ruolo(marco, "UTILIZZATORE").andExpect(status().isConflict());
        assertThat(keycloak.utenti.get(marco).ruolo()).isEqualTo(Ruolo.AMMINISTRATORE);
        // Ridargli il ruolo che ha già va bene.
        ruolo(marco, "AMMINISTRATORE").andExpect(status().isOk());
    }

    @Test
    void eliminatoSparisceDaKeycloakMaIlNomeRestaPerCaricataDa() throws Exception {
        crea("{\"username\":\"zia\",\"nome\":\"Zia Lina\",\"ruolo\":\"GESTORE\",\"password\":\"lunga-abbastanza\"}")
                .andExpect(status().isCreated());
        String zia = keycloak.perUsername("zia").orElseThrow().id();
        mvc.perform(delete("/api/gestione-utenti/" + zia).with(ADMIN).with(csrf())).andExpect(status().isNoContent());
        assertThat(keycloak.utenti).doesNotContainKey(zia);
        assertThat(registro.nomi()).containsEntry("zia", "Zia Lina");
        assertThat(registro.trova("zia").orElseThrow().eliminatoIl()).isNotNull();
        assertThat(registro.sessioniChiuseIl("zia")).isNotNull();
        // Fra i proprietari possibili di un telefono non c'è più.
        mvc.perform(get("/api/utenti").with(ADMIN)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.username == 'zia')]").isEmpty());
        mvc.perform(delete("/api/gestione-utenti/" + zia).with(ADMIN).with(csrf())).andExpect(status().isNotFound());
    }
}
