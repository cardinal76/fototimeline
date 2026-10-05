package it.fototimeline.condivisione;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un link che apre a chi non ha un account una galleria di sola lettura.
 * Le foto sono quelle scelte alla creazione: un album che cresce dopo non
 * allarga la condivisione.
 */
@Document("condivisioni")
public class Condivisione {

    public enum Origine { SELEZIONE, ALBUM, DATE }

    @Id
    private String id;

    /** 256 bit casuali in base64url: è l'unica chiave per entrare. */
    @Indexed(unique = true)
    private String token;

    private String titolo;
    /** Le foto, in ordine di scatto. */
    private List<String> foto = new ArrayList<>();
    private Origine origine;
    /** Da dove vengono le foto, per l'elenco: il nome dell'album o "2024-05-01 – 2024-05-03". */
    private String descrizioneOrigine;

    /** Null: non scade. */
    private Instant scadeIl;
    /** Si possono scaricare gli originali (uno per uno e lo zip). */
    private boolean download;
    /** Le coordinate GPS vanno nella risposta pubblica. */
    private boolean posizione;

    @Indexed
    private String creataDa;
    private Instant creataIl;

    private long visite;
    private Instant ultimoAccesso;

    private boolean revocata;
    private Instant revocataIl;

    public boolean scaduta(Instant adesso) {
        return scadeIl != null && !adesso.isBefore(scadeIl);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getTitolo() { return titolo; }
    public void setTitolo(String titolo) { this.titolo = titolo; }

    public List<String> getFoto() { return foto; }
    public void setFoto(List<String> foto) { this.foto = foto == null ? new ArrayList<>() : new ArrayList<>(foto); }

    public Origine getOrigine() { return origine; }
    public void setOrigine(Origine origine) { this.origine = origine; }

    public String getDescrizioneOrigine() { return descrizioneOrigine; }
    public void setDescrizioneOrigine(String descrizioneOrigine) { this.descrizioneOrigine = descrizioneOrigine; }

    public Instant getScadeIl() { return scadeIl; }
    public void setScadeIl(Instant scadeIl) { this.scadeIl = scadeIl; }

    public boolean isDownload() { return download; }
    public void setDownload(boolean download) { this.download = download; }

    public boolean isPosizione() { return posizione; }
    public void setPosizione(boolean posizione) { this.posizione = posizione; }

    public String getCreataDa() { return creataDa; }
    public void setCreataDa(String creataDa) { this.creataDa = creataDa; }

    public Instant getCreataIl() { return creataIl; }
    public void setCreataIl(Instant creataIl) { this.creataIl = creataIl; }

    public long getVisite() { return visite; }
    public void setVisite(long visite) { this.visite = visite; }

    public Instant getUltimoAccesso() { return ultimoAccesso; }
    public void setUltimoAccesso(Instant ultimoAccesso) { this.ultimoAccesso = ultimoAccesso; }

    public boolean isRevocata() { return revocata; }
    public void setRevocata(boolean revocata) { this.revocata = revocata; }

    public Instant getRevocataIl() { return revocataIl; }
    public void setRevocataIl(Instant revocataIl) { this.revocataIl = revocataIl; }
}
