package it.fototimeline.volti;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import it.fototimeline.volti.Persone.ElencoPersone;
import it.fototimeline.volti.Persone.NomeGiaUsato;
import it.fototimeline.volti.Persone.PersonaSimile;
import it.fototimeline.volti.Persone.Tipo;
import it.fototimeline.volti.Persone.VocePersona;
import it.fototimeline.volti.Persone.VoceVolto;
import it.fototimeline.volti.Persone.VoltoInFoto;
import it.fototimeline.volti.RiconoscimentoVolti.StatoLavoro;
import it.fototimeline.volti.RiconoscimentoVolti.StatoVolti;

/**
 * Volti e persone. Vedere e nominare le persone lo può chiunque usi l'app
 * (come i tag: è la famiglia che riconosce la famiglia); avviare e annullare
 * "Riconosci volti" è da admin (ConfigurazioneSicurezza). Niente di questo è
 * sotto {@code /api/condivise}: chi ha un link non vede nomi né volti.
 * Con la funzione spenta tutto risponde 409, salvo lo stato.
 */
@RestController
public class VoltiController {

    public record Nome(String nome) {
    }

    public record Nascosta(boolean nascosta) {
    }

    public record Unione(String in, List<String> da) {
    }

    public record Assegnazione(String persona) {
    }

    public record Copertina(String volto) {
    }

    public record Confermate(int confermate) {
    }

    private final RiconoscimentoVolti riconoscimento;
    private final Persone persone;
    private final RitaglioVolto ritaglio;

    public VoltiController(RiconoscimentoVolti riconoscimento, Persone persone, RitaglioVolto ritaglio) {
        this.riconoscimento = riconoscimento;
        this.persone = persone;
        this.ritaglio = ritaglio;
    }

    // ------------------------------------------------------------ il lavoro

    @GetMapping("/api/volti")
    public StatoVolti stato() {
        return riconoscimento.stato();
    }

    /** 202 e il lavoro va avanti in sottofondo; 409 se è già in corso o la funzione è spenta. */
    @PostMapping("/api/volti/riconosci")
    public ResponseEntity<StatoLavoro> riconosci(@RequestParam(defaultValue = "false") boolean ricostruisci) {
        return ResponseEntity.accepted().body(riconoscimento.avvia(ricostruisci));
    }

    @PostMapping("/api/volti/annulla")
    public ResponseEntity<Void> annulla() {
        return riconoscimento.annulla() ? ResponseEntity.accepted().build() : ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ i volti

    /** I volti di una foto con le loro persone, per il visore. */
    @GetMapping("/api/foto/{id}/volti")
    public List<VoltoInFoto> dellaFoto(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        return persone.dellaFoto(id);
    }

    /** Il volto ritagliato dalla miniatura (160×160). */
    @GetMapping(value = "/api/volti/{id}/ritaglio", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> ritaglio(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .contentType(MediaType.IMAGE_JPEG)
                .body(ritaglio.jpeg(persone.volto(id)));
    }

    /** "Non è lei/lui": il volto esce dalla sua persona. */
    @PostMapping("/api/volti/{id}/togli")
    public ResponseEntity<Void> togli(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        persone.togliVolto(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/volti/{id}/assegna")
    public ResponseEntity<Void> assegna(@PathVariable String id, @RequestBody Assegnazione a) {
        riconoscimento.richiediAttiva();
        persone.assegnaVolto(id, a.persona());
        return ResponseEntity.noContent().build();
    }

    /** "Chi è?" su un volto: una persona che c'è già, il nome al suo gruppo o una persona nuova. */
    @PostMapping("/api/volti/{id}/nome")
    public VocePersona nominaVolto(@PathVariable String id, @RequestBody Nome n) {
        riconoscimento.richiediAttiva();
        return persone.nominaVolto(id, n.nome());
    }

    @PostMapping("/api/volti/{id}/rifiuta")
    public ResponseEntity<Void> rifiuta(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        persone.rifiutaProposta(id);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------ le persone

    @GetMapping("/api/persone")
    public ElencoPersone elenco(@RequestParam(defaultValue = "NOMINATE") Tipo tipo,
            @RequestParam(defaultValue = "500") int limite) {
        riconoscimento.richiediAttiva();
        return persone.elenco(tipo, Math.clamp(limite, 1, 2000));
    }

    @GetMapping("/api/persone/{id}")
    public VocePersona persona(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        return persone.persona(id);
    }

    /** Dà o cambia il nome (vuoto: torna un gruppo senza nome); 409 se un'altra ha già quel nome. */
    @PutMapping("/api/persone/{id}")
    public VocePersona nomina(@PathVariable String id, @RequestBody Nome n) {
        riconoscimento.richiediAttiva();
        return persone.nomina(id, n.nome());
    }

    @PostMapping("/api/persone/{id}/nascondi")
    public VocePersona nascondi(@PathVariable String id, @RequestBody Nascosta n) {
        riconoscimento.richiediAttiva();
        return persone.nascondi(id, n.nascosta());
    }

    @PostMapping("/api/persone/unisci")
    public VocePersona unisci(@RequestBody Unione u) {
        riconoscimento.richiediAttiva();
        if (u.in() == null || u.da() == null || u.da().isEmpty()) {
            throw new IllegalArgumentException("Scegli le persone da unire");
        }
        return persone.unisci(u.in(), u.da());
    }

    @PostMapping("/api/persone/{id}/copertina")
    public ResponseEntity<Void> copertina(@PathVariable String id, @RequestBody Copertina c) {
        riconoscimento.richiediAttiva();
        persone.copertina(id, c.volto());
        return ResponseEntity.noContent().build();
    }

    /** I suoi volti, i meno somiglianti prima: lì si trovano quelli sbagliati. */
    @GetMapping("/api/persone/{id}/volti")
    public List<VoceVolto> volti(@PathVariable String id, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "100") int dimensione) {
        riconoscimento.richiediAttiva();
        return persone.volti(id, Math.max(pagina, 0), Math.clamp(dimensione, 1, 500));
    }

    @GetMapping("/api/persone/{id}/proposte")
    public List<VoceVolto> proposte(@PathVariable String id, @RequestParam(defaultValue = "200") int limite) {
        riconoscimento.richiediAttiva();
        return persone.proposte(id, Math.clamp(limite, 1, 1000));
    }

    @PostMapping("/api/persone/{id}/proposte/conferma")
    public Confermate confermaProposte(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        return new Confermate(persone.confermaProposte(id));
    }

    /** Gli altri gruppi che le somigliano: pezzi della stessa persona da unire. */
    @GetMapping("/api/persone/{id}/simili")
    public List<PersonaSimile> simili(@PathVariable String id) {
        riconoscimento.richiediAttiva();
        return persone.simili(id, 12);
    }

    @ExceptionHandler(NomeGiaUsato.class)
    public ProblemDetail nomeGiaUsato(NomeGiaUsato e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        p.setProperty("persona", e.altra().getId());
        p.setProperty("nome", e.altra().getNome());
        return p;
    }
}
