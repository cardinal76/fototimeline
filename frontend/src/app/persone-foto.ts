import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Foto, VocePersona, VoltoInFoto } from './modelli';

/**
 * I nomi per "Chi è?" / "È un'altra persona…" / "+ Aggiungi persona": le
 * persone con un nome e, solo all'admin (che le ha nascoste), anche le
 * nascoste; mai quelle di `escluse` (da {@link personeDaEscludere}).
 */
export function nomiDaProporre(
  nominate: VocePersona[],
  nascoste: VocePersona[],
  escluse: string | readonly string[] = [],
): { id: string; nome: string; nascosta: boolean }[] {
  const via = new Set(typeof escluse === 'string' ? [escluse] : escluse);
  const visti = new Set<string>();
  const nomi: { id: string; nome: string; nascosta: boolean }[] = [];
  for (const p of [...nominate, ...nascoste]) {
    if (!p.nome || via.has(p.id) || visti.has(p.id)) continue;
    visti.add(p.id);
    nomi.push({ id: p.id, nome: p.nome, nascosta: p.nascosta });
  }
  return nomi.sort((a, b) => a.nome.localeCompare(b.nome, 'it', { sensitivity: 'base' }));
}

/**
 * Chi non proporre, perché è già nella foto (una persona c'è una volta sola
 * per foto). Senza `volto`, per "+ Aggiungi persona": tutte quelle della foto.
 * Per il "Chi è?" di un volto: quelle sugli altri volti veri (una persona
 * aggiunta senza volto sì, il volto può essere proprio il suo) e, se il volto
 * ha già un nome, la sua, da cui si sposta.
 */
export function personeDaEscludere(volti: VoltoInFoto[], volto?: VoltoInFoto): string[] {
  const escluse = new Set<string>();
  for (const v of volti) {
    if (!v.personaId) continue;
    if (!volto || (v.id !== volto.id && !v.aggiunto) || (v.id === volto.id && v.nome)) {
      escluse.add(v.personaId);
    }
  }
  return [...escluse];
}

/**
 * Nel pannello dei dettagli del visore: le persone riconosciute nella foto.
 * Clic sul nome: le sue foto; su un volto senza nome si scrive chi è; una
 * proposta si conferma o si rifiuta. Chi cambia le foto (gestore) ha accanto
 * a ogni volto ✎: "È un'altra persona…" (sposta solo questo volto), "Non è
 * nessuno" (lo toglie, e il riconoscimento non glielo ridà) e "Non è un
 * volto" (lo cancella). "+ Aggiungi persona" mette nella foto chi visione non
 * ha trovato (di spalle, piccolo, tagliato): una riga senza ritaglio, con ×
 * per toglierla. Chi è già nella foto non si propone. Solo se il
 * riconoscimento c'è.
 */
