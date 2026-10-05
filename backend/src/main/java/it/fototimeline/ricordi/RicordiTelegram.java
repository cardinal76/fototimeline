package it.fototimeline.ricordi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.condivisione.CondivisioniService;
import it.fototimeline.dominio.Foto;
import it.fototimeline.quasiuguali.Impronta;
import it.fototimeline.quasiuguali.QuasiUgualiProperties;
import it.fototimeline.salute.Telegram;
import it.fototimeline.salute.TelegramProperties;
import it.fototimeline.service.ArchivioFile;

/**
 * "Accadde oggi" su Telegram: ogni mattina, dall'ora impostata, le foto dello
 * stesso giorno negli anni passati, in un album con una didascalia e il link
 * all'app (mai un link pubblico).
 *
 * <p>Il giorno fatto si ricorda in Mongo ({@code ultimoGiorno}) e si prenota
 * prima di mandare: mai due invii lo stesso giorno, anche dopo un riavvio. Se
 * all'ora giusta l'app era giù, manda al primo controllo dopo, finché è lo
 * stesso giorno. Se l'invio non riesce il giorno si libera e si riprova.
 *
 * <p>Le immagini sono sempre JPEG del disco locale senza EXIF (niente GPS, mai
 * l'originale): la vista ridotta dei link di condivisione, fatta adesso se il
 * cloud è montato, altrimenti la miniatura.
 */
@Service
public class RicordiTelegram {

    private static final Logger log = LoggerFactory.getLogger(RicordiTelegram.class);
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("d MMMM", Locale.ITALIAN);

    private final MongoTemplate mongo;
    private final Telegram telegram;
    private final TelegramProperties telegramProperties;
    private final RicordiTelegramProperties properties;
    private final ArchivioFile archivio;
    private final CondivisioniService condivisioni;
    /** Più vicine di così due foto scelte sono quasi doppioni: si tiene la prima. */
    private final int sogliaSimili;
    private final Clock clock;

    public RicordiTelegram(MongoTemplate mongo, Telegram telegram, TelegramProperties telegramProperties,
            RicordiTelegramProperties properties, ArchivioFile archivio, CondivisioniService condivisioni,
            QuasiUgualiProperties quasiUguali, Optional<Clock> clock) {
        this.mongo = mongo;
        this.telegram = telegram;
        this.telegramProperties = telegramProperties;
        this.properties = properties;
        this.archivio = archivio;
        this.condivisioni = condivisioni;
        // Quella delle raffiche: qui contano anche le foto quasi uguali ma non identiche.
        this.sogliaSimili = quasiUguali.sogliaRaffica();
        this.clock = clock.orElse(Clock.systemUTC());
        properties.zona();
    }

    // ------------------------------------------------------------ impostazioni

    /** Quello che si cambia dall'app. */
    public record Impostazioni(boolean attivo, String ora, int foto, boolean nessunRicordo) {
    }

    /** GET /api/ricordi-telegram: impostazioni, se Telegram è pronto e l'ultimo invio. Mai token né chat. */
    public record Stato(boolean attivo, String ora, int foto, boolean nessunRicordo, boolean telegramConfigurato,
            boolean chatSeparata, String fuso, String ultimoGiorno, Instant ultimoInvioIl, String ultimoEsito) {
    }

    public Stato stato() {
        Impostazioni i = impostazioni();
        ImpostazioniRicordi d = documento();
        return new Stato(i.attivo(), i.ora(), i.foto(), i.nessunRicordo(), telegram.configurato(),
                properties.chat() != null && !properties.chat().isBlank(), properties.fuso(),
                d != null ? d.ultimoGiorno() : null, d != null ? d.ultimoInvioIl() : null,
                d != null ? d.ultimoEsito() : null);
    }

