import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { Observable, firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Evento } from './modelli';

function semplice(testo: string): string {
  return testo.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * "Evento…" della barra della selezione: con le foto scelte si crea un evento
 * nuovo, si aggiungono a uno che c'è, si tolgono dal loro, o (una foto sola)
 * si divide il suo evento da lì. Le modifiche restano anche quando gli eventi
 * si rifanno.
 */
@Component({
  selector: 'app-scelta-evento',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  styleUrls: ['./cestino.css', './eventi.css'],
  template: `
    <div class="velo" (click)="chiudi.emit()">
      <section
        #dialogo
        class="dialogo stretto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="titolo-scelta-evento"
        tabindex="-1"
        (click)="$event.stopPropagation()"
      >
        <header>
          <h2 id="titolo-scelta-evento">Evento per {{ ids().length }} foto</h2>
          <button type="button" class="chiudi" aria-label="Chiudi" (click)="chiudi.emit()">✕</button>
        </header>

        @if (conEvento().length) {
          <div class="comandi">
            <button type="button" [disabled]="occupato()" (click)="togli()">
              Togli dal loro evento ({{ conEvento().length }})
            </button>
            @if (unaSola()?.evento; as evento) {
              <button
                type="button"
                [disabled]="occupato()"
                title="Questa foto e quelle dopo vanno in un evento nuovo"
                (click)="dividi(evento, unaSola()!.id)"
              >
                Dividi l'evento da qui
              </button>
            }
          </div>
        }

        <form class="comandi" (submit)="$event.preventDefault(); crea(nome.value)">
          <input #nome class="cerca" placeholder="Nome (vuoto: automatico)" aria-label="Nome dell'evento nuovo" />
          <button type="submit" class="primario" [disabled]="occupato()">Nuovo evento</button>
        </form>

        <div class="comandi">
          <input
            #cerca
            type="search"
            class="cerca"
            placeholder="Aggiungi a un evento: cerca…"
            aria-label="Cerca l'evento a cui aggiungerle"
            [value]="testo()"
            (input)="testo.set(cerca.value)"
          />
        </div>
        @if (eventi(); as elenco) {
          <ul class="scelte">
            @for (e of trovati(); track e.id) {
              <li>
                <button type="button" [disabled]="occupato()" (click)="aggiungi(e)">
                  <span class="nome-evento">{{ e.nome }}</span>
                  <small class="tenue">{{ e.inizio | date: 'd MMM y' }} · {{ e.conteggio }} foto</small>
                </button>
              </li>
            } @empty {
              <li class="tenue">Nessun evento{{ testo().trim() ? ' con questo nome' : '' }}.</li>
            }
          </ul>
        } @else {
          <span class="rotella" aria-label="Caricamento"></span>
        }
      </section>
    </div>
  `,
  styles: `
    .stretto {
      width: min(560px, 100%);
    }
    .scelte {
      list-style: none;
      margin: 0;
      padding: 0;
      max-height: 40vh;
      overflow-y: auto;
      display: grid;
      gap: 0.3rem;
    }
    .scelte button {
      width: 100%;
      display: flex;
      justify-content: space-between;
      gap: 0.5rem;
      text-align: left;
    }
  `,
})
export class SceltaEvento {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  readonly chiudi = output<void>();

  protected readonly eventi = signal<Evento[] | null>(null);
  protected readonly testo = signal('');
  protected readonly occupato = signal(false);
  protected readonly ids = computed(() => [...this.galleria.selezionate()]);
  /** Le foto scelte che hanno un evento. */
  protected readonly conEvento = computed(() => {
    const scelte = this.galleria.selezionate();
    return this.galleria.foto().filter((f) => scelte.has(f.id) && f.evento);
  });
  /** La foto, se ne è scelta una sola. */
  protected readonly unaSola = computed(() => {
    const scelte = this.galleria.selezionate();
    return scelte.size === 1 ? this.galleria.foto().find((f) => scelte.has(f.id)) : undefined;
  });
  protected readonly trovati = computed(() => {
    const cercato = semplice(this.testo().trim());
    return (this.eventi() ?? []).filter((e) => !cercato || semplice(e.nome).includes(cercato)).slice(0, 50);
  });

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    this.api.eventi(true).subscribe({
      next: (l) => this.eventi.set(l.eventi),
      error: () => this.eventi.set([]),
    });
  }

  protected crea(nome: string): Promise<void> {
    return this.esegui(this.api.creaEvento(this.ids(), nome.trim() || undefined), (e: Evento) => `Evento "${e.nome}" creato`);
  }

  protected aggiungi(e: Evento): Promise<void> {
    return this.esegui(this.api.aggiungiAEvento(e.id, this.ids()), (r) => `${r.modificate} foto in "${e.nome}"`);
  }

  protected togli(): Promise<void> {
    return this.esegui(this.api.togliDaEvento(this.ids()), (r) => `${r.modificate} foto tolte dal loro evento`);
  }

  protected dividi(evento: string, foto: string): Promise<void> {
    return this.esegui(this.api.dividiEvento(evento, foto), () => 'Evento diviso');
  }

  private async esegui<T>(chiamata: Observable<T>, testo: (r: T) => string): Promise<void> {
    this.occupato.set(true);
    try {
      const r = await firstValueFrom(chiamata);
      this.galleria.avvisa(testo(r));
      this.galleria.ricarica();
      this.chiudi.emit();
    } catch (e: unknown) {
      this.galleria.avvisa((e as { error?: { detail?: string } }).error?.detail ?? 'Non riuscito');
    } finally {
      this.occupato.set(false);
    }
  }
}
