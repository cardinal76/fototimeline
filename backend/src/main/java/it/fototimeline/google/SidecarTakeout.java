package it.fototimeline.google;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trova il JSON di Google Takeout di ogni foto o video di una cartella. I nomi
 * non sono regolari, e Google li ha cambiati più volte:
 * <ul>
 * <li>{@code IMG_1.jpg} → {@code IMG_1.jpg.json}, o (dal 2024)
 *     {@code IMG_1.jpg.supplemental-metadata.json}, anche troncato
 *     ({@code IMG_1.jpg.supplemental-metad.json}, {@code IMG_1.jpg.suppl.json});</li>
 * <li>nomi lunghi: il nome del JSON (senza {@code .json}) è tagliato a 46-51
 *     caratteri ({@code Screenshot_2020…_com.whatsa.json}), e a volte anche il
 *     nome del file;</li>
 * <li>vecchi Takeout: {@code IMG_1.json}, senza l'estensione della foto;</li>
 * <li>doppioni di nome: {@code IMG_1(1).jpg} ↔ {@code IMG_1.jpg(1).json} (o
 *     {@code IMG_1.jpg.supplemental-metadata(1).json});</li>
 * <li>{@code IMG_1-edited.jpg} (in italiano {@code -modificato}) non ha un JSON
 *     suo: usa quello dell'originale;</li>
 * <li>Live Photo e foto in movimento: {@code IMG_1.HEIC} + {@code IMG_1.MOV},
 *     {@code PXL_1.MP.jpg} + {@code PXL_1.MP}; il video spesso non ha JSON e
 *     prende quello della foto.</li>
 * </ul>
 * Lavora su una cartella alla volta: i JSON di un'altra cartella non contano.
 */
final class SidecarTakeout {

    /** Sotto questa lunghezza un nome non è "tagliato": evita di abbinare IMG_1 a IMG_12. */
    static final int MINIMO_TRONCATO = 40;
    static final String SUPPLEMENTARE = "supplemental-metadata";
    /** I suffissi delle foto modificate in Google Foto, nelle lingue più probabili. */
    static final List<String> MODIFICATE = List.of("-edited", "-modificato", "-modificata", "-bearbeitet",
            "-modifié", "-editado", "-editada", "-bewerkt", "-edytowane");
    /** JSON che non sono di una foto: album, abbonamenti di stampa, commenti. */
    private static final Set<String> NON_SIDECAR = Set.of("metadata.json", "metadati.json", "metadatos.json",
            "métadonnées.json", "metadaten.json", "print-subscriptions.json", "shared_album_comments.json",
            "user-generated-memory-titles.json");
    private static final Pattern NUMERO_FINALE = Pattern.compile("^(.*)\\((\\d+)\\)$");
    private static final Pattern NUMERO_PRIMA_ESTENSIONE = Pattern.compile("^(.*)\\((\\d+)\\)(\\.[^.]+)$");

    private SidecarTakeout() {
    }

    /** True se è un JSON di metadati di una foto (non quello dell'album). */
    static boolean eSidecar(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        return n.endsWith(".json") && !NON_SIDECAR.contains(n);
    }

    /** True se è il JSON dell'album ({@code metadata.json}, in italiano {@code metadati.json}). */
    static boolean eMetadatiAlbum(String nome) {
        String n = nome.toLowerCase(Locale.ROOT);
        return n.equals("metadata.json") || n.equals("metadati.json") || n.equals("metadatos.json")
                || n.equals("métadonnées.json") || n.equals("metadaten.json");
    }

    /** Un JSON: il nome senza {@code .json} e senza {@code (n)} finale, e quel numero (0 se non c'è). */
    private record Json(String nome, String radice, int numero) {

        static Json da(String nome) {
            String r = nome.substring(0, nome.length() - ".json".length());
            Matcher m = NUMERO_FINALE.matcher(r);
            if (m.matches()) {
                return new Json(nome, m.group(1), Integer.parseInt(m.group(2)));
            }
            return new Json(nome, r, 0);
        }
    }

    /** Un nome da cercare tra le radici dei JSON, col numero che deve avere. */
    private record Candidato(String chiave, int numero) {
    }

    /**
     * Per ogni foto o video di {@code media} (nomi senza cartella, della
     * stessa cartella) il nome del suo JSON tra {@code json}; chi non ce l'ha
     * non compare.
     */
    static Map<String, String> abbina(Collection<String> media, Collection<String> json) {
        List<Json> sidecar = json.stream().filter(SidecarTakeout::eSidecar).map(Json::da).toList();
        Map<String, String> trovati = new LinkedHashMap<>();
        for (String m : media) {
            String j = cerca(m, sidecar);
            if (j != null) {
                trovati.put(m, j);
            }
        }
        // Live Photo e foto in movimento: il video senza JSON prende quello della foto con la stessa radice.
        Map<String, String> perRadice = new HashMap<>();
        trovati.forEach((m, j) -> perRadice.putIfAbsent(radiceLive(m), j));
        for (String m : media) {
            if (!trovati.containsKey(m)) {
                String j = perRadice.get(radiceLive(m));
                if (j != null) {
                    trovati.put(m, j);
                }
            }
        }
        return trovati;
    }

