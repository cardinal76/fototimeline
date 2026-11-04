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
import { firstValueFrom } from 'rxjs';

import {
  EsitoDate,
  MASSIMO_DATE,
  ModoData,
  RichiestaDate,
  Spostamento,
  anteprima,
  inOrdine,
  problema,
} from './date-multiple';
import { FotoApi } from './foto-api';
import { Galleria } from './galleria';

type Campo = keyof Spostamento;

/**
 * "📅 Data…" della barra della selezione: la data di tutte le foto scelte
 * insieme. Tre modi: lo stesso giorno (ognuna con la sua ora), la stessa data
 * e ora (un secondo l'una dall'altra, nell'ordine di prima), o tutte avanti o
 * indietro della stessa quantità (l'orologio della fotocamera sbagliato).
 * Come dal visore: la data diventa "a mano" e i file cambiano cartella.
 */
@Component({
  selector: 'app-data-selezione',
  changeDetection: ChangeDetectionStrategy.OnPush,
  styleUrls: ['./cestino.css'],
  template: `
    <div class="velo" (click)="chiudi.emit()">
      <section
        #dialogo
        class="dialogo stretto"
        role="dialog"
        aria-modal="true"
        aria-labelledby="titolo-data-selezione"
        tabindex="-1"
        (click)="$event.stopPropagation()"
      >
        <header>
          <h2 id="titolo-data-selezione">📅 Data di {{ ids().length }} foto</h2>
          <button type="button" class="chiudi" aria-label="Chiudi" (click)="chiudi.emit()">✕</button>
        </header>

        @if (esito(); as e) {
          <p>
            <b>{{ e.modificate }} foto con la data nuova</b>
            @if (e.invariate) {
              · {{ e.invariate }} l'avevano già
            }
            @if (e.saltate) {
              · {{ e.saltate }} saltate (nel cestino, private di altri o senza data)
            }
          </p>
          @if (e.errori.length) {
            <p class="errore">{{ e.errori.length }} non cambiate:</p>
            <ul class="errori">
              @for (x of e.errori; track x.id) {
                <li>
                  <b>{{ x.nome }}</b>: {{ x.motivo }}
                </li>
              }
            </ul>
          }
          <div class="piede">
            <button type="button" class="primario" (click)="chiudi.emit()">Chiudi</button>
          </div>
        } @else {
          <fieldset class="modi" [disabled]="occupato()">
            <legend class="tenue">Come</legend>
            <label>
              <input type="radio" name="modo" value="STESSO_GIORNO" [checked]="modo() === 'STESSO_GIORNO'" (change)="modo.set('STESSO_GIORNO')" />
              Stessa data per tutte, ognuna con la sua ora
            </label>
            <label>
              <input type="radio" name="modo" value="STESSA_ORA" [checked]="modo() === 'STESSA_ORA'" (change)="modo.set('STESSA_ORA')" />
              Stessa data e ora per tutte
            </label>
            <label>
              <input type="radio" name="modo" value="SPOSTA" [checked]="modo() === 'SPOSTA'" (change)="modo.set('SPOSTA')" />
              Sposta di…
            </label>
          </fieldset>

          <div class="campi">
            @switch (modo()) {
              @case ('STESSO_GIORNO') {
                <label>
                  Giorno
                  <input #g type="date" aria-label="Giorno" [value]="giorno()" (input)="giorno.set(g.value)" />
                </label>
                <label class="spunta">
                  <input #m type="checkbox" [checked]="mantieniOra()" (change)="mantieniOra.set(m.checked)" />
                  Tieni l'ora di ognuna
                </label>
                <p class="tenue">
                  {{ mantieniOra() ? "Senza un'ora vera (data dalla cartella o mancante) vanno alle 12." : "Tutte alle 12, un secondo l'una dall'altra: l'ordine resta." }}
                </p>
              }
              @case ('STESSA_ORA') {
                <label>
                  Data e ora
                  <input #q type="datetime-local" step="1" aria-label="Data e ora" [value]="quando()" (input)="quando.set(q.value)" />
                </label>
                <p class="tenue">Un secondo in più per foto, nell'ordine di adesso: non si mescolano.</p>
              }
              @case ('SPOSTA') {
                <label>
                  <select #v aria-label="Avanti o indietro" (change)="indietro.set(v.value === 'indietro')">
                    <option value="avanti" [selected]="!indietro()">Avanti</option>
                    <option value="indietro" [selected]="indietro()">Indietro</option>
                  </select>
                </label>
                @for (c of campi; track c.campo) {
                  <label class="numero">
                    <input
                      #n
                      type="number"
                      min="0"
                      inputmode="numeric"
                      [attr.aria-label]="c.etichetta"
                      [value]="quantita()[c.campo] || ''"
                      placeholder="0"
                      (input)="imposta(c.campo, n.value)"
                    />
                    {{ c.etichetta }}
                  </label>
                }
                <p class="tenue">Le distanze fra le foto restano: utile se l'orologio della fotocamera era sbagliato.</p>
              }
            }
          </div>

          <p class="anteprima" role="status" [class.errore]="!!problema()">{{ testoAnteprima() }}</p>
          @if (troppe()) {
            <p class="errore">Al più {{ massimo }} foto alla volta: dividile in più gruppi.</p>
          }
          @if (errore(); as e) {
            <p class="errore">{{ e }}</p>
          }
          <p class="tenue">La data diventa "scelta a mano" e i file passano nella cartella del giorno nuovo{{ ids().length > 50 ? ': sul cloud ci vuole un po\\'' : '' }}.</p>

          <div class="piede">
            <button type="button" (click)="chiudi.emit()" [disabled]="occupato()">Annulla</button>
            <button type="button" class="primario" [disabled]="occupato() || !!problema() || troppe()" (click)="conferma()">
              @if (occupato()) {
                <span class="rotella piccola" aria-hidden="true"></span> Cambio la data…
              } @else {
                Cambia la data
              }
            </button>
          </div>
        }
      </section>
    </div>
  `,
  styles: `
    .stretto {
      width: min(520px, 100%);
    }
    .modi {
      display: grid;
      gap: 0.35rem;
      margin: 0;
      padding: 0;
      border: 0;
    }
    .modi legend {
      padding: 0;
      margin-bottom: 0.2rem;
    }
    label {
      display: flex;
      align-items: center;
      gap: 0.45rem;
    }
    .campi {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.6rem 0.9rem;
    }
    .campi p {
      flex-basis: 100%;
    }
    input[type='date'],
    input[type='datetime-local'],
    input[type='number'],
    select {
      font: inherit;
      color: inherit;
      background: var(--sfondo, transparent);
      border: 1px solid var(--bordo);
      border-radius: 8px;
      padding: 0.35rem 0.5rem;
      color-scheme: dark light;
    }
    .numero input {
      width: 4.2rem;
    }
    .anteprima {
      font-weight: 600;
    }
    .errori {
      margin: 0;
      padding-left: 1.2rem;
      max-height: 30vh;
      overflow: auto;
      font-size: 0.9rem;
    }
    .rotella.piccola {
      display: inline-block;
      width: 0.9em;
      height: 0.9em;
      vertical-align: -0.1em;
    }
  `,
})
export class DataSelezione {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  readonly chiudi = output<void>();
  /** Le date sono cambiate tutte (senza errori): la selezione ha finito. */
  readonly fatto = output<void>();

