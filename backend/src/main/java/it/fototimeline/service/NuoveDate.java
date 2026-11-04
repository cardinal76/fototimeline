package it.fototimeline.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineData;

/**
 * "📅 Data…" sulla selezione: le date nuove di più foto insieme, prima di
 * toccarle. Solo il calcolo, senza Mongo né file: lo applica
 * {@link FotoService#cambiaDate}. Lo stesso calcolo, per l'anteprima, sta nel
 * frontend ({@code date-multiple.ts}): un cambio va fatto in tutti e due.
 *
 * <p>L'ordine delle foto è quello della timeline letto dalla più vecchia
 * ({@link #ORDINE}): data di scatto, poi id; quelle senza data in fondo.
 */
public final class NuoveDate {

    /** L'ora data a chi non ne ha una vera (senza data, o col giorno preso dalla cartella). */
    public static final LocalTime MEZZOGIORNO = LocalTime.NOON;

    /** Gli anni ammessi: la cartella è AAAA, e una data fuori di qui è un errore di battitura. */
    public static final int PRIMO_ANNO = 1800;
    public static final int ULTIMO_ANNO = 2199;

    public enum Modo {
        /** Lo stesso giorno a tutte; l'ora resta quella di ognuna ({@code mantieniOra}) o le 12. */
        STESSO_GIORNO,
        /** Lo stesso momento a tutte, un secondo in più per foto: l'ordine non si perde. */
        STESSA_ORA,
        /** Tutte avanti o indietro della stessa quantità: le distanze fra le foto restano. */
        SPOSTA
    }

    /** Di quanto spostare (anche negativo): prima anni, mesi e giorni, poi ore e minuti. */
    public record Spostamento(int anni, int mesi, int giorni, int ore, int minuti) {

        boolean nullo() {
            return anni == 0 && mesi == 0 && giorni == 0 && ore == 0 && minuti == 0;
        }

        LocalDateTime applica(LocalDateTime d) {
            return d.plusYears(anni).plusMonths(mesi).plusDays(giorni).plusHours(ore).plusMinutes(minuti);
        }
    }

    /**
     * @param giorno      per STESSO_GIORNO
     * @param mantieniOra per STESSO_GIORNO: false mette tutte alle 12 (un secondo in più per foto)
     * @param quando      per STESSA_ORA
     * @param sposta      per SPOSTA
     */
    public record Richiesta(Modo modo, LocalDate giorno, Boolean mantieniOra, LocalDateTime quando,
            Spostamento sposta) {

        /** @throws IllegalArgumentException se manca quello che serve al modo (400) */
        public void valida() {
            if (modo == null) {
                throw new IllegalArgumentException("Manca il modo: stesso giorno, stessa ora o sposta");
            }
            switch (modo) {
                case STESSO_GIORNO -> {
                    if (giorno == null) {
                        throw new IllegalArgumentException("Manca il giorno");
                    }
                    annoAmmesso(giorno.getYear());
                }
                case STESSA_ORA -> {
                    if (quando == null) {
                        throw new IllegalArgumentException("Mancano la data e l'ora");
                    }
                    annoAmmesso(quando.getYear());
                }
                case SPOSTA -> {
                    if (sposta == null || sposta.nullo()) {
                        throw new IllegalArgumentException("Di quanto spostarle? Tutto a zero non cambia niente");
                    }
                }
            }
        }
    }

    /** Dalla più vecchia alla più nuova, come la timeline al contrario; senza data in fondo. */
    public static final Comparator<Foto> ORDINE = Comparator
            .comparing(Foto::getScattataIl, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Foto::getId, Comparator.nullsLast(Comparator.naturalOrder()));

    private NuoveDate() {
    }

    /** Se l'ora della foto vale qualcosa: no senza data, né col giorno preso dalla cartella (mezzanotte finta). */
    public static boolean haUnOra(Foto foto) {
        return foto.getScattataIl() != null && foto.getOrigineData() != OrigineData.CARTELLA;
    }

    /**
     * La data nuova di ogni foto, per id, nell'ordine di {@link #ORDINE}. Con
     * SPOSTA quelle senza data non ci sono (non c'è niente da spostare).
     */
    public static Map<String, LocalDateTime> calcola(List<Foto> foto, Richiesta r) {
        r.valida();
        List<Foto> inOrdine = foto.stream().sorted(ORDINE).toList();
        Map<String, LocalDateTime> nuove = new LinkedHashMap<>();
        int senzaOra = 0;
        for (int i = 0; i < inOrdine.size(); i++) {
            Foto f = inOrdine.get(i);
            LocalDateTime nuova = switch (r.modo()) {
                case STESSO_GIORNO -> {
                    if (!Boolean.FALSE.equals(r.mantieniOra()) && haUnOra(f)) {
                        yield r.giorno().atTime(f.getScattataIl().toLocalTime());
                    }
                    // Le 12, e un secondo in più per foto: così l'ordine resta.
                    yield r.giorno().atTime(MEZZOGIORNO).plusSeconds(senzaOra++);
                }
                case STESSA_ORA -> r.quando().plusSeconds(i);
                case SPOSTA -> f.getScattataIl() == null ? null : r.sposta().applica(f.getScattataIl());
            };
            if (nuova != null) {
                nuove.put(f.getId(), nuova);
            }
        }
        return nuove;
    }

    /** @throws IllegalArgumentException fuori da {@link #PRIMO_ANNO}–{@link #ULTIMO_ANNO} */
    static void annoAmmesso(int anno) {
        if (anno < PRIMO_ANNO || anno > ULTIMO_ANNO) {
            throw new IllegalArgumentException("L'anno " + anno + " non va: tra " + PRIMO_ANNO + " e " + ULTIMO_ANNO);
        }
    }
}
