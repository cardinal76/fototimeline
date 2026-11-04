package it.fototimeline.quasiuguali;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import it.fototimeline.quasiuguali.Raggruppamento.Voce;

/**
 * Quando due foto sono "quasi uguali". Il pHash da solo non basta: con
 * 140.000 foto, a 6 bit si trovavano coppie di foto diverse con la stessa
 * disposizione delle masse (cielo sopra, prato sotto), e soprattutto le foto
 * buie, uniformi o i documenti, dove le basse frequenze sono quasi tutte zero
 * e i bit del pHash li decide il rumore. Ora servono <b>tutti</b> i segnali:
 *
 * <ul>
 *   <li><b>forma</b>: pHash (bit di differenza su 64);
 *   <li><b>bordi</b>: dHash, che guarda i confini tra zone chiare e scure;
 *   <li><b>colori</b>: 4×4 riquadri di cromaticità (r/(r+g+b), g/(r+g+b)),
 *       che non cambia se la foto è solo più chiara o più scura;
 *   <li><b>proporzioni</b>: larghezza/altezza entro una tolleranza;
 *   <li><b>contenuto</b> (CLIP, se visione ha i vettori di tutte e due):
 *       sotto la soglia la coppia salta, è l'unico segnale "semantico".
 * </ul>
 *
 * <p>Il tempo sposta le soglie: una raffica (data EXIF a pochi secondi) ne ha
 * di più larghe, foto dello stesso momento (entro qualche ora) un po' più
 * larghe; due foto con data EXIF a giorni di distanza le hanno più strette.
 * Le foto povere di informazione (buie, quasi bianche, piccole, a basso
 * contrasto) vogliono quasi la stessa immagine; quelle uniformi non si
 * propongono mai. Senza firma (foto di prima, finché non passa "Calcola
 * impronte") basta il pHash, ma quasi identico.
 */
final class Confronto {

    /** Quanto è larga la ricerca, scelta nella pagina; la predefinita è {@link #SEVERA}. */
    enum Sensibilita {
        SEVERA(new Soglie(6, 10, 0.015, 10, 14, 8, 12, 0.08, 0.93, 2)),
        NORMALE(new Soglie(8, 14, 0.025, 12, 18, 10, 16, 0.12, 0.90, 2)),
        LARGA(new Soglie(8, 18, 0.035, 14, 22, 10, 20, 0.20, 0.86, 4));

        final Soglie soglie;

        Sensibilita(Soglie soglie) {
            this.soglie = soglie;
        }
    }

    /**
     * @param forma          pHash: bit di differenza massimi
     * @param bordi          dHash: bit di differenza massimi
     * @param colori         differenza media di cromaticità massima (0..1)
     * @param formaRaffica   pHash in una raffica (data EXIF a pochi secondi)
     * @param bordiRaffica   dHash in una raffica
     * @param formaVicine    pHash per foto dello stesso momento (qualche ora)
     * @param bordiVicine    dHash per foto dello stesso momento
     * @param proporzioni    |ln(rapporto A / rapporto B)| massimo
     * @param contenuto      coseno CLIP minimo, quando c'è
     * @param formaSenzaFirma pHash quando una delle due non ha ancora la firma
     */
    record Soglie(int forma, int bordi, double colori, int formaRaffica, int bordiRaffica, int formaVicine,
            int bordiVicine, double proporzioni, double contenuto, int formaSenzaFirma) {
    }

    /** Per le foto povere di informazione: quasi la stessa immagine, a ogni sensibilità. */
    static final int FORMA_POVERE = 4;
    static final int BORDI_POVERE = 6;
    static final double COLORI_POVERE = 0.015;
    static final int LUCE_POVERE = 10;
    /** Due foto con data EXIF più lontane di così: soglie più strette. */
    static final Duration LONTANE = Duration.ofDays(2);
    /** Sotto i 100.000 pixel (320×240) l'immagine dice troppo poco. */
    static final long AREA_MINIMA = 100_000;

    /** Come si sono confrontate: nella raffica, nello stesso momento, lontane nel tempo, senza date. */
    enum Tempo { RAFFICA, VICINE, LONTANE, QUALSIASI }

    /** Le date, una volta per tutte le coppie. */
    record Finestre(Duration raffica, Duration vicine) {
    }

