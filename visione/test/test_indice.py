import numpy as np
import pytest

from app.indice import GIORNALE, ISTANTANEA, IndiceVettori


def vettore(*componenti, dimensione=4):
    v = np.zeros(dimensione, dtype=np.float32)
    v[: len(componenti)] = componenti
    return v


@pytest.fixture
def indice(tmp_path):
    i = IndiceVettori(tmp_path, 4, "finto")
    i.carica()
    yield i
    i.chiudi()


def test_aggiunge_e_cerca_in_ordine_di_somiglianza(indice):
    indice.metti("a", vettore(1, 0))
    indice.metti("b", vettore(1, 1))
    indice.metti("c", vettore(0, 1))

    risultati = indice.cerca(vettore(1, 0), k=10)

    assert [i for i, _ in risultati] == ["a", "b", "c"]
    assert risultati[0][1] == pytest.approx(1.0, abs=1e-3)
    assert risultati[1][1] == pytest.approx(0.7071, abs=1e-3)
    assert indice.cerca(vettore(1, 0), k=2, soglia=0.5) == indice.cerca(vettore(1, 0), k=2)[:2]
    assert [i for i, _ in indice.cerca(vettore(1, 0), k=10, soglia=0.5)] == ["a", "b"]


def test_aggiornare_sostituisce_senza_doppioni(indice):
    indice.metti("a", vettore(1, 0))
    indice.metti("a", vettore(0, 1))

    assert len(indice) == 1
    assert indice.cerca(vettore(0, 1), k=1)[0][0] == "a"
    assert indice.cerca(vettore(0, 1), k=1)[0][1] == pytest.approx(1.0, abs=1e-3)


def test_togliere_non_lascia_buchi(indice):
    for n in range(5):
        indice.metti(f"f{n}", vettore(1, n))
    assert indice.togli("f1")
    assert not indice.togli("f1")
    assert not indice.togli("mai-visto")

    assert len(indice) == 4
    assert "f1" not in indice
    # L'ultimo ha preso il posto del tolto: si trova ancora, col suo vettore.
    assert indice.vettore("f4") == pytest.approx(vettore(1, 4) / np.linalg.norm(vettore(1, 4)), abs=1e-3)
    assert {i for i, _ in indice.cerca(vettore(1, 0), k=10)} == {"f0", "f2", "f3", "f4"}


def test_cresce_oltre_la_capacita_iniziale(tmp_path):
    indice = IndiceVettori(tmp_path, 4, "finto")
    indice.carica()
    for n in range(3000):
        indice.metti(f"f{n}", vettore(1, n / 3000))
    indice.metti("diversa", vettore(0, 0, 0, 1))
    assert len(indice) == 3001
    assert indice.vettore("f0") == pytest.approx(vettore(1), abs=1e-3)
    assert indice.cerca(vettore(0, 0, 0, 1), k=1)[0][0] == "diversa"
    indice.chiudi()


def test_vettori_non_validi(indice):
    with pytest.raises(ValueError):
        indice.metti("a", vettore(0, 0))
    with pytest.raises(ValueError):
        indice.metti("a", np.ones(3))
    with pytest.raises(ValueError):
        indice.metti("", vettore(1))
    assert indice.cerca(vettore(1), k=5) == []


def test_persistenza_dal_giornale_senza_istantanea(tmp_path):
    primo = IndiceVettori(tmp_path, 4, "finto")
    primo.carica()
    primo.metti("a", vettore(1, 0))
    primo.metti("b", vettore(0, 1))
    primo.metti("c", vettore(1, 1))
    primo.togli("b")
    # Il container muore: niente chiudi(), solo il giornale scritto riga per riga.
    assert not (tmp_path / ISTANTANEA).exists()

    secondo = IndiceVettori(tmp_path, 4, "finto")
    secondo.carica()
    assert len(secondo) == 2
    assert [i for i, _ in secondo.cerca(vettore(1, 0), k=5)] == ["a", "c"]


def test_istantanea_alla_chiusura_e_giornale_svuotato(tmp_path):
    primo = IndiceVettori(tmp_path, 4, "finto")
    primo.carica()
    primo.metti("a", vettore(1, 0))
    primo.metti("b", vettore(0, 1))
    primo.chiudi()
    assert (tmp_path / ISTANTANEA).exists()
    assert (tmp_path / GIORNALE).stat().st_size == 0

    secondo = IndiceVettori(tmp_path, 4, "finto")
    secondo.carica()
    secondo.metti("c", vettore(1, 1))
    secondo.togli("a")

    terzo = IndiceVettori(tmp_path, 4, "finto")
    terzo.carica()
    assert sorted(i for i, _ in terzo.cerca(vettore(1, 1), k=5)) == ["b", "c"]


def test_istantanea_ogni_tante_righe(tmp_path):
    indice = IndiceVettori(tmp_path, 4, "finto", righe_per_istantanea=10)
    indice.carica()
    for n in range(25):
        indice.metti(f"f{n}", vettore(1, n))
    assert (tmp_path / ISTANTANEA).exists()
    # Due istantanee fatte (a 10 e 20 righe), nel giornale le ultime 5.
    assert indice._righe_giornale == 5

    ricaricato = IndiceVettori(tmp_path, 4, "finto", righe_per_istantanea=10)
    ricaricato.carica()
    assert len(ricaricato) == 25


def test_riga_del_giornale_scritta_a_meta_si_ignora(tmp_path):
    primo = IndiceVettori(tmp_path, 4, "finto")
    primo.carica()
    primo.metti("a", vettore(1, 0))
    primo.metti("b", vettore(0, 1))
    giornale = tmp_path / GIORNALE
    dati = giornale.read_bytes()
    giornale.write_bytes(dati[:-3])

    secondo = IndiceVettori(tmp_path, 4, "finto")
    secondo.carica()
    assert len(secondo) == 1
    assert "a" in secondo


def test_un_altro_modello_riparte_vuoto(tmp_path):
    primo = IndiceVettori(tmp_path, 4, "vecchio")
    primo.carica()
    primo.metti("a", vettore(1, 0))
    primo.chiudi()

    nuovo = IndiceVettori(tmp_path, 4, "nuovo")
    nuovo.carica()
    assert len(nuovo) == 0
    assert (tmp_path / (ISTANTANEA + ".vecchio")).exists()
