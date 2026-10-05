package it.fototimeline.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.luoghi.LuoghiService.ElencoLuoghi;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

@RestController
@RequestMapping("/api")
public class LuoghiController {

    private final LuoghiService luoghi;
    private final LavoriImportazione lavori;

    public LuoghiController(LuoghiService luoghi, LavoriImportazione lavori) {
        this.luoghi = luoghi;
        this.lavori = lavori;
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
}
