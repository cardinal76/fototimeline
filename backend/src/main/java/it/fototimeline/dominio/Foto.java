package it.fototimeline.dominio;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("foto")
@CompoundIndex(name = "timeline", def = "{'scattataIl': -1, '_id': -1}")
public class Foto {

    @Id
    private String id;

    private String nomeOriginale;
    private String contentType;
    private long dimensione;
    private Integer larghezza;
    private Integer altezza;

    /** Ora locale di scatto, come la scrive la fotocamera (senza fuso). */
    private LocalDateTime scattataIl;
    /** "yyyy-MM-dd" di scattataIl: raggruppa la timeline senza problemi di fuso. */
    @Indexed
    private String giorno;
    private OrigineData origineData;
    private Instant caricataIl;

    private String titolo;
    private String descrizione;
    @Indexed
    private Set<String> tag = new TreeSet<>();
    @Indexed
    private String album;
    private boolean preferita;

    /** SHA-256 del file: la stessa foto non entra due volte. */
    @Indexed(unique = true)
    private String hash;
    private String fotocamera;
    private Double latitudine;
    private Double longitudine;

    /** Percorso dell'originale, relativo alla cartella dell'archivio. */
    @Indexed
    private String percorso;

    /** Un video (MP4, MOV) invece di una foto. */
    private boolean video;
    /** Durata del video, in secondi. */
    private Double durata;

    /**
     * Impronta percettiva (dHash a 64 bit) della miniatura, per trovare le foto
     * quasi uguali; null per i video e finché non è calcolata.
     */
    private Long impronta;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNomeOriginale() { return nomeOriginale; }
    public void setNomeOriginale(String nomeOriginale) { this.nomeOriginale = nomeOriginale; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public long getDimensione() { return dimensione; }
    public void setDimensione(long dimensione) { this.dimensione = dimensione; }

    public Integer getLarghezza() { return larghezza; }
    public void setLarghezza(Integer larghezza) { this.larghezza = larghezza; }

    public Integer getAltezza() { return altezza; }
    public void setAltezza(Integer altezza) { this.altezza = altezza; }

    public LocalDateTime getScattataIl() { return scattataIl; }
    public void setScattataIl(LocalDateTime scattataIl) {
        this.scattataIl = scattataIl;
        this.giorno = scattataIl == null ? null : scattataIl.toLocalDate().toString();
    }

    public String getGiorno() { return giorno; }
    public void setGiorno(String giorno) { this.giorno = giorno; }

    public OrigineData getOrigineData() { return origineData; }
    public void setOrigineData(OrigineData origineData) { this.origineData = origineData; }

    public Instant getCaricataIl() { return caricataIl; }
    public void setCaricataIl(Instant caricataIl) { this.caricataIl = caricataIl; }

    public String getTitolo() { return titolo; }
    public void setTitolo(String titolo) { this.titolo = titolo; }

    public String getDescrizione() { return descrizione; }
    public void setDescrizione(String descrizione) { this.descrizione = descrizione; }

    public Set<String> getTag() { return tag; }
    public void setTag(Set<String> tag) { this.tag = tag == null ? new TreeSet<>() : new TreeSet<>(tag); }

    public String getAlbum() { return album; }
    public void setAlbum(String album) { this.album = album; }

    public boolean isPreferita() { return preferita; }
    public void setPreferita(boolean preferita) { this.preferita = preferita; }

    public String getHash() { return hash; }
    public void setHash(String hash) { this.hash = hash; }

    public String getFotocamera() { return fotocamera; }
    public void setFotocamera(String fotocamera) { this.fotocamera = fotocamera; }

    public Double getLatitudine() { return latitudine; }
    public void setLatitudine(Double latitudine) { this.latitudine = latitudine; }

    public Double getLongitudine() { return longitudine; }
    public void setLongitudine(Double longitudine) { this.longitudine = longitudine; }

    public String getPercorso() { return percorso; }
    public void setPercorso(String percorso) { this.percorso = percorso; }

    public boolean isVideo() { return video; }
    public void setVideo(boolean video) { this.video = video; }

    public Double getDurata() { return durata; }
    public void setDurata(Double durata) { this.durata = durata; }

    public Long getImpronta() { return impronta; }
    public void setImpronta(Long impronta) { this.impronta = impronta; }
}
