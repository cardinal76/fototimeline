package it.fototimeline.notte;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.cloud.ArchivioNonDisponibile;
import it.fototimeline.contenuto.IndiceContenuto;
import it.fototimeline.eventi.EventiService;
import it.fototimeline.luoghi.LuoghiService;
import it.fototimeline.quasiuguali.CalcoloImpronte;
import it.fototimeline.salute.StatoSalute;
import it.fototimeline.salute.VoceSalute;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.LavoriImportazione;
import it.fototimeline.service.LavoriImportazione.Origine;
import it.fototimeline.service.LavoriImportazione.StatoLavoro;
import it.fototimeline.volti.RiconoscimentoVolti;

/**
 * Il giro notturno: ogni notte ({@code fototimeline.notte.cron}, predefinito
 * alle 2:30 ora italiana) i lavori di Strumenti uno dopo l'altro, ognuno solo
 * su quello che c'è da fare:
 * <ol>
 * <li><b>Indicizza archivio</b>: i file dell'archivio senza scheda (quelli
 *     che ce l'hanno si saltano senza aprirli);</li>
 * <li><b>Calcola luoghi</b>: solo le foto da calcolare (mai calcolate, o con
 *     una versione di prima), mai "tutte";</li>
 * <li><b>Calcola eventi</b>: i giorni toccati (il ricalcolo leggero), o il
 *     calcolo completo se uno di prima si era fermato a metà (riprende da lì);</li>
 * <li><b>Indicizza contenuto</b> e <b>Riconosci volti</b>: le foto senza
 *     segno, cioè le nuove;</li>
 * <li><b>Calcola impronte</b>: solo se qualche foto non ce l'ha.</li>
 * </ol>
 *
 * <p>Sono gli stessi lavori dei pulsanti, con le loro regole: i primi tre
 * stanno sul thread di {@link LavoriImportazione}, quindi uno alla volta, con
 * la barra, Annulla, e la precedenza sull'importazione automatica (che nel
 * frattempo non riparte: {@link LavoriImportazione#sospendiAutomatica}).
 * Ogni lavoro parte quando quello di prima è finito: niente parallelismo in
 * più, server2 di notte fa anche le build. Un lavoro annullato ferma il giro.
 *
 * <p>Salta (e lo scrive) se il cloud non è montato, o se all'ora del giro gira
 * già un lavoro chiesto da qualcuno per più di {@code attesa-massima}. Le
 * funzioni spente ({@code FOTOTIMELINE_VOLTI=false}, niente visione, niente
 * GeoNames) si saltano. L'esito sta in {@code impostazioni/giro-notturno}, lo
 * mostra la pagina Salute ({@link #voceSalute()}) e da lì arriva su Telegram.
 * Dopo un riavvio a metà giro riprende dal lavoro dove era arrivato.
 */
@Service
public class GiroNotturno {

    private static final Logger log = LoggerFactory.getLogger(GiroNotturno.class);
    static final String ID = "giro-notturno";
    static final String CHIAVE_SALUTE = "notte";
    static final String TITOLO_SALUTE = "Giro notturno";
    /** Senza un giro da più di così, con il giro acceso, la voce in Salute diventa gialla. */
    static final Duration TROPPO_VECCHIO = Duration.ofHours(49);
    /** Un giro rimasto "in corso" più vecchio di così, dopo un riavvio, non si riprende. */
    private static final Duration RIPRESA_MASSIMA = Duration.ofHours(20);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("d MMM y, HH:mm", Locale.ITALIAN);
    private static final DateTimeFormatter ORA = DateTimeFormatter.ofPattern("HH:mm", Locale.ITALIAN);

    /** I lavori del giro, in ordine. */
    public enum Passo {
        INDICIZZA("Indicizza archivio"),
        LUOGHI("Calcola luoghi"),
        EVENTI("Calcola eventi"),
        CONTENUTO("Indicizza contenuto"),
        VOLTI("Riconosci volti"),
        IMPRONTE("Calcola impronte");