    /**
     * L'esito di una coppia.
     *
     * @param bordi   null senza firma
     * @param colori  null senza firma
     * @param povera  una delle due è povera di informazione: confronto stretto
     */
    record Esito(boolean uguali, int forma, Integer bordi, Double colori, Tempo tempo, boolean povera) {

        /** Per ordinare le coppie: prima le più simili. */
        double costo() {
            return forma / 64.0 + (bordi == null ? 0.5 : bordi / 64.0) + (colori == null ? 0.5 : colori * 4);
        }

        /** Da 0 a 100, per mostrarla: la media di forma, bordi e colori (32 bit = foto qualsiasi). */
        int percentuale(Double contenuto) {
            List<Double> parti = new ArrayList<>();
            parti.add(1 - Math.min(forma, 32) / 32.0);
            if (bordi != null) {
                parti.add(1 - Math.min(bordi, 32) / 32.0);
            }
            if (colori != null) {
                parti.add(1 - Math.min(colori, 0.1) / 0.1);
            }
            if (contenuto != null) {
                parti.add(Math.clamp(contenuto, 0, 1));
            }
            return (int) Math.round(100 * parti.stream().mapToDouble(Double::doubleValue).average().orElse(0));
        }
    }

    private Confronto() {
    }

    /** Senza informazione: uniforme, il pHash è rumore. Non si propone mai. */
    static boolean piatta(Voce v) {
        return v.firma() != null && v.firma().contrasto() < 2;
    }

    /** Povera di informazione: buia, quasi bianca (documenti, schermate), a basso contrasto o piccola. */
    static boolean povera(Voce v) {
        Firma f = v.firma();
        boolean piccola = v.larghezza() > 0 && v.altezza() > 0 && (long) v.larghezza() * v.altezza() < AREA_MINIMA;
        return piccola || f != null && (f.luce() < 40 || f.chiari() >= 50 || f.contrasto() < 10);
    }

    static Tempo tempo(Voce a, Voce b, Finestre finestre) {
        if (a.scattataIl() == null || b.scattataIl() == null) {
            return Tempo.QUALSIASI;
        }
        Duration d = Duration.between(a.scattataIl(), b.scattataIl()).abs();
        if (a.exif() && b.exif() && finestre.raffica() != null && d.compareTo(finestre.raffica()) <= 0) {
            return Tempo.RAFFICA;
        }
        if (finestre.vicine() != null && d.compareTo(finestre.vicine()) <= 0) {
            return Tempo.VICINE;
        }
        // Solo con l'EXIF: la data di una copia di WhatsApp è quella dell'invio.
        if (a.exif() && b.exif() && d.compareTo(LONTANE) > 0) {
            return Tempo.LONTANE;
        }
        return Tempo.QUALSIASI;
    }

    /** La soglia di pHash più larga che {@link #valuta} può accettare, per cercare le coppie. */
    static int formaMassima(Soglie s, Tempo tempo) {
        return switch (tempo) {
            case RAFFICA -> Math.max(s.formaRaffica(), s.forma());
            case VICINE -> Math.max(s.formaVicine(), s.forma());
            case LONTANE -> Math.max(s.forma() - 2, 0);
            case QUALSIASI -> s.forma();
        };
    }