    public Stato salva(Impostazioni i) {
        if (i.foto() < 1 || i.foto() > Telegram.MASSIMO_FOTO) {
            throw new IllegalArgumentException("Le foto vanno da 1 a " + Telegram.MASSIMO_FOTO);
        }
        var ora = RicordiTelegramProperties.leggiOra(i.ora());
        mongo.upsert(perId(), new Update()
                .set("attivo", i.attivo())
                .set("ora", "%02d:%02d".formatted(ora.getHour(), ora.getMinute()))
                .set("foto", i.foto())
                .set("nessunRicordo", i.nessunRicordo()), ImpostazioniRicordi.class);
        return stato();
    }

    /** Quelle salvate dall'app, e per quelle mai toccate i valori dell'env. */
    Impostazioni impostazioni() {
        ImpostazioniRicordi d = documento();
        return new Impostazioni(
                d != null && d.attivo() != null ? d.attivo() : properties.attivo(),
                d != null && d.ora() != null ? d.ora() : properties.ora(),
                Math.clamp(d != null && d.foto() != null ? d.foto() : properties.foto(), 1, Telegram.MASSIMO_FOTO),
                d != null && d.nessunRicordo() != null ? d.nessunRicordo() : properties.nessunRicordo());
    }

    private ImpostazioniRicordi documento() {
        return mongo.findById(ImpostazioniRicordi.ID, ImpostazioniRicordi.class);
    }

    private static Query perId() {
        return Query.query(Criteria.where("_id").is(ImpostazioniRicordi.ID));
    }

    // ------------------------------------------------------------ invio

    @Scheduled(fixedDelayString = "${fototimeline.ricordi-telegram.controllo:PT5M}",
            initialDelayString = "${fototimeline.ricordi-telegram.ritardo-iniziale:PT1M}")
    public void seNecessario() {
        try {
            controlla();
        } catch (RuntimeException e) {
            log.warn("Ricordi su Telegram non mandati: {}", telegram.senzaToken(e.getMessage()));
        }
    }

    /** Un controllo: true se oggi è stato fatto adesso (mandato, o niente da mandare). */
    boolean controlla() {
        if (!telegram.configurato()) {
            return false;
        }
        Impostazioni imp = impostazioni();
        if (!imp.attivo()) {
            return false;
        }
        ZonedDateTime adesso = ZonedDateTime.now(clock.withZone(properties.zona()));
        if (adesso.toLocalTime().isBefore(RicordiTelegramProperties.leggiOra(imp.ora()))) {
            return false;
        }
        LocalDate oggi = adesso.toLocalDate();
        String giorno = oggi.toString();
        ImpostazioniRicordi d = documento();
        if (d != null && giorno.equals(d.ultimoGiorno())) {
            return false;
        }
        String precedente = d != null ? d.ultimoGiorno() : null;
        if (!prenota(giorno)) {
            return false;
        }
        try {
            String esito = manda(oggi, imp, false);
            esito(esito);
            log.info("Ricordi su Telegram del {}: {}", giorno, esito);
            return true;
        } catch (RuntimeException e) {
            // Il giorno torna libero: al controllo dopo si riprova.
            mongo.updateFirst(Query.query(Criteria.where("_id").is(ImpostazioniRicordi.ID).and("ultimoGiorno").is(giorno)),
                    new Update().set("ultimoGiorno", precedente)
                            .set("ultimoEsito", "Non mandati: " + telegram.senzaToken(e.getMessage()))
                            .set("ultimoInvioIl", clock.instant()),
                    ImpostazioniRicordi.class);
            throw e;
        }
    }

