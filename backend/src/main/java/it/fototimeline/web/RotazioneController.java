package it.fototimeline.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.Rotazioni;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * "Ruota a sinistra / a destra" dal visore e dalla selezione. La rotazione
 * si salva nella scheda, il file resta com'è (vedi {@link Rotazioni}).
 * Come le altre modifiche basta il login.
 */
@RestController
@RequestMapping("/api/foto")
public class RotazioneController {

    private final Rotazioni rotazioni;

    public RotazioneController(Rotazioni rotazioni) {
        this.rotazioni = rotazioni;
    }

    /** {@code gradi}: 90 a destra, -90 (o 270) a sinistra, 180; si somma alla rotazione che c'è. */
    public record RichiestaRotazione(int gradi) {
    }

    public record RichiestaRotazioneMultipla(@NotNull List<String> ids, int gradi) {
    }

    public record EsitoRotazione(long modificate) {
    }

    /** La foto girata (larghezza, altezza e rotazione nuove); 404 se non c'è o è nel cestino. */
    @PostMapping("/{id}/ruota")
    public ResponseEntity<Foto> ruota(@PathVariable String id, @RequestBody RichiestaRotazione r) {
        return ResponseEntity.of(rotazioni.ruota(id, r.gradi()));
    }

    /** "Ruota" sulla selezione: quante ne ha girate (quelle nel cestino no). */
    @PostMapping("/ruota")
    public EsitoRotazione ruotaMultiple(@Valid @RequestBody RichiestaRotazioneMultipla r) {
        return new EsitoRotazione(rotazioni.ruota(r.ids(), r.gradi()));
    }
}
