package it.fototimeline.salute;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Avvisi su Telegram quando una voce della pagina "Salute" cambia stato.
 *
 * @param token            il token del bot (BotFather); vuoto = avvisi spenti.
 *                         Non va mai nei log né nelle risposte.
 * @param chat             la chat a cui scrivere (il chat id); vuota = avvisi spenti
 * @param avvisaAttenzione se true avvisa anche quando una voce diventa gialla;
 *                         se false solo per il rosso e quando torna a posto
 * @param indirizzoApp     il link all'app nei messaggi, per esempio
 *                         {@code foto.example.it} o {@code https://...}; vuoto = senza link
 */
@ConfigurationProperties("fototimeline.telegram")
public record TelegramProperties(String token, String chat, boolean avvisaAttenzione, String indirizzoApp) {

    public boolean configurato() {
        return token != null && !token.isBlank() && chat != null && !chat.isBlank();
    }

    /** {@code foto.example.it} → {@code https://foto.example.it}; null se manca. */
    public String link() {
        if (indirizzoApp == null || indirizzoApp.isBlank()) {
            return null;
        }
        String i = indirizzoApp.trim();
        return i.contains("://") ? i : "https://" + i;
    }
}
