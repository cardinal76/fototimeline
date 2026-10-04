package it.fototimeline.web;

import org.springframework.boot.web.server.MimeMappings;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ConfigurazioneWeb {

    /** Tomcat non conosce .webmanifest: il manifest della PWA uscirebbe come application/octet-stream. */
    @Bean
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> tipiPwa() {
        return fabbrica -> {
            MimeMappings tipi = new MimeMappings(MimeMappings.DEFAULT);
            tipi.add("webmanifest", "application/manifest+json");
            fabbrica.setMimeMappings(tipi);
        };
    }
}