    /** Segna il giorno come fatto solo se non lo era: due controlli insieme non mandano due volte. */
    private boolean prenota(String giorno) {
        mongo.upsert(perId(), new Update().setOnInsert("ultimoGiorno", null), ImpostazioniRicordi.class);
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(ImpostazioniRicordi.ID).and("ultimoGiorno").ne(giorno)),
                new Update().set("ultimoGiorno", giorno), ImpostazioniRicordi.class).getModifiedCount() == 1;
    }

    private void esito(String esito) {
        mongo.updateFirst(perId(), new Update().set("ultimoEsito", esito).set("ultimoInvioIl", clock.instant()),
                ImpostazioniRicordi.class);
    }

    /** L'esito di "Manda ora una prova". */
    public record Esito(String messaggio) {
    }

    /**
     * "Manda ora una prova": i ricordi di oggi subito, anche se già mandati o
     * spenti; senza ricordi manda "Nessun ricordo oggi", così si vede che
     * arriva. Non segna il giorno come fatto.
     */
    public Esito prova() {
        if (!telegram.configurato()) {
            throw new IllegalStateException("Telegram non configurato: imposta FOTOTIMELINE_TELEGRAM_TOKEN e "
                    + "FOTOTIMELINE_TELEGRAM_CHAT nel .env del server (DEPLOY.md) e rilascia");
        }
        String esito = manda(LocalDate.now(clock.withZone(properties.zona())), impostazioni(), true);
        esito("Prova: " + esito);
        return new Esito(esito);
    }

    /** Manda i ricordi di {@code oggi}; restituisce cosa ha fatto, per l'app e i log. */
    private String manda(LocalDate oggi, Impostazioni imp, boolean prova) {
        List<Foto> tutte = delGiorno(oggi);
        String chat = properties.chat();
        if (tutte.isEmpty()) {
            if (!prova && !imp.nessunRicordo()) {
                return "Nessun ricordo oggi: niente messaggio";
            }
            telegram.invia(chat, "📅 <b>" + DATA.format(oggi) + "</b> · Nessun ricordo oggi");
            return "Nessun ricordo oggi: mandato il messaggio";
        }
        List<Scelta> scelte = scegli(tutte, imp.foto(), this::immagine);
        String didascalia = didascalia(oggi, scelte.isEmpty() ? tutte : scelte.stream().map(Scelta::foto).toList(),
                tutte, scelte.size());
        if (scelte.isEmpty()) {
            // Solo video, o nessuna immagine sul disco: almeno il testo col link.
            telegram.invia(chat, didascalia);
            return "Mandato il testo: nessuna foto da mostrare";
        }
        telegram.inviaFoto(chat, scelte.stream().map(Scelta::file).toList(), didascalia);
        return scelte.size() == 1 ? "Mandata 1 foto" : "Mandate " + scelte.size() + " foto";
    }

    /** Foto e video scattati lo stesso giorno e mese negli anni prima, in ordine di scatto. */
    private List<Foto> delGiorno(LocalDate oggi) {
        String meseGiorno = "-%02d-%02d".formatted(oggi.getMonthValue(), oggi.getDayOfMonth());
        Query query = Query.query(Criteria.where("giorno").regex(Pattern.quote(meseGiorno) + "$")
                .lt(oggi.getYear() + "-"))
                .with(Sort.by(Sort.Order.asc("scattataIl")));
        return mongo.find(query, Foto.class);
    }

    /**
     * Il JPEG da mandare, senza EXIF: la vista dei link di condivisione se c'è
     * già o se il cloud è montato per farla, altrimenti la miniatura; null se
     * sul disco non c'è niente.
     */
    private Path immagine(Foto f) {
        Path vista = archivio.vistaCondivisa(f.getId());
        if (Files.exists(vista)) {
            return vista;
        }
        if (archivio.disponibile()) {
            try {
                return condivisioni.vista(f);
            } catch (RuntimeException e) {
                log.debug("Vista ridotta di {} non fatta: {}", f.getId(), e.getMessage());
            }
        }
        Path miniatura = archivio.miniatura(f.getId());
        return Files.exists(miniatura) ? miniatura : null;
    }

    /** Una foto scelta e il file da caricare. */
    record Scelta(Foto foto, Path file) {
    }

    /**
     * Al massimo {@code massimo} foto, niente video: a giri, una per anno per
     * giro (prima l'anno più recente), e in ogni anno e in ogni giro prima le
     * preferite. Si salta una foto quasi uguale (impronta) a una già scelta, o
     * senza file ({@code file} restituisce null). Il risultato è in ordine di
     * scatto.
     */
    List<Scelta> scegli(List<Foto> tutte, int massimo, Function<Foto, Path> file) {
        Comparator<Foto> ordine = Comparator.comparing((Foto f) -> !f.isPreferita())
                .thenComparing(Foto::getScattataIl, Comparator.nullsLast(Comparator.naturalOrder()));
        Map<Integer, Deque<Foto>> perAnno = new TreeMap<>(Comparator.reverseOrder());
        tutte.stream().filter(f -> !f.isVideo() && f.getScattataIl() != null).sorted(ordine)
                .forEach(f -> perAnno.computeIfAbsent(f.getScattataIl().getYear(), a -> new ArrayDeque<>()).add(f));
        List<Scelta> scelte = new ArrayList<>();
        List<Deque<Foto>> code = new ArrayList<>(perAnno.values());
        while (scelte.size() < massimo && code.stream().anyMatch(c -> !c.isEmpty())) {
            // In ogni giro prima gli anni che hanno in testa una preferita (sort stabile: poi i più recenti).
            List<Deque<Foto>> giro = code.stream().filter(c -> !c.isEmpty())
                    .sorted(Comparator.comparing((Deque<Foto> c) -> !c.peek().isPreferita()))
                    .toList();
            for (Deque<Foto> coda : giro) {
                if (scelte.size() >= massimo) {
                    break;
                }
                while (!coda.isEmpty()) {
                    Foto f = coda.poll();
                    if (simile(f, scelte)) {
                        continue;
                    }
                    Path p = file.apply(f);
                    if (p != null) {
                        scelte.add(new Scelta(f, p));
                        break;
                    }
                }
            }
        }
        scelte.sort(Comparator.comparing(s -> s.foto().getScattataIl()));
        return scelte;
    }

    private boolean simile(Foto f, List<Scelta> scelte) {
        return f.getImpronta() != null && scelte.stream().anyMatch(s -> s.foto().getImpronta() != null
                && Impronta.distanza(f.getImpronta(), s.foto().getImpronta()) <= sogliaSimili);
    }

    /**
     * "📅 <b>5 ottobre</b> · 3 anni fa a Sperlonga"; con più anni una riga per
     * anno, dal più vecchio. Il luogo è il più frequente di quell'anno (tra
     * tutte le foto del giorno). In fondo il link all'app sulla sezione
     * "Accadde oggi", se c'è l'indirizzo.
     */
    String didascalia(LocalDate oggi, List<Foto> scelte, List<Foto> tutte, int mandate) {
        List<Integer> anni = scelte.stream().map(f -> f.getScattataIl().getYear()).distinct().sorted().toList();
        StringBuilder t = new StringBuilder("📅 <b>").append(DATA.format(oggi)).append("</b>");
        if (anni.size() == 1) {
            t.append(" · ").append(anno(oggi, anni.get(0), tutte));
        } else {
            for (int anno : anni) {
                t.append("\n• ").append(anno(oggi, anno, tutte));
            }
        }
        String link = telegramProperties.link();
        if (link != null) {
            t.append("\n\n<a href=\"").append(Telegram.html(link + "/?ricordi=oggi")).append("\">Tutte le foto di oggi</a>");
            if (tutte.size() > mandate) {
                t.append(" (").append(tutte.size()).append(')');
            }
        }
        return t.toString();
    }

    /** "3 anni fa a Sperlonga", "un anno fa". */
    private static String anno(LocalDate oggi, int anno, List<Foto> tutte) {
        int fa = oggi.getYear() - anno;
        String testo = fa == 1 ? "un anno fa" : fa + " anni fa";
        String luogo = tutte.stream()
                .filter(f -> f.getScattataIl() != null && f.getScattataIl().getYear() == anno)
                .map(Foto::getLuogo)
                .filter(l -> l != null && !l.isBlank())
                .collect(Collectors.groupingBy(l -> l, LinkedHashMap::new, Collectors.counting()))
                .entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey)
                .orElse(null);
        return luogo != null ? testo + " a " + Telegram.html(luogo) : testo;
    }
}
