package it.fototimeline.eventi;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Le soglie degli eventi automatici ({@code FOTOTIMELINE_EVENTI_*}, DEPLOY.md "Eventi").
 *
 * @param pausa       tra due scatti più lontani di così l'evento si spezza
 *                    (salvo i viaggi, vedi {@code giorniVuoti})
 * @param minimoFoto  un gruppo con meno foto non è un evento: i giorni normali
 *                    con poche foto
 * @param giorniVuoti in un viaggio (giorni di fila lontano da casa, nello stesso
 *                    luogo, regione o nazione) quanti giorni senza foto possono
 *                    stare in mezzo senza spezzarlo; 0 = solo giorni consecutivi
 * @param casa        il luogo di casa ("Roma"): lì i giorni di fila non fanno un
 *                    viaggio; vuoto = il luogo con più foto
 * @param ricalcolo   ogni quanto si rifanno gli eventi dei giorni toccati (foto
 *                    nuove, nel cestino, ripristinate)
 */
@ConfigurationProperties("fototimeline.eventi")
public record EventiProperties(Duration pausa, Integer minimoFoto, Integer giorniVuoti, String casa,
        Duration ricalcolo) {

    public EventiProperties {
        pausa = pausa == null || pausa.isNegative() || pausa.isZero() ? Duration.ofHours(4) : pausa;
        minimoFoto = minimoFoto == null ? 12 : Math.max(minimoFoto, 1);
        giorniVuoti = giorniVuoti == null ? 1 : Math.max(giorniVuoti, 0);
        casa = casa == null || casa.isBlank() ? null : casa.trim();
        ricalcolo = ricalcolo == null ? Duration.ofMinutes(10) : ricalcolo;
    }

    /** Le regole del rilevamento, con la casa già trovata. */
    RilevamentoEventi.Regole regole(LuogoFoto casa) {
        return new RilevamentoEventi.Regole(pausa, giorniVuoti, minimoFoto, casa);
    }
}
