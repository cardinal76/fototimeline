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
import { ElencoEventi, Evento } from './modelli';

/** Senza maiuscole né accenti, come il filtro dei luoghi. */
function semplice(testo: string): string {
  return testo.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}

/** Il modulo di un evento per date: nuovo (senza id) o da cambiare. */
interface ModuloPerDate {
  id?: string;
  nome: string;
  dal: string;
  al: string;
}

/**
 * La pagina "Eventi": le foto raggruppate da sole per tempo e posto (o a
 * mano), per anno, con copertina, nome, date e quante foto. Un clic apre le
 * foto dell'evento nella timeline (filtro "evento"). L'admin li rinomina,
 * unisce, nasconde o li fa tornare automatici e lancia "Calcola eventi"; il
 * gestore (e l'admin) crea, cambia ed elimina gli eventi per date (nome, dal,
 * al: tutte le foto di quei giorni). Esc la chiude (App).
 */
@Component({
  selector: 'app-eventi',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './eventi.html',
  styleUrls: ['./cestino.css', './eventi.css'],
})
export class Eventi {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  readonly chiudi = output<void>();

  protected readonly elenco = signal<ElencoEventi | null>(null);
  protected readonly caricando = signal(true);
  protected readonly occupato = signal(false);
  protected readonly nascosti = signal(false);
  protected readonly testo = signal('');
  /** Gli eventi scelti per unirli. */
  protected readonly scelti = signal<ReadonlySet<string>>(new Set());
  /** Il modulo aperto di un evento per date, null se chiuso. */
  protected readonly modulo = signal<ModuloPerDate | null>(null);

