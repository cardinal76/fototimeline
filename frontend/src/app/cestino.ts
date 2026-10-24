import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { VoceCestino } from './modelli';

/** Foto chieste al server per volta. */
const DIMENSIONE = 60;

/**
 * Il cestino: le foto eliminate (dal visore, dalla selezione, dalle quasi
 * uguali), con quando sono state eliminate e quanti giorni restano prima che
 * se ne vadano da sole. Si ripristinano o si eliminano per sempre, una alla
 * volta o selezionate; "Svuota cestino" le elimina tutte. Col cloud smontato
 * si guarda soltanto. Esc lo chiude (App).
 */
@Component({
  selector: 'app-cestino',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './cestino.html',
  styleUrl: './cestino.css',
})
export class Cestino {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  /** Col cloud smontato non si ripristina né si elimina, come l'eliminazione. */
  readonly disponibile = input(true);
  readonly chiudi = output<void>();

  protected readonly foto = signal<VoceCestino[]>([]);
  protected readonly totale = signal(0);
  protected readonly giorni = signal(30);
  protected readonly altre = signal(false);
  protected readonly caricando = signal(true);
  protected readonly occupato = signal(false);
  protected readonly scelte = signal<ReadonlySet<string>>(new Set());
  protected readonly tutteScelte = computed(() => this.foto().length > 0 && this.foto().every((f) => this.scelte().has(f.id)));

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');
  private pagina = 0;

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    void this.carica(true);
  }

  // ------------------------------------------------------------ elenco

  protected async carica(daCapo = false): Promise<void> {
    this.caricando.set(true);
    try {
      const pagina = daCapo ? 0 : this.pagina + 1;
      const p = await firstValueFrom(this.api.cestino(pagina, DIMENSIONE));
      this.pagina = pagina;
      this.foto.update((prima) => (daCapo ? p.foto : [...prima, ...p.foto]));
      this.totale.set(p.totale);
      this.giorni.set(p.giorni);
      this.altre.set(p.altre);
      if (daCapo) {
        const ci = new Set(p.foto.map((f) => f.id));
        this.scelte.update((s) => new Set([...s].filter((id) => ci.has(id))));
      }
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Cestino non leggibile');
    } finally {
      this.caricando.set(false);
    }
  }

  protected commuta(f: VoceCestino): void {
    this.scelte.update((s) => {
      const n = new Set(s);
      if (!n.delete(f.id)) {
        n.add(f.id);
      }
      return n;
    });
  }

  protected commutaTutte(): void {
    this.scelte.set(this.tutteScelte() ? new Set() : new Set(this.foto().map((f) => f.id)));
  }

  // ------------------------------------------------------------ azioni

  protected ripristina(ids: string[]): Promise<void> {
    return this.esegui(ids, () => this.api.ripristina(ids), (n) => `${n} foto ripristinate`, true);
  }

  protected eliminaPerSempre(ids: string[], nome?: string): Promise<void> {
    const domanda = nome
      ? `Eliminare per sempre "${nome}"? Il file viene cancellato dal cloud e non si potrà più ripristinare.`
      : `Eliminare per sempre ${ids.length} foto? I file vengono cancellati dal cloud e non si potranno più ripristinare.`;
    if (!confirm(domanda)) {
      return Promise.resolve();
    }
    return this.esegui(ids, () => this.api.eliminaPerSempre(ids), (n) => `${n} foto eliminate per sempre`, false);
  }

  protected async svuota(): Promise<void> {
    const n = this.totale();
    if (!n || !confirm(`Svuotare il cestino? ${n} foto vengono eliminate per sempre, file compresi.`)) {
      return;
    }
    this.occupato.set(true);
    try {
      const { fatte } = await firstValueFrom(this.api.svuotaCestino());
      this.galleria.avvisa(`Cestino svuotato: ${fatte} foto eliminate per sempre`);
      this.scelte.set(new Set());
      await this.carica(true);
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Cestino non svuotato');
    } finally {
      this.occupato.set(false);
    }
  }

  private async esegui(
    ids: string[],
    chiamata: () => ReturnType<FotoApi['ripristina']>,
    testo: (n: number) => string,
    ricaricaTimeline: boolean,
  ): Promise<void> {
    if (!ids.length) return;
    this.occupato.set(true);
    try {
      const { fatte } = await firstValueFrom(chiamata());
      this.galleria.avvisa(testo(fatte));
      const via = new Set(ids);
      this.foto.update((lista) => lista.filter((f) => !via.has(f.id)));
      this.totale.update((t) => Math.max(0, t - fatte));
      this.scelte.update((s) => new Set([...s].filter((id) => !via.has(id))));
      if (ricaricaTimeline) {
        this.galleria.ricarica();
      }
      if (!this.foto().length && this.totale()) {
        await this.carica(true);
      }
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Non riuscito');
    } finally {
      this.occupato.set(false);
    }
  }

  protected scelteIds(): string[] {
    return [...this.scelte()];
  }

  // ------------------------------------------------------------ formati

  protected rimasti(f: VoceCestino): string {
    if (f.giorniRimasti == null) return 'resta finché non la elimini';
    if (f.giorniRimasti === 0) return 'si elimina oggi';
    return f.giorniRimasti === 1 ? 'ancora 1 giorno' : `ancora ${f.giorniRimasti} giorni`;
  }

  protected rapporto(f: VoceCestino): number {
    return f.larghezza && f.altezza ? Math.min(Math.max(f.larghezza / f.altezza, 0.5), 2) : 1.33;
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
