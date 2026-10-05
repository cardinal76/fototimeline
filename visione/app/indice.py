"""Indice dei vettori: un vettore float16 normalizzato per id, tutto in memoria.

Con 150.000 foto e 512 dimensioni sono circa 150 MB: stanno qui e non nella JVM
dell'app (384 MB). Su disco, nella cartella dei dati (volume ``visione-dati``):

- ``indice.npz``: l'istantanea (ids, vettori, nome del modello);
- ``giornale.bin``: le modifiche dopo l'istantanea, una riga binaria per
  PUT o DELETE, scritte subito. All'avvio si carica l'istantanea e si
  rigioca il giornale; ogni tanto (e alla chiusura) si riscrive l'istantanea
  e il giornale riparte vuoto.

Così un PUT confermato all'app non si perde anche se il container muore un
attimo dopo, e non si riscrivono 150 MB a ogni foto.
"""

from __future__ import annotations

import logging
import os
import threading
from pathlib import Path

import numpy as np

log = logging.getLogger(__name__)

ISTANTANEA = "indice.npz"
GIORNALE = "giornale.bin"
METTI = b"P"
TOGLI = b"D"


class IndiceVettori:
    def __init__(self, cartella: Path, dimensione: int, modello: str, righe_per_istantanea: int = 5000):
        self.cartella = Path(cartella)
        self.dimensione = dimensione
        self.modello = modello
        self.righe_per_istantanea = righe_per_istantanea
        self._lock = threading.RLock()
        self._ids: list[str] = []
        self._posizioni: dict[str, int] = {}
        self._vettori = np.zeros((1024, dimensione), dtype=np.float16)
        self._giornale = None
        self._righe_giornale = 0

    # ------------------------------------------------------------ disco

    def carica(self) -> None:
        """Istantanea più giornale. Un modello diverso da quello dei vettori salvati: si riparte vuoti."""
        self.cartella.mkdir(parents=True, exist_ok=True)
        istantanea = self.cartella / ISTANTANEA
        giornale = self.cartella / GIORNALE
        with self._lock:
            if istantanea.exists():
                with np.load(istantanea, allow_pickle=False) as dati:
                    modello = str(dati["modello"])
                    vettori = dati["vettori"]
                    if modello != self.modello or vettori.shape[1] != self.dimensione:
                        log.warning("L'indice su disco è del modello %s (%d dimensioni), non di %s: riparto vuoto "
                                    "(il vecchio resta in %s.vecchio)", modello, vettori.shape[1], self.modello, ISTANTANEA)
                        os.replace(istantanea, istantanea.with_name(ISTANTANEA + ".vecchio"))
                        giornale.unlink(missing_ok=True)
                    else:
                        self._riempi([str(i) for i in dati["ids"]], vettori)
            if giornale.exists():
                self._righe_giornale = self._rigioca(giornale)
            self._giornale = open(giornale, "ab")
            log.info("Indice caricato: %d vettori (%d modifiche dal giornale)", len(self._ids), self._righe_giornale)
            if self._righe_giornale >= self.righe_per_istantanea:
                self.salva()

    def salva(self) -> None:
        """Riscrive l'istantanea (prima su un file a parte, poi lo spostamento) e svuota il giornale."""
        with self._lock:
            self.cartella.mkdir(parents=True, exist_ok=True)
            temporaneo = self.cartella / (ISTANTANEA + ".parziale.npz")
            n = len(self._ids)
            np.savez(temporaneo, ids=np.array(self._ids, dtype=str), vettori=self._vettori[:n],
                     modello=np.array(self.modello))
            os.replace(temporaneo, self.cartella / ISTANTANEA)
            if self._giornale is not None:
                self._giornale.close()
            self._giornale = open(self.cartella / GIORNALE, "wb")
            self._righe_giornale = 0
            log.info("Istantanea dell'indice scritta: %d vettori", n)

    def chiudi(self) -> None:
        with self._lock:
            if self._giornale is not None:
                if self._righe_giornale:
                    self.salva()
                self._giornale.close()
                self._giornale = None

    def _rigioca(self, giornale: Path) -> int:
        righe = 0
        dati = giornale.read_bytes()
        pos = 0
        lunghezza_vettore = self.dimensione * 2
        while pos + 2 <= len(dati):
            op = dati[pos:pos + 1]
            n = dati[pos + 1]
            fine_id = pos + 2 + n
            if op == METTI:
                fine = fine_id + lunghezza_vettore
            elif op == TOGLI:
                fine = fine_id
            else:
                log.warning("Giornale rovinato alla posizione %d: mi fermo lì", pos)
                break
            if fine > len(dati):
                # L'ultima riga scritta a metà (container fermato in quel momento): si ignora.
                log.warning("Ultima riga del giornale incompleta: la ignoro")
                break
            ident = dati[pos + 2:fine_id].decode()
            if op == METTI:
                self._metti(ident, np.frombuffer(dati[fine_id:fine], dtype="<f2"))
            else:
                self._togli(ident)
            righe += 1
            pos = fine
        return righe

    def _scrivi(self, riga: bytes) -> None:
        if self._giornale is None:
            return
        self._giornale.write(riga)
        self._giornale.flush()
        self._righe_giornale += 1
        if self._righe_giornale >= self.righe_per_istantanea:
            self.salva()

    # ------------------------------------------------------------ modifiche

    def metti(self, ident: str, vettore: np.ndarray) -> None:
        """Aggiunge o sostituisce il vettore di un id (normalizzato qui)."""
        v = normalizza(np.asarray(vettore, dtype=np.float32).reshape(-1))
        if v.shape[0] != self.dimensione:
            raise ValueError(f"Il vettore ha {v.shape[0]} dimensioni, ne servono {self.dimensione}")
        chiave = ident.encode()
        if not 0 < len(chiave) < 256:
            raise ValueError("Id vuoto o troppo lungo")
        v16 = v.astype("<f2")
        with self._lock:
            self._metti(ident, v16)
            self._scrivi(METTI + bytes([len(chiave)]) + chiave + v16.tobytes())

    def togli(self, ident: str) -> bool:
        chiave = ident.encode()
        with self._lock:
            if ident not in self._posizioni:
                return False
            self._togli(ident)
            self._scrivi(TOGLI + bytes([len(chiave)]) + chiave)
            return True

    def _riempi(self, ids: list[str], vettori: np.ndarray) -> None:
        self._ids = list(ids)
        self._posizioni = {i: p for p, i in enumerate(self._ids)}
        capacita = max(1024, len(ids) * 5 // 4)
        self._vettori = np.zeros((capacita, self.dimensione), dtype=np.float16)
        self._vettori[:len(ids)] = vettori

    def _metti(self, ident: str, v16: np.ndarray) -> None:
        pos = self._posizioni.get(ident)
        if pos is None:
            pos = len(self._ids)
            if pos == self._vettori.shape[0]:
                piu_grande = np.zeros((pos * 3 // 2 + 1024, self.dimensione), dtype=np.float16)
                piu_grande[:pos] = self._vettori
                self._vettori = piu_grande
            self._ids.append(ident)
            self._posizioni[ident] = pos
        self._vettori[pos] = v16

    def _togli(self, ident: str) -> None:
        pos = self._posizioni.pop(ident, None)
        if pos is None:
            return
        # L'ultimo prende il posto di quello tolto: niente buchi.
        ultimo = len(self._ids) - 1
        if pos != ultimo:
            spostato = self._ids[ultimo]
            self._ids[pos] = spostato
            self._vettori[pos] = self._vettori[ultimo]
            self._posizioni[spostato] = pos
        self._ids.pop()

    # ------------------------------------------------------------ lettura

    def __len__(self) -> int:
        return len(self._ids)

    def __contains__(self, ident: str) -> bool:
        return ident in self._posizioni

    def vettore(self, ident: str) -> np.ndarray | None:
        with self._lock:
            pos = self._posizioni.get(ident)
            return None if pos is None else self._vettori[pos].astype(np.float32)

    def cerca(self, domanda: np.ndarray, k: int, soglia: float | None = None) -> list[tuple[str, float]]:
        """I k id più simili (prodotto scalare tra vettori normalizzati = coseno), dal più simile."""
        q = normalizza(np.asarray(domanda, dtype=np.float32).reshape(-1))
        with self._lock:
            n = len(self._ids)
            if n == 0 or k <= 0:
                return []
            punteggi = np.empty(n, dtype=np.float32)
            # A blocchi: float16 → float32 su tutto l'indice vorrebbe 300 MB in un colpo.
            blocco = 4096
            for inizio in range(0, n, blocco):
                fine = min(n, inizio + blocco)
                punteggi[inizio:fine] = self._vettori[inizio:fine].astype(np.float32) @ q
            ids = list(self._ids)
        k = min(k, n)
        migliori = np.argpartition(-punteggi, k - 1)[:k]
        migliori = migliori[np.argsort(-punteggi[migliori], kind="stable")]
        risultati = [(ids[i], float(punteggi[i])) for i in migliori]
        if soglia is not None:
            risultati = [r for r in risultati if r[1] >= soglia]
        return risultati


def normalizza(v: np.ndarray) -> np.ndarray:
    norma = float(np.linalg.norm(v))
    if not np.isfinite(norma) or norma == 0:
        raise ValueError("Vettore nullo o non valido")
    return (v / norma).astype(np.float32)
