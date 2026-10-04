import { ChangeDetectionStrategy, Component, effect, inject, input, signal } from '@angular/core';

import { Galleria } from './galleria';

export const MESI = ['gen', 'feb', 'mar', 'apr', 'mag', 'giu', 'lug', 'ago', 'set', 'ott', 'nov', 'dic'];

/** Colonna con anni e mesi: dice dove si è e ci salta con un clic. */
@Component({
  selector: 'app-timeline-nav',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav aria-label="Timeline">
      <div class="testa">
        <span>{{ galleria.totaleArchivio() }} foto</span>
        @if (galleria.filtro().al) {
          <button type="button" class="link" (click)="galleria.imposta({ al: undefined })">↑ Più recenti</button>
        }
      </div>
      @for (a of galleria.anni(); track a.anno) {
        <section class="anno" [class.attivo]="attivo()?.startsWith(a.anno + '')">
          <button type="button" class="anno-titolo" (click)="commuta(a.anno)" [attr.aria-expanded]="aperto(a.anno)">
            <span>{{ a.anno }}</span>
            <small>{{ a.conteggio }}</small>
          </button>
          @if (aperto(a.anno)) {
            <ol>
              @for (m of a.mesi; track m.mese) {
                <li>
                  <button
                    type="button"
                    [class.attivo]="attivo() === chiave(m.anno, m.mese)"
                    (click)="galleria.salta(m.anno, m.mese)"
                  >
                    <span>{{ nomiMesi[m.mese - 1] }}</span>
                    <span class="barra"><i [style.width.%]="percentuale(m.conteggio)"></i></span>
                    <small>{{ m.conteggio }}</small>
                  </button>
                </li>
              }
            </ol>
          }
        </section>
      } @empty {
        <p class="vuoto">La timeline si riempie appena carichi le prime foto.</p>
      }
    </nav>
  `,
  styleUrl: './timeline-nav.css',
})
export class TimelineNav {
  protected readonly galleria = inject(Galleria);
  /** Mese visibile in alto, "yyyy-MM". */
  readonly attivo = input<string | null>(null);

  protected readonly nomiMesi = MESI;
  private readonly aperti = signal<ReadonlySet<number>>(new Set());

  constructor() {
    // L'anno che si sta guardando resta aperto.
    effect(() => {
      const a = this.attivo();
      if (a) {
        const anno = Number(a.slice(0, 4));
        if (!this.aperti().has(anno)) {
          this.aperti.update((s) => new Set(s).add(anno));
        }
      }
    });
  }

  protected aperto(anno: number): boolean {
    return this.aperti().has(anno);
  }

  protected commuta(anno: number): void {
    this.aperti.update((s) => {
      const n = new Set(s);
      if (!n.delete(anno)) {
        n.add(anno);
      }
      return n;
    });
  }

  protected chiave(anno: number, mese: number): string {
    return `${anno}-${String(mese).padStart(2, '0')}`;
  }

  protected percentuale(conteggio: number): number {
    const massimo = Math.max(1, ...this.galleria.mesi().map((m) => m.conteggio));
    return Math.max(4, (conteggio / massimo) * 100);
  }
}
