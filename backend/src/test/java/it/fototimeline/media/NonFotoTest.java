package it.fototimeline.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** I file col nome di una foto che una foto non sono ({@link NonFoto}), dal contenuto. */
class NonFotoTest {

    @TempDir
    Path cartella;

    @Test
    void unFileDiZeriSiRiconosceAncheGrandeSenzaLeggerloTutto() throws IOException {
        // Come i PICT0xxx.jpg: ~600 KB di byte 0, letti tutti.
        Path piccolo = scrivi("PICT0001.jpg", new byte[600 * 1024]);
        assertThat(NonFoto.riconosci(piccolo)).isEqualTo(NonFoto.ZERI);
        assertThat(NonFoto.soloZeri(piccolo)).isTrue();

        // 40 MB "bucati" (sparse): si guardano l'inizio, la fine e qualche blocco in mezzo.
        Path grande = cartella.resolve("grande.jpg");
        try (var f = new RandomAccessFile(grande.toFile(), "rw")) {
            f.setLength(40L * 1024 * 1024);
        }
        assertThat(NonFoto.riconosci(grande)).isEqualTo(NonFoto.ZERI);

        // Un byte non zero nella fine (o in un blocco guardato) basta per dire di no.
        try (var f = new RandomAccessFile(grande.toFile(), "rw")) {
            f.seek(f.length() - 10);
            f.write(1);
        }
        assertThat(NonFoto.riconosci(grande)).isNull();

        // Zeri all'inizio e poi qualcosa, in un file piccolo: lo si legge tutto, non è "solo zeri".
        byte[] quasi = new byte[300 * 1024];
        quasi[200 * 1024] = 7;
        assertThat(NonFoto.riconosci(scrivi("quasi.jpg", quasi))).isNull();
    }

    @Test
    void vuotoMacEPaginaWeb() throws IOException {
        assertThat(NonFoto.riconosci(scrivi("vuoto.jpg", new byte[0]))).isEqualTo(NonFoto.VUOTO);

        // AppleDouble: 00 05 16 07, 4096 byte, col nome rinominato da un'importazione di una volta.
        byte[] mac = new byte[4096];
        mac[1] = 0x05;
        mac[2] = 0x16;
        mac[3] = 0x07;
        mac[7] = 0x02;
        assertThat(NonFoto.riconosci(scrivi("foto1018._20160125_121852-1.jpg", mac))).isEqualTo(NonFoto.MAC);

        assertThat(NonFoto.riconosci(scrivi("6.png", "<!DOCTYPE html>\n<html><head>".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(NonFoto.PAGINA_WEB);
        // Col BOM di UTF-8, spazi e a capo prima, in minuscolo.
        byte[] conBom = ("﻿ \r\n\t<html lang=\"it\">").getBytes(StandardCharsets.UTF_8);
        assertThat(NonFoto.riconosci(scrivi("7.jpg", conBom))).isEqualTo(NonFoto.PAGINA_WEB);
        assertThat(NonFoto.riconosci(scrivi("8.jpg", "<?xml version=\"1.0\"?><x/>".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(NonFoto.PAGINA_WEB);

        // Le foto vere no.
        assertThat(NonFoto.riconosci(scrivi("vera.jpg", getClass().getResourceAsStream("/con-exif.jpg").readAllBytes())))
                .isNull();
        assertThat(NonFoto.riconosci(scrivi("testo.jpg", "ciao <html>".getBytes(StandardCharsets.UTF_8)))).isNull();
        assertThat(NonFoto.riconosci(cartella.resolve("mancante.jpg"))).isNull();
    }

    @Test
    void iMessaggiSonoDaButtare() {
        assertThat(NonFoto.ZERI.messaggio()).isEqualTo("File vuoto: solo zeri, la foto non c'è più");
        assertThat(NonFoto.MAC.messaggio()).isEqualTo("File del Mac (._), non è una foto");
        assertThat(NonFoto.PAGINA_WEB.messaggio()).isEqualTo("Pagina web, non una foto");
        assertThat(NonFoto.VUOTO.messaggio()).isEqualTo("File vuoto (0 byte)");
        for (NonFoto n : NonFoto.values()) {
            assertThat(NonFoto.daButtare(n.messaggio())).isTrue();
        }
        assertThat(NonFoto.daButtare("Immagine illeggibile (Bogus marker length)")).isFalse();
        assertThat(NonFoto.daButtare(null)).isFalse();
    }

    private Path scrivi(String nome, byte[] contenuto) throws IOException {
        Path file = cartella.resolve(nome);
        Files.write(file, contenuto);
        return file;
    }
}