        private final String nome;

        Passo(String nome) {
            this.nome = nome;
        }

        public String nome() {
            return nome;
        }
    }

    /**
     * Com'è andato il giro. SALTATO: non è partito niente (cloud smontato,
     * un altro lavoro troppo lungo); FALLITO: almeno un lavoro è fallito
     * (gli altri sono andati avanti); ANNULLATO: qualcuno ha premuto Annulla.
     */
    public enum Esito { IN_CORSO, FINITO, SALTATO, ANNULLATO, FALLITO }

    /** Com'è andato un lavoro. SPENTO: la funzione non c'è (o non c'era niente da fare per costruzione). */
    public enum EsitoPasso { FATTO, SPENTO, ANNULLATO, FALLITO }

    /**
     * Un lavoro del giro.
     *
     * @param quanti quante cose ha fatto: le foto nuove trovate, le foto calcolate, i giorni rifatti
     * @param cosa   cosa conta {@code quanti}, per le persone ("foto nuove", "giorni rifatti")
     * @param nota   perché è spento o fallito, o un dettaglio in più
     */
    public record Risultato(Passo passo, EsitoPasso esito, int quanti, String cosa, int errori, String nota,
            Instant iniziatoIl, Instant finitoIl) {
    }

    /**
     * L'ultimo giro (o quello in corso), in {@code impostazioni/giro-notturno}.
     *
     * @param giro     id di questo giro
     * @param aMano    "Lancia ora" in Strumenti; {@code chi} l'ha lanciato
     * @param passo    il lavoro in corso (null quando è finito)
     * @param messaggio perché è saltato o fallito
     * @param passi    i lavori fatti finora, in ordine
     */
    @Document("impostazioni")
    public record Giro(@Id String id, String giro, Esito esito, boolean aMano, String chi, Instant iniziatoIl,
            Instant finitoIl, Passo passo, String messaggio, List<Risultato> passi) {

        public Giro {
            passi = passi == null ? List.of() : List.copyOf(passi);
        }

        Giro con(Esito esito, Passo passo, String messaggio, List<Risultato> passi, Instant finitoIl) {
            return new Giro(id, giro, esito, aMano, chi, iniziatoIl, finitoIl, passo, messaggio, passi);
        }

        /** Le foto nuove trovate da "Indicizza archivio". */
        public int fotoNuove() {
            return passi.stream().filter(r -> r.passo() == Passo.INDICIZZA).mapToInt(Risultato::quanti).sum();
        }
    }

    /**
     * Per Strumenti: se è acceso, quando, e l'ultimo giro.
     *
     * @param orario   per le persone: "ogni notte alle 02:30" o il cron così com'è
     * @param prossimo il prossimo giro; null se spento
     */
    public record StatoNotte(boolean attivo, String cron, String fuso, String orario, Instant prossimo,
            boolean inCorso, Giro ultimo) {
    }

