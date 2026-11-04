package it.fototimeline.quasiuguali;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import it.fototimeline.quasiuguali.Confronto.Finestre;
import it.fototimeline.quasiuguali.Confronto.Sensibilita;
import it.fototimeline.quasiuguali.Raggruppamento.Voce;

/**
 * Le soglie di {@link Confronto} provate su immagini vere (sintetiche): le
 * copie stanno sotto anche con la sensibilità severa, le foto diverse no
 * nemmeno con la larga. Le misure (pHash, dHash, colori) si stampano.
 */
class ConfrontoTest {

    private static final Finestre SENZA_DATE = new Finestre(null, null);

    private static Voce voce(String id, BufferedImage img) {
        Impronta.Analisi a = Impronta.analizza(img);
        return new Voce(id, a.impronta(), a.firma(), null, false, img.getWidth(), img.getHeight());
    }

    private static Confronto.Esito valuta(BufferedImage a, BufferedImage b, Sensibilita s) {
        Confronto.Esito e = Confronto.valuta(voce("a", a), voce("b", b), s, SENZA_DATE);
        System.out.printf("%s: forma %d, bordi %d, colori %.3f -> %s%n", s, e.forma(), e.bordi(), e.colori(),
                e.uguali());
        return e;
    }

    @Test
    void leCopieVereSonoUgualiAncheConLaSeveraEAOgniSensibilita() throws IOException {
        for (long seme = 1; seme <= 12; seme++) {
            BufferedImage originale = Immagini.scena(seme, 1600, 1200);
            Map<String, BufferedImage> copie = new LinkedHashMap<>();
            copie.put("WhatsApp", Immagini.leggi(Immagini.jpeg(Immagini.ridimensiona(originale, 800, 600), 0.3f)));
            copie.put("JPEG al 10%", Immagini.leggi(Immagini.jpeg(originale, 0.1f)));
            copie.put("miniatura", Immagini.leggi(Immagini.jpeg(Immagini.ridimensiona(originale, 480, 360), 0.85f)));
            copie.put("ritagliata del 2%", Immagini.ritaglia(originale, 0.02));
            copie.put("più chiara", Immagini.luce(originale, 1.25));
            copie.put("più scura", Immagini.luce(originale, 0.7));
            for (var copia : copie.entrySet()) {
                for (Sensibilita s : Sensibilita.values()) {
                    assertThat(valuta(originale, copia.getValue(), s).uguali())
                            .as("seme %d, %s, %s", seme, copia.getKey(), s).isTrue();
                }
            }
        }
    }

    @Test
    void fotoDiverseNonSonoUgualiNemmenoConLaLarga() {
        for (long seme = 1; seme <= 12; seme++) {
            assertThat(valuta(Immagini.scena(seme, 1600, 1200), Immagini.scena(seme + 100, 1600, 1200),
                    Sensibilita.LARGA).uguali()).as("seme %d", seme).isFalse();
        }
    }

    @Test
    void leFotoBuieUniformiEIDocumentiDiversiNonSonoUguali() {
        for (long seme = 1; seme <= 8; seme++) {
            assertThat(valuta(Immagini.buia(seme), Immagini.buia(seme + 50), Sensibilita.LARGA).uguali())
                    .as("buie, seme %d", seme).isFalse();
            assertThat(valuta(Immagini.documento(seme), Immagini.documento(seme + 50), Sensibilita.LARGA).uguali())
                    .as("documenti, seme %d", seme).isFalse();
        }
        // Due muri, uno grigio scuro e uno marrone: per il pHash (solo rumore) potevano essere chiunque.
        BufferedImage grigio = Immagini.uniforme(new Color(20, 20, 25), 1);
        assertThat(Confronto.piatta(voce("g", grigio))).isTrue();
        assertThat(valuta(grigio, Immagini.uniforme(new Color(40, 30, 20), 2), Sensibilita.LARGA).uguali()).isFalse();
        // Uniformi: mai, nemmeno due uguali (un doppione identico lo ferma già l'hash del file).
        assertThat(valuta(grigio, Immagini.uniforme(new Color(20, 20, 25), 3), Sensibilita.LARGA).uguali())
                .isFalse();
    }

    @Test
    void unDocumentoEUnaFotoBuiaRicompressiRestanoUgualiPerchéQuasiIdentici() throws IOException {
        BufferedImage doc = Immagini.documento(4);
        BufferedImage copiaDoc = Immagini.leggi(Immagini.jpeg(Immagini.ridimensiona(doc, 300, 400), 0.5f));
        assertThat(valuta(doc, copiaDoc, Sensibilita.SEVERA).uguali()).isTrue();
        BufferedImage buia = Immagini.buia(2);
        Confronto.Esito e = valuta(buia, Immagini.leggi(Immagini.jpeg(buia, 0.9f)), Sensibilita.SEVERA);
        assertThat(e.povera()).isTrue();
        assertThat(e.uguali()).isTrue();
    }

    @Test
    void unaFotoConUnAltraSovrappostaPassaSoloConLaLarga() {
        int severa = 0;
        int normale = 0;
        int larga = 0;
        for (long seme = 1; seme <= 12; seme++) {
            BufferedImage a = Immagini.scena(seme, 1600, 1200);
            BufferedImage b = Immagini.misto(a, Immagini.scena(seme + 100, 1600, 1200), 0.25);
            severa += valuta(a, b, Sensibilita.SEVERA).uguali() ? 1 : 0;
            normale += valuta(a, b, Sensibilita.NORMALE).uguali() ? 1 : 0;
            larga += valuta(a, b, Sensibilita.LARGA).uguali() ? 1 : 0;
        }
        System.out.printf("Sovrapposte al 25%%: severa %d, normale %d, larga %d su 12%n", severa, normale, larga);
        assertThat(severa).isZero();
        assertThat(normale).isGreaterThanOrEqualTo(severa);
        assertThat(larga).isGreaterThan(normale);
    }

