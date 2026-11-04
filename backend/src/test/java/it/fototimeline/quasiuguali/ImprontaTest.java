package it.fototimeline.quasiuguali;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Il pHash da solo: le copie stanno sotto 6 bit, le foto diverse ben sopra.
 * Le soglie di ogni sensibilità, con gli altri segnali, in ConfrontoTest.
 */
class ImprontaTest {

    private static final int SOGLIA = 6;
    private static final int SOGLIA_RAFFICA = 12;

    @Test
    void stabile() {
        BufferedImage img = Immagini.scena(1, 800, 600);
        assertThat(Impronta.di(img)).isEqualTo(Impronta.di(Immagini.scena(1, 800, 600)));
    }

    @Test
    void robustaARidimensionamentoERicompressione() throws IOException {
        List<Integer> distanze = new ArrayList<>();
        for (long seme = 1; seme <= 20; seme++) {
            BufferedImage originale = Immagini.scena(seme, 1600, 1200);
            long impronta = Impronta.di(originale);
            // Come WhatsApp: più piccola e JPEG di bassa qualità; poi come la miniatura (480 px).
            BufferedImage whatsapp = Immagini.leggi(Immagini.jpeg(Immagini.ridimensiona(originale, 800, 600), 0.3f));
            BufferedImage miniatura = Immagini.leggi(Immagini.jpeg(Immagini.ridimensiona(whatsapp, 480, 360), 0.85f));
            distanze.add(Impronta.distanza(impronta, Impronta.di(whatsapp)));
            distanze.add(Impronta.distanza(impronta, Impronta.di(miniatura)));
            distanze.add(Impronta.distanza(impronta,
                    Impronta.di(Immagini.leggi(Immagini.jpeg(originale, 0.1f)))));
        }
        System.out.println("Distanze tra copie della stessa foto: " + distanze);
        assertThat(distanze).allSatisfy(d -> assertThat(d).isLessThanOrEqualTo(SOGLIA));
    }

    @Test
    void fotoDiverseSonoLontane() {
        List<Long> impronte = new ArrayList<>();
        for (long seme = 100; seme < 140; seme++) {
            impronte.add(Impronta.di(Immagini.scena(seme, 640, 480)));
        }
        int minima = 64;
        for (int i = 0; i < impronte.size(); i++) {
            for (int j = i + 1; j < impronte.size(); j++) {
                minima = Math.min(minima, Impronta.distanza(impronte.get(i), impronte.get(j)));
            }
        }
        System.out.println("Distanza minima tra foto diverse: " + minima);
        assertThat(minima).isGreaterThan(SOGLIA_RAFFICA);
    }

    @Test
    void unAltroScattoDellaRafficaEVicinoMaNonUguale() {
        BufferedImage primo = Immagini.scena(7, 1200, 900);
        int d = Impronta.distanza(Impronta.di(primo), Impronta.di(Immagini.altroScatto(primo, 12)));
        System.out.println("Distanza tra due scatti di una raffica: " + d);
        assertThat(d).isBetween(1, SOGLIA_RAFFICA);
    }

    @Test
    void lAnalisiDaLoStessoPhashEUnaFirmaDellaVersioneDiAdesso() {
        BufferedImage img = Immagini.scena(5, 800, 600);
        Impronta.Analisi a = Impronta.analizza(img);
        assertThat(a.impronta()).isEqualTo(Impronta.di(img));
        assertThat(a.firma().versione()).isEqualTo(Impronta.VERSIONE);
        assertThat(a.firma().colori()).hasSize(48);
        assertThat(a.firma().contrasto()).isGreaterThan(10);
        assertThat(a.firma().chiari()).isBetween(0, 100);
        // Bianca: tutta chiara, contrasto zero.
        BufferedImage bianca = new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
        bianca.getGraphics().fillRect(0, 0, 100, 100);
        Firma f = Impronta.analizza(bianca).firma();
        assertThat(f.chiari()).isEqualTo(100);
        assertThat(f.contrasto()).isZero();
        assertThat(f.luce()).isEqualTo(255);
    }

    @Test
    void distanzaDiHamming() {
        assertThat(Impronta.distanza(0L, -1L)).isEqualTo(64);
        assertThat(Impronta.distanza(0b1011L, 0b0001L)).isEqualTo(2);
    }
}
