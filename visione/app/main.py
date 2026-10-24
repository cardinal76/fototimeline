"""Servizio "visione": i modelli che guardano le foto, per l'app FotoTimeline.

Le API sono raggruppate per funzione:

- ``/clip/...``: CLIP, la ricerca per contenuto ("spiaggia", "torta di compleanno");
- ``/volti/...``: i volti (YuNet + SFace, ``volti.py``): dove sono in una foto
  e un vettore per ciascuno, più un indice dei vettori a parte (cartella
  ``volti`` nel volume) per trovare i volti più simili. Con
  ``VISIONE_VOLTI=false``, o senza OpenCV, queste rispondono 503.

Gira sulla rete interna di Docker, non esposto; l'app manda comunque il
segreto condiviso nell'header ``X-Visione-Segreto`` (``VISIONE_SEGRETO``).

Si avvia con ``uvicorn --factory app.main:crea_app``.
"""

from __future__ import annotations

import hmac
import logging
import os
import re
import threading
from contextlib import asynccontextmanager
from pathlib import Path

import numpy as np
from fastapi import APIRouter, Depends, FastAPI, Header, HTTPException, Query, Request, Response
from fastapi.concurrency import run_in_threadpool
from pydantic import BaseModel

from .indice import IndiceVettori
from .modello import Modello, apri_immagine
from .volti import ModelloVolti

log = logging.getLogger("visione")

ID_VALIDO = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
MAX_IMMAGINE = 20 * 1024 * 1024
MAX_K = 2000
# Volti: quante immagini per richiesta, quanti vettori per ricerca dei vicini.
MAX_IMMAGINI_VOLTI = 64
MAX_DOMANDE = 256
MAX_VICINI = 200
# Il testo di CLIP è addestrato su didascalie: "una foto di cane" va meglio di "cane" da solo.
FRASE = os.environ.get("VISIONE_FRASE", "una foto di {}")


class Testo(BaseModel):
    testo: str


class Vettore(BaseModel):
    vettore: list[float]


class VoceVolto(BaseModel):
    id: str
    vettore: list[float]


class Voci(BaseModel):
    voci: list[VoceVolto]


class Ids(BaseModel):
    ids: list[str]


class DomandaVicini(BaseModel):
    vettori: list[list[float]]
    k: int = 10
    soglia: float | None = None


def volti_attivi() -> bool:
    return os.environ.get("VISIONE_VOLTI", "true").strip().lower() not in ("false", "0", "no")


