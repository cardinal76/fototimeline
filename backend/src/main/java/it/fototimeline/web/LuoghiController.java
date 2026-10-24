package it.fototimeline.web;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.dominio.Foto;
import it.fototimeline.luoghi.Luogo;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.luoghi.LuoghiService.ElencoLuoghi;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

@RestController
@RequestMapping("/api")
public class LuoghiController {

    private final LuoghiService luoghi;
    private final LavoriImportazione lavori;
    private final FotoService foto;

    public LuoghiController(LuoghiService luoghi, LavoriImportazione lavori, FotoService foto) {
        this.luoghi = luoghi;
        this.lavori = lavori;
        this.foto = foto;
    }

    /** Nazioni, regioni e luoghi con quante foto: l'elenco del filtro "Luogo". */
    @GetMapping("/luoghi")
    public ElencoLuoghi elenco() {
        return luoghi.elenco();
    }

    /**
     * "Calcola luoghi" (solo admin, ConfigurazioneSicurezza): in sottofondo,
     * con lo stato di GET /api/importazioni/corrente. {@code tutte=true} rifà
     * anche le foto che il luogo ce l'hanno già.
     */
    @PostMapping("/archivio/luoghi")
    public ResponseEntity<StatoLavoro> calcola(@RequestParam(defaultValue = "false") boolean tutte) {
        return ResponseEntity.accepted().body(lavori.avviaLuoghi(tutte));
    }

    /** Città, province, regioni e nazioni per nome, per scegliere il luogo a mano: "Manch" → Manchester. */
    @GetMapping("/luoghi/cerca")
    public List<Luogo> cerca(@RequestParam(defaultValue = "") String q) {
        return luoghi.cerca(q);
    }

    /** @param chiave di un luogo di {@code /api/luoghi/cerca}; null o vuota = nessun luogo */
    public record SceltaLuogo(String chiave) {
    }

    /**
     * Il luogo scelto a mano (MANUALE: "Calcola luoghi" non lo tocca), anche
     * "nessun luogo"; risponde con la foto aggiornata, 404 se non c'è o è nel
     * cestino, 400 se la chiave non è un luogo.
     */
    @PutMapping("/foto/{id}/luogo")
    public ResponseEntity<Foto> imposta(@PathVariable String id, @RequestBody SceltaLuogo scelta) {
        return aggiorna(id, ids -> luoghi.imposta(ids, scelta == null ? null : scelta.chiave()));
    }

    /** "Usa il GPS": torna al luogo automatico, ricalcolato subito dalle coordinate. */
    @DeleteMapping("/foto/{id}/luogo")
    public ResponseEntity<Foto> usaGps(@PathVariable String id) {
        return aggiorna(id, luoghi::usaGps);
    }

    private ResponseEntity<Foto> aggiorna(String id, Consumer<List<String>> cambio) {
        Optional<Foto> trovata = foto.trova(id).filter(f -> !f.nelCestino());
        if (trovata.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        cambio.accept(List.of(id));
        return ResponseEntity.of(foto.trova(id));
    }
}
