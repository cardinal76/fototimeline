"""Le API con un modello finto: il colore medio dell'immagine diventa il vettore,
e il testo "rosso", "verde" o "blu" il vettore di quel colore."""

import io

import numpy as np
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.main import crea_app

SEGRETO = "segreto-di-prova"
COLORI = {"rosso": (1, 0, 0), "verde": (0, 1, 0), "blu": (0, 0, 1)}


class ModelloFinto:
    nome = "finto"
    dimensione = 3

    def __init__(self):
        self.testi_visti = []

    def immagini(self, immagini):
        v = np.array([np.asarray(i, dtype=np.float32).reshape(-1, 3).mean(axis=0) + 1 for i in immagini])
        return v / np.linalg.norm(v, axis=1, keepdims=True)

    def testi(self, testi):
        self.testi_visti.extend(testi)
        righe = []
        for t in testi:
            colore = next((c for nome, c in COLORI.items() if nome in t), (1, 1, 1))
            righe.append(np.array(colore, dtype=np.float32))
        v = np.array(righe)
        return v / np.linalg.norm(v, axis=1, keepdims=True)


def jpeg(colore):
    out = io.BytesIO()
    Image.new("RGB", (32, 24), colore).save(out, "JPEG")
    return out.getvalue()


@pytest.fixture
def modello():
    return ModelloFinto()


@pytest.fixture
def client(tmp_path, modello):
    with TestClient(crea_app(modello, tmp_path, SEGRETO), headers={"X-Visione-Segreto": SEGRETO}) as c:
        yield c


def test_senza_segreto_niente(tmp_path, modello):
    with TestClient(crea_app(modello, tmp_path, SEGRETO)) as c:
        assert c.get("/salute").status_code == 200
        assert c.get("/clip/indice").status_code == 401
        assert c.get("/clip/indice", headers={"X-Visione-Segreto": "sbagliato"}).status_code == 401
        assert c.put("/clip/indice/a", content=jpeg((255, 0, 0)), headers={"Content-Type": "image/jpeg"}).status_code == 401


def test_senza_segreto_configurato_non_parte(tmp_path, modello, monkeypatch):
    monkeypatch.delenv("VISIONE_SEGRETO", raising=False)
    with pytest.raises(RuntimeError):
        crea_app(modello, tmp_path)


def test_vettori_di_immagine_e_testo(client, modello):
    r = client.post("/clip/immagine", content=jpeg((250, 0, 0)), headers={"Content-Type": "image/jpeg"})
    assert r.status_code == 200
    v = np.array(r.json()["vettore"])
    assert np.linalg.norm(v) == pytest.approx(1, abs=1e-5)
    assert v.argmax() == 0

    r = client.post("/clip/immagine", files={"file": ("x.jpg", jpeg((0, 0, 250)), "image/jpeg")})
    assert np.array(r.json()["vettore"]).argmax() == 2

    r = client.post("/clip/testo", json={"testo": "verde"})
    assert np.array(r.json()["vettore"]).argmax() == 1
    # La parola va dentro una frase, come le didascalie su cui CLIP ha imparato.
    assert modello.testi_visti[-1] == "una foto di verde"

    assert client.post("/clip/immagine", content=b"non un jpeg", headers={"Content-Type": "image/jpeg"}).status_code == 400
    assert client.post("/clip/testo", json={"testo": "  "}).status_code == 400


def test_indice_aggiungi_cerca_togli(client):
    for ident, colore in [("rossa", (250, 10, 10)), ("verde", (10, 250, 10)), ("rossiccia", (200, 80, 60))]:
        r = client.put(f"/clip/indice/{ident}", content=jpeg(colore), headers={"Content-Type": "image/jpeg"})
        assert r.status_code == 200, r.text
    assert client.get("/clip/indice").json()["totale"] == 3

    risultati = client.get("/clip/cerca", params={"q": "rosso", "k": 10}).json()["risultati"]
    assert [r["id"] for r in risultati] == ["rossa", "rossiccia", "verde"]
    assert risultati[0]["punteggio"] > risultati[1]["punteggio"] > risultati[2]["punteggio"]

    soglia = client.get("/clip/cerca", params={"q": "rosso", "soglia": 0.8}).json()["risultati"]
    assert [r["id"] for r in soglia] == ["rossa", "rossiccia"]

    # Aggiorna: la "rossa" diventa blu.
    client.put("/clip/indice/rossa", content=jpeg((10, 10, 250)), headers={"Content-Type": "image/jpeg"})
    assert client.get("/clip/indice").json()["totale"] == 3
    assert client.get("/clip/cerca", params={"q": "blu", "k": 1}).json()["risultati"][0]["id"] == "rossa"

    assert client.delete("/clip/indice/rossa").status_code == 204
    assert client.delete("/clip/indice/rossa").status_code == 404
    ids = [r["id"] for r in client.get("/clip/cerca", params={"q": "blu"}).json()["risultati"]]
    assert "rossa" not in ids


def test_indice_da_vettore_json(client):
    r = client.put("/clip/indice/a", json={"vettore": [0, 0, 2]})
    assert r.status_code == 200
    assert client.get("/clip/cerca", params={"q": "blu", "k": 1}).json()["risultati"][0]["punteggio"] == pytest.approx(1)
    assert client.put("/clip/indice/b", json={"vettore": [1, 2]}).status_code == 400
    assert client.put("/clip/indice/b", json={"vettore": [0, 0, 0]}).status_code == 400


def test_id_non_validi(client):
    assert client.put("/clip/indice/a b", content=jpeg((1, 2, 3)), headers={"Content-Type": "image/jpeg"}).status_code == 400
    assert client.delete("/clip/indice/" + "x" * 200).status_code == 400


def test_blocco_di_foto(client):
    file = [
        ("file", ("uno", jpeg((250, 0, 0)), "image/jpeg")),
        ("file", ("due", jpeg((0, 250, 0)), "image/jpeg")),
        ("file", ("rotta", b"niente", "image/jpeg")),
        ("file", ("con spazio", jpeg((0, 0, 250)), "image/jpeg")),
    ]
    r = client.post("/clip/indice", files=file)
    assert r.status_code == 200
    corpo = r.json()
    assert corpo["aggiunte"] == ["uno", "due"]
    assert set(corpo["errori"]) == {"rotta", "con spazio"}
    assert corpo["totale"] == 2


def test_indice_sopravvive_al_riavvio(tmp_path, modello):
    with TestClient(crea_app(modello, tmp_path, SEGRETO), headers={"X-Visione-Segreto": SEGRETO}) as c:
        c.put("/clip/indice/a", content=jpeg((250, 0, 0)), headers={"Content-Type": "image/jpeg"})
        c.put("/clip/indice/b", content=jpeg((0, 250, 0)), headers={"Content-Type": "image/jpeg"})
        c.delete("/clip/indice/b")
    with TestClient(crea_app(modello, tmp_path, SEGRETO), headers={"X-Visione-Segreto": SEGRETO}) as c:
        assert c.get("/clip/indice").json()["totale"] == 1
        assert c.get("/clip/cerca", params={"q": "rosso"}).json()["risultati"][0]["id"] == "a"
        assert c.get("/salute").json()["vettori"] == 1