def crea_app(modello: Modello | None = None, cartella: str | Path | None = None,
             segreto: str | None = None, modello_volti: ModelloVolti | None = None) -> FastAPI:
    segreto = segreto if segreto is not None else os.environ.get("VISIONE_SEGRETO", "")
    if not segreto:
        raise RuntimeError("Manca VISIONE_SEGRETO: senza, chiunque sulla rete potrebbe usare il servizio")
    cartella = Path(cartella or os.environ.get("VISIONE_DATI", "/dati"))
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")

    stato: dict = {}
    # Un'inferenza alla volta: con pochi thread di torch due insieme vanno solo più piano.
    calcolo = threading.Lock()

    @asynccontextmanager
    async def durata(app: FastAPI):
        m = modello
        if m is None:
            from .modello import ClipMultilingue
            log.info("Carico il modello…")
            m = ClipMultilingue()
        indice = IndiceVettori(cartella, m.dimensione, m.nome)
        indice.carica()
        stato["modello"] = m
        stato["indice"] = indice
        mv = modello_volti
        if mv is None and volti_attivi():
            try:
                from .volti import YunetSface
                mv = YunetSface()
            except Exception as e:  # OpenCV o i pesi che mancano: CLIP va lo stesso
                log.error("Volti spenti: modello non caricato (%s)", e)
        if mv is not None:
            # Un indice suo, nella sottocartella: i vettori dei volti non si mescolano con quelli di CLIP.
            indice_volti = IndiceVettori(cartella / "volti", mv.dimensione, mv.nome)
            indice_volti.carica()
            stato["volti"] = mv
            stato["indice_volti"] = indice_volti
            log.info("Volti: modello %s, %d vettori nell'indice", mv.nome, len(indice_volti))
        log.info("Pronto: modello %s, %d vettori nell'indice", m.nome, len(indice))
        try:
            yield
        finally:
            indice.chiudi()
            if "indice_volti" in stato:
                stato["indice_volti"].chiudi()

    app = FastAPI(title="FotoTimeline visione", lifespan=durata)

    def autorizza(x_visione_segreto: str = Header(default="")) -> None:
        if not hmac.compare_digest(x_visione_segreto.encode(), segreto.encode()):
            raise HTTPException(401, "Segreto mancante o sbagliato")

    def m() -> Modello:
        return stato["modello"]

    def indice() -> IndiceVettori:
        return stato["indice"]

    def vettore_immagini(dati: list[bytes]) -> np.ndarray:
        immagini = [apri_immagine(d) for d in dati]
        with calcolo:
            return m().immagini(immagini)

    def vettore_testo(testo: str) -> np.ndarray:
        testo = testo.strip()
        if not testo:
            raise ValueError("Testo vuoto")
        with calcolo:
            return m().testi([FRASE.format(testo)])[0]

    async def immagine_dalla_richiesta(request: Request) -> bytes:
        """Il corpo grezzo (image/jpeg) o il campo ``file`` di un multipart."""
        tipo = request.headers.get("content-type", "")
        if tipo.startswith("multipart/form-data"):
            modulo = await request.form()
            file = modulo.get("file")
            if file is None or isinstance(file, str):
                raise HTTPException(400, "Manca il campo file")
            dati = await file.read()
        else:
            dati = await request.body()
        if len(dati) > MAX_IMMAGINE:
            raise HTTPException(413, "Immagine troppo grande: mandare la miniatura")
        return dati

    def controlla_id(ident: str) -> str:
        if not ID_VALIDO.match(ident):
            raise HTTPException(400, "Id non valido")
        return ident

    @app.get("/salute")
    def salute():
        """Per l'healthcheck di Docker: senza segreto, non dice niente di privato."""
        pronto = "indice" in stato
        return {"stato": "ok" if pronto else "avvio", "vettori": len(indice()) if pronto else 0,
                "volti": "volti" in stato}

    clip = APIRouter(prefix="/clip", dependencies=[Depends(autorizza)])

    @clip.post("/immagine")
    async def immagine(request: Request):
        dati = await immagine_dalla_richiesta(request)
        try:
            v = await run_in_threadpool(lambda: vettore_immagini([dati])[0])
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        return {"modello": m().nome, "vettore": v.tolist()}

    @clip.post("/testo")
    async def testo(corpo: Testo):
        try:
            v = await run_in_threadpool(vettore_testo, corpo.testo)
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        return {"modello": m().nome, "vettore": v.tolist()}

    @clip.get("/indice")
    def stato_indice():
        return {"modello": m().nome, "dimensione": m().dimensione, "totale": len(indice())}

    @clip.put("/indice/{ident}")
    async def metti(ident: str, request: Request):
        """Aggiunge o sostituisce: dal JPEG (lo calcola qui) o da ``{"vettore": [...]}``."""
        controlla_id(ident)
        if request.headers.get("content-type", "").startswith("application/json"):
            try:
                v = np.asarray(Vettore.model_validate_json(await request.body()).vettore, dtype=np.float32)
            except Exception as e:
                raise HTTPException(400, f"Vettore non valido ({e})") from e
        else:
            dati = await immagine_dalla_richiesta(request)
            try:
                v = await run_in_threadpool(lambda: vettore_immagini([dati])[0])
            except ValueError as e:
                raise HTTPException(400, str(e)) from e
        try:
            await run_in_threadpool(indice().metti, ident, v)
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        return {"id": ident, "totale": len(indice())}

    @clip.post("/indice")
    async def metti_blocco(request: Request):
        """Più foto in una richiesta (multipart, un campo ``file`` per foto con l'id come nome del file):
        il modello le guarda tutte insieme, più in fretta che una per volta."""
        modulo = await request.form()
        voci: list[tuple[str, bytes]] = []
        errori: dict[str, str] = {}
        for file in modulo.getlist("file"):
            if isinstance(file, str):
                continue
            ident = file.filename or ""
            dati = await file.read()
            if not ID_VALIDO.match(ident):
                errori[ident or "?"] = "Id non valido"
            elif len(dati) > MAX_IMMAGINE:
                errori[ident] = "Immagine troppo grande"
            else:
                voci.append((ident, dati))

        def calcola() -> list[str]:
            leggibili: list[tuple[str, object]] = []
            for ident, dati in voci:
                try:
                    leggibili.append((ident, apri_immagine(dati)))
                except ValueError as e:
                    errori[ident] = str(e)
            if not leggibili:
                return []
            with calcolo:
                vettori = m().immagini([img for _, img in leggibili])
            for (ident, _), v in zip(leggibili, vettori):
                indice().metti(ident, v)
            return [ident for ident, _ in leggibili]

        aggiunte = await run_in_threadpool(calcola)
        return {"aggiunte": aggiunte, "errori": errori, "totale": len(indice())}

    @clip.delete("/indice/{ident}", status_code=204)
    def togli(ident: str):
        controlla_id(ident)
        if not indice().togli(ident):
            raise HTTPException(404, "Non è nell'indice")
        return Response(status_code=204)

    @clip.get("/cerca")
    def cerca(q: str = Query(min_length=1, max_length=500), k: int = Query(100, ge=1, le=MAX_K),
              soglia: float | None = Query(None, ge=-1, le=1)):
        """Le foto più simili al testo, dalla più simile: id e punteggio (coseno, da -1 a 1)."""
        try:
            v = vettore_testo(q)
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        risultati = indice().cerca(v, k, soglia)
        return {"modello": m().nome, "risultati": [{"id": i, "punteggio": round(p, 4)} for i, p in risultati]}

    app.include_router(clip)

    # ------------------------------------------------------------ volti

    def mv() -> ModelloVolti:
        if "volti" not in stato:
            raise HTTPException(503, "Volti spenti in visione (VISIONE_VOLTI=false o modello non caricato)")
        return stato["volti"]

    def indice_volti() -> IndiceVettori:
        mv()
        return stato["indice_volti"]

    volti = APIRouter(prefix="/volti", dependencies=[Depends(autorizza)])

    @volti.post("/rileva")
    async def rileva(request: Request, lato_minimo: int = Query(32, ge=1, le=2000),
                     punteggio_minimo: float = Query(0.8, ge=0, le=1)):
        """I volti di una o più immagini (multipart, un campo ``file`` per foto con l'id come nome del
        file): per ciascuna riquadro (in frazioni dell'immagine), punteggio e vettore. Non tocca l'indice."""
        modello_v = mv()
        modulo = await request.form()
        voci: list[tuple[str, bytes]] = []
        errori: dict[str, str] = {}
        for file in modulo.getlist("file"):
            if isinstance(file, str):
                continue
            ident = file.filename or ""
            dati = await file.read()
            if not ID_VALIDO.match(ident):
                errori[ident or "?"] = "Id non valido"
            elif len(dati) > MAX_IMMAGINE:
                errori[ident] = "Immagine troppo grande: mandare la miniatura"
            else:
                voci.append((ident, dati))
        if len(voci) > MAX_IMMAGINI_VOLTI:
            raise HTTPException(413, f"Al massimo {MAX_IMMAGINI_VOLTI} immagini per richiesta")

        def calcola() -> dict:
            risultati: dict[str, dict] = {}
            for ident, dati in voci:
                try:
                    img = apri_immagine(dati)
                except ValueError as e:
                    errori[ident] = str(e)
                    continue
                with calcolo:
                    trovati = modello_v.volti(img, lato_minimo, punteggio_minimo)
                risultati[ident] = {
                    "larghezza": img.width,
                    "altezza": img.height,
                    "volti": [{
                        "riquadro": {"x": round(t.x, 5), "y": round(t.y, 5),
                                     "larghezza": round(t.larghezza, 5), "altezza": round(t.altezza, 5)},
                        "punteggio": round(t.punteggio, 4),
                        "vettore": [round(float(v), 6) for v in t.vettore],
                    } for t in trovati],
                }
            return risultati

        risultati = await run_in_threadpool(calcola)
        return {"modello": modello_v.nome, "dimensione": modello_v.dimensione, "risultati": risultati,
                "errori": errori}

    @volti.get("/indice")
    def stato_indice_volti():
        return {"modello": mv().nome, "dimensione": mv().dimensione, "totale": len(indice_volti())}

    @volti.post("/indice")
    def metti_volti(corpo: Voci):
        """Aggiunge o sostituisce i vettori di più volti (l'id è quello del volto nell'app)."""
        errori: dict[str, str] = {}
        messi = 0
        for voce in corpo.voci:
            if not ID_VALIDO.match(voce.id):
                errori[voce.id or "?"] = "Id non valido"
                continue
            try:
                indice_volti().metti(voce.id, np.asarray(voce.vettore, dtype=np.float32))
                messi += 1
            except ValueError as e:
                errori[voce.id] = str(e)
        return {"messi": messi, "errori": errori, "totale": len(indice_volti())}

    @volti.post("/indice/togli")
    def togli_volti(corpo: Ids):
        """Toglie più volti; quelli che non c'erano si contano a parte."""
        tolti = sum(1 for i in corpo.ids if ID_VALIDO.match(i) and indice_volti().togli(i))
        return {"tolti": tolti, "totale": len(indice_volti())}

    @volti.post("/vicini")
    def vicini(corpo: DomandaVicini):
        """Per ogni vettore, i volti più simili dell'indice (coseno), dal più simile."""
        if len(corpo.vettori) > MAX_DOMANDE:
            raise HTTPException(413, f"Al massimo {MAX_DOMANDE} vettori per richiesta")
        if not 1 <= corpo.k <= MAX_VICINI:
            raise HTTPException(400, f"k va da 1 a {MAX_VICINI}")
        try:
            risultati = indice_volti().cerca_molti(np.asarray(corpo.vettori, dtype=np.float32).reshape(
                len(corpo.vettori), -1) if corpo.vettori else np.zeros((0, mv().dimensione)), corpo.k, corpo.soglia)
        except ValueError as e:
            raise HTTPException(400, str(e)) from e
        return {"risultati": [[{"id": i, "punteggio": round(p, 4)} for i, p in r] for r in risultati]}

    app.include_router(volti)
    return app
