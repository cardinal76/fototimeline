package it.fototimeline.cestino;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.cestino.Cestino.PaginaCestino;

/**
 * La pagina "Cestino". Stessi permessi dell'eliminazione ({@code DELETE
 * /api/foto/{id}}): basta poter usare l'app (ConfigurazioneSicurezza). Col
 * cloud smontato ripristino ed eliminazione rispondono 503.
 */
@RestController
@RequestMapping("/api/cestino")
public class CestinoController {

    private final Cestino cestino;

    public CestinoController(Cestino cestino) {
        this.cestino = cestino;
    }

    @GetMapping
    public PaginaCestino elenco(@RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "60") int dimensione) {
        return cestino.elenco(Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }

    public record Scelte(List<String> ids) {
    }

    public record Esito(long fatte) {
    }

    @PostMapping("/ripristina")
    public Esito ripristina(@RequestBody Scelte s) {
        return new Esito(cestino.ripristina(s.ids()));
    }

    /** "Elimina per sempre": file compresi, solo foto del cestino. */
    @PostMapping("/elimina")
    public Esito eliminaPerSempre(@RequestBody Scelte s) {
        return new Esito(cestino.eliminaPerSempre(s.ids()));
    }

    @PostMapping("/svuota")
    public Esito svuota() {
        return new Esito(cestino.svuota());
    }
}
