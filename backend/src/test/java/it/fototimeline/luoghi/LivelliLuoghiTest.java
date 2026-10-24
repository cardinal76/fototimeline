package it.fototimeline.luoghi;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import it.fototimeline.dominio.Foto;
import it.fototimeline.dominio.OrigineLuogo;
import it.fototimeline.luoghi.IndiceLuoghi.Trovato;
import it.fototimeline.luoghi.Luogo.Livello;

/** I livelli del luogo (città, provincia, regione, nazione), i nomi in italiano e la ricerca per nome. */
class LivelliLuoghiTest {

    private static final GeocodificaInversa GEO =
            new GeocodificaInversa(new LuoghiProperties("classpath:geonames", 30), new DefaultResourceLoader());

    @Test
    void inTrentinoCittaProvinciaERegione() {
        Trovato rovereto = GEO.trova(45.8904, 11.0405).orElseThrow();
        assertThat(rovereto.nome()).isEqualTo("Rovereto");
        // "Provincia di Trento", non "Trento" (si confonderebbe con la città) né "Provincia autonoma di Trento".
        assertThat(rovereto.provincia()).isEqualTo("Provincia di Trento");
        assertThat(rovereto.regione()).isEqualTo("Trentino-Alto Adige");
        assertThat(rovereto.nazione()).isEqualTo("Italia");
        Trovato trento = GEO.trova(46.0700, 11.1200).orElseThrow();
        assertThat(trento.nome()).isEqualTo("Trento");
        assertThat(trento.provincia()).isEqualTo("Provincia di Trento");
        // Una provincia senza nome italiano con "Provincia": quello di GeoNames.
        assertThat(GEO.trova(41.2545, 13.43).orElseThrow().provincia()).isEqualTo("Provincia di Latina");
        assertThat(GEO.trova(41.9, 12.5).orElseThrow().provincia()).isEqualTo("Provincia di Roma");
    }

    @Test
    void allEsteroCittaRegioneENazioneInItaliano() {
        Trovato amsterdam = GEO.trova(52.3731, 4.8926).orElseThrow(); // piazza Dam
        assertThat(amsterdam.nome()).isEqualTo("Amsterdam");
        assertThat(amsterdam.provincia()).isNull();
        assertThat(amsterdam.regione()).isEqualTo("Olanda Settentrionale");
        assertThat(amsterdam.nazione()).isEqualTo("Paesi Bassi");
        assertThat(amsterdam.codiceNazione()).isEqualTo("NL");

        Trovato manchester = GEO.trova(53.4794, -2.2453).orElseThrow(); // Piccadilly Gardens
        assertThat(manchester.nome()).isEqualTo("Manchester");
        // admin2 fuori dall'Italia non si legge: niente provincia "Manchester".
        assertThat(manchester.provincia()).isNull();
        assertThat(manchester.regione()).isEqualTo("Inghilterra");
        assertThat(manchester.nazione()).isEqualTo("Regno Unito");
    }

    @Test
    void nomiDelleNazioniInItaliano() {
        assertThat(GeocodificaInversa.nomeNazione("NL", Map.of())).isEqualTo("Paesi Bassi");
        assertThat(GeocodificaInversa.nomeNazione("GB", Map.of())).isEqualTo("Regno Unito");
        assertThat(GeocodificaInversa.nomeNazione("DE", Map.of())).isEqualTo("Germania");
        assertThat(GeocodificaInversa.nomeNazione("US", Map.of())).isEqualTo("Stati Uniti");
        // Un codice che Java non sa dire: il nome di countryInfo.
        assertThat(GeocodificaInversa.nomeNazione("XK", Map.of("XK", "Kosovo"))).isNotBlank();
        // Gli altri nomi in italiano di GeoNames, per la ricerca.
        assertThat(GEO.altriNomi("NL")).contains("Olanda");
        assertThat(GEO.altriNomi("GB")).contains("UK");
        assertThat(GEO.nazioniPerNome("olanda")).containsExactly("NL");
        assertThat(GEO.nazioniPerNome("Paesi")).containsExactly("NL");
    }