    /** Il JSON di un file, o null. */
    static String cerca(String media, List<Json> sidecar) {
        for (Candidato c : candidati(media)) {
            String j = migliore(c, sidecar);
            if (j != null) {
                return j;
            }
        }
        return null;
    }

    /**
     * I nomi da provare, dal più preciso: il nome com'è, poi senza
     * {@code (n)} (che va cercato nel JSON), poi senza {@code -edited}.
     */
    private static List<Candidato> candidati(String media) {
        List<Candidato> c = new ArrayList<>();
        c.add(new Candidato(media, 0));
        Matcher m = NUMERO_PRIMA_ESTENSIONE.matcher(media);
        String senzaNumero = media;
        int numero = 0;
        if (m.matches()) {
            senzaNumero = m.group(1) + m.group(3);
            numero = Integer.parseInt(m.group(2));
            c.add(new Candidato(senzaNumero, numero));
        }
        String originale = senzaModifica(senzaNumero);
        if (originale != null) {
            c.add(new Candidato(originale, numero));
            if (numero != 0) {
                c.add(new Candidato(originale, 0));
            }
        }
        return c;
    }

    /** {@code IMG_1-edited.jpg} → {@code IMG_1.jpg}; null se non è una foto modificata. */
    static String senzaModifica(String nome) {
        int punto = nome.lastIndexOf('.');
        String base = punto > 0 ? nome.substring(0, punto) : nome;
        String estensione = punto > 0 ? nome.substring(punto) : "";
        String minuscolo = base.toLowerCase(Locale.ROOT);
        for (String suffisso : MODIFICATE) {
            if (minuscolo.endsWith(suffisso) && base.length() > suffisso.length()) {
                return base.substring(0, base.length() - suffisso.length()) + estensione;
            }
        }
        return null;
    }

    /**
     * Il JSON migliore per un nome: identico; con {@code .supplemental-metadata}
     * (anche tagliato); senza l'estensione della foto; tagliato a 46-51
     * caratteri. A parità vince quello col nome più lungo (il più preciso).
     */
    private static String migliore(Candidato c, List<Json> sidecar) {
        String chiave = c.chiave();
        int punto = chiave.lastIndexOf('.');
        String senzaEstensione = punto > 0 ? chiave.substring(0, punto) : chiave;
        String completo = chiave + "." + SUPPLEMENTARE;
        Json scelto = null;
        int punteggioScelto = Integer.MAX_VALUE;
        for (Json j : sidecar) {
            if (j.numero() != c.numero()) {
                continue;
            }
            String r = j.radice();
            int punteggio;
            if (r.equals(chiave)) {
                punteggio = 0;
            } else if (r.startsWith(chiave + ".") && SUPPLEMENTARE.startsWith(r.substring(chiave.length() + 1))
                    && r.length() > chiave.length() + 1) {
                punteggio = 1;
            } else if (r.equals(senzaEstensione)) {
                punteggio = 2;
            } else if (r.length() >= MINIMO_TRONCATO && completo.startsWith(r)) {
                // Il nome del JSON è tagliato.
                punteggio = 3;
            } else if (senzaEstensione.length() >= MINIMO_TRONCATO && r.startsWith(senzaEstensione)) {
                // È tagliato il nome del file, non quello del JSON.
                punteggio = 4;
            } else {
                continue;
            }
            if (punteggio < punteggioScelto
                    || punteggio == punteggioScelto && r.length() > scelto.radice().length()) {
                scelto = j;
                punteggioScelto = punteggio;
            }
        }
        return scelto == null ? null : scelto.nome();
    }

    /**
     * La radice comune di foto e video di una Live Photo: {@code IMG_1.HEIC} e
     * {@code IMG_1.MOV} → {@code IMG_1}; {@code PXL_1.MP.jpg} e {@code PXL_1.MP}
     * → {@code PXL_1}.
     */
    static String radiceLive(String nome) {
        String r = nome;
        int punto = r.lastIndexOf('.');
        if (punto > 0) {
            r = r.substring(0, punto);
        }
        if (r.toUpperCase(Locale.ROOT).endsWith(".MP")) {
            r = r.substring(0, r.length() - 3);
        }
        return r.toLowerCase(Locale.ROOT);
    }
}
