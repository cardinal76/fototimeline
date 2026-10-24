package it.fototimeline.eventi;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.fototimeline.eventi.RilevamentoEventi.FotoPerEvento;
import it.fototimeline.eventi.RilevamentoEventi.Gruppo;
import it.fototimeline.eventi.RilevamentoEventi.Regole;

/** Il rilevamento e i nomi, senza Mongo. */
class RilevamentoEventiTest {

    static final LuogoFoto ROMA = new LuogoFoto("Roma", "Provincia di Roma", "Lazio", "Italia", "IT");
    static final LuogoFoto SPERLONGA = new LuogoFoto("Sperlonga", "Provincia di Latina", "Lazio", "Italia", "IT");
    static final LuogoFoto CANAZEI = new LuogoFoto("Canazei", "Provincia di Trento", "Trentino-Alto Adige", "Italia", "IT");
    static final LuogoFoto MOENA = new LuogoFoto("Moena", "Provincia di Trento", "Trentino-Alto Adige", "Italia", "IT");
    static final LuogoFoto BOLZANO = new LuogoFoto("Bolzano", "Provincia di Bolzano", "Trentino-Alto Adige", "Italia", "IT");
    /** Scelti a mano solo come regione o nazione: senza città. */
    static final LuogoFoto SOLO_TRENTINO = new LuogoFoto(null, null, "Trentino-Alto Adige", "Italia", "IT");
    static final LuogoFoto SOLO_FRANCIA = new LuogoFoto(null, null, null, "Francia", "FR");
    static final LuogoFoto PARIGI = new LuogoFoto("Parigi", null, "Île-de-France", "Francia", "FR");
    static final LuogoFoto VIGO = new LuogoFoto("Vigo di Fassa", "Provincia di Trento", "Trentino-Alto Adige", "Italia", "IT");
    static final LuogoFoto MANCHESTER = new LuogoFoto("Manchester", null, "Inghilterra", "Regno Unito", "GB");
    static final LuogoFoto LIVERPOOL = new LuogoFoto("Liverpool", null, "Inghilterra", "Regno Unito", "GB");
    static final LuogoFoto LONDRA = new LuogoFoto("Londra", null, "Inghilterra", "Regno Unito", "GB");

    private static final Regole REGOLE = new Regole(Duration.ofHours(4), 1, 5, ROMA);

    private int numero;

    /** {@code quante} foto ogni 10 minuti da {@code inizio}. */
    private List<FotoPerEvento> raffica(LocalDateTime inizio, int quante, LuogoFoto luogo) {
        List<FotoPerEvento> foto = new ArrayList<>();
        for (int i = 0; i < quante; i++) {
            foto.add(new FotoPerEvento("f" + (++numero), inizio.plusMinutes(10L * i), luogo, false, false));
        }
        return foto;
    }

    @SafeVarargs
    private static List<Gruppo> gruppi(Regole regole, List<FotoPerEvento>... parti) {
        List<Gruppo> chiusi = new ArrayList<>();
        RilevamentoEventi r = new RilevamentoEventi(regole, chiusi::add);
        for (List<FotoPerEvento> parte : parti) {
            parte.forEach(r::aggiungi);
        }
        r.finisci();
        return chiusi;
    }

    @Test
    void unaPausaLungaSeparaLeFoto() {
        LocalDateTime mattina = LocalDateTime.of(2021, 5, 12, 9, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(mattina, 6, ROMA),
                // 50 minuti di foto, poi 4 ore e mezza di niente.
                raffica(mattina.plusHours(5).plusMinutes(20), 7, ROMA));

        assertThat(g).hasSize(2);
        assertThat(g.get(0).conteggio()).isEqualTo(6);
        assertThat(g.get(1).conteggio()).isEqualTo(7);
    }

    @Test
    void unaFestaDiUnaSeraOltreLaMezzanotteResta() {
        List<Gruppo> g = gruppi(REGOLE, raffica(LocalDateTime.of(2021, 5, 12, 21, 0), 24, ROMA));

        assertThat(g).singleElement().satisfies(festa -> {
            assertThat(festa.conteggio()).isEqualTo(24);
            assertThat(festa.primoGiorno()).isEqualTo(LocalDate.of(2021, 5, 12));
            assertThat(festa.ultimoGiorno()).isEqualTo(LocalDate.of(2021, 5, 13));
        });
    }

    @Test
    void unViaggioDiPiuGiorniEUnEventoSolo() {
        LocalDateTime giorno = LocalDateTime.of(2019, 8, 10, 10, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 5, CANAZEI),
                raffica(giorno.plusHours(8), 3, CANAZEI),
                raffica(giorno.plusDays(1), 4, MOENA),
                // Un giorno senza foto in mezzo (giorniVuoti = 1).
                raffica(giorno.plusDays(3), 4, CANAZEI),
                // A casa: il viaggio è finito.
                raffica(giorno.plusDays(4), 3, ROMA));

