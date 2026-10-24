import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Evento } from './modelli';

/**
 * Il filtro "Evento" (si combina con gli altri): il nome dell'evento scelto
 * con ✕ per toglierlo, oppure il bottone che apre la pagina Eventi. Con
 * {@code riga} è la riga sopra la timeline ("Foto dell'evento …").
 */
@Component({
  selector: 'app-filtro-evento',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (galleria.filtro().evento) {
      @if (riga()) {
        <span>🗓 Foto dell'evento <b>{{ evento()?.nome ?? '…' }}</b></span>
        <button type="button" class="link" (click)="togli()">tutte le foto</button>
      } @else {
        <button type="button" class="scelta" aria-pressed="true" (click)="scegli.emit()">🗓 {{ evento()?.nome ?? '…' }}</button>
        <button type="button" class="link togli" aria-label="Togli il filtro per evento" (click)="togli()">✕</button>
      }
    } @else if (!riga()) {
      <button type="button" class="scelta" aria-pressed="false" (click)="scegli.emit()">🗓 Tutti gli eventi</button>
    }
  `,
  styles: `
    :host {
      display: inline-flex;
      align-items: center;
      gap: 0.35rem;
      min-width: 0;
    }
    /* Come il filtro dei luoghi. */
    .scelta {
      color: var(--testo);
      background: var(--superficie-2);
      border: 1px solid var(--bordo);
      border-radius: 8px;
      padding: 0.45rem 0.75rem;
      cursor: pointer;
      max-width: 220px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .scelta[aria-pressed='true'] {
      background: var(--accento-tenue);
      border-color: var(--accento);
      color: var(--accento-forte);
    }
    .scelta:focus-visible {
      outline: 2px solid var(--accento);
      outline-offset: 1px;
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
export class FiltroEvento {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  /** La riga sopra la timeline invece del bottone dei filtri. */
  readonly riga = input(false);
  /** Apri la pagina Eventi per sceglierne uno. */
  readonly scegli = output<void>();

  protected readonly evento = signal<Evento | null>(null);

  constructor() {
    effect(() => {
      const id = this.galleria.filtro().evento;
      if (!id) {
        this.evento.set(null);
      } else if (this.evento()?.id !== id) {
        this.api.evento(id).subscribe({ next: (e) => this.evento.set(e), error: () => this.evento.set(null) });
      }
    });
  }

  protected togli(): void {
    this.galleria.imposta({ evento: undefined });
  }
}
