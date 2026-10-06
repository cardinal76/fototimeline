package it.fototimeline.contenuto;

import java.nio.file.Path;
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

/**
 * Client del servizio visione ({@code visione/app/main.py}): le API di CLIP.
 * Le miniature partono dal disco del server, mai gli originali (stanno nel
 * cloud e pesano troppo). Con la funzione spenta ({@link VisioneProperties#attiva()})
 * ogni chiamata lancia {@link IllegalStateException}.
 */
@Component
public class Visione {

    static final String SEGRETO = "X-Visione-Segreto";

    /** Una foto trovata: id e somiglianza col testo (coseno, da -1 a 1). */
    public record Somiglianza(String id, double punteggio) {
    }

    record RispostaCerca(List<Somiglianza> risultati) {
    }

    /** Esito di un blocco: gli id messi nell'indice e, per gli altri, il perché. */
    public record EsitoBlocco(List<String> aggiunte, Map<String, String> errori) {
    }

    record StatoIndice(String modello, int dimensione, long totale) {
    }

    private final RestClient rest;

    public Visione(VisioneProperties properties, RestClient.Builder builder) {
        this.rest = properties.attiva()
                ? builder.baseUrl(properties.url())
                        .defaultHeader(SEGRETO, properties.segreto() == null ? "" : properties.segreto())
                        .build()
                : null;
    }

    /** Mette (o aggiorna) nell'indice il vettore della miniatura. */
    public void metti(String id, Path miniatura) {
        chiama("indice/" + id, () -> client().put().uri("/clip/indice/{id}", id)
                .contentType(MediaType.IMAGE_JPEG)
                .body(new FileSystemResource(miniatura))
                .retrieve()
                .toBodilessEntity());
    }

    /** Più miniature in una richiesta: visione le guarda tutte insieme. */
    public EsitoBlocco mettiBlocco(Map<String, Path> miniature) {
        // L'id va nel nome del file: visione lo legge da lì.
        var parti = new LinkedMultiValueMap<String, Object>();
        miniature.forEach((id, file) -> {
            var intestazioni = new HttpHeaders();
            intestazioni.setContentType(MediaType.IMAGE_JPEG);
            intestazioni.setContentDisposition(ContentDisposition.formData().name("file").filename(id).build());
            parti.add("file", new HttpEntity<>(new FileSystemResource(file), intestazioni));
        });
        EsitoBlocco esito = chiama("indice", () -> client().post().uri("/clip/indice")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(parti)
                .retrieve()
                .body(EsitoBlocco.class));
        if (esito == null) {
            return new EsitoBlocco(List.of(), Map.of());
        }
        return new EsitoBlocco(esito.aggiunte() == null ? List.of() : esito.aggiunte(),
                esito.errori() == null ? Map.of() : esito.errori());
    }

    /** Toglie dall'indice; un id che non c'era va bene lo stesso. */
    public void togli(String id) {
        chiama("indice/" + id, () -> {
            try {
                return client().delete().uri("/clip/indice/{id}", id).retrieve().toBodilessEntity();
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 404) {
                    return null;
                }
                throw e;
            }
        });
    }

    /** Le foto più simili al testo, dalla più simile, con punteggio almeno {@code soglia}. */
    public List<Somiglianza> cerca(String testo, int k, double soglia) {
        RispostaCerca r = chiama("cerca", () -> client().get()
                .uri(u -> u.path("/clip/cerca").queryParam("q", testo).queryParam("k", k)
                        .queryParam("soglia", soglia).build())
                .retrieve()
                .body(RispostaCerca.class));
        return r == null || r.risultati() == null ? List.of() : r.risultati();
    }

    /** Quanti vettori ha l'indice di visione. */
    public long totale() {
        StatoIndice s = chiama("indice", () -> client().get().uri("/clip/indice").retrieve().body(StatoIndice.class));
        return s == null ? 0 : s.totale();
    }

    private RestClient client() {
        if (rest == null) {
            throw new IllegalStateException("La ricerca per contenuto è spenta: manca fototimeline.visione.url");
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
            throw new VisioneNonRisponde("Visione (" + cosa + ") risponde " + e.getStatusCode().value() + ": "
                    + e.getResponseBodyAsString());
        } catch (RestClientException e) {
            throw new VisioneNonRisponde("Visione (" + cosa + ") non risponde: " + e.getMessage());
        }
    }
}
