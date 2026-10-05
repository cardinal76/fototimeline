"""Servizio "visione": i modelli che guardano le foto, per l'app FotoTimeline.

Per ora solo CLIP (ricerca per contenuto: "spiaggia", "torta di compleanno");
le API sono raggruppate per funzione (``/clip/...``) così un domani i volti
stanno accanto (``/volti/...``) senza toccare queste.

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

log = logging.getLogger("visione")

ID_VALIDO = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")
MAX_IMMAGINE = 20 * 1024 * 1024
MAX_K = 2000
# Il testo di CLIP è addestrato su didascalie: "una foto di cane" va meglio di "cane" da solo.
FRASE = os.environ.get("VISIONE_FRASE", "una foto di {}")


class Testo(BaseModel):
    testo: str


class Vettore(BaseModel):
    vettore: list[float]


def crea_app(modello: Modello | None = None, cartella: str | Path | None = None,
             segreto: str | None = None) -> FastAPI:
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
        log.info("Pronto: modello %s, %d vettori nell'indice", m.nome, len(indice))
        try:
            yield
        finally:
            indice.chiudi()

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
        return {"stato": "ok" if pronto else "avvio", "vettori": len(indice()) if pronto else 0}

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
    return app
