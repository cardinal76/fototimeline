/**
 * Selezione di molte foto in fretta, come in Google Foto: Maiusc+clic per un
 * intervallo, trascinamento col mouse (dal cerchio, o da una foto mentre si
 * seleziona) e, sul telefono, pressione lunga e poi il dito sulle foto.
 *
 * L'ordine è quello delle foto caricate (la timeline, attraverso giorni e
 * mesi): quelle non ancora caricate non entrano negli intervalli.
 */

/** Posizione di ogni foto nell'ordine della timeline: si calcola una volta per elenco. */
export function indiciPerId(foto: readonly { id: string }[]): Map<string, number> {
  const indice = new Map<string, number>();
  foto.forEach((f, i) => indice.set(f.id, i));
  return indice;
}

/**
 * La selezione con l'intervallo da `da` ad `a` (compresi, in qualunque verso)
 * scelto o tolto: `scegli` è lo stato della foto di partenza, come in Google Foto.
 * Null se una delle due non è fra le foto caricate.
 */
export function conIntervallo(
  selezionate: ReadonlySet<string>,
  foto: readonly { id: string }[],
  indice: ReadonlyMap<string, number>,
  da: string,
  a: string,
  scegli: boolean,
): Set<string> | null {
  const i = indice.get(da);
  const j = indice.get(a);
  if (i === undefined || j === undefined) {
    return null;
  }
  const n = new Set(selezionate);
  for (let k = Math.min(i, j); k <= Math.max(i, j); k++) {
    if (scegli) {
      n.add(foto[k].id);
    } else {
      n.delete(foto[k].id);
    }
  }
  return n;
}

/** Velocità dello scorrimento automatico, in pixel per fotogramma, vicino al bordo. */
export function velocitaBordo(y: number, cima: number, fondo: number, margine = 64, massima = 24): number {
  if (y < cima + margine) {
    return -Math.ceil(massima * Math.min(1, (cima + margine - y) / margine));
  }
  if (y > fondo - margine) {
    return Math.ceil(massima * Math.min(1, (y - fondo + margine) / margine));
  }
  return 0;
}

/** Quanto si deve muovere il mouse prima che una pressione diventi un trascinamento. */
const SOGLIA_MOUSE = 6;
/** Oltre questo il dito sta scorrendo la pagina: niente pressione lunga. */
const SOGLIA_DITO = 10;
/** Durata della pressione lunga, prima del menu del browser sulle immagini. */
const PRESSIONE_LUNGA = 450;

export interface OpzioniTrascina {
  /** Il contenitore che scorre (`<main>`). */
  contenitore: () => HTMLElement;
  /** Si può selezionare (gestore o amministratore). */
  puo: () => boolean;
  /** Si è già in modalità selezione. */
  inSelezione: () => boolean;
  /** Entra in modalità selezione. */
  entra: () => void;
  /** Stato attuale di una foto. */
  scelta: (id: string) => boolean;
  /** La selezione di partenza con l'intervallo da `da` ad `a` scelto o tolto. */
  applica: (base: ReadonlySet<string>, da: string, a: string, scegli: boolean) => void;
  /** La selezione attuale, fotografata all'inizio del trascinamento. */
  selezionate: () => ReadonlySet<string>;
  /** La foto da cui partirà il prossimo Maiusc+clic. */
  ancora: (id: string) => void;
}

interface Gesto {
  id: string;
  pointerId: number;
  tocco: boolean;
  x: number;
  y: number;
  /** Partito: si sta selezionando (col dito, dopo la pressione lunga). */
  attivo: boolean;
  scegli: boolean;
  base: ReadonlySet<string>;
  ultimo: string;
  timer?: ReturnType<typeof setTimeout>;
}

/**
 * Il trascinamento sulla griglia. Gli ascoltatori sul documento ci sono solo
 * mentre un gesto è in corso; per ogni movimento si guarda una sola foto
 * (quella sotto il puntatore) e si rifà la selezione solo se cambia.
 */
export class TrascinaSelezione {
  private gesto: Gesto | null = null;
  private x = 0;
  private y = 0;
  private fotogramma = 0;
  private aggiornaInAttesa = false;
  private assorbi = false;

  constructor(private readonly o: OpzioniTrascina) {}

  /** Pointerdown su un tassello. */
  premi(id: string, e: PointerEvent): void {
    this.annulla();
    if (!this.o.puo() || e.button !== 0 || e.shiftKey || e.ctrlKey || e.metaKey) {
      return;
    }
    const tocco = e.pointerType !== 'mouse';
    const dalCerchio = !!(e.target as Element | null)?.closest?.('.spunta');
    if (!tocco && !this.o.inSelezione() && !dalCerchio) {
      return;
    }
    this.gesto = {
      id,
      pointerId: e.pointerId,
      tocco,
      x: e.clientX,
      y: e.clientY,
      attivo: false,
      scegli: true,
      base: new Set(),
      ultimo: id,
    };
    this.x = e.clientX;
    this.y = e.clientY;
    document.addEventListener('pointermove', this.muovi);
    document.addEventListener('pointerup', this.lascia);
    document.addEventListener('pointercancel', this.interrotto);
    document.addEventListener('dragstart', this.bloccaEvento, true);
    document.addEventListener('selectstart', this.bloccaEvento, true);
    if (tocco) {
      // Non passivo: dopo la pressione lunga il dito seleziona invece di scorrere.
      document.addEventListener('touchmove', this.toccoMuove, { passive: false });
      document.addEventListener('contextmenu', this.bloccaEvento, true);
      this.gesto.timer = setTimeout(() => this.parti(), PRESSIONE_LUNGA);
    }
  }

