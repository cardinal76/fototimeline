package it.fototimeline.salute;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import it.fototimeline.salute.Salute.Rapporto;

/**
 * Ogni tanto controlla la salute e scrive su Telegram solo quando una voce
 * cambia stato: diventa rossa, torna verde ("risolto") e, se
 * {@code avvisa-attenzione}, diventa gialla. Gli stati visti si ricordano in
 * Mongo: un riavvio non rimanda tutto.
 *
 * <p>Se l'invio non riesce gli stati restano quelli di prima, così al
 * controllo dopo si riprova.
 */
@Service
public class AvvisiSalute {

    private static final Logger log = LoggerFactory.getLogger(AvvisiSalute.class);

    private final Salute salute;
    private final Telegram telegram;
    private final TelegramProperties properties;
    private final MongoTemplate mongo;

    public AvvisiSalute(Salute salute, Telegram telegram, TelegramProperties properties, MongoTemplate mongo) {
        this.salute = salute;
        this.telegram = telegram;
        this.properties = properties;
        this.mongo = mongo;
    }

    @Scheduled(fixedDelayString = "${fototimeline.telegram.controllo:PT15M}",
            initialDelayString = "${fototimeline.telegram.ritardo-iniziale:PT5M}")
    public void seNecessario() {
        if (!telegram.configurato()) {
            return;
        }
        try {
            controlla();
        } catch (RuntimeException e) {
            log.warn("Avvisi della salute non mandati: {}", telegram.senzaToken(e.getMessage()));
        }
    }

    /** Un controllo; restituisce quante voci sono cambiate (e quindi avvisate). */
    int controlla() {
        if (!telegram.configurato()) {
            return 0;
        }
        Rapporto rapporto = salute.controlla();
        StatiAvvisati salvati = mongo.findById(StatiAvvisati.ID, StatiAvvisati.class);
        Map<String, StatoSalute> prima = salvati != null && salvati.stati() != null ? salvati.stati() : Map.of();
        Map<String, StatoSalute> adesso = new HashMap<>();
        List<String> righe = new ArrayList<>();
        for (VoceSalute v : rapporto.voci()) {
            adesso.put(v.chiave(), v.stato());
            // Una voce mai vista si considera verde: se nasce rossa si avvisa.
            StatoSalute precedente = prima.getOrDefault(v.chiave(), StatoSalute.OK);
            if (rilevante(v.stato()) != rilevante(precedente)) {
                righe.add(riga(v, precedente));
            }
        }
        if (!righe.isEmpty()) {
            StringBuilder testo = new StringBuilder("<b>FotoTimeline</b>\n").append(String.join("\n", righe));
            String link = properties.link();
            if (link != null) {
                testo.append("\n\n<a href=\"").append(Telegram.html(link)).append("\">Apri l'app</a>");
            }
            telegram.invia(testo.toString());
            log.info("Avviso Telegram: {} voci cambiate", righe.size());
        }
        if (!adesso.equals(prima)) {
            mongo.save(new StatiAvvisati(StatiAvvisati.ID, adesso));
        }
        return righe.size();
    }

    /** Con avvisa-attenzione spento il giallo conta come verde. */
    private StatoSalute rilevante(StatoSalute s) {
        return s == StatoSalute.ATTENZIONE && !properties.avvisaAttenzione() ? StatoSalute.OK : s;
    }

    private String riga(VoceSalute v, StatoSalute precedente) {
        String titolo = "<b>" + Telegram.html(v.titolo()) + "</b>";
        if (rilevante(v.stato()) == StatoSalute.OK) {
            return "✅ " + titolo + ": risolto" + (v.stato() == StatoSalute.OK ? "" : " (ora " + nome(v.stato()) + ")")
                    + "\n" + Telegram.html(v.messaggio());
        }
        String simbolo = v.stato() == StatoSalute.ERRORE ? "🔴" : "🟡";
        return simbolo + " " + titolo + ": " + nome(v.stato()) + "\n" + Telegram.html(v.messaggio());
    }

    private static String nome(StatoSalute s) {
        return switch (s) {
            case OK -> "OK";
            case ATTENZIONE -> "attenzione";
            case ERRORE -> "errore";
        };
    }

    /** "Manda un messaggio di prova" dalla pagina. */
    public void prova() {
        if (!telegram.configurato()) {
            throw new IllegalStateException("Avvisi Telegram spenti: imposta FOTOTIMELINE_TELEGRAM_TOKEN e "
                    + "FOTOTIMELINE_TELEGRAM_CHAT nel .env del server (DEPLOY.md) e rilascia");
        }
        Rapporto r = salute.controlla();
        StringBuilder testo = new StringBuilder("<b>FotoTimeline</b>: messaggio di prova.\nGli avvisi arrivano qui. "
                + "Adesso: " + nome(r.stato()) + ".");
        String link = properties.link();
        if (link != null) {
            testo.append("\n\n<a href=\"").append(Telegram.html(link)).append("\">Apri l'app</a>");
        }
        telegram.invia(testo.toString());
    }
}
