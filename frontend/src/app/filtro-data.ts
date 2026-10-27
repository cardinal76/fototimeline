import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';

import { Galleria } from './galleria';

/** Come si sceglie la data: niente, un anno, un mese, due giorni. */
export type ModoData = '' | 'anno' | 'mese' | 'tra';

const ANNO = /^\d{4}$/;
const MESE = /^\d{4}-(0[1-9]|1[0-2])$/;
const GIORNO = /^\d{4}-\d{2}-\d{2}$/;

const MESI = ['gennaio', 'febbraio', 'marzo', 'aprile', 'maggio', 'giugno', 'luglio', 'agosto', 'settembre', 'ottobre', 'novembre', 'dicembre'];

/** Il modo di un periodo già scritto ("2019", "2019-08", "2019-08-01..2019-09-15"). */
export function modoDi(periodo: string | undefined): ModoData {
  if (!periodo) return '';
  if (ANNO.test(periodo)) return 'anno';
  if (MESE.test(periodo)) return 'mese';
  return 'tra';
}

/** "2019-08-05" → "5 agosto 2019". */
function giorno(g: string): string {
  const [a, m, d] = g.split('-');
  return `${Number(d)} ${MESI[Number(m) - 1]} ${a}`;
}

/** Il periodo a parole, per la riga sopra la timeline. */
export function descriviPeriodo(periodo: string): string {
  if (ANNO.test(periodo)) return `del ${periodo}`;
  if (MESE.test(periodo)) {
    const [a, m] = periodo.split('-');
    return `di ${MESI[Number(m) - 1]} ${a}`;
  }
  const [dal, al] = periodo.split('..');
  if (dal && al) return dal === al ? `del ${giorno(dal)}` : `dal ${giorno(dal)} al ${giorno(al)}`;
  if (dal) return `dal ${giorno(dal)} in poi`;
  return `fino al ${giorno(al ?? '')}`;
}

/**
 * Il filtro "Data" (si combina con gli altri e vale per tutti i ruoli): un
 * anno, un mese o due giorni compresi, con un lato vuoto senza limite. Manda
 * {@code periodo} al server (Periodo.java), che filtra sulla data di scatto.
 * Con {@code riga} è la riga sopra la timeline ("Foto del 2019").
 */
@Component({
  selector: 'app-filtro-data',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { '[class.in-riga]': 'riga()' },
  template: `
    @if (riga()) {
      @if (galleria.filtro().periodo; as p) {
        <span>📅 Foto <b>{{ descrivi(p) }}</b></span>
        <button type="button" class="link" (click)="togli()">tutte le date</button>
      }
    } @else {
      <div class="riga">
        <select aria-label="Data" [value]="modo()" (change)="cambiaModo($any($event.target).value)">
          <option value="">Tutte le date</option>
          <option value="anno">Un anno</option>
          <option value="mese">Un mese</option>
          <option value="tra">Tra due date</option>
        </select>
        @switch (modo()) {
          @case ('anno') {
            <input
              type="number"
              inputmode="numeric"
              min="1900"
              max="2100"
              placeholder="2019"
              aria-label="Anno"
              [value]="anno()"
              (change)="scegliAnno($any($event.target).value)"
            />
          }
          @case ('mese') {
            <!-- Senza il selettore del mese (Firefox, Safari) si scrive 2019-08. -->
            <input type="month" placeholder="2019-08" aria-label="Mese" [value]="mese()" (change)="scegliMese($any($event.target).value)" />
          }
          @case ('tra') {
            <input type="date" aria-label="Dal" title="Dal (vuoto: dall'inizio)" [value]="dal()" (change)="scegliGiorni($any($event.target).value, al())" />
            <span aria-hidden="true">–</span>
            <input type="date" aria-label="Al" title="Al, compreso (vuoto: fino a oggi)" [value]="al()" (change)="scegliGiorni(dal(), $any($event.target).value)" />
          }
        }
      </div>
      @if (errore(); as e) {
        <span class="errore" role="alert">{{ e }}</span>
      }
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      flex-direction: column;
      gap: 0.25rem;
      min-width: 0;
    }
    :host(.in-riga) {
      flex-direction: row;
      align-items: center;
      gap: 0.35rem;
    }
    .riga {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.35rem;
    }
    select,
    input {
      font: inherit;
      color: var(--testo);
      background: var(--superficie-2);
      border: 1px solid var(--bordo);
      border-radius: 8px;
      padding: 0.4rem 0.6rem;
      min-width: 0;
    }
    input[type='number'] {
      width: 6.5rem;
    }
    input[type='date'],
    input[type='month'] {
      max-width: 11rem;
    }
    select:focus-visible,
    input:focus-visible {
      outline: 2px solid var(--accento);
      outline-offset: 1px;
    }
    .errore {
      color: var(--pericolo);
      font-size: 0.85em;
    }
    .link {
      all: unset;
      cursor: pointer;
      color: var(--accento);
      font-size: 0.9em;
      padding: 0 0.2rem;
    }
    .link:hover {
      text-decoration: underline;
    }
  `,
})
export class FiltroData {
  protected readonly galleria = inject(Galleria);

