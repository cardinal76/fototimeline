package it.fototimeline.luoghi;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** I nomi dei luoghi confrontati senza maiuscole né accenti: "parigi" trova "Parigi", "ile" "Île-de-France". */
final class Nomi {

    private static final Pattern SEGNI = Pattern.compile("\\p{M}+");
    /** Fin qui i caratteri si piegano con una tabella (latino, greco, cirillico): il resto col Normalizer. */
    private static final int TABELLA = 0x0530;
    /** Il carattere piegato, o 0 se piegato diventa più di un carattere (ß, Æ…). */
    private static final char[] PIEGATI = new char[TABELLA];

    static {
        for (char c = 0; c < TABELLA; c++) {
            String p = lento(String.valueOf(c));
            PIEGATI[c] = p.length() == 1 ? p.charAt(0) : 0;
        }
    }

    private Nomi() {
    }

    /**
     * Minuscolo e senza accenti. La ricerca per nome lo fa su ogni punto
     * (225 mila): con la tabella, senza Normalizer né regex per i caratteri
     * comuni.
     */
    static String piega(String s) {
        if (s == null) {
            return "";
        }
        char[] fuori = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char p = c < TABELLA ? PIEGATI[c] : 0;
            if (p == 0) {
                return lento(s);
            }
            if (p != c && fuori == null) {
                fuori = s.toCharArray();
            }
            if (fuori != null) {
                fuori[i] = p;
            }
        }
        return fuori == null ? s : new String(fuori);
    }

    private static String lento(String s) {
        return SEGNI.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /**
     * Quanto {@code nome} somiglia a {@code cercato} (tutti e due già piegati):
     * 0 uguale, 1 comincia così, 2 una parola dentro comincia così ("den
     * haag" per "haag"), -1 niente.
     */
    static int corrisponde(String nome, String cercato) {
        if (!nome.startsWith(cercato)) {
            for (int da = nome.indexOf(cercato, 1); da > 0; da = nome.indexOf(cercato, da + 1)) {
                if (!Character.isLetterOrDigit(nome.charAt(da - 1))) {
                    return 2;
                }
            }
            return -1;
        }
        return nome.length() == cercato.length() ? 0 : 1;
    }
}
