package it.fototimeline.volti;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Una persona (con nome) o un gruppo di volti simili ancora senza nome
 * (collezione {@code persone}). I volti puntano qui con {@code personaId};
 * le foto ne tengono l'id in {@code Foto.persone} per il filtro.
 */
@Document("persone")
public class Persona {

    @Id
    private String id;
    /** Null per un gruppo senza nome. */
    private String nome;
    /** "Non mostrare": sconosciuti, folla. Resta un gruppo, così i volti simili ci finiscono dentro. */
    private boolean nascosta;
    /** Il volto da mostrare nella griglia. */
    private String copertina;
    private Instant creataIl;
    private Instant nominataIl;

    public boolean conNome() {
        return nome != null;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public boolean isNascosta() { return nascosta; }
    public void setNascosta(boolean nascosta) { this.nascosta = nascosta; }

    public String getCopertina() { return copertina; }
    public void setCopertina(String copertina) { this.copertina = copertina; }

    public Instant getCreataIl() { return creataIl; }
    public void setCreataIl(Instant creataIl) { this.creataIl = creataIl; }

    public Instant getNominataIl() { return nominataIl; }
    public void setNominataIl(Instant nominataIl) { this.nominataIl = nominataIl; }
}
