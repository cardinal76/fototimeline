package it.fototimeline.eventi;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
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

import it.fototimeline.eventi.EventiService.ElencoEventi;
import it.fototimeline.eventi.EventiService.VoceEvento;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;

/**
 * Gli eventi, dietro login come il resto (mai nei link condivisi). Le
 * modifiche a mano può farle chiunque usi l'app, come gli album; "Calcola
 * eventi" ({@code POST /api/archivio/eventi}) solo l'admin
 * (ConfigurazioneSicurezza).
 */
@RestController
@RequestMapping("/api")
public class EventiController {

    private final EventiService eventi;
    private final LavoriImportazione lavori;

    public EventiController(EventiService eventi, LavoriImportazione lavori) {
        this.eventi = eventi;
        this.lavori = lavori;
    }

    public record Ids(List<String> ids) {
    }

    public record Nuovo(String nome, List<String> ids) {
    }

    /** {@code nome}: null lascia com'è, vuoto torna al nome automatico; {@code nascosto}: null lascia com'è. */
    public record ModificaEvento(String nome, Boolean nascosto) {
    }

    public record Divisione(String foto) {
    }

    @GetMapping("/eventi")
    public ElencoEventi elenco(@RequestParam(defaultValue = "false") boolean nascosti) {
        return eventi.elenco(nascosti);
    }

    /** I nomi degli eventi (non nascosti) tra questi id: per "Accadde oggi". */
    @GetMapping("/eventi/nomi")
    public Map<String, String> nomi(@RequestParam(defaultValue = "") List<String> ids) {
        return eventi.nomi(ids.stream().filter(s -> !s.isBlank()).limit(200).toList());
    }

    @GetMapping("/eventi/{id}")
    public ResponseEntity<VoceEvento> evento(@PathVariable String id) {
        return ResponseEntity.of(eventi.evento(id));
    }

    @PostMapping("/eventi")
    public ResponseEntity<VoceEvento> crea(@RequestBody Nuovo nuovo) {
        return ResponseEntity.status(HttpStatus.CREATED).body(eventi.crea(nuovo.nome(), nuovo.ids()));
    }

    @PutMapping("/eventi/{id}")
    public VoceEvento modifica(@PathVariable String id, @RequestBody ModificaEvento modifica) {
        VoceEvento e = null;
        if (modifica.nome() != null) {
            e = eventi.rinomina(id, modifica.nome());
        }
        if (modifica.nascosto() != null) {
            e = eventi.nascondi(id, modifica.nascosto());
        }
        return e != null ? e : eventi.evento(id).orElseThrow(() -> new IllegalArgumentException("Evento sconosciuto"));
    }

    /** "Torna automatico": l'evento si scioglie e le sue foto si raggruppano di nuovo da sole. */
    @DeleteMapping("/eventi/{id}")
    public ResponseEntity<Void> sciogli(@PathVariable String id) {
        return eventi.sciogli(id) ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    @PostMapping("/eventi/unisci")
    public VoceEvento unisci(@RequestBody Ids ids) {
        return eventi.unisci(ids.ids());
    }

    @PostMapping("/eventi/{id}/dividi")
    public List<VoceEvento> dividi(@PathVariable String id, @RequestBody Divisione divisione) {
        return eventi.dividi(id, divisione.foto());
    }

    @PostMapping("/eventi/{id}/foto")
    public Map<String, Long> aggiungi(@PathVariable String id, @RequestBody Ids foto) {
        return Map.of("modificate", eventi.aggiungi(id, foto.ids()));
    }

    /** Le foto escono dal loro evento e ci restano fuori. */
    @PostMapping("/eventi/togli")
    public Map<String, Long> togli(@RequestBody Ids foto) {
        return Map.of("modificate", eventi.togli(foto.ids()));
    }

    /**
     * "Calcola eventi" (admin): in sottofondo, con lo stato di
     * GET /api/importazioni/corrente; riprende dove si era fermato, salvo
     * {@code daCapo=true}.
     */
    @PostMapping("/archivio/eventi")
    public ResponseEntity<StatoLavoro> calcola(@RequestParam(defaultValue = "false") boolean daCapo) {
        return ResponseEntity.accepted().body(lavori.avviaEventi(daCapo));
    }
}