  /** Per anno, dal più recente; con la ricerca solo quelli col nome che la contiene. */
  protected readonly anni = computed(() => {
    const cercato = semplice(this.testo().trim());
    const anni: { anno: string; eventi: Evento[]; foto: number }[] = [];
    for (const e of this.elenco()?.eventi ?? []) {
      if (cercato && !semplice(e.nome).includes(cercato)) continue;
      const anno = e.primoGiorno.slice(0, 4);
      let a = anni.at(-1);
      if (a?.anno !== anno) {
        a = { anno, eventi: [], foto: 0 };
        anni.push(a);
      }
      a.eventi.push(e);
      a.foto += e.conteggio;
    }
    return anni;
  });

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    void this.carica();
  }

  protected async carica(): Promise<void> {
    this.caricando.set(true);
    try {
      this.elenco.set(await firstValueFrom(this.api.eventi(this.nascosti())));
      const ci = new Set(this.elenco()!.eventi.map((e) => e.id));
      this.scelti.update((s) => new Set([...s].filter((id) => ci.has(id))));
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Eventi non leggibili');
    } finally {
      this.caricando.set(false);
    }
  }

  protected commutaNascosti(): void {
    this.nascosti.update((n) => !n);
    void this.carica();
  }

  /** Le foto dell'evento nella timeline, con gli altri filtri com'erano (il salto di date no). */
  protected apri(e: Evento): void {
    this.galleria.imposta({ evento: e.id, al: undefined });
    this.chiudi.emit();
  }

  protected commuta(e: Evento): void {
    this.scelti.update((s) => {
      const n = new Set(s);
      if (!n.delete(e.id)) n.add(e.id);
      return n;
    });
  }

  protected deseleziona(): void {
    this.scelti.set(new Set());
  }

  // ------------------------------------------------------------ modifiche

  protected rinomina(e: Evento): Promise<void> {
    const nome = prompt('Nome dell\'evento (vuoto: quello automatico)', e.nome);
    if (nome === null) return Promise.resolve();
    return this.esegui(() => this.api.modificaEvento(e.id, { nome }), 'Evento rinominato').then(() => {});
  }

  protected nascondi(e: Evento): Promise<void> {
    return this.esegui(
      () => this.api.modificaEvento(e.id, { nascosto: !e.nascosto }),
      e.nascosto ? 'Evento di nuovo in vista' : 'Evento nascosto: le foto restano nella timeline',
    ).then(() => {});
  }

  protected sciogli(e: Evento): Promise<void> {
    if (!confirm(`"${e.nome}" torna automatico: le modifiche a mano si perdono e le foto si raggruppano di nuovo da sole (entro pochi minuti).`)) {
      return Promise.resolve();
    }
    return this.esegui(() => this.api.sciogliEvento(e.id), 'Evento tornato automatico').then(() => {});
  }

  protected unisci(): Promise<void> {
    const ids = [...this.scelti()];
    if (ids.length < 2 || !confirm(`Unire ${ids.length} eventi in uno solo?`)) return Promise.resolve();
    return this.esegui(() => this.api.unisciEventi(ids), 'Eventi uniti').then(() => this.deseleziona());
  }

  // ------------------------------------------------------------ eventi per date

  protected nuovoPerDate(): void {
    this.modulo.set({ nome: '', dal: '', al: '' });
  }

  protected cambiaPerDate(e: Evento): void {
    this.modulo.set({ id: e.id, nome: e.nome, dal: e.dal ?? '', al: e.al ?? '' });
  }

  protected scrivi(campo: keyof Omit<ModuloPerDate, 'id'>, valore: string): void {
    this.modulo.update((m) => (m ? { ...m, [campo]: valore } : m));
  }

  /** Cosa manca o non va nel modulo, null se si può salvare. */
  protected readonly problemaModulo = computed(() => {
    const m = this.modulo();
    if (!m) return null;
    if (!m.nome.trim()) return 'Scrivi un nome';
    if (!m.dal || !m.al) return 'Scegli il primo e l\'ultimo giorno';
    if (m.dal > m.al) return 'Il primo giorno viene dopo l\'ultimo';
    return null;
  });

  protected async salvaPerDate(): Promise<void> {
    const m = this.modulo();
    if (!m || this.problemaModulo()) return;
    const nome = m.nome.trim();
    const riuscita = await this.esegui(
      () => (m.id ? this.api.modificaEventoPerDate(m.id, nome, m.dal, m.al) : this.api.creaEventoPerDate(nome, m.dal, m.al)),
      m.id ? 'Evento cambiato' : `Evento "${nome}" creato`,
    );
    if (riuscita) this.modulo.set(null);
  }

  protected eliminaPerDate(e: Evento): Promise<void> {
    if (!confirm(`Eliminare "${e.nome}"? Le foto restano: tornano agli eventi automatici (entro pochi minuti).`)) {
      return Promise.resolve();
    }
    return this.esegui(() => this.api.eliminaEventoPerDate(e.id), 'Evento eliminato').then(() => {});
  }

  protected async calcola(): Promise<void> {
    const interrotto = this.elenco()?.calcolo?.interrotto;
    const daCapo = !interrotto && !!this.elenco()?.calcolo;
    if (daCapo && !confirm('Rifare gli eventi automatici di tutto l\'archivio? Quelli toccati a mano restano come sono.')) {
      return;
    }
    try {
      await this.galleria.avviaEventi(daCapo);
      this.chiudi.emit();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Calcolo degli eventi non partito');
    }
  }

  /** True se è riuscita. */
  private async esegui(chiamata: () => Observable<unknown>, testo: string): Promise<boolean> {
    this.occupato.set(true);
    try {
      await firstValueFrom(chiamata());
      this.galleria.avvisa(testo);
      await this.carica();
      return true;
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Non riuscito');
      return false;
    } finally {
      this.occupato.set(false);
    }
  }

  // ------------------------------------------------------------ formati

  protected stessoGiorno(e: Evento): boolean {
    return e.dal ? e.dal === e.al : e.primoGiorno === e.ultimoGiorno;
  }

  /** Un giorno "2019-08-10" come ora locale, per DatePipe (senza ora sarebbe UTC). */
  protected locale(giorno: string): string {
    return giorno + 'T00:00:00';
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
