import { ChangeDetectionStrategy, Component, effect, inject, input, output, signal } from '@angular/core';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Evento, Foto } from './modelli';

/**
 * Nel pannello dei dettagli del visore: l'evento della foto, se ne ha uno (e
 * non è nascosto). Un clic mostra le foto dell'evento nella timeline.
 */
@Component({
  selector: 'app-evento-foto',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (evento(); as e) {
      <p class="evento">
        <button type="button" title="Mostra le foto di questo evento" (click)="filtra(e)">🗓 {{ e.nome }}</button>
        <small>{{ e.conteggio }} foto{{ e.manuale ? ' · a mano' : '' }}</small>
      </p>
    }
  `,
  styles: `
    .evento {
      margin: 0.9rem 0 0.75rem;
      display: flex;
      flex-wrap: wrap;
      align-items: baseline;
      gap: 0.4rem;
    }
    button {
      all: unset;
      cursor: pointer;
      color: var(--accento-forte);
      font-weight: 600;
    }
    button:hover {
      text-decoration: underline;
    }
    button:focus-visible {
      outline: 2px solid var(--accento);
    }
    small {
      color: var(--testo-tenue);
    }
  `,
})
export class EventoFoto {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  readonly foto = input.required<Foto>();
  /** L'evento è diventato il filtro: il visore si chiude. */
  readonly scelto = output<void>();

  protected readonly evento = signal<Evento | null>(null);

  constructor() {
    effect(() => {
      const id = this.foto().evento;
      this.evento.set(null);
      if (id) {
        this.api.evento(id).subscribe({
          next: (e) => this.evento.set(e.nascosto || this.foto().evento !== id ? null : e),
          error: () => {},
        });
      }
    });
  }

  protected filtra(e: Evento): void {
    this.galleria.imposta({ evento: e.id, al: undefined });
    this.scelto.emit();
  }
}
