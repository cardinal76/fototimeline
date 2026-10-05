package it.fototimeline.contenuto;

/** Il servizio visione è spento, non raggiungibile o ha rifiutato la richiesta (502). */
public class VisioneNonRisponde extends RuntimeException {

    public VisioneNonRisponde(String messaggio) {
        super(messaggio);
    }
}
