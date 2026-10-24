package it.fototimeline.volti;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.util.Set;
import java.util.TreeSet;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un volto trovato in una foto (collezione {@code volti}). Il vettore sta
 * qui (float32 in binario, 512 byte con SFace) e nell'indice dei volti di
 * visione, che serve a cercare i vicini: se quell'indice va perso si rifà da
 * qui ("Riconosci volti" → ricostruisci), senza riguardare le foto.
 */
@Document("volti")
@CompoundIndexes({
        // I conteggi delle persone e i loro volti, senza quelli delle foto nel cestino.
        @CompoundIndex(name = "persona", def = "{'personaId': 1, 'nelCestino': 1}"),
        @CompoundIndex(name = "proposta", def = "{'proposta': 1, 'nelCestino': 1}")
})
public class Volto {

    /** Dove sta il volto nella miniatura, in frazioni (0–1) dall'angolo in alto a sinistra. */
    public record Riquadro(double x, double y, double larghezza, double altezza) {
    }

    @Id
    private String id;
    @Indexed
    private String fotoId;
    private Riquadro riquadro;
    /** Quanto il rilevatore è sicuro che sia un volto (0–1). */
    private double punteggio;
    private byte[] vettore;
    private String modello;
    /** La persona o il gruppo senza nome; null = nessuno (ancora, o tolto a mano). */
    private String personaId;
    /** Una persona con nome a cui somiglia, ma non abbastanza per darglielo da solo. */
    private String proposta;
    private Double somiglianzaProposta;
    /** Le persone a cui questo volto non va dato: tolto a mano o proposta rifiutata. */
    private Set<String> esclusi = new TreeSet<>();
    /** La persona l'ha decisa qualcuno (assegnato o tolto a mano). */
    private boolean manuale;
    /** La sua foto è nel cestino: non conta e non è nell'indice dei vicini. */
    private boolean nelCestino;
    private Instant trovatoIl;

    public float[] vettoreFloat() {
        return daByte(vettore);
    }

    static byte[] inByte(float[] v) {
        ByteBuffer b = ByteBuffer.allocate(v.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float x : v) {
            b.putFloat(x);
        }
        return b.array();
    }

    static float[] daByte(byte[] dati) {
        if (dati == null) {
            return new float[0];
        }
        var b = ByteBuffer.wrap(dati).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        float[] v = new float[b.remaining()];
        b.get(v);
        return v;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getFotoId() { return fotoId; }
    public void setFotoId(String fotoId) { this.fotoId = fotoId; }

    public Riquadro getRiquadro() { return riquadro; }
    public void setRiquadro(Riquadro riquadro) { this.riquadro = riquadro; }

    public double getPunteggio() { return punteggio; }
    public void setPunteggio(double punteggio) { this.punteggio = punteggio; }

    public byte[] getVettore() { return vettore; }
    public void setVettore(byte[] vettore) { this.vettore = vettore; }

    public String getModello() { return modello; }
    public void setModello(String modello) { this.modello = modello; }

    public String getPersonaId() { return personaId; }
    public void setPersonaId(String personaId) { this.personaId = personaId; }

    public String getProposta() { return proposta; }
    public void setProposta(String proposta) { this.proposta = proposta; }

    public Double getSomiglianzaProposta() { return somiglianzaProposta; }
    public void setSomiglianzaProposta(Double somiglianzaProposta) { this.somiglianzaProposta = somiglianzaProposta; }

    public Set<String> getEsclusi() { return esclusi; }
    public void setEsclusi(Set<String> esclusi) { this.esclusi = esclusi == null ? new TreeSet<>() : esclusi; }

    public boolean isManuale() { return manuale; }
    public void setManuale(boolean manuale) { this.manuale = manuale; }

    public boolean isNelCestino() { return nelCestino; }
    public void setNelCestino(boolean nelCestino) { this.nelCestino = nelCestino; }

    public Instant getTrovatoIl() { return trovatoIl; }
    public void setTrovatoIl(Instant trovatoIl) { this.trovatoIl = trovatoIl; }
}
