import { Injectable, signal } from '@angular/core';

/** Quello che serve per sapere se una foto è privata: la scheda (`privataDi`) o il punto della mappa (`privata`). */
export interface ForsePrivata {
  id: string;
  privataDi?: string;
  privata?: boolean;
}

/** Per i lettori di schermo, sulle miniature sfocate. */
export const ETICHETTA_SFOCATA = 'Foto privata, tocca per mostrarla';

/**
 * Le proprie foto private si vedono sfocate (miniature e visore) finché non
 * le si tocca: chi guarda lo schermo accanto non le vede per caso. Toccata
 * una volta resta svelata fino al prossimo caricamento della pagina: gli id
 * stanno solo in memoria, non si salvano da nessuna parte.
 *
 * Fuori dalla selezione il primo clic svela soltanto, il secondo apre;
 * nella selezione il clic sceglie e basta (scegliere non è guardare).
 */
@Injectable({ providedIn: 'root' })
export class Sfocate {
  private readonly svelate = signal<ReadonlySet<string>>(new Set());

  /** Privata e non ancora toccata: va mostrata sfocata. */
  sfocata(f: ForsePrivata): boolean {
    return !!(f.privataDi || f.privata) && !this.svelate().has(f.id);
  }

  svela(id: string): void {
    if (!this.svelate().has(id)) {
      this.svelate.update((s) => new Set(s).add(id));
    }
  }

  /**
   * Il clic su una miniatura fuori dalla selezione: se era sfocata la svela
   * e risponde true (quel clic non deve aprire niente), altrimenti false.
   */
  svelaAlPrimoClic(f: ForsePrivata): boolean {
    if (!this.sfocata(f)) {
      return false;
    }
    this.svela(f.id);
    return true;
  }
}
