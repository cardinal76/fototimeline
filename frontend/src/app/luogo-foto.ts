import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';

import { Galleria } from './galleria';
import { LivelloFoto, livelliFoto } from './luoghi';
import { Foto } from './modelli';
import { ScegliLuogo } from './scegli-luogo';

/**
 * Il luogo di una foto nel pannello del visore: i livelli come tag (città,
 * provincia, regione, nazione; un clic filtra la timeline) e la modifica a
 * mano, cercando il luogo per nome. Un luogo scelto a mano resta finché non
 * si torna al GPS.
 */
@Component({
  selector: 'app-luogo-foto',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ScegliLuogo],
  template: `
    <div class="riga">
      @for (l of livelli(); track $index; let primo = $first) {
        <button type="button" class="tag" [title]="'Mostra le foto di ' + l.testo" (click)="filtra(l)">
          @if (primo) {
            📍
          }
          {{ l.testo }}
        </button>
      } @empty {
        <span class="tenue">📍 Nessun luogo</span>
      }
      @if (!modifica() && galleria.puoGestire()) {
        <button type="button" class="cambia" title="Cambia il luogo (L)" aria-label="Cambia il luogo" (click)="apri()">✎</button>
      }
    </div>
    @if (foto().origineLuogo === 'MANUALE') {
      <small class="nota">
        Scelto a mano{{ approssimativa() ? ': sulla mappa la foto sta lì, in modo approssimativo' : '' }}
      </small>
    }
    @if (modifica()) {
      <div class="modifica">
        <app-scegli-luogo (scelto)="salva($event.chiave)" />
        <div class="azioni">
          @if (foto().origineLuogo === 'MANUALE') {
            <button
              type="button"
              [title]="gps() ? 'Torna al luogo calcolato dalle coordinate GPS' : 'La foto non ha il GPS: resta senza luogo'"
              (click)="salva(undefined)"
            >
              Usa il GPS
            </button>
          }
          @if (livelli().length) {
            <button type="button" (click)="salva(null)">Nessun luogo</button>
          }
          <button type="button" (click)="modifica.set(false)">Annulla</button>
        </div>
      </div>
    }
  `,
  styles: `
    :host {
      display: grid;
      gap: 0.35rem;
      margin: 0 0 1.25rem;
    }
    .riga {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      gap: 0.3rem;
    }
    button {
      all: unset;
      cursor: pointer;
      font-size: 0.9rem;
      border-radius: 999px;
    }
    button:focus-visible {
      outline: 2px solid #7eaaff;
    }
    .tag {
      padding: 0.15rem 0.55rem;
      background: rgb(255 255 255 / 0.08);
      border: 1px solid rgb(255 255 255 / 0.14);
    }
    .tag:hover {
      border-color: #7eaaff;
    }
    .cambia {
      padding: 0.15rem 0.4rem;
      color: #9aa0ab;
    }
    .cambia:hover {
      color: inherit;
    }
    .tenue,
    .nota {
      color: #9aa0ab;
      font-size: 0.8rem;
    }
    .modifica {
      display: grid;
      gap: 0.4rem;
    }
    .azioni {
      display: flex;
      flex-wrap: wrap;
      gap: 0.4rem;
    }
    .azioni button {
      border-radius: 8px;
      padding: 0.3rem 0.65rem;
      font-size: 0.85rem;
      border: 1px solid rgb(255 255 255 / 0.18);
    }
    .azioni button:hover {
      border-color: #7eaaff;
    }
  `,
})
export class LuogoFoto {
  protected readonly galleria = inject(Galleria);

  readonly foto = input.required<Foto>();
  /** La foto col luogo nuovo. */
  readonly cambiata = output<Foto>();
  /** Un livello è diventato il filtro della timeline: il visore si chiude. */
  readonly filtrata = output<void>();

  protected readonly modifica = signal(false);
  protected readonly livelli = computed(() => livelliFoto(this.foto()));
  protected readonly gps = computed(() => this.foto().latitudine != null && this.foto().longitudine != null);
  /** Sulla mappa alle coordinate della città scelta (FotoService.mappa): senza GPS, o col GPS lontano. */
  protected readonly approssimativa = computed(() => this.foto().latitudineLuogo != null && !this.gps());

  /** Apre la ricerca del luogo (anche dal tasto L del visore). */
  apri(): void {
    if (!this.galleria.puoGestire()) {
      return;
    }
    this.modifica.set(true);
  }

  protected filtra(l: LivelloFoto): void {
    this.galleria.imposta(l.filtro);
    this.filtrata.emit();
  }

  /** chiave: un luogo di GeoNames; null = nessun luogo; undefined = di nuovo dal GPS. */
  protected async salva(chiave: string | null | undefined): Promise<void> {
    try {
      const aggiornata = await this.galleria.cambiaLuogo(this.foto(), chiave);
      this.modifica.set(false);
      this.cambiata.emit(aggiornata);
      this.galleria.avvisa(chiave === undefined ? 'Luogo dal GPS' : 'Luogo salvato');
    } catch {
      this.galleria.avvisa('Luogo non salvato');
    }
  }
}