    /** Due foto sono quasi uguali? Senza il contenuto (CLIP), che si guarda dopo e solo per chi passa. */
    static Esito valuta(Voce a, Voce b, Sensibilita sensibilita, Finestre finestre) {
        Soglie s = sensibilita.soglie;
        int forma = Impronta.distanza(a.impronta(), b.impronta());
        Tempo tempo = tempo(a, b, finestre);
        if (a.firma() == null || b.firma() == null) {
            return new Esito(forma <= s.formaSenzaFirma(), forma, null, null, tempo, false);
        }
        int bordi = Impronta.distanza(a.firma().dhash(), b.firma().dhash());
        double colori = distanzaColori(a.firma(), b.firma());
        if (piatta(a) || piatta(b) || !proporzioniSimili(a, b, s.proporzioni())) {
            return new Esito(false, forma, bordi, colori, tempo, true);
        }
        if (povera(a) || povera(b)) {
            boolean uguali = forma <= FORMA_POVERE && bordi <= BORDI_POVERE && colori <= COLORI_POVERE
                    && Math.abs(a.firma().luce() - b.firma().luce()) <= LUCE_POVERE
                    && contrastiSimili(a.firma(), b.firma());
            return new Esito(uguali, forma, bordi, colori, tempo, true);
        }
        int maxForma;
        int maxBordi;
        switch (tempo) {
            case RAFFICA -> {
                maxForma = Math.max(s.formaRaffica(), s.forma());
                maxBordi = Math.max(s.bordiRaffica(), s.bordi());
            }
            case VICINE -> {
                maxForma = Math.max(s.formaVicine(), s.forma());
                maxBordi = Math.max(s.bordiVicine(), s.bordi());
            }
            case LONTANE -> {
                maxForma = Math.max(s.forma() - 2, 0);
                maxBordi = Math.max(s.bordi() - 4, 0);
            }
            default -> {
                maxForma = s.forma();
                maxBordi = s.bordi();
            }
        }
        boolean uguali = forma <= maxForma && bordi <= maxBordi && colori <= s.colori();
        return new Esito(uguali, forma, bordi, colori, tempo, false);
    }

    /**
     * Differenza media di cromaticità dei 4×4 riquadri, da 0 a 1 (in pratica
     * fino a 0,15 per due foto qualsiasi). Le costanti smorzano il rumore dei
     * riquadri bui, dove r/(r+g+b) salta per un'unità.
     */
    static double distanzaColori(Firma a, Firma b) {
        double somma = 0;
        for (int q = 0; q < 16; q++) {
            double ta = a.colore(q, 0) + a.colore(q, 1) + a.colore(q, 2) + 30;
            double tb = b.colore(q, 0) + b.colore(q, 1) + b.colore(q, 2) + 30;
            for (int c = 0; c < 2; c++) {
                somma += Math.abs((a.colore(q, c) + 10) / ta - (b.colore(q, c) + 10) / tb);
            }
        }
        return somma / 32;
    }

    private static boolean proporzioniSimili(Voce a, Voce b, double tolleranza) {
        if (a.larghezza() <= 0 || a.altezza() <= 0 || b.larghezza() <= 0 || b.altezza() <= 0) {
            return true;
        }
        double ra = (double) a.larghezza() / a.altezza();
        double rb = (double) b.larghezza() / b.altezza();
        return Math.abs(Math.log(ra / rb)) <= tolleranza;
    }

    private static boolean contrastiSimili(Firma a, Firma b) {
        int max = Math.max(a.contrasto(), b.contrasto());
        return Math.abs(a.contrasto() - b.contrasto()) <= Math.max(2, max / 4);
    }

    /** Perché stanno insieme, in parole: per la pagina, accanto a ogni foto. */
    static List<String> motivi(Esito e, Double contenuto, Duration distanzaNelTempo) {
        List<String> motivi = new ArrayList<>();
        motivi.add("forma " + e.forma() + "/64 bit");
        if (e.bordi() != null) {
            motivi.add("bordi " + e.bordi() + "/64 bit");
        }
        if (e.colori() != null) {
            motivi.add("colori " + Math.round(100 * (1 - Math.min(e.colori(), 0.1) / 0.1)) + "%");
        }
        if (contenuto != null) {
            motivi.add("contenuto " + Math.round(100 * contenuto) + "%");
        }
        switch (e.tempo()) {
            case RAFFICA -> motivi.add("raffica (" + durata(distanzaNelTempo) + ")");
            case VICINE -> motivi.add("scattate a " + durata(distanzaNelTempo));
            case LONTANE -> motivi.add("date lontane: confronto stretto");
            default -> {
            }
        }
        if (e.povera()) {
            motivi.add("poca informazione (buia, chiara o piccola): confronto stretto");
        }
        if (e.bordi() == null) {
            motivi.add("solo impronta: manca \"Calcola impronte\"");
        }
        return motivi;
    }

    private static String durata(Duration d) {
        if (d == null) {
            return "?";
        }
        long s = d.toSeconds();
        if (s < 60) {
            return s + " s";
        }
        if (s < 3600) {
            return (s / 60) + " min";
        }
        return (s / 3600) + " h";
    }
}
