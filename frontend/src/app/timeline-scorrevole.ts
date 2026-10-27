import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  signal,
} from '@angular/core';

import { Galleria } from './galleria';
import { VoceMese } from './modelli';
import { MESI } from './timeline-nav';

/** Distanza minima, in pixel, fra due etichette di anno sulla striscia. */
const SPAZIO_ETICHETTA = 22;

/**
 * Sul telefono, al posto della colonna con anni e mesi: una striscia sottile
 * sul bordo destro. Si trascina col dito (o si tocca) e, lasciando, si salta
 * al mese scelto; mentre si trascina un fumetto dice dove si arriva.
 */
@Component({
  selector: 'app-timeline-scorrevole',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (mesi().length > 1) {
      <div
        class="striscia"
        [class.attiva]="scelto() !== null"
        role="slider"
        tabindex="0"
        aria-label="Salta a un mese della timeline"
        aria-orientation="vertical"
        [attr.aria-valuemin]="0"
        [attr.aria-valuemax]="mesi().length - 1"
        [attr.aria-valuenow]="indiceMostrato()"
        [attr.aria-valuetext]="testo(mesi()[indiceMostrato()])"
        (pointerdown)="inizia($event)"
        (pointermove)="muovi($event)"
        (pointerup)="finisci($event)"
        (pointercancel)="annulla()"
        (lostpointercapture)="annulla()"
        (keydown)="tasto($event)"
        (contextmenu)="$event.preventDefault()"
      >
        <div class="binario">
          @for (e of etichette(); track e.anno) {
            <span class="anno" [style.top.%]="e.y">{{ e.anno }}</span>
          }
          <span class="cursore" [style.top.%]="posizione(indiceMostrato())"></span>
          @if (scelto() !== null) {
            <span class="fumetto" [style.top.%]="posizione(scelto()!)">{{ testo(mesi()[scelto()!]) }}</span>
          }
        </div>
      </div>
    }
  `,
  styleUrl: './timeline-scorrevole.css',
})
export class TimelineScorrevole {
  private readonly galleria = inject(Galleria);
  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef);
  /** Mese visibile in alto, "yyyy-MM". */
  readonly attivo = input<string | null>(null);

  protected readonly mesi = this.galleria.mesi;
  /** Altezza della striscia, per non accavallare le etichette; si aggiorna con la rotazione. */
  private readonly altezza = signal(0);
  /** Il mese sotto il dito mentre si trascina; null quando non si tocca. */
  protected readonly scelto = signal<number | null>(null);

  /** Il mese in cima alla lista, come indice in mesi(). */
  private readonly indiceAttivo = computed(() => {
    const a = this.attivo();
    const i = a ? this.mesi().findIndex((m) => this.chiave(m) === a) : -1;
    return Math.max(0, i);
  });
  protected readonly indiceMostrato = computed(() => this.scelto() ?? this.indiceAttivo());

  /** Un'etichetta per anno, dove comincia; quelle troppo vicine alla precedente si saltano. */
  protected readonly etichette = computed(() => {
    const etichette: { anno: number; y: number }[] = [];
    this.mesi().forEach((m, i) => {
      if (i > 0 && this.mesi()[i - 1].anno === m.anno) {
        return;
      }
      const y = this.posizione(i);
      const ultima = etichette.at(-1);
      if (!ultima || ((y - ultima.y) / 100) * this.altezza() >= SPAZIO_ETICHETTA) {
        etichette.push({ anno: m.anno, y });
      }
    });
    return etichette;
  });

  constructor() {
    const osservatore = new ResizeObserver(() => this.misura());
    afterNextRender(() => {
      osservatore.observe(this.host.nativeElement);
      this.misura();
    });
    inject(DestroyRef).onDestroy(() => osservatore.disconnect());
  }

  /**
   * Dove sta un mese sulla striscia, in percentuale dall'alto: ogni mese con
   * foto ha lo stesso spazio, dal più recente (in alto) al più vecchio.
   */
  protected posizione(indice: number): number {
    const n = this.mesi().length;
    return n > 1 ? (indice / (n - 1)) * 100 : 0;
  }

  protected testo(m: VoceMese | undefined): string {
    return m ? `${MESI[m.mese - 1]} ${m.anno}` : '';
  }

  protected inizia(e: PointerEvent): void {
    if (e.button > 0) {
      return;
    }
    // Il dito resta "agganciato" alla striscia anche se esce: la pagina sotto non scorre.
    try {
      (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
    } catch {
      // Puntatore già sparito (o sintetico): si trascina lo stesso finché resta sulla striscia.
    }
    e.preventDefault();
    this.scelto.set(this.indiceDa(e));
  }

  protected muovi(e: PointerEvent): void {
    if (this.scelto() === null) {
      return;
    }
    e.preventDefault();
    const i = this.indiceDa(e);
    if (i !== this.scelto()) {
      this.scelto.set(i);
    }
  }

  protected finisci(e: PointerEvent): void {
    const i = this.scelto();
    if (i === null) {
      return;
    }
    this.scelto.set(this.indiceDa(e));
    this.vai(this.scelto()!);
    this.scelto.set(null);
  }

  protected annulla(): void {
    this.scelto.set(null);
  }

  protected tasto(e: KeyboardEvent): void {
    const passi: Record<string, number> = { ArrowDown: 1, ArrowRight: 1, ArrowUp: -1, ArrowLeft: -1 };
    let i = this.indiceAttivo();
    if (e.key in passi) {
      i += passi[e.key];
    } else if (e.key === 'Home') {
      i = 0;
    } else if (e.key === 'End') {
      i = this.mesi().length - 1;
    } else {
      return;
    }
    e.preventDefault();
    this.vai(Math.min(this.mesi().length - 1, Math.max(0, i)));
  }

  private vai(indice: number): void {
    const m = this.mesi()[indice];
    if (!m) {
      return;
    }
    if (indice === 0) {
      this.galleria.imposta({ al: undefined }); // In cima: le più recenti, senza "Foto fino a…".
    } else {
      this.galleria.salta(m.anno, m.mese);
    }
  }

  private indiceDa(e: PointerEvent): number {
    const n = this.mesi().length;
    const striscia = (e.currentTarget as HTMLElement).querySelector('.binario')!.getBoundingClientRect();
    const frazione = striscia.height > 0 ? (e.clientY - striscia.top) / striscia.height : 0;
    return Math.round(Math.min(1, Math.max(0, frazione)) * (n - 1));
  }

  private misura(): void {
    // Il binario lascia un po' di margine sopra e sotto (timeline-scorrevole.css).
    this.altezza.set(Math.max(0, this.host.nativeElement.clientHeight - 40));
  }

  private chiave(m: VoceMese): string {
    return `${m.anno}-${String(m.mese).padStart(2, '0')}`;
  }
}
