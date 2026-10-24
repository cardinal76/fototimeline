import { ChangeDetectionStrategy, Component, inject, output, signal } from '@angular/core';

import { FotoApi } from './foto-api';
import { Foto, Ricordo } from './modelli';

const CHIAVE = 'fototimeline.ricordi-chiusi';

/**
 * "Accadde oggi": in cima alla timeline, le foto di oggi negli anni passati. Si chiude per la giornata.
 * Con `?ricordi=oggi` (il link del messaggio su Telegram) si vede anche se chiuso.
 */
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
                @if (nomeEvento(r); as nome) {
                  <span class="evento" [title]="nome">· {{ nome }}</span>
                }
              </p>
              <div class="striscia">
                @for (f of r.foto; track f.id) {
                  <button type="button" [attr.aria-label]="f.titolo || f.nomeOriginale" (click)="apri.emit(f)">
                    <img [src]="miniatura(f)" alt="" loading="lazy" />
                  </button>
                }
              </div>
            </div>
          }
        </div>
      </section>
    } @else if (richiesti() && caricati() && !ricordi().length) {
      <section class="ricordi" aria-label="Accadde oggi">
        <header>
          <h2>Accadde oggi</h2>
          <button type="button" class="chiudi" aria-label="Chiudi" (click)="richiesti.set(false)">✕</button>
        </header>
        <p class="vuoto">Oggi nessuna foto degli anni passati.</p>
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
    .anno p .evento {
      max-width: 14rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
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
    .vuoto {
      margin: 0.4rem 0 0;
      color: var(--testo-tenue);
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
  protected readonly caricati = signal(false);
  /** I nomi degli eventi dei ricordi (non nascosti). */
  private readonly nomiEventi = signal<Record<string, string>>({});
  /** Aperti dal link di Telegram: si vedono anche se chiusi per oggi. */
  protected readonly richiesti = signal(dalLink());
  protected readonly chiusi = signal(!this.richiesti() && chiusiOggi());

  constructor() {
    this.api.ricordi().subscribe({
      next: (r) => {
        this.ricordi.set(r);
        this.caricati.set(true);
        const eventi = [...new Set(r.map((x) => eventoDi(x)).filter((e): e is string => !!e))];
        if (eventi.length) {
          this.api.nomiEventi(eventi).subscribe({ next: (n) => this.nomiEventi.set(n), error: () => {} });
        }
      },
      error: () => {},
    });
  }

  /** Il nome dell'evento della maggior parte delle foto di quell'anno, se ne hanno uno. */
  protected miniatura(f: Foto): string {
    return FotoApi.miniatura(f);
  }

  protected nomeEvento(r: Ricordo): string | undefined {
    const e = eventoDi(r);
    return e ? this.nomiEventi()[e] : undefined;
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

/** L'evento più frequente tra le foto di un anno (almeno la metà), o niente. */
function eventoDi(r: Ricordo): string | undefined {
  const conti = new Map<string, number>();
  for (const f of r.foto) {
    if (f.evento) conti.set(f.evento, (conti.get(f.evento) ?? 0) + 1);
  }
  const [primo] = [...conti.entries()].sort((a, b) => b[1] - a[1]);
  return primo && primo[1] * 2 >= r.foto.length ? primo[0] : undefined;
}

/** True se l'indirizzo ha `?ricordi`; lo toglie, così un ricaricamento non lo riapre. */
function dalLink(): boolean {
  const url = new URL(location.href);
  if (!url.searchParams.has('ricordi')) {
    return false;
  }
  url.searchParams.delete('ricordi');
  history.replaceState(history.state, '', url.pathname + url.search + url.hash);
  return true;
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
