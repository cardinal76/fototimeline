"""Volti: dove sono in una foto e un vettore per ciascuno, per riconoscere le persone.

- Rilevamento: **YuNet** (``face_detection_yunet_2023mar.onnx``, licenza MIT,
  230 KB), il rilevatore di volti di OpenCV: riquadro, cinque punti (occhi,
  naso, bocca) e un punteggio, in una decina di millisecondi su una miniatura.
- Vettore: **SFace** (``face_recognition_sface_2021dec.onnx``, Apache 2.0,
  37 MB): il volto raddrizzato sui cinque punti (``alignCrop``, 112×112) diventa
  un vettore di 128 numeri; due volti della stessa persona hanno coseno alto
  (OpenCV indica 0,363 come confine su LFW), persone diverse vicino a zero.

Tutti e due girano con ``cv2.dnn`` (onnx dentro OpenCV), senza torch: circa
120 MB di RAM in più nel servizio. I pesi si scaricano quando si costruisce
l'immagine Docker (``scarica()``), da Hugging Face a una revisione fissa e con
lo SHA-256 controllato, mai a runtime. OpenCV si importa solo qui, così i test
girano con un modello finto senza installarlo.
"""

from __future__ import annotations

import hashlib
import os
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import numpy as np
from PIL import Image

NOME = "yunet-2023mar+sface-2021dec"
DIMENSIONE = 128
# Repository di Hugging Face dell'organizzazione OpenCV (gli stessi file di opencv_zoo), a una revisione fissa.
RILEVATORE = ("opencv/face_detection_yunet", "3cc26e7f1014a5ee5d74a42acee58bafc9d0a310",
              "face_detection_yunet_2023mar.onnx",
              "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4")
RICONOSCITORE = ("opencv/face_recognition_sface", "3d7082438a6e4551e840c9b2bb60b71e8da4b524",
                 "face_recognition_sface_2021dec.onnx",
                 "0ba9fbfa01b5270c96627c4ef784da859931e02f04419c829e83484087c34e79")
# Oltre questo lato l'immagine si rimpicciolisce prima di cercare i volti: la miniatura dell'app ne ha 480.
LATO_MASSIMO = 1280


@dataclass
class VoltoTrovato:
    """Un volto: riquadro in frazioni dell'immagine (0–1, dall'angolo in alto a sinistra), punteggio del
    rilevatore e vettore normalizzato."""
    x: float
    y: float
    larghezza: float
    altezza: float
    punteggio: float
    vettore: np.ndarray


class ModelloVolti(Protocol):
    nome: str
    dimensione: int

    def volti(self, immagine: Image.Image, lato_minimo: int, punteggio_minimo: float) -> list[VoltoTrovato]:
        """I volti dell'immagine con il lato corto del riquadro di almeno ``lato_minimo`` pixel."""


class YunetSface:
    nome = NOME
    dimensione = DIMENSIONE

    def __init__(self, thread: int | None = None):
        import cv2

        # Pochi thread, come per CLIP: server2 fa anche le build della CI.
        cv2.setNumThreads(thread or int(os.environ.get("VISIONE_THREAD", "2")))
        self._cv2 = cv2
        cartella = cartella_modelli()
        # Soglia bassa qui: quella vera la decide chi chiama (punteggio_minimo).
        self._rilevatore = cv2.FaceDetectorYN.create(str(cartella / RILEVATORE[2]), "", (320, 320), 0.5, 0.3, 500)
        self._riconoscitore = cv2.FaceRecognizerSF.create(str(cartella / RICONOSCITORE[2]), "")

    def volti(self, immagine: Image.Image, lato_minimo: int, punteggio_minimo: float) -> list[VoltoTrovato]:
        cv2 = self._cv2
        larghezza, altezza = immagine.size
        scala = min(1.0, LATO_MASSIMO / max(larghezza, altezza))
        if scala < 1:
            immagine = immagine.resize((max(1, round(larghezza * scala)), max(1, round(altezza * scala))))
        bgr = cv2.cvtColor(np.asarray(immagine), cv2.COLOR_RGB2BGR)
        a, l = bgr.shape[:2]
        self._rilevatore.setInputSize((l, a))
        _, righe = self._rilevatore.detect(bgr)
        trovati: list[VoltoTrovato] = []
        for r in righe if righe is not None else []:
            x, y, w, h = (float(v) for v in r[:4])
            punteggio = float(r[14])
            # Il lato minimo è in pixel dell'immagine ricevuta, non di quella rimpicciolita.
            if punteggio < punteggio_minimo or min(w, h) / scala < lato_minimo:
                continue
            vettore = self._riconoscitore.feature(self._riconoscitore.alignCrop(bgr, r)).reshape(-1)
            norma = float(np.linalg.norm(vettore))
            if not np.isfinite(norma) or norma == 0:
                continue
            x0, y0 = max(0.0, x), max(0.0, y)
            x1, y1 = min(float(l), x + w), min(float(a), y + h)
            trovati.append(VoltoTrovato(x0 / l, y0 / a, (x1 - x0) / l, (y1 - y0) / a, punteggio,
                                        (vettore / norma).astype(np.float32)))
        return trovati


def cartella_modelli() -> Path:
    return Path(os.environ.get("VISIONE_MODELLI", "/modelli")) / "volti"


def scarica() -> None:
    """Scarica i due modelli in VISIONE_MODELLI/volti: lo fa il Dockerfile, quando costruisce l'immagine."""
    from huggingface_hub import hf_hub_download

    cartella = cartella_modelli()
    for repository, revisione, nome, sha in (RILEVATORE, RICONOSCITORE):
        file = Path(hf_hub_download(repository, nome, revision=revisione, local_dir=cartella))
        trovato = hashlib.sha256(file.read_bytes()).hexdigest()
        if trovato != sha:
            raise SystemExit(f"{nome}: SHA-256 {trovato}, atteso {sha}")


if __name__ == "__main__":
    scarica()