    @Test
    void leProporzioniDiverseNonSonoUguali() {
        BufferedImage a = Immagini.scena(3, 1600, 1200);
        // La stessa scena stirata a 16:9: il pHash (riquadri, non pixel) non se ne accorge.
        BufferedImage stirata = Immagini.ridimensiona(a, 1600, 900);
        assertThat(Impronta.distanza(Impronta.di(a), Impronta.di(stirata))).isLessThanOrEqualTo(2);
        assertThat(valuta(a, stirata, Sensibilita.LARGA).uguali()).isFalse();
    }

    /**
     * Una sfumatura da una foto all'altra (0, 25, 50, 75, 100%): a due a due
     * si somigliano, ma i gruppi non le mettono insieme a catena, e ogni foto
     * di un gruppo è quasi uguale a tutte le altre.
     */
    @Test
    void unaCatenaDiFotoSimiliADueADueNonFinisceInUnGruppo() {
        for (long seme = 1; seme <= 6; seme++) {
            BufferedImage a = Immagini.scena(seme, 1600, 1200);
            BufferedImage b = Immagini.scena(seme + 200, 1600, 1200);
            List<Voce> voci = new ArrayList<>();
            for (int passo = 0; passo <= 4; passo++) {
                voci.add(voce("t" + passo * 25, Immagini.misto(a, b, passo / 4.0)));
            }
            for (Sensibilita s : Sensibilita.values()) {
                var gruppi = Raggruppamento.gruppi(voci, s, SENZA_DATE, (x, y) -> false, null).gruppi();
                for (List<Integer> g : gruppi) {
                    Set<String> ids = g.stream().map(i -> voci.get(i).id()).collect(Collectors.toSet());
                    assertThat(ids.containsAll(Set.of("t0", "t100"))).as("seme %d, %s", seme, s).isFalse();
                    assertThat(ids.containsAll(Set.of("t0", "t50"))).as("seme %d, %s", seme, s).isFalse();
                    assertThat(ids).as("seme %d, %s", seme, s).hasSizeLessThanOrEqualTo(2);
                    for (int i : g) {
                        for (int j : g) {
                            if (i != j) {
                                assertThat(Confronto.valuta(voci.get(i), voci.get(j), s, SENZA_DATE).uguali())
                                        .isTrue();
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void unaRafficaVaInsiemeSoloConLaDataExifVicina() {
        BufferedImage primo = Immagini.scena(7, 1200, 900);
        BufferedImage secondo = Immagini.altroScatto(primo, 12);
        var ora = java.time.LocalDateTime.of(2024, 8, 15, 10, 0);
        Impronta.Analisi a = Impronta.analizza(primo);
        Impronta.Analisi b = Impronta.analizza(secondo);
        Finestre finestre = new Finestre(Duration.ofSeconds(10), Duration.ofHours(6));
        Voce va = new Voce("a", a.impronta(), a.firma(), ora, true, 1200, 900);
        Voce raffica = new Voce("b", b.impronta(), b.firma(), ora.plusSeconds(2), true, 1200, 900);
        Voce dopoUnAnno = new Voce("b", b.impronta(), b.firma(), ora.plusYears(1), true, 1200, 900);
        Confronto.Esito e = Confronto.valuta(va, raffica, Sensibilita.SEVERA, finestre);
        System.out.printf("Raffica: forma %d, bordi %d, colori %.3f%n", e.forma(), e.bordi(), e.colori());
        assertThat(e.tempo()).isEqualTo(Confronto.Tempo.RAFFICA);
        assertThat(e.uguali()).isTrue();
        assertThat(Confronto.valuta(va, dopoUnAnno, Sensibilita.SEVERA, finestre).uguali()).isFalse();
        assertThat(Confronto.valuta(va, raffica, Sensibilita.SEVERA, SENZA_DATE).uguali()).isFalse();
        assertThat(Confronto.motivi(e, 0.97, Duration.ofSeconds(2)))
                .contains("raffica (2 s)", "contenuto 97%");
        assertThat(e.percentuale(0.97)).isBetween(70, 100);
    }

    @Test
    void ogniSensibilitaAccettaTuttoQuelloCheAccettaLaPiuSevera() {
        List<BufferedImage> immagini = new ArrayList<>();
        for (long seme = 1; seme <= 4; seme++) {
            BufferedImage o = Immagini.scena(seme, 800, 600);
            immagini.add(o);
            immagini.add(Immagini.ritaglia(o, 0.03));
            immagini.add(Immagini.misto(o, Immagini.scena(seme + 100, 800, 600), 0.25));
            immagini.add(Immagini.altroScatto(o, 30));
        }
        List<Voce> voci = new ArrayList<>();
        for (int i = 0; i < immagini.size(); i++) {
            voci.add(voce("v" + i, immagini.get(i)));
        }
        for (Voce x : voci) {
            for (Voce y : voci) {
                boolean severa = Confronto.valuta(x, y, Sensibilita.SEVERA, SENZA_DATE).uguali();
                boolean normale = Confronto.valuta(x, y, Sensibilita.NORMALE, SENZA_DATE).uguali();
                boolean larga = Confronto.valuta(x, y, Sensibilita.LARGA, SENZA_DATE).uguali();
                assertThat(!severa || normale).isTrue();
                assertThat(!normale || larga).isTrue();
            }
        }
    }
}
