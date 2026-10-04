package it.fototimeline.backup;

import java.io.IOException;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.backup.BackupMetadati.Copia;

/** Elenco per tutti; "fai ora" solo per gli amministratori (ConfigurazioneSicurezza). */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupMetadati backup;

    public BackupController(BackupMetadati backup) {
        this.backup = backup;
    }

    @GetMapping
    public List<Copia> elenco() throws IOException {
        return backup.elenco();
    }

    @PostMapping
    public Copia esegui() throws IOException {
        return backup.esegui();
    }
}
