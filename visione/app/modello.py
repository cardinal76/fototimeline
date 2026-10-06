"""CLIP multilingue: immagini e testo (anche in italiano) nello stesso spazio a 512 dimensioni.

- Immagini: la parte visiva di CLIP ViT-B/32 di OpenAI (``openai/clip-vit-base-patch32``,
  la stessa che sentence-transformers chiama ``clip-ViT-B-32``), solo la torre
  visiva: circa 88 milioni di parametri.
- Testo: ``sentence-transformers/clip-ViT-B-32-multilingual-v1``, una DistilBERT
  multilingue (50+ lingue, italiano compreso) addestrata a dare per una frase
  lo stesso vettore che darebbe il testo inglese di CLIP: circa 135 milioni di
  parametri.

Pesi scaricati in ``VISIONE_MODELLI`` quando si costruisce l'immagine Docker
(``scarica()``), mai a runtime: si caricano da quella cartella. torch e transformers si importano solo qui,
così i test del servizio girano con un modello finto senza installarli.
"""

from __future__ import annotations

import io
import os
from pathlib import Path
from typing import Protocol

import numpy as np
from PIL import Image, ImageOps

IMMAGINI = "openai/clip-vit-base-patch32"
TESTO = "sentence-transformers/clip-ViT-B-32-multilingual-v1"
# Solo i file che servono (niente pesi TensorFlow e Flax): circa 1,1 GB in tutto.
FILE_IMMAGINI = ["config.json", "preprocessor_config.json", "pytorch_model.bin"]
FILE_TESTO = ["*.json", "vocab.txt", "model.safetensors", "1_Pooling/*", "2_Dense/*"]
NOME = "clip-vit-b-32+multilingual-v1"
DIMENSIONE = 512


class Modello(Protocol):
    nome: str
    dimensione: int

    def immagini(self, immagini: list[Image.Image]) -> np.ndarray:
        """Una riga normalizzata per immagine."""

    def testi(self, testi: list[str]) -> np.ndarray:
        """Una riga normalizzata per testo."""


def apri_immagine(dati: bytes) -> Image.Image:
    """JPEG (la miniatura dell'app) o un altro formato che Pillow sa leggere, già dritto e in RGB."""
    if not dati:
        raise ValueError("Immagine vuota")
    try:
        img = Image.open(io.BytesIO(dati))
        img = ImageOps.exif_transpose(img)
        return img.convert("RGB")
    except Exception as e:  # Pillow lancia di tutto, per un file rovinato
        raise ValueError(f"Immagine illeggibile ({e})") from e


class ClipMultilingue:
    nome = NOME
    dimensione = DIMENSIONE

    def __init__(self, thread: int | None = None):
        import torch
        from sentence_transformers import SentenceTransformer
        from transformers import CLIPImageProcessor, CLIPVisionModelWithProjection

        # Pochi thread: server2 fa anche le build della CI.
        torch.set_num_threads(thread or int(os.environ.get("VISIONE_THREAD", "2")))
        self._torch = torch
        immagini, testo = cartelle()
        self._visione = CLIPVisionModelWithProjection.from_pretrained(immagini).eval()
        self._processore = CLIPImageProcessor.from_pretrained(immagini)
        self._testo = SentenceTransformer(str(testo), device="cpu")

    def immagini(self, immagini: list[Image.Image]) -> np.ndarray:
        with self._torch.inference_mode():
            ingresso = self._processore(images=immagini, return_tensors="pt")
            v = self._visione(**ingresso).image_embeds.numpy().astype(np.float32)
        return v / np.linalg.norm(v, axis=1, keepdims=True)

    def testi(self, testi: list[str]) -> np.ndarray:
        v = self._testo.encode(testi, convert_to_numpy=True, normalize_embeddings=True, show_progress_bar=False)
        return v.astype(np.float32)


def cartelle() -> tuple[Path, Path]:
    radice = Path(os.environ.get("VISIONE_MODELLI", "/modelli"))
    return radice / "clip-vit-b-32", radice / "clip-multilingue"


def scarica() -> None:
    """Scarica i pesi in VISIONE_MODELLI: lo fa il Dockerfile, una volta, quando costruisce l'immagine."""
    from huggingface_hub import snapshot_download

    immagini, testo = cartelle()
    snapshot_download(IMMAGINI, local_dir=immagini, allow_patterns=FILE_IMMAGINI)
    snapshot_download(TESTO, local_dir=testo, allow_patterns=FILE_TESTO)


if __name__ == "__main__":
    scarica()