  /** Vero una volta sola dopo un trascinamento: il clic che segue non apre né commuta. */
  assorbiClic(): boolean {
    const a = this.assorbi;
    this.assorbi = false;
    return a;
  }

  get attivo(): boolean {
    return !!this.gesto?.attivo;
  }

  /** Fine del gesto senza toccare la selezione già fatta. */
  annulla(): void {
    const g = this.gesto;
    if (!g) {
      return;
    }
    clearTimeout(g.timer);
    cancelAnimationFrame(this.fotogramma);
    this.fotogramma = 0;
    this.gesto = null;
    document.removeEventListener('pointermove', this.muovi);
    document.removeEventListener('pointerup', this.lascia);
    document.removeEventListener('pointercancel', this.interrotto);
    document.removeEventListener('dragstart', this.bloccaEvento, true);
    document.removeEventListener('selectstart', this.bloccaEvento, true);
    document.removeEventListener('touchmove', this.toccoMuove);
    // Il menu della pressione lunga può arrivare appena dopo: lo si blocca ancora un attimo.
    setTimeout(() => this.gesto || document.removeEventListener('contextmenu', this.bloccaEvento, true), 400);
  }

  private parti(): void {
    const g = this.gesto;
    if (!g || g.attivo) {
      return;
    }
    if (!this.o.inSelezione()) {
      this.o.entra();
    }
    g.attivo = true;
    g.scegli = !this.o.scelta(g.id);
    g.base = new Set(this.o.selezionate());
    this.o.applica(g.base, g.id, g.id, g.scegli);
    this.o.ancora(g.id);
    if (g.tocco) {
      navigator.vibrate?.(20);
    }
    this.scorriBordo();
  }

  private readonly muovi = (e: PointerEvent): void => {
    const g = this.gesto;
    if (!g || e.pointerId !== g.pointerId) {
      return;
    }
    this.x = e.clientX;
    this.y = e.clientY;
    if (!g.attivo) {
      const spostamento = Math.hypot(e.clientX - g.x, e.clientY - g.y);
      if (g.tocco) {
        // Il dito si muove prima della pressione lunga: è uno scorrimento.
        if (spostamento > SOGLIA_DITO) {
          this.annulla();
        }
        return;
      }
      if (spostamento < SOGLIA_MOUSE) {
        return;
      }
      this.parti();
    }
    e.preventDefault();
    this.pianifica();
  };

  private readonly lascia = (e: PointerEvent): void => {
    const g = this.gesto;
    if (!g || e.pointerId !== g.pointerId) {
      return;
    }
    if (g.attivo) {
      this.aggiorna();
      // Il clic arriva subito dopo pointerup (se arriva): non deve commutare di nuovo.
      this.assorbi = true;
      setTimeout(() => (this.assorbi = false), 0);
    }
    this.annulla();
  };

  private readonly interrotto = (e: PointerEvent): void => {
    if (this.gesto && e.pointerId === this.gesto.pointerId) {
      this.annulla();
    }
  };

  private readonly toccoMuove = (e: TouchEvent): void => {
    if (this.gesto?.attivo && e.cancelable) {
      e.preventDefault();
    }
  };

  private readonly bloccaEvento = (e: Event): void => {
    e.preventDefault();
  };

  private pianifica(): void {
    if (this.aggiornaInAttesa) {
      return;
    }
    this.aggiornaInAttesa = true;
    requestAnimationFrame(() => {
      this.aggiornaInAttesa = false;
      this.aggiorna();
    });
  }

  /** La foto sotto il puntatore; fra due foto resta l'ultima. */
  private aggiorna(): void {
    const g = this.gesto;
    if (!g?.attivo) {
      return;
    }
    const r = this.o.contenitore().getBoundingClientRect();
    const y = Math.min(Math.max(this.y, r.top + 2), r.bottom - 2);
    const x = Math.min(Math.max(this.x, r.left + 2), r.right - 2);
    // Anche sotto quello che sta sopra la griglia: la barra della selezione, l'intestazione del mese.
    const sotto = document.elementsFromPoint(x, y).find((el) => el instanceof HTMLElement && el.dataset['id']) as HTMLElement | undefined;
    const id = sotto?.dataset['id'];
    if (id && id !== g.ultimo) {
      g.ultimo = id;
      this.o.applica(g.base, g.id, id, g.scegli);
    }
  }

  /** Vicino al bordo in alto o in basso del contenitore, scorre da solo. */
  private scorriBordo(): void {
    const passo = (): void => {
      const g = this.gesto;
      if (!g?.attivo) {
        return;
      }
      const c = this.o.contenitore();
      const r = c.getBoundingClientRect();
      const v = velocitaBordo(this.y, r.top, r.bottom);
      if (v) {
        const prima = c.scrollTop;
        c.scrollTop += v;
        if (c.scrollTop !== prima) {
          this.aggiorna();
        }
      }
      this.fotogramma = requestAnimationFrame(passo);
    };
    this.fotogramma = requestAnimationFrame(passo);
  }
}
