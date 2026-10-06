package it.fototimeline.contenuto;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.contenuto.IndiceContenuto.StatoContenuto;
import it.fototimeline.contenuto.IndiceContenuto.StatoLavoro;
import it.fototimeline.service.FiltroFoto;
import it.fototimeline.service.Risultati.PaginaFoto;

/**
 * Ricerca per contenuto. Lo stato lo legge chiunque (il frontend nasconde
 * l'interruttore se la funzione è spenta); avviare e annullare
 * "Indicizza contenuto" è da admin (ConfigurazioneSicurezza).
 */
@RestController
public class ContenutoController {

    private final IndiceContenuto indice;

    public ContenutoController(IndiceContenuto indice) {
        this.indice = indice;
    }

    @GetMapping("/api/contenuto")
    public StatoContenuto stato() {
        return indice.stato();
    }

    /** 202 e il lavoro va avanti in sottofondo; 409 se è già in corso o la funzione è spenta. */
    @PostMapping("/api/contenuto/indicizza")
    public ResponseEntity<StatoLavoro> indicizza(@RequestParam(defaultValue = "false") boolean daCapo) {
        return ResponseEntity.accepted().body(indice.avvia(daCapo));
    }

    @PostMapping("/api/contenuto/annulla")
    public ResponseEntity<Void> annulla() {
        return indice.annulla() ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
    }

    /** Le foto più simili a {@code q}, dalla più simile, con gli altri filtri della timeline. */
    @GetMapping("/api/foto/cerca-contenuto")
    public PaginaFoto cerca(
            @RequestParam String q,
            @RequestParam(required = false) String tag,
            @RequestParam(required = false) String album,
            @RequestParam(required = false) Boolean preferite,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dal,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate al,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "60") int dimensione) {
        return indice.cerca(q, new FiltroFoto(null, tag, album, preferite, dal, al),
                Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }
}
