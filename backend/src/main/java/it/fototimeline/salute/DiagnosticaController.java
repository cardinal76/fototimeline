package it.fototimeline.salute;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.sun.management.HotSpotDiagnosticMXBean;

import it.fototimeline.service.FileInLavorazione;
import it.fototimeline.service.LavoriImportazione;

/**
 * Per capire cosa fa l'app quando un lavoro sembra fermo, solo per gli
 * amministratori (ConfigurazioneSicurezza): {@code GET /api/diagnostica/thread}
 * dà in testo lo stato del lavoro di importazione (i file in lavorazione, con
 * fase e tempo) e il dump di tutti i thread della JVM, virtuali compresi.
 * Nel container non c'è jcmd e {@code kill -3} non elenca i thread virtuali:
 * basta un curl (DEPLOY.md).
 */
@RestController
@RequestMapping("/api/diagnostica")
public class DiagnosticaController {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticaController.class);

    private final LavoriImportazione lavori;

    public DiagnosticaController(LavoriImportazione lavori) {
        this.lavori = lavori;
    }

    @GetMapping(value = "/thread", produces = "text/plain;charset=UTF-8")
    public String thread() {
        StringBuilder testo = new StringBuilder("FotoTimeline, dump dei thread del ").append(Instant.now()).append("\n\n");
        lavori.corrente().ifPresentOrElse(l -> {
            testo.append("Lavoro di importazione: ").append(l.origine()).append(' ').append(l.stato())
                    .append(" su ").append(l.cartella()).append(", ").append(l.fatte()).append(" / ").append(l.trovate())
                    .append(", ").append(l.errori()).append(" errori")
                    .append(l.annullamento() ? ", annullato" : "").append(l.inAttesa() ? ", in attesa" : "")
                    .append(l.ricerca() ? ", cerca i file in " + l.cercaIn() : "").append('\n');
            for (FileInLavorazione.Vista f : l.inLavorazione()) {
                testo.append("  in lavorazione: ").append(f.file()).append(" · ").append(f.fase()).append(" · ")
                        .append(f.secondi()).append(" s (").append(f.secondiFase()).append(" s nella fase)\n");
            }
        }, () -> testo.append("Nessun lavoro di importazione\n"));
        testo.append('\n');
        try {
            testo.append(dumpCompleto());
        } catch (IOException | RuntimeException e) {
            log.warn("Dump dei thread con HotSpotDiagnosticMXBean non riuscito, uso Thread.getAllStackTraces: {}", e.toString());
            testo.append(dumpSemplice());
        }
        return testo.toString();
    }

    /** Tutti i thread, virtuali compresi (JDK 21: HotSpotDiagnosticMXBean.dumpThreads). */
    static String dumpCompleto() throws IOException {
        Path cartella = Files.createTempDirectory("fototimeline-thread-");
        // dumpThreads vuole un percorso assoluto di un file che non c'è ancora.
        Path file = cartella.resolve("thread.txt");
        try {
            ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class)
                    .dumpThreads(file.toAbsolutePath().toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.TEXT_PLAIN);
            return Files.readString(file);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(cartella);
        }
    }

    /** Di riserva: i thread di piattaforma, con stato e pila. */
    static String dumpSemplice() {
        StringBuilder testo = new StringBuilder();
        for (Map.Entry<Thread, StackTraceElement[]> voce : Thread.getAllStackTraces().entrySet()) {
            Thread t = voce.getKey();
            testo.append('"').append(t.getName()).append("\" #").append(t.threadId())
                    .append(t.isDaemon() ? " daemon" : "").append(t.isVirtual() ? " virtual" : "")
                    .append(' ').append(t.getState()).append('\n');
            for (StackTraceElement e : voce.getValue()) {
                testo.append("\tat ").append(e).append('\n');
            }
            testo.append('\n');
        }
        return testo.toString();
    }
}