    @Test
    void cercaPerNomeAncheAMetaParola() {
        var manch = GEO.cerca("Manch", 10);
        assertThat(manch).hasSize(2);
        // La più popolosa prima: Manchester inglese, poi quella del New Hampshire.
        Luogo inglese = manch.get(0);
        assertThat(inglese.livello()).isEqualTo(Livello.CITTA);
        assertThat(inglese.chiave()).isEqualTo("citta:2643123");
        assertThat(inglese.luogo()).isEqualTo("Manchester");
        assertThat(inglese.regione()).isEqualTo("Inghilterra");
        assertThat(inglese.nazione()).isEqualTo("Regno Unito");
        assertThat(inglese.latitudine()).isCloseTo(53.481, org.assertj.core.data.Offset.offset(0.001));
        assertThat(inglese.longitudine()).isCloseTo(-2.237, org.assertj.core.data.Offset.offset(0.001));
        assertThat(manch.get(1).regione()).isEqualTo("New Hampshire");

        // "Olanda": prima la nazione (altro nome dei Paesi Bassi), poi la regione che comincia così.
        var olanda = GEO.cerca("olanda", 10);
        assertThat(olanda).extracting(Luogo::chiave).startsWith("nazione:NL", "regione:NL.07");
        assertThat(olanda.get(0).nazione()).isEqualTo("Paesi Bassi");

        // "trentino": la regione; le città che si chiamano così non ci sono.
        var trentino = GEO.cerca("Trentino", 10);
        assertThat(trentino).extracting(Luogo::chiave).containsExactly("regione:IT.17");
        // La provincia per parola dentro il nome: "trento" → la città prima, poi "Provincia di Trento".
        assertThat(GEO.cerca("trento", 10)).extracting(Luogo::chiave)
                .containsExactly("citta:3165243", "provincia:IT.17.TN");
        // Senza accenti né maiuscole.
        assertThat(GEO.cerca("ile-de", 10)).extracting(Luogo::regione).containsExactly("Île-de-France");
        assertThat(GEO.cerca("m", 10)).isEmpty();
    }

    @Test
    void laChiaveTornaAlLuogo() {
        Luogo provincia = GEO.luogo("provincia:IT.17.TN").orElseThrow();
        assertThat(provincia.livello()).isEqualTo(Livello.PROVINCIA);
        assertThat(provincia.luogo()).isNull();
        assertThat(provincia.provincia()).isEqualTo("Provincia di Trento");
        assertThat(provincia.regione()).isEqualTo("Trentino-Alto Adige");
        assertThat(provincia.codiceNazione()).isEqualTo("IT");
        assertThat(provincia.latitudine()).isNull();
        assertThat(GEO.luogo("nazione:nl").orElseThrow().nazione()).isEqualTo("Paesi Bassi");
        assertThat(GEO.luogo("regione:GB.ENG").orElseThrow().nazione()).isEqualTo("Regno Unito");
        assertThat(GEO.luogo("citta:3168854").orElseThrow().provincia()).isEqualTo("Provincia di Trento");
        assertThat(GEO.luogo("citta:1")).isEmpty();
        assertThat(GEO.luogo("citta:abc")).isEmpty();
        assertThat(GEO.luogo("regione:XX.99")).isEmpty();
        assertThat(GEO.luogo("boh")).isEmpty();
    }

    @Test
    void ilGpsNonToccaUnLuogoManuale() {
        Foto foto = new Foto();
        foto.setLatitudine(53.4794);
        foto.setLongitudine(-2.2453);
        foto.setLuogo("Amsterdam");
        foto.setOrigineLuogo(OrigineLuogo.MANUALE);
        GEO.applica(foto);
        assertThat(foto.getLuogo()).isEqualTo("Amsterdam");

        foto.setOrigineLuogo(null);
        GEO.applica(foto);
        assertThat(foto.getLuogo()).isEqualTo("Manchester");
        assertThat(foto.getOrigineLuogo()).isEqualTo(OrigineLuogo.GPS);
        assertThat(foto.getVersioneLuoghi()).isEqualTo(GeocodificaInversa.VERSIONE);
    }

    /** La ricerca per nome scorre tutti i punti: con 225 mila deve restare sotto il centinaio di millisecondi. */
    @Test
    void cercaPerNomeVeloce() {
        Random caso = new Random(3);
        var c = new IndiceLuoghi.Costruttore();
        String[] sillabe = {"ma", "ro", "san", "to", "ve", "ne", "zia", "chè", "li", "ba", "ri", "co"};
        for (int i = 0; i < 225_000; i++) {
            StringBuilder nome = new StringBuilder();
            for (int k = 0; k < 2 + caso.nextInt(3); k++) {
                nome.append(sillabe[caso.nextInt(sillabe.length)]);
            }
            nome.setCharAt(0, Character.toUpperCase(nome.charAt(0)));
            c.aggiungi(i + 1, caso.nextDouble() * 120 - 60, caso.nextDouble() * 360 - 180, nome.toString(), null,
                    "Regione", "XX", "Prova", caso.nextInt(100_000));
        }
        IndiceLuoghi indice = c.costruisci();
        indice.cerca("san", 10); // riscaldamento
        long inizio = System.nanoTime();
        for (int k = 0; k < 10; k++) {
            assertThat(indice.cerca(k % 2 == 0 ? "sanma" : "chel", 10)).isNotEmpty();
        }
        long ms = (System.nanoTime() - inizio) / 1_000_000 / 10;
        System.out.printf("Ricerca per nome su %d punti: %d ms%n", indice.dimensione(), ms);
        assertThat(ms).isLessThan(500);
    }
}