        assertThat(g).hasSize(2);
        assertThat(g.get(0).conteggio()).isEqualTo(16);
        assertThat(g.get(0).primoGiorno()).isEqualTo(LocalDate.of(2019, 8, 10));
        assertThat(g.get(0).ultimoGiorno()).isEqualTo(LocalDate.of(2019, 8, 13));
        assertThat(g.get(1).conteggio()).isEqualTo(3);
    }

    @Test
    void aCasaIGiorniDiFilaRestanoSeparati() {
        LocalDateTime giorno = LocalDateTime.of(2022, 3, 1, 18, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 3, ROMA),
                raffica(giorno.plusDays(1), 3, ROMA),
                raffica(giorno.plusDays(2), 3, ROMA));

        assertThat(g).hasSize(3).allSatisfy(x -> assertThat(x.conteggio()).isEqualTo(3));
    }

    @Test
    void troppiGiorniSenzaFotoSpezzanoIlViaggio() {
        LocalDateTime giorno = LocalDateTime.of(2019, 8, 10, 10, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 5, SPERLONGA),
                raffica(giorno.plusDays(3), 5, SPERLONGA));

        assertThat(g).hasSize(2);
    }

    @Test
    void leFotoSenzaGpsDentroUnViaggioRestanoNelViaggio() {
        LocalDateTime giorno = LocalDateTime.of(2018, 3, 9, 10, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 5, MANCHESTER),
                // La fotocamera senza GPS, il giorno dopo.
                raffica(giorno.plusDays(1), 6, null),
                raffica(giorno.plusDays(2), 4, LIVERPOOL));

        assertThat(g).singleElement().satisfies(viaggio -> assertThat(viaggio.conteggio()).isEqualTo(15));
    }

    @Test
    void leFotoSenzaGpsDellUltimoGiornoRestanoNelViaggio() {
        LocalDateTime giorno = LocalDateTime.of(2018, 3, 9, 10, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 5, MANCHESTER),
                raffica(giorno.plusDays(1), 6, null),
                // Settimane dopo, a casa.
                raffica(giorno.plusDays(20), 4, ROMA));

        assertThat(g).extracting(Gruppo::conteggio).containsExactly(11, 4);
        assertThat(gruppi(REGOLE, raffica(giorno, 5, MANCHESTER), raffica(giorno.plusDays(1), 6, null)))
                .extracting(Gruppo::conteggio).containsExactly(11);
    }

    @Test
    void leFotoSenzaGpsDopoUnViaggioFinitoStannoDaSole() {
        LocalDateTime giorno = LocalDateTime.of(2018, 3, 9, 10, 0);
        List<Gruppo> g = gruppi(REGOLE,
                raffica(giorno, 5, MANCHESTER),
                raffica(giorno.plusDays(1), 6, null),
                // Il segmento col GPS dopo è a casa: il viaggio non continuava.
                raffica(giorno.plusDays(2), 4, ROMA));

        assertThat(g).extracting(Gruppo::conteggio).containsExactly(5, 6, 4);
    }

    @Test
    void laCopertinaELaPrimaPreferitaOLaPrimaFoto() {
        LocalDateTime t = LocalDateTime.of(2020, 1, 1, 10, 0);
        List<Gruppo> g = gruppi(REGOLE, List.of(
                new FotoPerEvento("video", t, ROMA, true, true),
                new FotoPerEvento("prima", t.plusMinutes(1), ROMA, false, false),
                new FotoPerEvento("bella", t.plusMinutes(2), ROMA, true, false),
                new FotoPerEvento("altra", t.plusMinutes(3), ROMA, true, false)));
        assertThat(g.getFirst().copertina()).isEqualTo("bella");
        assertThat(g.getFirst().primaFoto()).isEqualTo("video");

        g = gruppi(REGOLE, List.of(
                new FotoPerEvento("video", t, ROMA, false, true),
                new FotoPerEvento("prima", t.plusMinutes(1), ROMA, false, false)));
        assertThat(g.getFirst().copertina()).isEqualTo("prima");
    }

    // ------------------------------------------------------------ nomi

    @Test
    void ilNomeHaIlLuogoPrevalenteELaData() {
        LocalDateTime t = LocalDateTime.of(2021, 5, 12, 16, 0);
        Gruppo festa = gruppi(REGOLE, raffica(t, 3, SPERLONGA), raffica(t.plusMinutes(40), 1, ROMA)).getFirst();
        assertThat(NomiEventi.nome(festa, ROMA)).isEqualTo("Sperlonga, 12 maggio 2021");
    }

    @Test
    void senzaLuogoSoloLaData() {
        Gruppo g = gruppi(REGOLE, raffica(LocalDateTime.of(2021, 5, 12, 16, 0), 5, null)).getFirst();
        assertThat(NomiEventi.nome(g, ROMA)).isEqualTo("12 maggio 2021");
        assertThat(NomiEventi.nome(g, null)).isEqualTo("12 maggio 2021");
    }

    @Test
    void suPiuGiorniLeDateOIlMese() {
        assertThat(NomiEventi.date(LocalDate.of(2018, 3, 9), LocalDate.of(2018, 3, 11))).isEqualTo("9–11 marzo 2018");
        assertThat(NomiEventi.date(LocalDate.of(2019, 7, 30), LocalDate.of(2019, 8, 2)))
                .isEqualTo("30 luglio – 2 agosto 2019");
        assertThat(NomiEventi.date(LocalDate.of(2019, 12, 31), LocalDate.of(2020, 1, 1)))
                .isEqualTo("31 dicembre 2019 – 1 gennaio 2020");
        assertThat(NomiEventi.date(LocalDate.of(2019, 8, 3), LocalDate.of(2019, 8, 17))).isEqualTo("agosto 2019");
        assertThat(NomiEventi.date(LocalDate.of(2019, 7, 25), LocalDate.of(2019, 8, 5))).isEqualTo("luglio–agosto 2019");
        assertThat(NomiEventi.date(LocalDate.of(2019, 12, 20), LocalDate.of(2020, 1, 6)))
                .isEqualTo("dicembre 2019 – gennaio 2020");

        LocalDateTime giorno = LocalDateTime.of(2019, 8, 10, 10, 0);
        Gruppo viaggio = gruppi(REGOLE,
                raffica(giorno, 4, CANAZEI),
                raffica(giorno.plusDays(1), 4, MOENA),
                raffica(giorno.plusDays(2), 2, VIGO)).getFirst();
        // Nessun paese ha la metà delle foto: la regione.
        assertThat(NomiEventi.nome(viaggio, ROMA)).isEqualTo("Trentino-Alto Adige, 10–12 agosto 2019");
    }

    @Test
    void allEsteroLaCittaPrevalenteOLaNazione() {
        Map<LuogoFoto, Integer> manchester = new LinkedHashMap<>();
        manchester.put(MANCHESTER, 12);
        manchester.put(LIVERPOOL, 3);
        assertThat(NomiEventi.nomeLuogo(manchester, ROMA)).isEqualTo("Manchester");

        Map<LuogoFoto, Integer> giro = new LinkedHashMap<>();
        giro.put(MANCHESTER, 5);
        giro.put(LIVERPOOL, 4);
        giro.put(LONDRA, 4);
        assertThat(NomiEventi.nomeLuogo(giro, ROMA)).isEqualTo("Regno Unito");
    }

    @Test
    void conILuoghiStrutturatiLaRegioneTraPiuProvinceELeFotoSenzaCitta() {
        // Un giro tra due province del Trentino-Alto Adige: nessuna città ha la metà, la regione sì.
        Map<LuogoFoto, Integer> giro = new LinkedHashMap<>();
        giro.put(CANAZEI, 4);
        giro.put(BOLZANO, 4);
        giro.put(MOENA, 2);
        assertThat(NomiEventi.nomeLuogo(giro, ROMA)).isEqualTo("Trentino-Alto Adige");

        // Metà delle foto con la sola regione scelta a mano: conta per la regione, non per una città.
        Map<LuogoFoto, Integer> aMano = new LinkedHashMap<>();
        aMano.put(SOLO_TRENTINO, 6);
        aMano.put(CANAZEI, 3);
        aMano.put(SPERLONGA, 3);
        assertThat(NomiEventi.nomeLuogo(aMano, ROMA)).isEqualTo("Trentino-Alto Adige");

        // Solo luoghi senza città: il livello più preciso che c'è.
        assertThat(NomiEventi.nomeLuogo(Map.of(SOLO_TRENTINO, 5), ROMA)).isEqualTo("Trentino-Alto Adige");
        assertThat(NomiEventi.nomeLuogo(Map.of(SOLO_FRANCIA, 5), ROMA)).isEqualTo("Francia");

        // All'estero: la città se ne ha la metà, anche con foto della sola nazione; se no la nazione.
        Map<LuogoFoto, Integer> francia = new LinkedHashMap<>();
        francia.put(SOLO_FRANCIA, 3);
        francia.put(PARIGI, 5);
        assertThat(NomiEventi.nomeLuogo(francia, ROMA)).isEqualTo("Parigi");
        francia.put(SOLO_FRANCIA, 8);
        assertThat(NomiEventi.nomeLuogo(francia, ROMA)).isEqualTo("Francia");
    }
}
