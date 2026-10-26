package it.fototimeline.eventi;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Il rilevamento degli eventi, senza Mongo: riceve le foto una alla volta in
 * ordine di scatto ({@link #aggiungi}) e consegna i gruppi chiusi
 * ({@link Uscita#chiuso}), dal primo all'ultimo. In memoria tiene solo i
 * riepiloghi dei gruppi aperti (date, conteggi, luoghi), mai l'elenco delle
 * foto: 110 mila foto passano in pochi MB.
 *
 * <p>Due passi:
 * <ol>
 * <li><b>Pausa</b>: le foto scattate a meno di {@link Regole#pausa} l'una
 * dall'altra fanno un segmento (una festa che va oltre la mezzanotte
 * compresa).</li>
 * <li><b>Viaggi</b>: un segmento si unisce al gruppo prima se comincia lo
 * stesso giorno o in quello dopo (con al più {@link Regole#giorniVuoti} giorni
 * senza foto in mezzo) e il suo primo luogo e l'ultimo del gruppo sono lo
 * stesso luogo, la stessa regione o la stessa nazione, lontano da casa (un
 * luogo diverso da quello di casa, una regione diversa da quella di casa,
 * una nazione straniera). A casa i giorni di fila restano separati.</li>
 * </ol>
 * Un segmento senza GPS dopo un gruppo in viaggio resta in sospeso: se il
 * segmento seguente col GPS continua il viaggio entra con lui; se dopo non
 * c'è niente di vicino (l'ultimo giorno del viaggio) entra lo stesso; se il
 * seguente è altrove (a casa) si chiude da solo. Così le foto senza GPS (una
 * fotocamera, uno scanner) dentro un viaggio restano nel viaggio per
 * vicinanza nel tempo.
 *
 * <p>Il chiamante decide cosa è un evento ({@link Gruppo#conteggio} contro
 * {@link Regole#minimoFoto}): i gruppi piccoli vanno consegnati lo stesso,
 * perché le loro foto escono da un evento vecchio.
 */
final class RilevamentoEventi {

    /** Segmenti senza GPS in sospeso al massimo: oltre, il viaggio si chiude. */
    private static final int MASSIMO_SOSPESI = 31;

    /**
     * @param casa il posto di casa ({@link EventiService#casa()}); null se non si sa
     */
    record Regole(Duration pausa, int giorniVuoti, int minimoFoto, LuogoFoto casa) {

        /** True se i due posti continuano un viaggio: stesso luogo, regione o nazione, lontano da casa. */
        boolean viaggio(LuogoFoto prima, LuogoFoto dopo) {
            if (prima == null || dopo == null) {
                return false;
            }
            return prima.stessoLuogo(dopo) && !prima.stessoLuogo(casa)
                    || prima.stessaRegione(dopo) && !prima.stessaRegione(casa)
                    || prima.stessaNazione(dopo) && !prima.stessaNazione(casa);
        }
    }

    /** Quello che serve di una foto. {@code luogo} null senza un luogo. */
    record FotoPerEvento(String id, LocalDateTime scattataIl, LuogoFoto luogo, boolean preferita, boolean video) {
    }

    interface Uscita {
        void chiuso(Gruppo gruppo);
    }

    /** Il riepilogo di un gruppo di foto vicine: un evento se sono abbastanza. */
    static final class Gruppo {
        private LocalDateTime inizio;
        private LocalDateTime fine;
        private int conteggio;
        private String primaFoto;
        private String copertina;
        /** 2 una preferita, 1 una foto, 0 un video: vince il più alto, poi il primo. */
        private int rangoCopertina = -1;
        private final Map<LuogoFoto, Integer> luoghi = new LinkedHashMap<>();
        private LuogoFoto primoLuogo;
        private LuogoFoto ultimoLuogo;

        Gruppo(FotoPerEvento f) {
            inizio = f.scattataIl();
            primaFoto = f.id();
            aggiungi(f);
        }

        void aggiungi(FotoPerEvento f) {
            fine = f.scattataIl();
            conteggio++;
            int rango = f.video() ? 0 : f.preferita() ? 2 : 1;
            if (rango > rangoCopertina) {
                rangoCopertina = rango;
                copertina = f.id();
            }
            if (f.luogo() != null) {
                luoghi.merge(f.luogo(), 1, Integer::sum);
                if (primoLuogo == null) {
                    primoLuogo = f.luogo();
                }
                ultimoLuogo = f.luogo();
            }
        }

        /** Il gruppo {@code dopo} (tutto più tardi di questo) entra in questo. */
        void unisci(Gruppo dopo) {
            fine = dopo.fine;
            conteggio += dopo.conteggio;
            if (dopo.rangoCopertina > rangoCopertina) {
                rangoCopertina = dopo.rangoCopertina;
                copertina = dopo.copertina;
            }
            dopo.luoghi.forEach((l, n) -> luoghi.merge(l, n, Integer::sum));
            if (primoLuogo == null) {
                primoLuogo = dopo.primoLuogo;
            }
            if (dopo.ultimoLuogo != null) {
                ultimoLuogo = dopo.ultimoLuogo;
            }
        }

        boolean conLuogo() {
            return !luoghi.isEmpty();
        }

        LocalDateTime inizio() { return inizio; }
        LocalDateTime fine() { return fine; }
        LocalDate primoGiorno() { return inizio.toLocalDate(); }
        LocalDate ultimoGiorno() { return fine.toLocalDate(); }
        int conteggio() { return conteggio; }
        String primaFoto() { return primaFoto; }
        String copertina() { return copertina; }
        /** Quante foto per posto, solo quelle con un luogo. */
        Map<LuogoFoto, Integer> luoghi() { return Collections.unmodifiableMap(luoghi); }
    }

    private final Regole regole;
    private final Uscita uscita;
    /** Il gruppo in corso: segmenti già uniti. */
    private Gruppo gruppo;
    /** Segmenti senza GPS dopo un gruppo in viaggio: entrano se il viaggio continua. */
    private final List<Gruppo> sospesi = new ArrayList<>();
    /** Il segmento che si sta formando, per pausa. */
    private Gruppo segmento;

    RilevamentoEventi(Regole regole, Uscita uscita) {
        this.regole = regole;
        this.uscita = uscita;
    }

    /** La foto dopo, in ordine di scatto (a parità, di id). */
    void aggiungi(FotoPerEvento f) {
        if (segmento != null && !Duration.between(segmento.fine(), f.scattataIl()).minus(regole.pausa()).isPositive()) {
            segmento.aggiungi(f);
            return;
        }
        if (segmento != null) {
            chiudiSegmento(segmento);
        }
        segmento = new Gruppo(f);
    }

    /** Le foto sono finite: consegna tutto quello che resta. */
    void finisci() {
        if (segmento != null) {
            chiudiSegmento(segmento);
            segmento = null;
        }
        assorbiSospesi();
        consegnaTutto();
    }

    private void chiudiSegmento(Gruppo s) {
        if (gruppo == null) {
            gruppo = s;
            return;
        }
        Gruppo ultimo = sospesi.isEmpty() ? gruppo : sospesi.getLast();
        long giorni = ChronoUnit.DAYS.between(ultimo.ultimoGiorno(), s.primoGiorno());
        boolean vicino = giorni <= 1L + regole.giorniVuoti();
        if (vicino && !s.conLuogo() && inViaggio() && sospesi.size() < MASSIMO_SOSPESI) {
            sospesi.add(s);
            return;
        }
        if (vicino && regole.viaggio(gruppo.ultimoLuogo, s.primoLuogo)) {
            assorbiSospesi();
            gruppo.unisci(s);
            return;
        }
        if (!vicino) {
            // Dopo il viaggio niente di vicino: nulla dice che le foto senza GPS fossero altrove.
            assorbiSospesi();
        }
        consegnaTutto();
        gruppo = s;
    }

    /** Il gruppo in corso è lontano da casa: un viaggio che può continuare il giorno dopo. */
    private boolean inViaggio() {
        return regole.viaggio(gruppo.ultimoLuogo, gruppo.ultimoLuogo);
    }

    private void assorbiSospesi() {
        // Senza nessuna foto (un tratto rimasto vuoto, per esempio dopo il cestino) non c'è un gruppo:
        // gruppo::unisci su null lanciava anche con sospesi vuoto, e il ricalcolo leggero restava fermo lì.
        if (gruppo != null) {
            sospesi.forEach(gruppo::unisci);
        }
        sospesi.clear();
    }

    private void consegnaTutto() {
        if (gruppo != null) {
            uscita.chiuso(gruppo);
            gruppo = null;
        }
        sospesi.forEach(uscita::chiuso);
        sospesi.clear();
    }
}
