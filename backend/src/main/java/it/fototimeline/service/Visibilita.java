package it.fototimeline.service;

import java.util.Arrays;

import org.springframework.data.mongodb.core.query.Criteria;

import it.fototimeline.dominio.Foto;
import it.fototimeline.sicurezza.UtenteCorrente;

/**
 * Chi può vedere una foto. Una foto "privata" ({@link Foto#getPrivataDi()},
 * lo username Keycloak di chi l'ha messa nel suo album "Privato") la vede
 * solo quell'account: né gli altri familiari né l'amministratore, in nessun
 * elenco, conteggio o indirizzo dell'app. Le altre le vedono tutti.
 *
 * <p>È una regola dell'app, non del disco: il file resta dov'è nel cloud, con
 * la sua miniatura, e chi ha accesso diretto al cloud, al server o a MongoDB
 * lo vede lo stesso (DEPLOY.md, "Foto private").
 *
 * <p>Da dove si parte:
 * <ul>
 * <li>{@link FotoService#visibili()} e {@link FotoService#criteri(FiltroFoto)}:
 *     ogni lettura fatta per qualcuno (timeline, ricerca, mappa, filtri,
 *     conteggi) e ogni modifica di più foto. Chi guarda lo dice la richiesta
 *     ({@link #chiGuarda()}); un thread senza richiesta non vede le private
 *     di nessuno: dimenticarsi di passarlo nasconde, non mostra.</li>
 * <li>{@link FotoService#trovaVisibile(String)} per una foto chiesta per id
 *     (scheda, miniatura, vista, file, volti): 404 se è privata di un altro,
 *     come se non ci fosse.</li>
 * <li>{@link FotoService#diFamiglia()} per quello che un lavoro in
 *     sottofondo fa vedere a tutti (eventi, quasi uguali, ricordi su
 *     Telegram, numeri della Salute): solo le foto non private.</li>
 * <li>{@link FotoService#nonNelCestino()} resta "tutte, anche le private":
 *     solo per i lavori che non mostrano niente (luoghi, impronte, contenuto,
 *     video compatibili, backup).</li>
 * </ul>
 */
public final class Visibilita {

    /** Il campo di {@link Foto#getPrivataDi()}: null (o assente) per le foto di tutti. */
    public static final String CAMPO = "privataDi";

    private Visibilita() {
    }

    /** Lo username di chi fa la richiesta; null senza login, senza sessione e in sottofondo. */
    public static String chiGuarda() {
        return UtenteCorrente.usernameDellaRichiesta();
    }

    /** Le foto che {@code username} può vedere: quelle di tutti e le sue private (null: solo quelle di tutti). */
    public static Criteria di(String username) {
        return username == null ? Criteria.where(CAMPO).is(null)
                : Criteria.where(CAMPO).in(Arrays.asList(null, username));
    }

    /** {@link #di(String)} per chi fa la richiesta. */
    public static Criteria perChiGuarda() {
        return di(chiGuarda());
    }

    /** Solo le foto non private di nessuno. */
    public static Criteria diTutti() {
        return Criteria.where(CAMPO).is(null);
    }

    /** True se {@code username} può vedere la foto. */
    public static boolean puoVedere(Foto foto, String username) {
        return foto.getPrivataDi() == null || foto.getPrivataDi().equals(username);
    }

    /** True se chi fa la richiesta può vedere la foto. */
    public static boolean puoVedere(Foto foto) {
        return puoVedere(foto, chiGuarda());
    }
}
