package it.fototimeline.volti;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import it.fototimeline.contenuto.VisioneNonRisponde;
import it.fototimeline.contenuto.VisioneProperties;
import it.fototimeline.volti.Volto.Riquadro;

/**
 * Client delle API dei volti del servizio visione ({@code /volti/...} in
 * {@code visione/app/main.py}). Come per CLIP partono le miniature dal disco
 * del server, mai gli originali. Senza visione ogni chiamata lancia
 * {@link IllegalStateException}; se visione non risponde, {@link VisioneNonRisponde}.
 */
@Component
public class VisioneVolti {

    static final String SEGRETO = "X-Visione-Segreto";

    public record VoltoRilevato(Riquadro riquadro, double punteggio, float[] vettore) {
    }

    public record ImmagineVolti(int larghezza, int altezza, List<VoltoRilevato> volti) {
    }

    /** Per ogni foto (id) i volti trovati; per quelle che non si leggono, il perché. */
    public record Rilevamento(String modello, Map<String, ImmagineVolti> risultati, Map<String, String> errori) {
    }

    /** Un volto dell'indice simile a quello chiesto (coseno, da -1 a 1). */
    public record Vicino(String id, double punteggio) {
    }

    record Voce(String id, float[] vettore) {
    }

    record Voci(List<Voce> voci) {
    }

    record Ids(Collection<String> ids) {
    }

    record DomandaVicini(List<float[]> vettori, int k, double soglia) {
    }

    record RispostaVicini(List<List<Vicino>> risultati) {
    }

    record StatoIndice(String modello, int dimensione, long totale) {
    }

    private final RestClient rest;

    public VisioneVolti(VisioneProperties properties, RestClient.Builder builder) {
        this.rest = properties.attiva()
                ? builder.baseUrl(properties.url())
                        .defaultHeader(SEGRETO, properties.segreto() == null ? "" : properties.segreto())
                        .build()
                : null;
    }

    /** I volti delle miniature (id della foto → file), senza toccare l'indice. */
    public Rilevamento rileva(Map<String, Path> miniature, int latoMinimo, double punteggioMinimo) {
        var parti = new LinkedMultiValueMap<String, Object>();
        miniature.forEach((id, file) -> {
            var intestazioni = new HttpHeaders();
            intestazioni.setContentType(MediaType.IMAGE_JPEG);
            intestazioni.setContentDisposition(ContentDisposition.formData().name("file").filename(id).build());
            parti.add("file", new HttpEntity<>(new FileSystemResource(file), intestazioni));
        });
        Rilevamento r = chiama("rileva", () -> client().post()
                .uri(u -> u.path("/volti/rileva").queryParam("lato_minimo", latoMinimo)
                        .queryParam("punteggio_minimo", punteggioMinimo).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parti)
                .retrieve()
                .body(Rilevamento.class));
        if (r == null) {
            return new Rilevamento(null, Map.of(), Map.of());
        }
        return new Rilevamento(r.modello(), r.risultati() == null ? Map.of() : r.risultati(),
                r.errori() == null ? Map.of() : r.errori());
    }

    /** Mette (o sostituisce) nell'indice dei volti: id del volto → vettore. */
    public void metti(Map<String, float[]> vettori) {
        if (vettori.isEmpty()) {
            return;
        }
        List<Voce> voci = vettori.entrySet().stream().map(e -> new Voce(e.getKey(), e.getValue())).toList();
        chiama("indice", () -> client().post().uri("/volti/indice").contentType(MediaType.APPLICATION_JSON)
                .body(new Voci(voci)).retrieve().toBodilessEntity());
    }

    /** Toglie dall'indice; quelli che non c'erano vanno bene lo stesso. */
    public void togli(Collection<String> ids) {
        if (ids.isEmpty()) {
            return;
        }
        chiama("indice/togli", () -> client().post().uri("/volti/indice/togli").contentType(MediaType.APPLICATION_JSON)
                .body(new Ids(ids)).retrieve().toBodilessEntity());
    }

    /** Per ogni vettore, i {@code k} volti più simili dell'indice con almeno {@code soglia}, dal più simile. */
    public List<List<Vicino>> vicini(List<float[]> vettori, int k, double soglia) {
        if (vettori.isEmpty()) {
            return List.of();
        }
        RispostaVicini r = chiama("vicini", () -> client().post().uri("/volti/vicini")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new DomandaVicini(vettori, k, soglia))
                .retrieve()
                .body(RispostaVicini.class));
        return r == null || r.risultati() == null ? List.of() : r.risultati();
    }

    /** Quanti volti ha l'indice di visione. */
    public long totale() {
        StatoIndice s = chiama("indice", () -> client().get().uri("/volti/indice").retrieve().body(StatoIndice.class));
        return s == null ? 0 : s.totale();
    }

    private RestClient client() {
        if (rest == null) {
            throw new IllegalStateException("Il riconoscimento dei volti è spento: manca fototimeline.visione.url");
        }
        return rest;
    }

    private interface Chiamata<T> {
        T esegui();
    }

    private static <T> T chiama(String cosa, Chiamata<T> chiamata) {
        try {
            return chiamata.esegui();
        } catch (RestClientResponseException e) {
            throw new VisioneNonRisponde("Visione (volti/" + cosa + ") risponde " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new VisioneNonRisponde("Visione (volti/" + cosa + ") non risponde: " + e.getMessage());
        }
    }
}
