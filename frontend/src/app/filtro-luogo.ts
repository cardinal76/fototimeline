import { ChangeDetectionStrategy, Component, ElementRef, computed, inject, signal } from '@angular/core';

import { Galleria } from './galleria';
import { Filtro } from './modelli';

type SceltaLuogo = Pick<Filtro, 'nazione' | 'regione' | 'luogo'>;

interface Risultato {
  chiave: string;
  nome: string;
  /** "Lazio, Italia". */
  dove: string;
  conteggio: number;
  filtro: SceltaLuogo;
}

/** Senza maiuscole né accenti: "parigi" trova "Parigi", "ile" trova "Île-de-France". */
function semplice(testo: string): string {
  return testo.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/**
 * Il filtro "Luogo" nella barra: nazioni, regioni e luoghi con quante foto
 * (GET /api/luoghi), oppure la ricerca per nome.
 */
@Component({
  selector: 'app-filtro-luogo',
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: {
    '(document:click)': 'fuori($event)',
    '(document:keydown.escape)': 'aperto.set(false)',
  },
  template: `
    <button
      type="button"
      class="scelta"
      aria-haspopup="dialog"
      [attr.aria-expanded]="aperto()"
      [attr.aria-pressed]="!!etichetta()"
      (click)="commutaPannello()"
    >
      📍 {{ etichetta() ?? 'Tutti i luoghi' }}
    </button>
    @if (etichetta()) {
      <button type="button" class="link togli" aria-label="Togli il filtro per luogo" (click)="scegli({})">✕</button>
    }
    @if (aperto()) {
      <div class="pannello" role="dialog" aria-label="Filtra per luogo">
        <input
          #campo
          type="search"
          placeholder="Cerca un luogo, una regione, una nazione…"
          aria-label="Cerca un luogo"
          [value]="testo()"
          (input)="testo.set(campo.value)"
        />
        @if (galleria.luoghi(); as elenco) {
          @if (testo().trim()) {
            <ul class="risultati">
              @for (r of risultati(); track r.chiave) {
                <li>
                  <button type="button" (click)="scegli(r.filtro)">
                    <span class="nome">{{ r.nome }}</span>
                    @if (r.dove) {
                      <small>{{ r.dove }}</small>
                    }
                    <span class="conto">{{ r.conteggio }}</span>
                  </button>
                </li>
              } @empty {
                <li class="tenue">Nessun luogo con questo nome.</li>
              }
            </ul>
          } @else {
            <ul class="albero">
              @for (n of elenco.nazioni; track n.codice) {
                <li>
                  <div class="voce">
                    <button type="button" class="espandi" [attr.aria-expanded]="espansa(n.codice)" (click)="commuta(n.codice)">
                      {{ espansa(n.codice) ? '▾' : '▸' }}
                    </button>
                    <button type="button" class="nome" (click)="scegli({ nazione: n.codice })">{{ n.nome }}</button>
                    <span class="conto">{{ n.conteggio }}</span>
                  </div>
                  @if (espansa(n.codice)) {
                    <ul>
                      @for (r of n.regioni; track r.nome ?? '') {
                        @let chiave = n.codice + '|' + (r.nome ?? '');
                        <li>
                          <div class="voce">
                            <button type="button" class="espandi" [attr.aria-expanded]="espansa(chiave)" (click)="commuta(chiave)">
                              {{ espansa(chiave) ? '▾' : '▸' }}
                            </button>
                            @if (r.nome) {
                              <button type="button" class="nome" (click)="scegli({ nazione: n.codice, regione: r.nome })">{{ r.nome }}</button>
                            } @else {
                              <span class="nome tenue">Regione sconosciuta</span>
                            }
                            <span class="conto">{{ r.conteggio }}</span>
                          </div>
                          @if (espansa(chiave)) {
                            <ul>
                              @for (l of r.luoghi; track l.nome) {
                                <li>
                                  <div class="voce">
                                    <button type="button" class="nome" (click)="scegli({ nazione: n.codice, regione: r.nome, luogo: l.nome })">
                                      {{ l.nome }}
                                    </button>
                                    <span class="conto">{{ l.conteggio }}</span>
                                  </div>
                                </li>
                              }
                            </ul>
                          }
                        </li>
                      }
                    </ul>
                  }
                </li>
              } @empty {
                <li class="tenue">
                  @if (elenco.disponibile) {
                    Nessuna foto con un luogo, per ora.
                    @if (elenco.daCalcolare) {
                      {{ elenco.daCalcolare }} foto col GPS aspettano "Calcola luoghi".
                    }
                  } @else {
                    I luoghi non sono attivi su questo server (manca il dataset di GeoNames).
                  }
                </li>
              }
            </ul>
          }
        } @else {
          <p class="tenue">Carico i luoghi…</p>
        }
        <p class="attribuzione">
          Nomi dei luoghi da <a href="https://www.geonames.org/" target="_blank" rel="noopener">GeoNames</a>,
          <a href="https://creativecommons.org/licenses/by/4.0/deed.it" target="_blank" rel="noopener">CC BY 4.0</a>
        </p>
      </div>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: inline-flex;
      align-items: center;
      gap: 0.25rem;
    }
    /* Come i select della barra (app.css non arriva qui dentro). */
    .scelta,
    .pannello input {
      color: var(--testo);
      background: var(--superficie-2);
      border: 1px solid var(--bordo);
      border-radius: 8px;
      padding: 0.45rem 0.75rem;
    }
    .scelta {
      cursor: pointer;
      max-width: 220px;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .scelta:hover {
      border-color: var(--testo-tenue);
    }
    .scelta[aria-pressed='true'] {
      background: var(--accento-tenue);
      border-color: var(--accento);
      color: var(--accento-forte);
    }
    .scelta:focus-visible,
    .pannello input:focus-visible {
      outline: 2px solid var(--accento);
      outline-offset: 1px;
    }
    .togli {
      all: unset;
      cursor: pointer;
      color: var(--accento);
      font-size: 0.9em;
      padding: 0 0.2rem;
    }
    .pannello {
      position: absolute;
      top: calc(100% + 6px);
      left: 0;
      z-index: 1000;
      width: min(360px, 90vw);
      max-height: min(70vh, 520px);
      display: flex;
      flex-direction: column;
      gap: 0.5rem;
      padding: 0.6rem;
      border-radius: 10px;
      background: var(--superficie);
      border: 1px solid var(--bordo);
      box-shadow: 0 8px 24px rgb(0 0 0 / 0.35);
    }
    .pannello input {
      width: 100%;
      box-sizing: border-box;
    }
    ul {
      list-style: none;
      margin: 0;
      padding: 0;
    }
    .albero,
    .risultati {
      overflow-y: auto;
      min-height: 0;
    }
    .albero ul {
      padding-left: 1.1rem;
    }
    .voce {
      display: flex;
      align-items: center;
      gap: 0.25rem;
    }
    .pannello button {
      all: unset;
      cursor: pointer;
      border-radius: 6px;
    }
    .pannello button:focus-visible {
      outline: 2px solid var(--accento);
    }
    .espandi {
      width: 1.2rem;
      text-align: center;
      color: var(--testo-tenue);
    }
    .nome {
      flex: 1;
      padding: 0.2rem 0.3rem;
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .voce button.nome:hover,
    .risultati button:hover {
      background: var(--superficie-2);
    }
    .conto {
      color: var(--testo-tenue);
      font-size: 0.85em;
      font-variant-numeric: tabular-nums;
    }
    .risultati button {
      display: flex;
      align-items: baseline;
      gap: 0.4rem;
      width: 100%;
      box-sizing: border-box;
      padding: 0.3rem 0.4rem;
    }
    .risultati small {
      flex: 1;
      color: var(--testo-tenue);
      overflow: hidden;
      text-overflow: ellipsis;
      white-space: nowrap;
    }
    .risultati .nome {
      flex: 0 1 auto;
      padding: 0;
    }
    .tenue {
      margin: 0;
      color: var(--testo-tenue);
      padding: 0.3rem;
    }
    .attribuzione {
      margin: 0;
      font-size: 0.75rem;
      color: var(--testo-tenue);
    }
    .attribuzione a {
      color: inherit;
    }
  `,
})
export class FiltroLuogo {
  protected readonly galleria = inject(Galleria);
  private readonly elemento = inject<ElementRef<HTMLElement>>(ElementRef);

  protected readonly aperto = signal(false);
  protected readonly testo = signal('');
  private readonly aperte = signal<ReadonlySet<string>>(new Set());

  /** Cosa c'è scritto sul bottone: il luogo scelto, o la regione, o la nazione; null senza filtro. */
  protected readonly etichetta = computed(() => {
    const f = this.galleria.filtro();
    if (f.luogo) return f.luogo;
    if (f.regione) return f.regione;
    if (f.nazione) return this.galleria.luoghi()?.nazioni.find((n) => n.codice === f.nazione)?.nome ?? f.nazione;
    return null;
  });

  /** Ricerca per nome: prima i luoghi, poi regioni e nazioni; i più fotografati prima, al massimo 60. */
  protected readonly risultati = computed<Risultato[]>(() => {
    const cercato = semplice(this.testo().trim());
    const elenco = this.galleria.luoghi();
    if (!cercato || !elenco) return [];
    const trovati: Risultato[] = [];
    for (const n of elenco.nazioni) {
      if (semplice(n.nome).includes(cercato)) {
        trovati.push({ chiave: n.codice, nome: n.nome, dove: '', conteggio: n.conteggio, filtro: { nazione: n.codice } });
      }
      for (const r of n.regioni) {
        if (r.nome && semplice(r.nome).includes(cercato)) {
          trovati.push({
            chiave: `${n.codice}|${r.nome}`,
            nome: r.nome,
            dove: n.nome,
            conteggio: r.conteggio,
            filtro: { nazione: n.codice, regione: r.nome },
          });
        }
        for (const l of r.luoghi) {
          if (semplice(l.nome).includes(cercato)) {
            trovati.push({
              chiave: `${n.codice}|${r.nome ?? ''}|${l.nome}`,
              nome: l.nome,
              dove: [r.nome, n.nome].filter(Boolean).join(', '),
              conteggio: l.conteggio,
              filtro: { nazione: n.codice, regione: r.nome, luogo: l.nome },
            });
          }
        }
      }
    }
    // Chi comincia con il testo cercato prima degli altri.
    const inizia = (r: Risultato) => (semplice(r.nome).startsWith(cercato) ? 0 : 1);
    return trovati.sort((a, b) => inizia(a) - inizia(b) || b.conteggio - a.conteggio).slice(0, 60);
  });

  protected commutaPannello(): void {
    const apri = !this.aperto();
    this.aperto.set(apri);
    if (apri) {
      // I conteggi cambiano con importazioni e calcoli: si rileggono a ogni apertura.
      this.galleria.aggiornaLuoghi();
      const elenco = this.galleria.luoghi();
      if (elenco?.nazioni.length === 1 && !this.aperte().size) {
        this.commuta(elenco.nazioni[0].codice);
      }
    }
  }

  protected espansa(chiave: string): boolean {
    return this.aperte().has(chiave);
  }

  protected commuta(chiave: string): void {
    this.aperte.update((s) => {
      const n = new Set(s);
      if (!n.delete(chiave)) n.add(chiave);
      return n;
    });
  }

  protected scegli(f: SceltaLuogo): void {
    this.galleria.imposta({ nazione: f.nazione, regione: f.regione, luogo: f.luogo });
    this.aperto.set(false);
    this.testo.set('');
  }

  /** Un clic fuori dal pannello lo chiude. */
  protected fuori(e: MouseEvent): void {
    if (this.aperto() && !this.elemento.nativeElement.contains(e.target as Node)) {
      this.aperto.set(false);
    }
  }
}
