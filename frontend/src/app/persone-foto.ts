import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Foto, VoltoInFoto } from './modelli';

/**
 * Nel pannello dei dettagli del visore: le persone riconosciute nella foto.
 * Clic sul nome: le sue foto; su un volto senza nome si scrive chi è; una
 * proposta si conferma o si rifiuta. Solo se il riconoscimento c'è.
 */
@Component({
  selector: 'app-persone-foto',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (attiva() && volti().length) {
      <section class="persone-foto" aria-label="Persone nella foto">
        <h3>Persone</h3>
        <ul>
          @for (v of visibili(); track v.id) {
            <li>
              <img [src]="ritaglio(v.id)" alt="" loading="lazy" />
              @if (v.nome) {
                <button type="button" class="nome" title="Mostra le sue foto" (click)="filtra(v)">{{ v.nome }}</button>
              } @else if (!galleria.puoGestire()) {
                @if (v.personaId) {
                  <button type="button" class="tenue" title="Le foto di questo gruppo" (click)="filtra(v)">Senza nome</button>
                }
              } @else if (inModifica() === v.id) {
                <form (submit)="$event.preventDefault(); nomina(v, campo.value)">
                  <input #campo list="nomi-persone" placeholder="Chi è?" aria-label="Chi è?" maxlength="80" autocomplete="off" />
                  <button type="submit" [disabled]="occupato()">OK</button>
                </form>
              } @else {
                @if (v.proposta) {
                  <span class="forse">Forse {{ v.nomeProposta }}?</span>
                  <button type="button" aria-label="Sì" title="Sì" [disabled]="occupato()" (click)="conferma(v)">✓</button>
                  <button type="button" aria-label="No" title="No" [disabled]="occupato()" (click)="rifiuta(v)">✕</button>
                } @else if (v.personaId) {
                  <button type="button" class="tenue" title="Le foto di questo gruppo" (click)="filtra(v)">Senza nome</button>
                }
                <button type="button" class="link" (click)="inModifica.set(v.id)">Chi è?</button>
              }
            </li>
          }
        </ul>
        @if (nascosti()) {
          <p class="tenue">e {{ nascosti() }} {{ nascosti() === 1 ? 'volto nascosto' : 'volti nascosti' }}</p>
        }
        <datalist id="nomi-persone">
          @for (p of galleria.personeNominate(); track p.id) {
            <option [value]="p.nome"></option>
          }
        </datalist>
      </section>
    }
  `,
  styles: `
    .persone-foto {
      margin: 0.8rem 0;
    }
    h3 {
      margin: 0 0 0.4rem;
      font-size: 0.85rem;
      font-weight: 600;
      color: var(--testo-tenue);
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
      display: grid;
      gap: 0.35rem;
    }
    li {
      display: flex;
      align-items: center;
      gap: 0.4rem;
      flex-wrap: wrap;
    }
    img {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      object-fit: cover;
      background: var(--superficie-2);
    }
    button {
      font: inherit;
      color: var(--testo);
      background: var(--superficie-2);
      border: 1px solid var(--bordo);
      border-radius: 999px;
      padding: 0.15rem 0.6rem;
      cursor: pointer;
    }
    .nome {
      font-weight: 600;
    }
    .link {
      border: 0;
      background: none;
      color: var(--accento);
      font-size: 0.85em;
      padding: 0;
    }
    .tenue,
    .forse {
      color: var(--testo-tenue);
      font-size: 0.9em;
    }
    form {
      display: flex;
      gap: 0.3rem;
    }
    input {
      font: inherit;
      width: 9rem;
      color: var(--testo);
      background: var(--sfondo);
      border: 1px solid var(--bordo);
      border-radius: 6px;
      padding: 0.15rem 0.4rem;
    }
  `,
})
export class PersoneFoto {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  readonly foto = input.required<Foto>();
  /** Si è scelto di vedere le foto di una persona: il visore si chiude. */
  readonly scelta = output<void>();

  protected readonly attiva = computed(() => !!this.galleria.volti()?.attiva && !this.foto().video);
  protected readonly volti = signal<VoltoInFoto[]>([]);
  protected readonly visibili = computed(() => this.volti().filter((v) => !v.nascosta));
  protected readonly nascosti = computed(() => this.volti().length - this.visibili().length);
  protected readonly inModifica = signal<string | null>(null);
  protected readonly occupato = signal(false);

  constructor() {
    effect(() => {
      const f = this.foto();
      this.inModifica.set(null);
      this.volti.set([]);
      if (this.attiva()) {
        this.carica(f.id);
      }
    });
  }

  private carica(id: string): void {
    this.api.voltiDellaFoto(id).subscribe({
      next: (v) => {
        if (this.foto().id === id) {
          this.volti.set(v);
        }
      },
      error: () => this.volti.set([]),
    });
  }

  protected ritaglio(id: string): string {
    return FotoApi.ritaglio(id);
  }

  protected filtra(v: VoltoInFoto): void {
    if (v.personaId) {
      this.galleria.filtraPersona({ id: v.personaId, nome: v.nome, nascosta: false, volti: 0, proposte: 0 });
      this.scelta.emit();
    }
  }

  protected async nomina(v: VoltoInFoto, nome: string): Promise<void> {
    if (!nome.trim()) return;
    await this.fai(async () => {
      await firstValueFrom(this.api.nominaVolto(v.id, nome.trim()));
      this.galleria.aggiornaPersone();
    });
  }

  protected async conferma(v: VoltoInFoto): Promise<void> {
    if (v.proposta) {
      const persona = v.proposta;
      await this.fai(() => firstValueFrom(this.api.assegnaVolto(v.id, persona)));
    }
  }

  protected async rifiuta(v: VoltoInFoto): Promise<void> {
    await this.fai(() => firstValueFrom(this.api.rifiutaProposta(v.id)));
  }

  private async fai(azione: () => Promise<unknown>): Promise<void> {
    this.occupato.set(true);
    try {
      await azione();
      this.inModifica.set(null);
      this.carica(this.foto().id);
    } catch (e: unknown) {
      this.galleria.avvisa((e as { error?: { detail?: string } }).error?.detail ?? 'Non riuscito');
    } finally {
      this.occupato.set(false);
    }
  }
}
