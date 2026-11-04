package it.fototimeline.quasiuguali;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.quasiuguali.CalcoloImpronte.StatoCalcolo;
import it.fototimeline.quasiuguali.Confronto.Sensibilita;
import it.fototimeline.quasiuguali.QuasiUguali.Esito;
import it.fototimeline.quasiuguali.QuasiUguali.PaginaGruppi;

/**
 * Le foto quasi uguali. Togliere vale come l'eliminazione normale (chiunque
 * possa usare l'app); "Calcola impronte" è da admin (ConfigurazioneSicurezza).
 */
@RestController
@RequestMapping("/api/quasi-uguali")
public class QuasiUgualiController {

    private final QuasiUguali quasiUguali;
    private final CalcoloImpronte calcolo;

    public QuasiUgualiController(QuasiUguali quasiUguali, CalcoloImpronte calcolo) {
        this.quasiUguali = quasiUguali;
        this.calcolo = calcolo;
    }

    /** {@code sensibilita}: SEVERA (predefinita), NORMALE o LARGA. */
    @GetMapping
    public PaginaGruppi gruppi(@RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "20") int dimensione,
            @RequestParam(defaultValue = "SEVERA") Sensibilita sensibilita) {
        return quasiUguali.gruppi(sensibilita, Math.max(pagina, 0), Math.clamp(dimensione, 1, 200));
    }

    public record Risoluzione(List<String> tieni, List<String> togli) {
    }

    @PostMapping("/risolvi")
    public Esito risolvi(@RequestBody Risoluzione r) {
        return quasiUguali.risolvi(r.tieni(), r.togli());
    }

    public record Ignora(List<String> ids) {
    }

    /** "Non sono doppioni". */
    @PostMapping("/ignora")
    public ResponseEntity<Void> ignora(@RequestBody Ignora r) {
        quasiUguali.ignora(r.ids());
        return ResponseEntity.noContent().build();
    }

    public record Separa(String id, List<String> da) {
    }

    /** "Non è uguale": la foto {@code id} non si propone più con nessuna di {@code da}. */
    @PostMapping("/separa")
    public ResponseEntity<Void> separa(@RequestBody Separa r) {
        quasiUguali.separa(r.id(), r.da());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/calcola")
    public StatoCalcolo statoCalcolo() {
        return calcolo.stato();
    }

    /** "Calcola impronte": 202 e va avanti in sottofondo; 409 se è già in corso. */
    @PostMapping("/calcola")
    public ResponseEntity<StatoCalcolo> calcola() {
        return ResponseEntity.accepted().body(calcolo.avvia());
    }

    @PostMapping("/calcola/annulla")
    public ResponseEntity<Void> annulla() {
        return calcolo.annulla() ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
    }
}