  protected readonly massimo = MASSIMO_DATE;
  protected readonly campi: { campo: Campo; etichetta: string }[] = [
    { campo: 'anni', etichetta: 'anni' },
    { campo: 'mesi', etichetta: 'mesi' },
    { campo: 'giorni', etichetta: 'giorni' },
    { campo: 'ore', etichetta: 'ore' },
    { campo: 'minuti', etichetta: 'minuti' },
  ];

  protected readonly ids = computed(() => [...this.galleria.selezionate()]);
  /** Le scelte caricate in pagina, dalla più vecchia: servono all'anteprima. */
  private readonly scelte = computed(() => {
    const ids = this.galleria.selezionate();
    return inOrdine(this.galleria.foto().filter((f) => ids.has(f.id)));
  });

  protected readonly modo = signal<ModoData>('STESSO_GIORNO');
  /** Di partenza il giorno e l'ora della più vecchia. */
  protected readonly giorno = signal(this.scelte()[0]?.scattataIl?.slice(0, 10) ?? '');
  protected readonly mantieniOra = signal(true);
  protected readonly quando = signal(this.scelte()[0]?.scattataIl?.slice(0, 19) ?? '');
  protected readonly indietro = signal(false);
  protected readonly quantita = signal<Spostamento>({ anni: 0, mesi: 0, giorni: 0, ore: 0, minuti: 0 });

  protected readonly occupato = signal(false);
  protected readonly errore = signal<string | null>(null);
  protected readonly esito = signal<EsitoDate | null>(null);

  protected readonly richiesta = computed<RichiestaDate>(() => {
    switch (this.modo()) {
      case 'STESSO_GIORNO':
        return { modo: 'STESSO_GIORNO', giorno: this.giorno(), mantieniOra: this.mantieniOra() };
      case 'STESSA_ORA': {
        const q = this.quando();
        // Il datetime-local senza secondi dà "1993-06-20T10:00".
        return { modo: 'STESSA_ORA', quando: q.length === 16 ? q + ':00' : q };
      }
      case 'SPOSTA': {
        const segno = this.indietro() ? -1 : 1;
        const q = this.quantita();
        return {
          modo: 'SPOSTA',
          sposta: {
            anni: segno * q.anni,
            mesi: segno * q.mesi,
            giorni: segno * q.giorni,
            ore: segno * q.ore,
            minuti: segno * q.minuti,
          },
        };
      }
    }
  });
  protected readonly problema = computed(() => problema(this.richiesta()));
  protected readonly troppe = computed(() => this.ids().length > MASSIMO_DATE);
  protected readonly testoAnteprima = computed(() => anteprima(this.scelte(), this.richiesta(), this.ids().length));

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
  }

  protected imposta(campo: Campo, valore: string): void {
    const n = Math.max(0, Math.trunc(Number(valore) || 0));
    this.quantita.update((q) => ({ ...q, [campo]: n }));
  }

  protected async conferma(): Promise<void> {
    if (this.problema() || this.troppe()) return;
    this.occupato.set(true);
    this.errore.set(null);
    try {
      const esito = await firstValueFrom(this.api.cambiaDate(this.ids(), this.richiesta()));
      this.galleria.ricarica();
      if (esito.errori.length) {
        // Gli errori restano in vista; le altre sono già cambiate.
        this.esito.set(esito);
      } else {
        this.galleria.avvisa(
          `${esito.modificate} foto con la data nuova` + (esito.saltate ? ` · ${esito.saltate} saltate` : ''),
        );
        this.fatto.emit();
        this.chiudi.emit();
      }
    } catch (e: unknown) {
      const r = e as { status?: number; error?: { detail?: string } };
      this.errore.set(
        r.error?.detail ?? (r.status === 503 ? 'Il cloud è smontato: le foto non si spostano' : 'Data non cambiata'),
      );
    } finally {
      this.occupato.set(false);
    }
  }
}