@Component({
  selector: 'app-persone-foto',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (attiva() && (volti().length || galleria.puoGestire())) {
      <section class="persone-foto" aria-label="Persone nella foto">
        <h3>Persone</h3>
        @if (volti().length) {
          <ul>
            @for (v of volti(); track v.id) {
              <li>
                @if (v.aggiunto) {
                  <span class="senza-volto" aria-hidden="true" title="Aggiunta a mano: il volto non si vede">{{
                    iniziale(v)
                  }}</span>
                  <button type="button" class="nome" title="Mostra le sue foto" (click)="filtra(v)">{{ v.nome ?? 'Senza nome' }}</button>
                  @if (galleria.puoGestire()) {
                    <button
                      type="button"
                      class="togli"
                      [attr.aria-label]="'Togli ' + (v.nome ?? 'la persona') + ' dalla foto'"
                      [title]="'Togli ' + (v.nome ?? 'la persona') + ' dalla foto'"
                      [disabled]="occupato()"
                      (click)="togli(v)"
                    >
                      ×
                    </button>
                  }
                } @else {
                  <img [src]="ritaglio(v.id)" alt="" loading="lazy" />
                  @if (inModifica() === v.id) {
                    <form (submit)="$event.preventDefault(); nomina(v, campo.value)">
                      <input
                        #campo
                        list="nomi-persone"
                        [placeholder]="v.nome ? 'Chi è davvero?' : 'Chi è?'"
                        [attr.aria-label]="v.nome ? 'Chi è davvero?' : 'Chi è?'"
                        maxlength="80"
                        autocomplete="off"
                        (keydown.escape)="inModifica.set(null)"
                      />
                      <button type="submit" [disabled]="occupato()">OK</button>
                      <button type="button" class="link" (click)="inModifica.set(null)">Annulla</button>
                    </form>
                  } @else if (v.nome) {
                    <button type="button" class="nome" title="Mostra le sue foto" (click)="filtra(v)">{{ v.nome }}</button>
                  } @else if (!galleria.puoGestire()) {
                    @if (v.personaId) {
                      <button type="button" class="tenue" title="Le foto di questo gruppo" (click)="filtra(v)">Senza nome</button>
                    }
                  } @else {
                    @if (v.proposta) {
                      <span class="forse">Forse {{ v.nomeProposta }}?</span>
                      <button type="button" aria-label="Sì" title="Sì" [disabled]="occupato()" (click)="conferma(v)">✓</button>
                      <button type="button" aria-label="No" title="No" [disabled]="occupato()" (click)="rifiuta(v)">✕</button>
                    } @else if (v.personaId) {
                      <button type="button" class="tenue" title="Le foto di questo gruppo" (click)="filtra(v)">Senza nome</button>
                    }
                    <button type="button" class="link" (click)="cambia(v)">Chi è?</button>
                  }
                  @if (galleria.puoGestire() && inModifica() !== v.id) {
                    <button
                      type="button"
                      class="matita"
                      aria-label="Correggi"
                      title="Correggi"
                      aria-haspopup="menu"
                      [attr.aria-expanded]="menu() === v.id"
                      [disabled]="occupato()"
                      (click)="apriMenu(v)"
                    >
                      ✎
                    </button>
                  }
                  @if (menu() === v.id) {
                    <div class="menu" role="menu" [attr.aria-label]="'Correggi ' + (v.nome ?? 'il volto')" (keydown.escape)="menu.set(null)">
                      <button type="button" role="menuitem" (click)="cambia(v)">
                        {{ v.nome ? "È un'altra persona…" : 'Chi è?' }}
                      </button>
                      @if (v.personaId) {
                        <button type="button" role="menuitem" (click)="togli(v)">Non è nessuno / togli</button>
                      }
                      <button type="button" role="menuitem" (click)="scarta(v)">Non è un volto</button>
                      <button type="button" role="menuitem" class="link" (click)="menu.set(null)">Annulla</button>
                    </div>
                  }
                }
              </li>
            }
          </ul>
        }
        @if (galleria.puoGestire()) {
          @if (aggiungendo()) {
            <form class="aggiungi" (submit)="$event.preventDefault(); aggiungi(campo)">
              <input
                #campo
                list="nomi-persone"
                placeholder="Chi c'è?"
                aria-label="Persona da aggiungere"
                maxlength="80"
                autocomplete="off"
                (keydown.escape)="aggiungendo.set(false)"
              />
              <button type="submit" [disabled]="occupato()">Aggiungi</button>
              <button type="button" class="link" (click)="aggiungendo.set(false)">Fine</button>
            </form>
          } @else {
            <button type="button" class="link aggiungi" (click)="apriAggiungi()">+ Aggiungi persona</button>
          }
        }
        <datalist id="nomi-persone">
          @for (p of nomi(); track p.id) {
            <option [value]="p.nome">{{ p.nascosta ? 'nascosta' : '' }}</option>
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
    .matita,
    .togli {
      border: 0;
      background: none;
      color: var(--testo-tenue);
      padding: 0 0.3rem;
    }
    .matita:hover,
    .togli:hover,
    .matita[aria-expanded='true'] {
      color: var(--accento);
    }
    .menu {
      flex-basis: 100%;
      display: flex;
      flex-wrap: wrap;
      gap: 0.3rem;
      margin-left: calc(32px + 0.4rem);
    }
    .menu button {
      font-size: 0.85em;
    }
    .senza-volto {
      width: 32px;
      height: 32px;
      border-radius: 50%;
      border: 1px dashed var(--bordo);
      display: inline-flex;
      align-items: center;
      justify-content: center;
      color: var(--testo-tenue);
      font-size: 0.85rem;
      flex: none;
    }
    .aggiungi {
      margin-top: 0.4rem;
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
  /** Le persone nascoste non ci sono: il server non le manda. */
  protected readonly volti = signal<VoltoInFoto[]>([]);
  /** Il volto a cui si sta scrivendo il nome. */
  protected readonly inModifica = signal<string | null>(null);
  /** Il volto col menu ✎ aperto. */
  protected readonly menu = signal<string | null>(null);
  /** Il campo di "+ Aggiungi persona" è aperto: si aggiungono una dopo l'altra. */
  protected readonly aggiungendo = signal(false);
  protected readonly occupato = signal(false);
  /** Le nascoste, per l'admin: si caricano la prima volta che si scrive un nome. */
  private readonly nascoste = signal<VocePersona[]>([]);
  private nascosteCaricate = false;
  private readonly campo = viewChild<ElementRef<HTMLInputElement>>('campo');

  /** Chi è già nella foto non si propone: una persona c'è una volta sola per foto. */
  protected readonly nomi = computed(() => {
    const volti = this.volti();
    const escluse = this.aggiungendo()
      ? personeDaEscludere(volti)
      : personeDaEscludere(volti, volti.find((x) => x.id === this.inModifica()));
    return nomiDaProporre(this.galleria.personeNominate(), this.galleria.admin() ? this.nascoste() : [], escluse);
  });

  constructor() {
    effect(() => {
      const f = this.foto();
      this.inModifica.set(null);
      this.menu.set(null);
      this.aggiungendo.set(false);
      this.volti.set([]);
      if (this.attiva()) {
        this.carica(f.id);
      }
    });
    // Il campo del nome prende il fuoco appena compare.
    effect(() => this.campo()?.nativeElement.focus());
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

  protected iniziale(v: VoltoInFoto): string {
    return (v.nome ?? '?').trim().charAt(0).toUpperCase();
  }

  protected filtra(v: VoltoInFoto): void {
    if (v.personaId) {
      this.galleria.filtraPersona({ id: v.personaId, nome: v.nome, nascosta: false, volti: 0, proposte: 0 });
      this.scelta.emit();
    }
  }

  protected apriMenu(v: VoltoInFoto): void {
    this.menu.set(this.menu() === v.id ? null : v.id);
  }

  /** "Chi è?" / "È un'altra persona…": il campo col nome, con i suggerimenti. */
  protected cambia(v: VoltoInFoto): void {
    this.menu.set(null);
    this.aggiungendo.set(false);
    this.inModifica.set(v.id);
    this.caricaNascoste();
  }

  /** "+ Aggiungi persona": il campo, con gli stessi suggerimenti del "Chi è?". */
  protected apriAggiungi(): void {
    this.menu.set(null);
    this.inModifica.set(null);
    this.aggiungendo.set(true);
    this.caricaNascoste();
  }

  private caricaNascoste(): void {
    if (this.galleria.admin() && !this.nascosteCaricate) {
      this.nascosteCaricate = true;
      this.api.persone('NASCOSTE').subscribe({ next: (e) => this.nascoste.set(e.persone), error: () => {} });
    }
  }

  /** Il campo resta aperto e si svuota: un'altra persona, o "Fine". */
  protected async aggiungi(campo: HTMLInputElement): Promise<void> {
    const nome = campo.value.trim();
    if (!nome) return;
    const fatto = await this.fai(async () => {
      const p = await firstValueFrom(this.api.aggiungiPersona(this.foto().id, nome));
      this.galleria.aggiornaPersone();
      if (p.nascosta) {
        this.galleria.avvisa(`${p.nome} è una persona nascosta: nella foto è nascosta con lei`);
      }
    });
    if (fatto) {
      campo.value = '';
      campo.focus();
    }
  }

  protected async nomina(v: VoltoInFoto, nome: string): Promise<void> {
    if (!nome.trim()) return;
    await this.fai(async () => {
      const p = await firstValueFrom(this.api.nominaVolto(v.id, nome.trim()));
      this.galleria.aggiornaPersone();
      if (p.nascosta) {
        this.galleria.avvisa(`${p.nome} è una persona nascosta: il volto ora è nascosto con lei`);
      }
    });
  }

  /**
   * "Non è nessuno": il volto resta senza persona e quella di prima non se lo
   * riprende. Una persona aggiunta a mano esce dalla foto.
   */
  protected async togli(v: VoltoInFoto): Promise<void> {
    await this.fai(async () => {
      await firstValueFrom(this.api.togliVolto(v.id));
      this.galleria.aggiornaPersone();
    });
  }

  protected async scarta(v: VoltoInFoto): Promise<void> {
    if (!confirm('Non è un volto? Il riquadro si toglie per sempre e il riconoscimento non lo ripropone.')) {
      return;
    }
    await this.fai(async () => {
      await firstValueFrom(this.api.scartaVolto(v.id));
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

  /** @returns se è riuscita (se no l'avviso col motivo del server, per esempio un 409) */
  private async fai(azione: () => Promise<unknown>): Promise<boolean> {
    this.occupato.set(true);
    try {
      await azione();
      this.inModifica.set(null);
      this.menu.set(null);
      this.carica(this.foto().id);
      return true;
    } catch (e: unknown) {
      this.galleria.avvisa((e as { error?: { detail?: string } }).error?.detail ?? 'Non riuscito');
      return false;
    } finally {
      this.occupato.set(false);
    }
  }
}
