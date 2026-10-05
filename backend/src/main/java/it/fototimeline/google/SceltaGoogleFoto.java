package it.fototimeline.google;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import it.fototimeline.google.ClientGoogle.GoogleNonRiesce;
import it.fototimeline.google.ClientGoogle.PaginaScelte;
import it.fototimeline.google.ClientGoogle.Scelta;
import it.fototimeline.google.ClientGoogle.SessionePicker;
import it.fototimeline.google.ClientGoogle.Token;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import it.fototimeline.service.FotoService.MetadatiEsterni;
import it.fototimeline.service.Risultati.Caricamento;

/**
 * "Scegli da Google Foto", per chiunque sia entrato: l'utente collega il suo
 * account Google (OAuth, solo lo scope del Picker), sceglie le foto nella
 * pagina di Google e l'app le scarica e le importa come "Carica foto"
 * (stessi doppioni, "caricate da" lui).
 *
 * <p>Il refresh token sta in {@code google_token}, cifrato; l'access token
 * solo in memoria. Ogni utente ha al massimo una scelta in corso: un thread
 * chiede a Google ogni {@code pollInterval} se ha finito, poi scarica e
 * importa una foto alla volta e chiude la sessione del Picker.
 */
@Service
public class SceltaGoogleFoto {

    private static final Logger log = LoggerFactory.getLogger(SceltaGoogleFoto.class);
    private static final int MAX_MESSAGGI = 30;
    /** Quanto vale il {@code state} del consenso. */
    static final Duration VALIDITA_STATO = Duration.ofMinutes(15);
    /** Un access token che scade entro poco si rinnova prima. */
    private static final Duration MARGINE = Duration.ofSeconds(60);
    private static final SecureRandom CASO = new SecureRandom();

