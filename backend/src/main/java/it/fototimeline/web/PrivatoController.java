package it.fototimeline.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.cestino.Cestino;
import it.fototimeline.cestino.Cestino.PaginaCestino;

/**
 * L'album "Privato" nel cestino. Le foto private si mettono e si tolgono con
 * {@code POST /api/foto/multiple} (METTI_NEL_PRIVATO, TOGLI_DAL_PRIVATO) e si
 * guardano con il filtro {@code privato=true} della timeline; qui solo le
 * private di chi chiede finite nel cestino: la pagina Cestino
 * dell'amministratore non le mostra (sono di un altro), e senza questo un
 * gestore non potrebbe riaverle. Da gestore in su (ConfigurazioneSicurezza).
 */
@RestController
@RequestMapping("/api/privato")
public class PrivatoController {

    private final Cestino cestino;

    public PrivatoController(Cestino cestino) {
        this.cestino = cestino;
    }

    @GetMapping("/cestino")
    public PaginaCestino cestino(@RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "60") int dimensione) {
        return cestino.elencoPrivate(Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }

    public record Scelte(List<String> ids) {
    }

    public record Esito(long fatte) {
    }

    /** Rimette nell'archivio (sempre private) le sue foto scelte; col cloud smontato 503. */
    @PostMapping("/cestino/ripristina")
    public Esito ripristina(@RequestBody Scelte s) {
        return new Esito(cestino.ripristinaPrivate(s.ids()));
    }
}
