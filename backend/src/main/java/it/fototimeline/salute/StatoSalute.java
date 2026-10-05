package it.fototimeline.salute;

/** Il pallino: verde, giallo, rosso. L'ordine conta, dal migliore al peggiore. */
public enum StatoSalute {
    OK, ATTENZIONE, ERRORE;

    public StatoSalute peggiore(StatoSalute altro) {
        return altro != null && altro.ordinal() > ordinal() ? altro : this;
    }
}