    private final GoogleProperties properties;
    private final ClientGoogle client;
    private final CifraturaToken cifratura;
    private final FotoService fotoService;
    private final ArchivioFile archivio;
    private final MongoTemplate mongo;
    private final Clock clock;
    private final ExecutorService esecutore = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "google-foto");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Token> accessi = new ConcurrentHashMap<>();
    private final Map<String, Lavoro> scelte = new ConcurrentHashMap<>();

    @Autowired
    SceltaGoogleFoto(GoogleProperties properties, ClientGoogle client, CifraturaToken cifratura,
            FotoService fotoService, ArchivioFile archivio, MongoTemplate mongo, Optional<Clock> clock) {
        this(properties, client, cifratura, fotoService, archivio, mongo, clock.orElse(Clock.systemDefaultZone()));
    }

    SceltaGoogleFoto(GoogleProperties properties, ClientGoogle client, CifraturaToken cifratura,
            FotoService fotoService, ArchivioFile archivio, MongoTemplate mongo, Clock clock) {
        this.properties = properties;
        this.client = client;
        this.cifratura = cifratura;
        this.fotoService = fotoService;
        this.archivio = archivio;
        this.mongo = mongo;
        this.clock = clock;
    }

    // ------------------------------------------------------------ per l'API

    public enum Fase { ATTESA_SCELTA, SCARICO, FINITA, ANNULLATA, SCADUTA, FALLITA }

    /**
     * Una scelta in corso o l'ultima.
     *
     * @param pickerUri la pagina di Google da aprire, già con {@code /autoclose}
     */
    public record StatoScelta(String id, Fase fase, boolean inCorso, String pickerUri, Instant iniziataIl,
            Instant finitaIl, int totali, int fatte, int nuove, int giaPresenti, int errori, List<String> messaggi,
            String errore) {
    }

    /** Per l'app: la funzione c'è ({@code configurato}), questo utente è collegato, la sua scelta. */
    public record Collegamento(boolean configurato, boolean collegato, Instant collegatoIl, StatoScelta scelta) {
    }

    public boolean configurato() {
        return properties.configurato();
    }

    public Collegamento collegamento(String utente) {
        if (!configurato()) {
            return new Collegamento(false, false, null, null);
        }
        TokenGoogle t = mongo.findById(utente, TokenGoogle.class);
        Lavoro l = scelte.get(utente);
        return new Collegamento(true, t != null, t != null ? t.collegatoIl() : null, l != null ? l.stato() : null);
    }

    // ------------------------------------------------------------ collegamento (OAuth)

    /** Un {@code state} nuovo: va in sessione con l'ora e l'utente, Google lo rimanda al callback. */
    public record StatoOAuth(String valore, String utente, Instant creatoIl) implements java.io.Serializable {
    }

    public StatoOAuth nuovoStato(String utente) {
        byte[] b = new byte[32];
        CASO.nextBytes(b);
        return new StatoOAuth(Base64.getUrlEncoder().withoutPadding().encodeToString(b), utente, clock.instant());
    }

    public String urlConsenso(StatoOAuth stato, String redirectUri) {
        richiedeConfigurazione();
        return client.urlConsenso(stato.valore(), redirectUri);
    }

    /**
     * True se il {@code state} arrivato è quello in sessione, dello stesso
     * utente e ancora valido. Confronto a tempo costante.
     */
    public boolean statoValido(StatoOAuth inSessione, String arrivato, String utente) {
        if (inSessione == null || arrivato == null || !inSessione.utente().equals(utente)
                || inSessione.creatoIl().plus(VALIDITA_STATO).isBefore(clock.instant())) {
            return false;
        }
        return MessageDigest.isEqual(inSessione.valore().getBytes(), arrivato.getBytes());
    }

    /** Scambia il codice di Google e salva il refresh token cifrato. */
    public void collega(String utente, String codice, String redirectUri) {
        richiedeConfigurazione();
        Token t = client.scambiaCodice(codice, redirectUri, clock.instant());
        if (t.refresh() == null) {
            throw new GoogleNonRiesce("Google non ha dato l'accesso permanente: scollega l'app da "
                    + "myaccount.google.com/permissions e riprova", 0);
        }
        mongo.save(new TokenGoogle(utente, cifratura.cifra(t.refresh(), utente), clock.instant()));
        accessi.put(utente, t);
        log.info("Google collegato per {}", utente);
    }

    /** "Scollega Google": revoca su Google e cancella il token. True se c'era. */
    public boolean scollega(String utente) {
        TokenGoogle t = mongo.findById(utente, TokenGoogle.class);
        accessi.remove(utente);
        if (t == null) {
            return false;
        }
        try {
            client.revoca(cifratura.decifra(t.refreshCifrato(), utente));
        } catch (RuntimeException e) {
            // Il token si cancella lo stesso: da qui non si usa più. Su Google si toglie da myaccount.
            log.warn("Revoca su Google per {} non riuscita: {}", utente, e.getMessage());
        }
        mongo.remove(Query.query(Criteria.where("_id").is(utente)), TokenGoogle.class);
        log.info("Google scollegato per {}", utente);
        return true;
    }

    /** Un access token valido: quello in memoria o uno nuovo dal refresh token. */
    private String accesso(String utente) {
        Token t = accessi.get(utente);
        if (t != null && t.scade().minus(MARGINE).isAfter(clock.instant())) {
            return t.accesso();
        }
        TokenGoogle salvato = mongo.findById(utente, TokenGoogle.class);
        if (salvato == null) {
            throw new IllegalStateException("Google non è collegato: premi \"Collega Google\"");
        }
        String refresh;
        try {
            refresh = cifratura.decifra(salvato.refreshCifrato(), utente);
        } catch (IllegalArgumentException e) {
            mongo.remove(Query.query(Criteria.where("_id").is(utente)), TokenGoogle.class);
            throw new IllegalStateException("Il collegamento a Google non vale più (chiave cambiata): ricollega Google");
        }
        try {
            Token nuovo = client.rinnova(refresh, clock.instant());
            accessi.put(utente, nuovo);
            return nuovo.accesso();
        } catch (GoogleNonRiesce e) {
            if (e.stato() == 400 || e.stato() == 401) {
                // invalid_grant: revocato da Google, scaduto (app in prova: 7 giorni) o password cambiata.
                mongo.remove(Query.query(Criteria.where("_id").is(utente)), TokenGoogle.class);
                throw new IllegalStateException("Google non accetta più il collegamento (scaduto o revocato): "
                        + "ricollega Google");
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ la scelta

    /**
     * Crea una sessione del Picker e comincia ad aspettare la scelta. Già una
     * in corso, Google non collegato o cloud smontato: eccezione (409/503).
     */
    public StatoScelta nuova(String utente) {
        richiedeConfigurazione();
        archivio.verificaDisponibile();
        Lavoro attuale = scelte.get(utente);
        if (attuale != null && attuale.inCorso()) {
            throw new IllegalStateException("Hai già una scelta da Google Foto in corso: finiscila o annullala");
        }
        SessionePicker s = client.creaSessione(accesso(utente));
        String uri = s.pickerUri();
        if (uri == null || !uri.startsWith("https://")) {
            throw new GoogleNonRiesce("Google non ha dato l'indirizzo per scegliere", 0);
        }
        Lavoro l = new Lavoro(s.id(), uri.replaceAll("/+$", "") + "/autoclose", clock.instant());
        scelte.put(utente, l);
        esecutore.submit(() -> esegui(utente, l, s));
        log.info("Google Foto: {} sceglie (sessione {})", utente, s.id());
        return l.stato();
    }

    public Optional<StatoScelta> scelta(String utente) {
        return Optional.ofNullable(scelte.get(utente)).map(Lavoro::stato);
    }

    public boolean annulla(String utente) {
        Lavoro l = scelte.get(utente);
        if (l == null || !l.inCorso()) {
            return false;
        }
        l.annullata = true;
        return true;
    }

    /** Toglie dalla vista una scelta finita. */
    public void dimentica(String utente) {
        scelte.computeIfPresent(utente, (u, l) -> l.inCorso() ? l : null);
    }

    private void esegui(String utente, Lavoro l, SessionePicker sessione) {
        try {
            SessionePicker s = sessione;
            Instant limite = clock.instant().plus(s.tempoMassimo());
            while (!s.scelte()) {
                if (l.annullata) {
                    l.finisci(Fase.ANNULLATA, null, clock.instant());
                    return;
                }
                if (clock.instant().isAfter(limite) || s.scade() != null && clock.instant().isAfter(s.scade())) {
                    l.finisci(Fase.SCADUTA, "Nessuna foto scelta in tempo: riprova", clock.instant());
                    return;
                }
                Thread.sleep(s.attesa());
                s = client.leggiSessione(accesso(utente), l.sessione);
            }
            List<Scelta> tutte = new ArrayList<>();
            String pagina = null;
            do {
                PaginaScelte p = client.elencoScelte(accesso(utente), l.sessione, pagina);
                tutte.addAll(p.scelte());
                pagina = p.prossima();
            } while (pagina != null);
            l.scarico(tutte.size());
            log.info("Google Foto: {} ha scelto {} file", utente, tutte.size());
            for (Scelta scelta : tutte) {
                if (l.annullata) {
                    l.finisci(Fase.ANNULLATA, null, clock.instant());
                    return;
                }
                importa(utente, scelta, l);
            }
            l.finisci(Fase.FINITA, null, clock.instant());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            l.finisci(Fase.ANNULLATA, null, clock.instant());
        } catch (RuntimeException e) {
            log.warn("Google Foto: scelta di {} non riuscita: {}", utente, e.getMessage());
            l.finisci(Fase.FALLITA, e.getMessage(), clock.instant());
        } finally {
            try {
                client.eliminaSessione(accesso(utente), l.sessione);
            } catch (RuntimeException e) {
                log.debug("Sessione {} non chiusa: {}", l.sessione, e.getMessage());
            }
            StatoScelta fine = l.stato();
            log.info("Google Foto: scelta di {} {}: {} nuove, {} già presenti, {} errori", utente, fine.fase(),
                    fine.nuove(), fine.giaPresenti(), fine.errori());
        }
    }

    /** Scarica e importa un file; un errore conta, ma non ferma gli altri. */
    private void importa(String utente, Scelta s, Lavoro l) {
        String nome = nome(s);
        Path temporaneo = null;
        try {
            temporaneo = Files.createTempFile("fototimeline-google-", "." + estensione(nome));
            client.scarica(accesso(utente), s, temporaneo);
            LocalDateTime creata = s.creataIl() == null ? null : LocalDateTime.ofInstant(s.creataIl(), clock.getZone());
            Caricamento esito = fotoService.importa(nome, temporaneo, null, null, utente,
                    new MetadatiEsterni(creata, null, null, null, false));
            l.fatta(esito.esito(), nome + ": " + esito.messaggio());
        } catch (Exception e) {
            l.fatta(it.fototimeline.service.Risultati.Esito.ERRORE, nome + ": " + e.getMessage());
        } finally {
            if (temporaneo != null) {
                try {
                    Files.deleteIfExists(temporaneo);
                } catch (java.io.IOException e) {
                    log.debug("Temporaneo {} non tolto", temporaneo);
                }
            }
        }
    }

    /** Il nome del file come lo dà Google; senza, uno inventato con l'estensione giusta. */
    static String nome(Scelta s) {
        if (s.nome() != null && !s.nome().isBlank()) {
            return s.nome();
        }
        String tipo = s.tipoMime() == null ? "" : s.tipoMime().toLowerCase(Locale.ROOT);
        String estensione = switch (tipo) {
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            case "image/webp" -> "webp";
            case "image/heic", "image/heif" -> "heic";
            case "video/quicktime" -> "mov";
            case "video/mp4" -> "mp4";
            default -> s.video() ? "mp4" : "jpg";
        };
        return "google-" + (s.id() != null ? s.id().replaceAll("[^A-Za-z0-9_-]", "") : UUID.randomUUID()) + "."
                + estensione;
    }

    private static String estensione(String nome) {
        int punto = nome.lastIndexOf('.');
        return punto < 0 ? "bin" : nome.substring(punto + 1).replaceAll("[^A-Za-z0-9]", "");
    }

    private void richiedeConfigurazione() {
        if (!configurato()) {
            throw new NonConfigurato();
        }
    }

    /** Senza client ID e secret la funzione non c'è (404). */
    public static final class NonConfigurato extends RuntimeException {
        NonConfigurato() {
            super("Google Foto non è configurato su questo server");
        }
    }

    @PreDestroy
    void chiudi() {
        scelte.values().forEach(l -> l.annullata = true);
        esecutore.shutdownNow();
    }

    /** Lo stato di una scelta: lo aggiorna il thread, lo legge l'API. */
    private static final class Lavoro {
        private final String id = UUID.randomUUID().toString();
        private final String sessione;
        private final String pickerUri;
        private final Instant iniziataIl;
        private volatile boolean annullata;
        private Fase fase = Fase.ATTESA_SCELTA;
        private Instant finitaIl;
        private int totali;
        private int fatte;
        private int nuove;
        private int giaPresenti;
        private int errori;
        private String errore;
        private final List<String> messaggi = new ArrayList<>();

        Lavoro(String sessione, String pickerUri, Instant iniziataIl) {
            this.sessione = sessione;
            this.pickerUri = pickerUri;
            this.iniziataIl = iniziataIl;
        }

        synchronized boolean inCorso() {
            return finitaIl == null;
        }

        synchronized void scarico(int n) {
            fase = Fase.SCARICO;
            totali = n;
        }

        synchronized void fatta(it.fototimeline.service.Risultati.Esito esito, String messaggio) {
            fatte++;
            switch (esito) {
                case CARICATA -> nuove++;
                case DUPLICATA -> giaPresenti++;
                case ERRORE -> {
                    errori++;
                    if (messaggi.size() < MAX_MESSAGGI) {
                        messaggi.add(messaggio);
                    }
                }
            }
        }

        synchronized void finisci(Fase f, String motivo, Instant quando) {
            if (finitaIl != null) {
                return;
            }
            fase = f;
            errore = motivo;
            finitaIl = quando;
        }

        synchronized StatoScelta stato() {
            return new StatoScelta(id, fase, finitaIl == null, pickerUri, iniziataIl, finitaIl, totali, fatte, nuove,
                    giaPresenti, errori, List.copyOf(messaggi), errore);
        }
    }
}
