package it.fototimeline.eventi;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * Un evento: foto vicine nel tempo (e spesso nel posto) con un nome. Le foto
 * lo sanno da {@code Foto.evento}; qui c'è il riepilogo per l'elenco.
 *
 * <p>Gli automatici ({@code manuale} false, id "a-" + la prima foto) li rifà
 * il calcolo. Uno toccato a mano (rinominato, unito, diviso, nascosto, con
 * foto tolte o aggiunte, creato da una selezione) diventa {@code manuale}: le
 * sue foto hanno {@code Foto.eventoFisso} e il ricalcolo non le tocca più.
 */
@Document("eventi")
public class Evento {

    @Id
    private String id;
    private String nome;
    /** Il nome l'ha scritto qualcuno: non si rifà quando cambiano le foto. */
    private boolean nomeAMano;
    @Indexed
    private LocalDateTime inizio;
    private LocalDateTime fine;
    private LocalDate primoGiorno;
    private LocalDate ultimoGiorno;
    /** Foto dell'evento fuori dal cestino. */
    private int conteggio;
    /** La foto della copertina: la prima preferita, o la prima foto (non video). */
    private String copertina;
    /** Il nome del posto ({@link NomiEventi#nomeLuogo}), null senza. */
    private String luogo;
    @Indexed
    private boolean manuale;
    private boolean nascosto;
    private Instant creatoIl;
    private Instant modificatoIl;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNome() { return nome; }
    public void setNome(String nome) { this.nome = nome; }

    public boolean isNomeAMano() { return nomeAMano; }
    public void setNomeAMano(boolean nomeAMano) { this.nomeAMano = nomeAMano; }

    public LocalDateTime getInizio() { return inizio; }
    public void setInizio(LocalDateTime inizio) { this.inizio = inizio; }

    public LocalDateTime getFine() { return fine; }
    public void setFine(LocalDateTime fine) { this.fine = fine; }

    public LocalDate getPrimoGiorno() { return primoGiorno; }
    public void setPrimoGiorno(LocalDate primoGiorno) { this.primoGiorno = primoGiorno; }

    public LocalDate getUltimoGiorno() { return ultimoGiorno; }
    public void setUltimoGiorno(LocalDate ultimoGiorno) { this.ultimoGiorno = ultimoGiorno; }

    public int getConteggio() { return conteggio; }
    public void setConteggio(int conteggio) { this.conteggio = conteggio; }

    public String getCopertina() { return copertina; }
    public void setCopertina(String copertina) { this.copertina = copertina; }

    public String getLuogo() { return luogo; }
    public void setLuogo(String luogo) { this.luogo = luogo; }

    public boolean isManuale() { return manuale; }
    public void setManuale(boolean manuale) { this.manuale = manuale; }

    public boolean isNascosto() { return nascosto; }
    public void setNascosto(boolean nascosto) { this.nascosto = nascosto; }

    public Instant getCreatoIl() { return creatoIl; }
    public void setCreatoIl(Instant creatoIl) { this.creatoIl = creatoIl; }

    public Instant getModificatoIl() { return modificatoIl; }
    public void setModificatoIl(Instant modificatoIl) { this.modificatoIl = modificatoIl; }
}
