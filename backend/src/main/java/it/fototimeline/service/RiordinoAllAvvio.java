package it.fototimeline.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import it.fototimeline.cloud.Cloud;
import it.fototimeline.cloud.CloudProperties;

/** All'avvio porta l'archivio nella forma anno/mese/giorno. */
@Component
class RiordinoAllAvvio implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RiordinoAllAvvio.class);

    private final FotoService service;
    private final ArchivioFile archivio;
    private final Cloud cloud;
    private final CloudProperties cloudProperties;

    RiordinoAllAvvio(FotoService service, ArchivioFile archivio, Cloud cloud, CloudProperties cloudProperties) {
        this.service = service;
        this.archivio = archivio;
        this.cloud = cloud;
        this.cloudProperties = cloudProperties;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        if (cloudProperties.gestito() && cloudProperties.montaAllAvvio()) {
            try {
                cloud.monta();
            } catch (RuntimeException e) {
                log.warn("Montaggio all'avvio non riuscito: {}", e.getMessage());
            }
        }
        if (!archivio.disponibile()) {
            log.info("Archivio smontato: niente riordino finché non viene montato");
            return;
        }
        spostaVecchieMiniature();
        int spostate = service.riordinaArchivio();
        if (spostate > 0) {
            log.info("Archivio riordinato per giorno: {} foto spostate", spostate);
        }
    }

    /** Le prime versioni tenevano le miniature in "miniature/", ora in ".miniature/". */
    private void spostaVecchieMiniature() throws IOException {
        Path vecchie = archivio.radice().resolve("miniature");
        if (!Files.isDirectory(vecchie)) {
            return;
        }
        try (DirectoryStream<Path> file = Files.newDirectoryStream(vecchie, "*.jpg")) {
            for (Path f : file) {
                Path nuova = archivio.cartellaMiniature().resolve(f.getFileName());
                if (!Files.exists(nuova)) {
                    Files.move(f, nuova);
                }
            }
        }
        archivio.pulisciCartelleVuote(vecchie);
    }
}
