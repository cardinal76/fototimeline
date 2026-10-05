package it.fototimeline.ricordi;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * "Accadde oggi" su Telegram: i valori iniziali. Attivo, ora, numero di foto
 * e "Nessun ricordo oggi" si cambiano poi dall'app (salvati in Mongo).
 *
 * @param attivo        se mandare i ricordi ogni mattina (servono anche token e chat di Telegram)
 * @param ora           da che ora mandarli, "HH:mm" nel {@code fuso}
 * @param foto          quante foto al massimo (1–10)
 * @param chat          la chat dei ricordi; vuota = quella degli avvisi ({@code fototimeline.telegram.chat})
 * @param fuso          il fuso dell'ora e del "giorno di oggi"
 * @param nessunRicordo se true, nei giorni senza foto manda "Nessun ricordo oggi"
 */
@ConfigurationProperties("fototimeline.ricordi-telegram")
public record RicordiTelegramProperties(Boolean attivo, String ora, Integer foto, String chat, String fuso,
        Boolean nessunRicordo) {

    public RicordiTelegramProperties {
        attivo = attivo == null || attivo;
        ora = ora == null || ora.isBlank() ? "08:00" : ora.trim();
        foto = foto == null ? 6 : foto;
        nessunRicordo = nessunRicordo != null && nessunRicordo;
        fuso = fuso == null || fuso.isBlank() ? "Europe/Rome" : fuso.trim();
    }

    public ZoneId zona() {
        try {
            return ZoneId.of(fuso);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Fuso dei ricordi non valido: " + fuso, e);
        }
    }

    /** "8:30" o "08:30" → 08:30; IllegalArgumentException se non è un'ora. */
    public static LocalTime leggiOra(String ora) {
        try {
            String[] p = ora.trim().split(":");
            if (p.length != 2) {
                throw new IllegalArgumentException();
            }
            return LocalTime.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Ora non valida: \"" + ora + "\" (serve HH:mm, per esempio 08:00)");
        }
    }
}
