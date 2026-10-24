import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterNextRender,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';

import { FotoApi } from './foto-api';
import { LIVELLI, doveLuogo, nomeLuogo } from './luoghi';
import { Luogo } from './modelli';

/** Dopo quanto dall'ultimo tasto si chiede al server. */
const ATTESA_MS = 200;

/**
 * Il campo per scegliere un luogo per nome, con i suggerimenti di GeoNames
 * (GET /api/luoghi/cerca): "Manch" propone "Manchester, Inghilterra, Regno
 * Unito". Frecce e Invio per scegliere.
 */
@Component({
  selector: 'app-scegli-luogo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <input
      #campo
      type="search"
      role="combobox"
      autocomplete="off"
      placeholder="Cerca una città, una regione, una nazione…"
      aria-label="Cerca il luogo"
      aria-controls="proposte-luogo"
      [attr.aria-expanded]="proposte().length > 0"
      [attr.aria-activedescendant]="proposte().length ? 'proposta-luogo-' + attiva() : null"
      (input)="scrivi(campo.value)"
      (keydown)="tasto($event)"
    />
    @if (proposte().length) {
      <ul id="proposte-luogo" role="listbox" aria-label="Luoghi trovati">
        @for (l of proposte(); track l.chiave; let i = $index) {
          <li
            [id]="'proposta-luogo-' + i"
            role="option"
            [attr.aria-selected]="i === attiva()"
            [class.attiva]="i === attiva()"
            (mousedown)="$event.preventDefault()"
            (click)="scelto.emit(l)"
          >
            <span class="nome">{{ nome(l) }}</span>
            <small>{{ dove(l) }}</small>
            <span class="livello">{{ livelli[l.livello] }}</span>
          </li>
        }
      </ul>
    } @else if (cercato() && !inCorso()) {
      <p class="tenue">Nessun luogo con questo nome.</p>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 0.3rem;
    }
    input {
      width: 100%;
      box-sizing: border-box;
      font: inherit;
      color: inherit;
      background: rgb(255 255 255 / 0.06);
      border: 1px solid rgb(255 255 255 / 0.18);
      border-radius: 8px;
      padding: 0.45rem 0.6rem;
    }
    input:focus-visible {
      outline: 2px solid #7eaaff;
      outline-offset: 1px;
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0.2rem;
      max-height: 260px;
      overflow-y: auto;
      border-radius: 8px;
      background: rgb(0 0 0 / 0.25);
    }
    li {
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
      padding: 0.35rem 0.45rem;
      border-radius: 6px;
      cursor: pointer;
    }
    li:hover,
    li.attiva {
      background: rgb(126 170 255 / 0.2);
    }
    .nome {
      white-space: nowrap;
    }
    small {
      flex: 1;
      min-width: 0;
      opacity: 0.7;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .livello,
    .tenue {
      opacity: 0.6;
      font-size: 0.8em;
    }
    .tenue {
      margin: 0;
    }
  `,
})
export class ScegliLuogo {
  private readonly api = inject(FotoApi);
  private readonly campo = viewChild.required<ElementRef<HTMLInputElement>>('campo');

  readonly scelto = output<Luogo>();

  protected readonly proposte = signal<Luogo[]>([]);
  protected readonly attiva = signal(0);
  protected readonly cercato = signal('');
  protected readonly inCorso = signal(false);
  protected readonly livelli = LIVELLI;
  private timer?: ReturnType<typeof setTimeout>;
  /** Solo l'ultima risposta conta: una più lenta, arrivata dopo, non sovrascrive. */
  private richiesta = 0;

  constructor() {
    afterNextRender(() => this.campo().nativeElement.focus());
    inject(DestroyRef).onDestroy(() => clearTimeout(this.timer));
  }

  protected nome(l: Luogo): string {
    return nomeLuogo(l);
  }

  protected dove(l: Luogo): string {
    return doveLuogo(l);
  }

  protected scrivi(testo: string): void {
    clearTimeout(this.timer);
    const q = testo.trim();
    this.cercato.set(q.length >= 2 ? q : '');
    if (q.length < 2) {
      this.proposte.set([]);
      return;
    }
    this.inCorso.set(true);
    this.timer = setTimeout(() => {
      const numero = ++this.richiesta;
      this.api.cercaLuoghi(q).subscribe({
        next: (l) => {
          if (numero === this.richiesta) {
            this.proposte.set(l);
            this.attiva.set(0);
            this.inCorso.set(false);
          }
        },
        error: () => this.inCorso.set(false),
      });
    }, ATTESA_MS);
  }

  protected tasto(e: KeyboardEvent): void {
    const n = this.proposte().length;
    if (e.key === 'ArrowDown' && n) {
      this.attiva.update((i) => (i + 1) % n);
    } else if (e.key === 'ArrowUp' && n) {
      this.attiva.update((i) => (i - 1 + n) % n);
    } else if (e.key === 'Enter' && n) {
      this.scelto.emit(this.proposte()[this.attiva()]);
    } else {
      return;
    }
    e.preventDefault();
  }
}