    private final NotteProperties properties;
    private final LavoriImportazione lavori;
    private final LuoghiService luoghi;
    private final EventiService eventi;
    private final IndiceContenuto contenuto;
    private final RiconoscimentoVolti volti;
    private final CalcoloImpronte impronte;
    private final ArchivioFile archivio;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "giro-notturno");
        t.setDaemon(true);
        return t;
    });

    /** Il giro in corso o l'ultimo; null se non ce n'è mai stato uno. */
    private volatile Giro attuale;
    private volatile boolean annullato;
    /** Il prossimo orario del cron; si sposta avanti a ogni giro (anche saltato). */
    private volatile ZonedDateTime atteso;

    public GiroNotturno(NotteProperties properties, LavoriImportazione lavori, LuoghiService luoghi,
            EventiService eventi, IndiceContenuto contenuto, RiconoscimentoVolti volti, CalcoloImpronte impronte,
            ArchivioFile archivio, MongoTemplate mongo, Optional<Clock> clock) {
        this.properties = properties;
        this.lavori = lavori;
        this.luoghi = luoghi;
        this.eventi = eventi;
        this.contenuto = contenuto;
        this.volti = volti;
        this.impronte = impronte;
        this.archivio = archivio;
        this.mongo = mongo;
        this.clock = clock.orElse(Clock.systemUTC());
        this.attuale = mongo.findById(ID, Giro.class);
        CronExpression cron = properties.espressione();
        this.atteso = cron == null ? null : cron.next(adesso());
    }

    // ------------------------------------------------------------ quando

    private ZonedDateTime adesso() {
        return ZonedDateTime.ofInstant(clock.instant(), properties.zona());
    }

    /**
     * Ogni minuto: se è passata l'ora del cron, il giro. Un'ora persa mentre
     * l'app era spenta non si recupera: si aspetta la prossima. Se il giro di
     * prima è ancora in corso, questo si salta.
     */
    @Scheduled(fixedDelayString = "${fototimeline.notte.controllo-orario:PT1M}",
            initialDelayString = "${fototimeline.notte.controllo-orario:PT1M}")
    public void controllaOrario() {
        CronExpression cron = properties.espressione();
        ZonedDateTime previsto = atteso;
        if (cron == null || previsto == null) {
            return;
        }
        ZonedDateTime adesso = adesso();
        if (adesso.isBefore(previsto)) {
            return;
        }
        atteso = cron.next(adesso);
        if (inCorso()) {
            log.info("Giro notturno delle {}: quello di prima è ancora in corso, salto", ORA.format(previsto));
            return;
        }
        log.info("Giro notturno: parte (orario {})", DATA.format(previsto));
        parti(false, null);
    }

    /** Il prossimo giro; null se spento. */
    public Instant prossimo() {
        ZonedDateTime p = atteso;
        return p == null ? null : p.toInstant();
    }

    public StatoNotte stato() {
        return new StatoNotte(properties.attivo(), properties.attivo() ? properties.cron() : null, properties.fuso(),
                orario(), prossimo(), inCorso(), attuale);
    }

    /** "ogni notte alle 02:30" per il cron più comune; altrimenti il cron. */
    String orario() {
        if (!properties.attivo()) {
            return null;
        }
        String[] campi = properties.cron().split("\\s+");
        if (campi.length == 6 && campi[0].matches("\\d+") && campi[1].matches("\\d+") && campi[2].matches("\\d+")
                && campi[3].equals("*") && campi[4].equals("*") && (campi[5].equals("*") || campi[5].equals("?"))) {
            int ora = Integer.parseInt(campi[2]);
            String quando = ora < 6 || ora >= 22 ? "ogni notte" : "ogni giorno";
            return "%s alle %02d:%02d".formatted(quando, ora, Integer.parseInt(campi[1]));
        }
        return "cron \"" + properties.cron() + "\"";
    }

    // ------------------------------------------------------------ avvio, annullamento, ripresa

    public boolean inCorso() {
        Giro g = attuale;
        return g != null && g.esito() == Esito.IN_CORSO;
    }

    /**
     * "Lancia ora" (Strumenti, solo amministratore). Con un giro già in
     * corso, o col cloud smontato, IllegalStateException (409): a mano non si
     * registra un giro saltato, lo si dice subito.
     */
    public Giro lanciaOra(String chi) {
        if (!archivio.disponibile()) {
            throw new IllegalStateException("Il cloud non è montato: montalo e rilancia il giro");
        }
        return parti(true, chi);
    }

    /** Il giro, dal cron o da "Lancia ora"; pacchetto per i test (il cron col cloud smontato). */
    synchronized Giro parti(boolean aMano, String chi) {
        if (inCorso()) {
            throw new IllegalStateException("Il giro notturno è già in corso");
        }
        Giro giro = new Giro(ID, UUID.randomUUID().toString(), Esito.IN_CORSO, aMano, chi, clock.instant(), null,
                null, null, List.of());
        annullato = false;
        salva(giro);
        esecutore.submit(() -> esegui(giro, Passo.INDICIZZA));
        return giro;
    }

    /** Ferma il giro: annulla il lavoro in corso (come il suo Annulla) e non fa partire i dopo. */
    public boolean annulla() {
        Giro g = attuale;
        if (g == null || g.esito() != Esito.IN_CORSO) {
            return false;
        }
        annullato = true;
        Passo p = g.passo();
        if (p != null) {
            switch (p) {
                case INDICIZZA, LUOGHI -> lavori.annulla();
                case EVENTI -> {
                    if (lavori.corrente().filter(l -> l.origine() == Origine.EVENTI).isPresent()) {
                        lavori.annulla();
                    }
                }
                case CONTENUTO -> contenuto.annulla();
                case VOLTI -> volti.annulla();
                case IMPRONTE -> impronte.annulla();
            }
        }
        return true;
    }

    /**
     * L'app si è fermata a metà giro: riparte dal lavoro dove era arrivato
     * (i lavori stessi riprendono da dove erano, o rifanno solo il resto).
     * Un giro di ieri rimasto in corso si chiude e basta.
     */
    @EventListener(ApplicationReadyEvent.class)
    public synchronized void riprendi() {
        Giro g = mongo.findById(ID, Giro.class);
        if (g == null || g.esito() != Esito.IN_CORSO) {
            return;
        }
        if (g.iniziatoIl() == null || g.iniziatoIl().plus(RIPRESA_MASSIMA).isBefore(clock.instant())) {
            salva(g.con(Esito.FALLITO, null, "Interrotto da un riavvio dell'app e non ripreso", g.passi(),
                    clock.instant()));
            return;
        }
        Passo da = g.passo() != null ? g.passo() : Passo.INDICIZZA;
        log.info("Riprendo il giro notturno da \"{}\", interrotto da un riavvio", da.nome());
        // I risultati del lavoro interrotto si rifanno: resta quello che era finito prima.
        List<Risultato> fatti = g.passi().stream().filter(r -> r.passo().ordinal() < da.ordinal()).toList();
        Giro ripreso = g.con(Esito.IN_CORSO, da, null, fatti, null);
        annullato = false;
        salva(ripreso);
        esecutore.submit(() -> esegui(ripreso, da));
    }

    @PreDestroy
    void chiudi() {
        // Il giro in corso resta "in corso" nel documento: al riavvio riprende.
        esecutore.shutdownNow();
    }

    /**
     * Prima su Mongo, poi in {@link #attuale}: {@link #inCorso()} e la Salute
     * leggono {@code attuale}, quindi quando dicono "finito" il documento è
     * già scritto. Al contrario (prima {@code attuale}) c'era un momento in
     * cui il giro risultava finito ma Mongo diceva ancora "in corso": un
     * "Lancia ora" lì in mezzo salvava il giro nuovo e subito dopo la
     * scrittura in ritardo del vecchio ("finito") lo copriva, e dopo un
     * riavvio il giro nuovo non si riprendeva. {@code attuale} si aggiorna
     * anche se Mongo non risponde, se no il giro resterebbe "in corso" per sempre.
     */
    private void salva(Giro g) {
        try {
            mongo.save(g);
        } finally {
            attuale = g;
        }
    }

    // ------------------------------------------------------------ il giro

    private void esegui(Giro giro, Passo da) {
        lavori.sospendiAutomatica(true);
        Giro g = giro;
        try {
            if (!archivio.disponibile()) {
                log.warn("Giro notturno saltato: il cloud non è montato");
                salva(g.con(Esito.SALTATO, null, "Il cloud non era montato: niente da fare senza le foto", g.passi(),
                        clock.instant()));
                return;
            }
            String occupato = aspettaLibero();
            if (occupato != null) {
                log.warn("Giro notturno saltato: {} in corso da più di {}", occupato, properties.attesaMassima());
                salva(g.con(Esito.SALTATO, null, occupato + " era in corso e non è finito entro "
                        + durata(properties.attesaMassima()) + ": giro saltato", g.passi(), clock.instant()));
                return;
            }
            List<Risultato> fatti = new ArrayList<>(g.passi());
            for (Passo p : Passo.values()) {
                if (p.ordinal() < da.ordinal()) {
                    continue;
                }
                if (annullato || Thread.currentThread().isInterrupted()) {
                    break;
                }
                g = g.con(Esito.IN_CORSO, p, null, fatti, null);
                salva(g);
                Risultato r = passo(p);
                fatti.add(r);
                log.info("Giro notturno, {}: {} ({} {}, {} errori){}", p.nome(), r.esito(), r.quanti(), r.cosa(),
                        r.errori(), r.nota() != null ? " " + r.nota() : "");
                if (r.esito() == EsitoPasso.ANNULLATO) {
                    annullato = true;
                }
            }
            if (Thread.currentThread().isInterrupted() && !annullato) {
                // L'app si sta fermando: il documento resta "in corso", al riavvio riprende.
                return;
            }
            boolean fallito = fatti.stream().anyMatch(r -> r.esito() == EsitoPasso.FALLITO);
            Esito esito = annullato ? Esito.ANNULLATO : fallito ? Esito.FALLITO : Esito.FINITO;
            String messaggio = annullato ? "Annullato" : fallito ? fatti.stream()
                    .filter(r -> r.esito() == EsitoPasso.FALLITO)
                    .map(r -> r.passo().nome() + ": " + (r.nota() != null ? r.nota() : "non riuscito"))
                    .reduce((a, b) -> a + "; " + b).orElse(null) : null;
            salva(g.con(esito, null, messaggio, fatti, clock.instant()));
            log.info("Giro notturno {}: {} foto nuove", esito, g.con(esito, null, null, fatti, null).fotoNuove());
        } catch (Throwable e) {
            log.error("Giro notturno fermato da un errore inatteso", e);
            salva(g.con(Esito.FALLITO, null, "Errore inatteso: " + e, g.passi(), clock.instant()));
        } finally {
            lavori.sospendiAutomatica(false);
        }
    }

    /** Un lavoro: lo fa partire quando non gira nient'altro, e aspetta che finisca. */
    private Risultato passo(Passo p) {
        Instant inizio = clock.instant();
        String occupato = aspettaLibero();
        if (annullato) {
            return risultato(p, EsitoPasso.ANNULLATO, 0, "", 0, null, inizio);
        }
        if (occupato != null) {
            return risultato(p, EsitoPasso.FALLITO, 0, "", 0, "non partito: " + occupato + " in corso", inizio);
        }
        try {
            return switch (p) {
                case INDICIZZA -> daLavoro(p, lavori.avviaIndicizzazione(), "foto nuove", inizio);
                case LUOGHI -> luoghi.disponibile()
                        ? daLavoro(p, lavori.avviaLuoghi(false), "foto calcolate", inizio)
                        : risultato(p, EsitoPasso.SPENTO, 0, "", 0, "senza i file di GeoNames", inizio);
                case EVENTI -> eventi(inizio);
                case CONTENUTO -> contenuto(inizio);
                case VOLTI -> volti(inizio);
                case IMPRONTE -> impronte(inizio);
            };
        } catch (ArchivioNonDisponibile e) {
            return risultato(p, EsitoPasso.FALLITO, 0, "", 0, "il cloud non è più montato", inizio);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return risultato(p, EsitoPasso.FALLITO, 0, "", 0, "interrotto dall'arresto dell'app", inizio);
        } catch (RuntimeException e) {
            log.warn("Giro notturno, {} non riuscito", p.nome(), e);
            return risultato(p, EsitoPasso.FALLITO, 0, "", 0, e.getMessage() != null ? e.getMessage() : e.toString(),
                    inizio);
        }
    }

    /** Un lavoro di {@link LavoriImportazione}: lo segue finché non è finito. */
    private Risultato daLavoro(Passo p, StatoLavoro partito, String cosa, Instant inizio) throws InterruptedException {
        StatoLavoro fine = aspetta(() -> lavori.corrente().filter(l -> l.id().equals(partito.id())).orElse(null),
                l -> l.stato() != LavoriImportazione.Stato.IN_CORSO);
        if (fine == null) {
            return risultato(p, EsitoPasso.FALLITO, 0, cosa, 0, "il lavoro non si trova più", inizio);
        }
        // Indicizza: le foto nuove; luoghi ed eventi: le foto fatte.
        int quanti = p == Passo.INDICIZZA ? fine.importate() : fine.fatte();
        return switch (fine.stato()) {
            case FINITA, IN_CORSO -> risultato(p, EsitoPasso.FATTO, quanti, cosa, fine.errori(), null, inizio);
            case ANNULLATA -> risultato(p, EsitoPasso.ANNULLATO, quanti, cosa, fine.errori(), null, inizio);
            case FALLITA -> risultato(p, EsitoPasso.FALLITO, quanti, cosa, fine.errori(), fine.errore(), inizio);
        };
    }

    /** Il calcolo completo se uno di prima si era fermato, se no solo i giorni toccati. */
    private Risultato eventi(Instant inizio) throws InterruptedException {
        EventiService.StatoCalcolo s = eventi.stato();
        if (s != null && s.interrotto()) {
            return daLavoro(Passo.EVENTI, lavori.avviaEventi(false), "foto raggruppate", inizio);
        }
        int giorni = eventi.ricalcolaGiorniToccati();
        return risultato(Passo.EVENTI, EsitoPasso.FATTO, giorni, "giorni rifatti", 0, null, inizio);
    }

    private Risultato contenuto(Instant inizio) throws InterruptedException {
        if (!contenuto.attiva()) {
            return risultato(Passo.CONTENUTO, EsitoPasso.SPENTO, 0, "", 0, "manca il servizio visione", inizio);
        }
        IndiceContenuto.StatoLavoro partito = contenuto.avvia(false);
        IndiceContenuto.StatoLavoro fine = aspetta(
                () -> contenuto.corrente().filter(l -> l.id().equals(partito.id())).orElse(null),
                l -> l.stato() != IndiceContenuto.Stato.IN_CORSO);
        if (fine == null) {
            return risultato(Passo.CONTENUTO, EsitoPasso.FALLITO, 0, "foto", 0, "il lavoro non si trova più", inizio);
        }
        return risultato(Passo.CONTENUTO, switch (fine.stato()) {
            case FINITO, IN_CORSO -> EsitoPasso.FATTO;
            case ANNULLATO -> EsitoPasso.ANNULLATO;
            case FALLITO -> EsitoPasso.FALLITO;
        }, fine.fatte(), "foto nell'indice", fine.errori(), fine.errore(), inizio);
    }

    private Risultato volti(Instant inizio) throws InterruptedException {
        if (!volti.attiva()) {
            return risultato(Passo.VOLTI, EsitoPasso.SPENTO, 0, "", 0,
                    "spento (FOTOTIMELINE_VOLTI=false o manca il servizio visione)", inizio);
        }
        RiconoscimentoVolti.StatoLavoro partito = volti.avvia(false);
        RiconoscimentoVolti.StatoLavoro fine = aspetta(
                () -> volti.corrente().filter(l -> l.id().equals(partito.id())).orElse(null),
                l -> l.stato() != RiconoscimentoVolti.Stato.IN_CORSO);
        if (fine == null) {
            return risultato(Passo.VOLTI, EsitoPasso.FALLITO, 0, "foto", 0, "il lavoro non si trova più", inizio);
        }
        return risultato(Passo.VOLTI, switch (fine.stato()) {
            case FINITO, IN_CORSO -> EsitoPasso.FATTO;
            case ANNULLATO -> EsitoPasso.ANNULLATO;
            case FALLITO -> EsitoPasso.FALLITO;
        }, fine.fatte(), "foto guardate", fine.errori(),
                fine.stato() == RiconoscimentoVolti.Stato.FALLITO ? fine.errore() : fine.volti() + " volti trovati",
                inizio);
    }

    private Risultato impronte(Instant inizio) throws InterruptedException {
        if (impronte.stato().senzaImpronta() == 0) {
            return risultato(Passo.IMPRONTE, EsitoPasso.FATTO, 0, "foto calcolate", 0, null, inizio);
        }
        impronte.avvia();
        CalcoloImpronte.StatoCalcolo fine = aspetta(impronte::stato,
                s -> s.stato() != CalcoloImpronte.Stato.IN_CORSO);
        return risultato(Passo.IMPRONTE, switch (fine.stato()) {
            case ANNULLATO -> EsitoPasso.ANNULLATO;
            case FALLITO -> EsitoPasso.FALLITO;
            default -> EsitoPasso.FATTO;
        }, fine.calcolate(), "foto calcolate", fine.errori(), fine.errore(), inizio);
    }

    private Risultato risultato(Passo p, EsitoPasso esito, int quanti, String cosa, int errori, String nota,
            Instant inizio) {
        return new Risultato(p, esito, quanti, cosa, errori, nota, inizio, clock.instant());
    }

    /** Aspetta finché {@code finito} non dice sì; null se lo stato non c'è più. */
    private <T> T aspetta(Supplier<T> stato, java.util.function.Predicate<T> finito) throws InterruptedException {
        while (true) {
            T s = stato.get();
            if (s == null || finito.test(s)) {
                return s;
            }
            Thread.sleep(properties.controllo().toMillis());
        }
    }

    /**
     * Aspetta che non giri nessun lavoro chiesto da qualcuno (l'importazione
     * automatica no: il primo lavoro del giro le passa avanti, come
     * "Indicizza archivio" a mano). Null se libero (o annullato), altrimenti
     * il lavoro ancora in corso dopo {@code attesa-massima}.
     */
    private String aspettaLibero() {
        long scadenza = System.nanoTime() + properties.attesaMassima().toNanos();
        String occupato;
        while ((occupato = occupato()) != null && !annullato) {
            if (System.nanoTime() - scadenza > 0) {
                return occupato;
            }
            try {
                Thread.sleep(properties.controllo().toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return occupato;
            }
        }
        return null;
    }

    /** Il lavoro chiesto da qualcuno che gira adesso; null se nessuno. */
    String occupato() {
        Optional<StatoLavoro> l = lavori.corrente();
        if (l.isPresent() && l.get().stato() == LavoriImportazione.Stato.IN_CORSO
                && l.get().origine() != Origine.AUTOMATICA) {
            return switch (l.get().origine()) {
                case INDICIZZAZIONE -> "\"Indicizza archivio\"";
                case LUOGHI -> "\"Calcola luoghi\"";
                case EVENTI -> "\"Calcola eventi\"";
                default -> "Un'importazione";
            };
        }
        if (eventi.calcoloInCorso()) {
            return "\"Calcola eventi\"";
        }
        if (contenuto.corrente().filter(c -> c.stato() == IndiceContenuto.Stato.IN_CORSO).isPresent()) {
            return "\"Indicizza contenuto\"";
        }
        if (volti.corrente().filter(c -> c.stato() == RiconoscimentoVolti.Stato.IN_CORSO).isPresent()) {
            return "\"Riconosci volti\"";
        }
        if (impronte.stato().stato() == CalcoloImpronte.Stato.IN_CORSO) {
            return "\"Calcola impronte\"";
        }
        return null;
    }

    // ------------------------------------------------------------ Salute

    /**
     * La voce della pagina Salute (chiave {@code notte}): verde se l'ultimo
     * giro è finito (o il giro è spento), gialla se è saltato, è stato
     * annullato o non gira da due giorni, rossa se un lavoro è fallito. Nei
     * dettagli ogni lavoro con quante foto ha fatto, e il prossimo giro.
     */
    public VoceSalute voceSalute() {
        Giro g = attuale;
        List<String> dettagli = new ArrayList<>();
        if (g != null) {
            for (Risultato r : g.passi()) {
                dettagli.add(riga(r));
            }
            if (g.esito() == Esito.IN_CORSO && g.passo() != null) {
                dettagli.add(g.passo().nome() + ": in corso");
            }
        }
        Instant prossimo = prossimo();
        dettagli.add(prossimo != null ? "Prossimo giro: " + data(prossimo) + " (" + orario() + ")"
                : "Spento: si accende con FOTOTIMELINE_NOTTE_CRON (per esempio \"0 30 2 * * *\")");
        if (g == null) {
            return voce(StatoSalute.OK, properties.attivo() ? "Ancora nessun giro" : "Spento", dettagli);
        }
        String quando = data(g.iniziatoIl()) + (g.aMano() ? " (lanciato a mano)" : "");
        StatoSalute stato = switch (g.esito()) {
            case IN_CORSO, FINITO -> StatoSalute.OK;
            case SALTATO, ANNULLATO -> StatoSalute.ATTENZIONE;
            case FALLITO -> StatoSalute.ERRORE;
        };
        String messaggio = switch (g.esito()) {
            case IN_CORSO -> "In corso dalle " + ORA.format(ZonedDateTime.ofInstant(g.iniziatoIl(), properties.zona()))
                    + (g.passo() != null ? ": " + g.passo().nome() : "");
            case FINITO -> "Ultimo giro " + quando + ": finito, " + g.fotoNuove() + " foto nuove"
                    + (g.finitoIl() != null ? " (" + durata(Duration.between(g.iniziatoIl(), g.finitoIl())) + ")" : "");
            case SALTATO -> "Ultimo giro " + quando + " saltato: " + g.messaggio();
            case ANNULLATO -> "Ultimo giro " + quando + " annullato";
            case FALLITO -> "Ultimo giro " + quando + " non riuscito: " + g.messaggio();
        };
        if (stato == StatoSalute.OK && properties.attivo() && g.esito() != Esito.IN_CORSO
                && g.iniziatoIl().plus(TROPPO_VECCHIO).isBefore(clock.instant())) {
            stato = StatoSalute.ATTENZIONE;
            messaggio = "Nessun giro dal " + data(g.iniziatoIl()) + ": l'app era spenta all'ora del giro?";
        }
        return voce(stato, messaggio, dettagli);
    }

    private static VoceSalute voce(StatoSalute stato, String messaggio, List<String> dettagli) {
        return new VoceSalute(CHIAVE_SALUTE, TITOLO_SALUTE, stato, messaggio, dettagli);
    }

    private static String riga(Risultato r) {
        String testo = switch (r.esito()) {
            case FATTO -> r.quanti() + " " + r.cosa() + (r.errori() > 0 ? " · " + r.errori() + " errori" : "");
            case SPENTO -> "saltato, " + r.nota();
            case ANNULLATO -> "annullato" + (r.quanti() > 0 ? " dopo " + r.quanti() + " " + r.cosa() : "");
            case FALLITO -> "non riuscito: " + (r.nota() != null ? r.nota() : "?");
        };
        if (r.esito() == EsitoPasso.FATTO && r.nota() != null) {
            testo += " (" + r.nota() + ")";
        }
        return r.passo().nome() + ": " + testo;
    }

    private String data(Instant i) {
        return DATA.format(ZonedDateTime.ofInstant(i, properties.zona()));
    }

    private static String durata(Duration d) {
        long minuti = d.toMinutes();
        if (minuti < 1) {
            return d.toSeconds() + " s";
        }
        return minuti < 60 ? minuti + " min" : minuti / 60 + " h " + minuti % 60 + " min";
    }
}
