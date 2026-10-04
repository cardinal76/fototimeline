package it.fototimeline.cloud;

import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.service.FotoService;

/** Stato del cloud per tutti; montare e smontare solo per gli amministratori (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/cloud")
public class CloudController {

    private static final Logger log = LoggerFactory.getLogger(CloudController.class);

    private final Cloud cloud;
    private final FotoService fotoService;

    public CloudController(Cloud cloud, FotoService fotoService) {
        this.cloud = cloud;
        this.fotoService = fotoService;
    }

    @GetMapping
    public Cloud.Stato stato() {
        return cloud.stato();
    }

    @PostMapping("/monta")
    public Cloud.Stato monta() {
        Cloud.Stato stato = cloud.monta();
        // Le foto rimaste fuori posto mentre era smontato (date cambiate, archivi vecchi).
        CompletableFuture.runAsync(() -> {
            int spostate = fotoService.riordinaArchivio();
            if (spostate > 0) {
                log.info("Dopo il montaggio: {} foto rimesse nella cartella del loro giorno", spostate);
            }
        });
        return stato;
    }

    @PostMapping("/smonta")
    public Cloud.Stato smonta() {
        return cloud.smonta();
    }
}