  /** La riga sopra la timeline invece della scelta nei filtri. */
  readonly riga = input(false);

  /** Il modo scelto: resta anche prima di scrivere l'anno o le date. */
  protected readonly modo = signal<ModoData>(modoDi(this.galleria.filtro().periodo));
  protected readonly errore = signal<string | null>(null);

  private readonly periodo = computed(() => this.galleria.filtro().periodo ?? '');
  protected readonly anno = computed(() => (ANNO.test(this.periodo()) ? this.periodo() : ''));
  protected readonly mese = computed(() => (MESE.test(this.periodo()) ? this.periodo() : ''));
  protected readonly dal = computed(() => (this.periodo().includes('..') ? this.periodo().split('..')[0] : ''));
  protected readonly al = computed(() => (this.periodo().includes('..') ? this.periodo().split('..')[1] : ''));

  constructor() {
    // "Azzera filtri" (o "tutte le date") toglie il periodo: la scelta torna a "Tutte le date".
    let prima = this.galleria.filtro().periodo;
    effect(() => {
      const p = this.galleria.filtro().periodo;
      if (p) {
        this.modo.set(modoDi(p));
      } else if (prima) {
        this.modo.set('');
      }
      prima = p;
    });
  }

  protected descrivi(periodo: string): string {
    return descriviPeriodo(periodo);
  }

  protected cambiaModo(modo: ModoData): void {
    this.modo.set(modo);
    this.errore.set(null);
    if (!modo || modoDi(this.galleria.filtro().periodo) !== modo) {
      this.applica(undefined);
    }
  }

  protected scegliAnno(valore: string): void {
    const v = valore.trim();
    if (v && !ANNO.test(v)) {
      this.errore.set("Scrivi l'anno con quattro cifre, per esempio 2019");
      return;
    }
    this.applica(v || undefined);
  }

  protected scegliMese(valore: string): void {
    const v = valore.trim();
    if (v && !MESE.test(v)) {
      this.errore.set('Scrivi il mese come 2019-08');
      return;
    }
    this.applica(v || undefined);
  }

  protected scegliGiorni(dal: string, al: string): void {
    if ((dal && !GIORNO.test(dal)) || (al && !GIORNO.test(al))) {
      this.errore.set('Data non valida');
      return;
    }
    if (dal && al && dal > al) {
      this.errore.set('La prima data viene dopo la seconda');
      return;
    }
    this.applica(dal || al ? `${dal}..${al}` : undefined);
  }

  protected togli(): void {
    this.applica(undefined);
  }

  /** Un periodo nuovo: il salto della timeline (al) riparte dalle più recenti del periodo. */
  private applica(periodo: string | undefined): void {
    this.errore.set(null);
    if (periodo === this.galleria.filtro().periodo) return;
    this.galleria.imposta({ periodo, al: undefined });
  }
}
