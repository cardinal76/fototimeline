"""Le API dei volti con un modello finto: ogni immagine non nera ha un "volto" nella metà sinistra, il cui
vettore è il colore medio; un'immagine nera non ne ha. YuNet e SFace veri non servono (né OpenCV)."""

import io

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.main import crea_app
from app.volti import VoltoTrovato

from test_api import ModelloFinto, SEGRETO, jpeg


class VoltiFinti:
    nome = "volti-finti"
    dimensione = 3

    def __init__(self):
        self.chiamate = []

    def volti(self, immagine, lato_minimo, punteggio_minimo):
        self.chiamate.append((immagine.size, lato_minimo, punteggio_minimo))
        media = np.asarray(immagine, dtype=np.float32).reshape(-1, 3).mean(axis=0)
        if media.sum() < 1:
            return []
        v = media / np.linalg.norm(media)
        return [VoltoTrovato(0.0, 0.25, 0.5, 0.5, 0.97, v.astype(np.float32))]


@pytest.fixture
def volti():
    return VoltiFinti()


@pytest.fixture
def client(tmp_path, volti):
    with TestClient(crea_app(ModelloFinto(), tmp_path, SEGRETO, volti), headers={"X-Visione-Segreto": SEGRETO}) as c:
        yield c


def test_senza_segreto_niente(tmp_path, volti):
    with TestClient(crea_app(ModelloFinto(), tmp_path, SEGRETO, volti)) as c:
        assert c.get("/volti/indice").status_code == 401
        assert c.post("/volti/vicini", json={"vettori": [[1, 0, 0]]}).status_code == 401


def test_rileva_i_volti_di_piu_immagini(client, volti):
    file = [
        ("file", ("rossa", jpeg((250, 0, 0)), "image/jpeg")),
        ("file", ("nera", jpeg((0, 0, 0)), "image/jpeg")),
        ("file", ("rotta", b"niente", "image/jpeg")),
        ("file", ("con spazio", jpeg((0, 0, 250)), "image/jpeg")),
    ]
    r = client.post("/volti/rileva", files=file, params={"lato_minimo": 40, "punteggio_minimo": 0.6})
    assert r.status_code == 200, r.text
    corpo = r.json()
    assert corpo["modello"] == "volti-finti" and corpo["dimensione"] == 3
    assert set(corpo["risultati"]) == {"rossa", "nera"}
    assert corpo["risultati"]["nera"]["volti"] == []
    rossa = corpo["risultati"]["rossa"]
    assert (rossa["larghezza"], rossa["altezza"]) == (32, 24)
    volto = rossa["volti"][0]
    assert volto["riquadro"] == {"x": 0.0, "y": 0.25, "larghezza": 0.5, "altezza": 0.5}
    assert volto["punteggio"] == pytest.approx(0.97)
    assert np.linalg.norm(volto["vettore"]) == pytest.approx(1, abs=1e-4)
    assert np.argmax(volto["vettore"]) == 0
    assert set(corpo["errori"]) == {"rotta", "con spazio"}
    # I limiti arrivano al modello.
    assert volti.chiamate[0] == ((32, 24), 40, 0.6)
    # Rilevare non mette niente nell'indice: lo decide l'app.
    assert client.get("/volti/indice").json()["totale"] == 0


def test_indice_dei_volti_vicini_e_togli(client):
    voci = [{"id": "a", "vettore": [1, 0, 0]}, {"id": "b", "vettore": [0.9, 0.1, 0]},
            {"id": "c", "vettore": [0, 1, 0]}, {"id": "x y", "vettore": [0, 0, 1]},
            {"id": "nullo", "vettore": [0, 0, 0]}]
    r = client.post("/volti/indice", json={"voci": voci}).json()
    assert r["messi"] == 3 and r["totale"] == 3
    assert set(r["errori"]) == {"x y", "nullo"}

    r = client.post("/volti/vicini", json={"vettori": [[1, 0, 0], [0, 1, 0]], "k": 2})
    assert r.status_code == 200
    primo, secondo = r.json()["risultati"]
    assert [v["id"] for v in primo] == ["a", "b"]
    assert primo[0]["punteggio"] == pytest.approx(1, abs=1e-3)
    assert secondo[0]["id"] == "c"
    con_soglia = client.post("/volti/vicini", json={"vettori": [[1, 0, 0]], "k": 10, "soglia": 0.9}).json()
    assert [v["id"] for v in con_soglia["risultati"][0]] == ["a", "b"]
    assert client.post("/volti/vicini", json={"vettori": [], "k": 3}).json() == {"risultati": []}
    assert client.post("/volti/vicini", json={"vettori": [[1, 0]], "k": 3}).status_code == 400
    assert client.post("/volti/vicini", json={"vettori": [[1, 0, 0]], "k": 0}).status_code == 400

    assert client.post("/volti/indice/togli", json={"ids": ["a", "non-c-e"]}).json() == {"tolti": 1, "totale": 2}
    ids = [v["id"] for v in client.post("/volti/vicini", json={"vettori": [[1, 0, 0]]}).json()["risultati"][0]]
    assert "a" not in ids


def test_i_volti_hanno_un_indice_loro(tmp_path, volti):
    with TestClient(crea_app(ModelloFinto(), tmp_path, SEGRETO, volti), headers={"X-Visione-Segreto": SEGRETO}) as c:
        c.put("/clip/indice/foto", content=jpeg((250, 0, 0)), headers={"Content-Type": "image/jpeg"})
        c.post("/volti/indice", json={"voci": [{"id": "v1", "vettore": [0, 1, 0]}]})
        assert c.get("/clip/indice").json()["totale"] == 1
        assert c.get("/volti/indice").json()["totale"] == 1
    # Dopo un riavvio ci sono ancora, ognuno nel suo.
    assert (tmp_path / "volti").is_dir()
    with TestClient(crea_app(ModelloFinto(), tmp_path, SEGRETO, volti), headers={"X-Visione-Segreto": SEGRETO}) as c:
        assert c.get("/volti/indice").json()["totale"] == 1
        assert c.post("/volti/vicini", json={"vettori": [[0, 1, 0]], "k": 1}).json()["risultati"][0][0]["id"] == "v1"
        assert c.get("/salute").json()["volti"] is True


def test_volti_spenti(tmp_path, monkeypatch):
    monkeypatch.setenv("VISIONE_VOLTI", "false")
    with TestClient(crea_app(ModelloFinto(), tmp_path, SEGRETO), headers={"X-Visione-Segreto": SEGRETO}) as c:
        assert c.get("/volti/indice").status_code == 503
        assert c.post("/volti/rileva", files=[("file", ("a", jpeg((1, 2, 3)), "image/jpeg"))]).status_code == 503
        # CLIP va come prima.
        assert c.get("/clip/indice").status_code == 200
        assert c.get("/salute").json()["volti"] is False


def test_un_immagine_grande_non_passa(client):
    grande = io.BytesIO()
    Image.new("RGB", (10, 10)).save(grande, "PNG")
    dati = grande.getvalue() + b"0" * (21 * 1024 * 1024)
    r = client.post("/volti/rileva", files=[("file", ("grande", dati, "image/png"))])
    assert r.status_code == 200
    assert r.json()["errori"] == {"grande": "Immagine troppo grande: mandare la miniatura"}
