import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';

import { FotoApi } from './foto-api';
import { Foto, Ricordo } from './modelli';

const CHIAVE = 'fototimeline.ricordi-chiusi';

/** "Accadde oggi": in cima alla timeline, le foto di oggi negli anni passati. Si chiude per la giornata. */
@Component({
  selector: 'app-ricordi',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (ricordi().length && !chiusi()) {
      <section class="ricordi" aria-label="Accadde oggi">
        <header>
          <h2>Accadde oggi</h2>
          <button type="button" class="chiudi" aria-label="Chiudi fino a domani" (click)="chiudi()">✕</button>
        </header>
        <div class="anni">
          @for (r of ricordi(); track r.anno) {
            <div class="anno">
              <p>
                <b>{{ r.anniFa === 1 ? 'Un anno fa' : r.anniFa + ' anni fa' }}</b>
                <span>{{ r.anno }}</span>
              </p>
              <div class="striscia">
                @for (f of r.foto; track f.id) {
                  <button type="button" [attr.aria-label]="f.titolo || f.nomeOriginale" (click)="apri.emit(f)">
                    <img [src]="'/api/foto/' + f.id + '/miniatura'" alt="" loading="lazy" />
                  </button>
                }
              </div>
            </div>
          }
        </div>
      </section>
    }
  `,
  styles: `
    .ricordi {
      margin: 0.75rem 0 0.25rem;
      padding: 0.75rem 0.9rem;
      border-radius: 12px;
      background: linear-gradient(135deg, var(--accento-tenue), transparent 70%), var(--superficie);
      border: 1px solid var(--bordo);
    }
    header {
      display: flex;
      justify-content: space-between;
      align-items: center;
    }
    h2 {
      margin: 0;
      font-size: 1.05rem;
    }
    .chiudi {
      border: 0;
      background: none;
      color: var(--testo-tenue);
      cursor: pointer;
      font-size: 1rem;
    }
    .anni {
      display: flex;
      gap: 1.25rem;
      overflow-x: auto;
      padding-top: 0.5rem;
    }
    .anno p {
      margin: 0 0 0.35rem;
      font-size: 0.85rem;
      display: flex;
      gap: 0.4rem;
      align-items: baseline;
    }
    .anno p span {
      color: var(--testo-tenue);
    }
    .striscia {
      display: flex;
      gap: 4px;
    }
    .striscia button {
      padding: 0;
      border: 0;
      cursor: zoom-in;
      border-radius: 8px;
      overflow: hidden;
      background: var(--superficie-2);
    }
    .striscia img {
      display: block;
      width: 96px;
      height: 96px;
      object-fit: cover;
    }
  `,
})
export class Ricordi {
  private readonly api = inject(FotoApi);

  readonly apri = output<Foto>();

  protected readonly ricordi = signal<Ricordo[]>([]);
  protected readonly chiusi = signal(chiusiOggi());

  constructor() {
    this.api.ricordi().subscribe({ next: (r) => this.ricordi.set(r), error: () => {} });
  }

  protected chiudi(): void {
    this.chiusi.set(true);
    try {
      localStorage.setItem(CHIAVE, oggi());
    } catch {
      // senza localStorage si richiude solo per questa visita
    }
  }
}

function oggi(): string {
  return new Date().toISOString().slice(0, 10);
}

function chiusiOggi(): boolean {
  try {
    return localStorage.getItem(CHIAVE) === oggi();
  } catch {
    return false;
  }
}
