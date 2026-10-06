import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  OnDestroy,
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
import { FotoSimile, GruppoSimili, StatoImpronte } from './modelli';

/** Gruppi chiesti al server per volta. */
const DIMENSIONE = 20;

/**
 * "Quasi uguali": un gruppo di foto simili alla volta (raffiche, copie
 * ricompresse o ridimensionate). Si sceglie quali tenere e si tolgono le
 * altre, oppure si dice che non sono doppioni, o si salta. Esc lo chiude (App).
 */
@Component({
  selector: 'app-quasi-uguali',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  templateUrl: './quasi-uguali.html',
  styleUrl: './quasi-uguali.css',
})
export class QuasiUguali implements OnDestroy {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  readonly admin = input(false);
  /** Col cloud smontato non si elimina (gli originali non si raggiungono). */
  readonly disponibile = input(true);
  readonly chiudi = output<void>();

  /** I gruppi ancora da vedere, il primo è quello mostrato. */
  protected readonly coda = signal<GruppoSimili[]>([]);
  protected readonly totale = signal(0);
  protected readonly caricando = signal(true);
  protected readonly occupato = signal(false);
  /** Per il gruppo mostrato: id → da tenere. */
  protected readonly tieni = signal<ReadonlyMap<string, boolean>>(new Map());
  protected readonly impronte = signal<StatoImpronte | null>(null);

  protected readonly gruppo = computed(() => this.coda()[0] ?? null);
  protected readonly daTogliere = computed(() => this.gruppo()?.foto.filter((f) => !this.tieni().get(f.id)) ?? []);
  protected readonly daTenere = computed(() => this.gruppo()?.foto.filter((f) => this.tieni().get(f.id)) ?? []);
  /** Quanti gruppi sono stati saltati: il mostrato è il successivo. */
  private readonly quantiSaltati = signal(0);
  protected readonly posizione = computed(() => this.quantiSaltati() + 1);

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');
  /** Gruppi saltati: restano in testa all'elenco del server, si chiedono le pagine dopo. */
  private readonly saltati = new Set<string>();
  private altre = false;
  private improntaTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    void this.carica();
    this.seguiImpronte();
  }

  ngOnDestroy(): void {
    clearTimeout(this.improntaTimer);
  }

  // ------------------------------------------------------------ gruppi

  private async carica(): Promise<void> {
    this.caricando.set(true);
    try {
      let pagina = Math.floor(this.saltati.size / DIMENSIONE);
      for (;;) {
        const p = await firstValueFrom(this.api.quasiUguali(pagina, DIMENSIONE));
        this.totale.set(p.totale);
        this.altre = p.altre;
        const nuovi = p.gruppi.filter((g) => !this.saltati.has(chiave(g)));
        if (nuovi.length || !p.altre) {
          this.coda.set(nuovi);
          break;
        }
        pagina++;
      }
      this.prepara();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Foto quasi uguali non leggibili');
    } finally {
      this.caricando.set(false);
    }
  }

  /** Le scelte iniziali del gruppo mostrato: tenere la suggerita e le preferite. */
  private prepara(): void {
    this.tieni.set(new Map(this.gruppo()?.foto.map((f) => [f.id, f.tieni]) ?? []));
  }

  private avanti(): void {
    this.coda.update((c) => c.slice(1));
    if (!this.coda().length && (this.altre || this.saltati.size < this.totale())) {
      void this.carica();
    } else {
      this.prepara();
    }
  }

  protected commuta(f: FotoSimile): void {
    const tenuta = !!this.tieni().get(f.id);
    if (tenuta && f.preferita) {
      this.galleria.avvisa('È tra le preferite: toglila dalle preferite per eliminarla');
      return;
    }
    this.tieni.update((m) => new Map(m).set(f.id, !tenuta));
  }

  protected async togliAltre(): Promise<void> {
    const togli = this.daTogliere().map((f) => f.id);
    const tieni = this.daTenere().map((f) => f.id);
    if (!togli.length || !tieni.length) return;
    if (!confirm(`Eliminare ${togli.length} foto? I file vengono cancellati dal disco.`)) {
      return;
    }
    this.occupato.set(true);
    try {
      const { eliminate } = await firstValueFrom(this.api.risolvi(tieni, togli));
      this.galleria.avvisa(`${eliminate} foto eliminate`);
      this.galleria.ricarica();
      this.totale.update((t) => t - 1);
      this.avanti();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Eliminazione non riuscita');
    } finally {
      this.occupato.set(false);
    }
  }

  protected async nonDoppioni(): Promise<void> {
    const g = this.gruppo();
    if (!g) return;
    this.occupato.set(true);
    try {
      await firstValueFrom(this.api.ignora(g.foto.map((f) => f.id)));
      this.totale.update((t) => t - 1);
      this.avanti();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Non riuscito');
    } finally {
      this.occupato.set(false);
    }
  }

  protected salta(): void {
    const g = this.gruppo();
    if (g) {
      this.saltati.add(chiave(g));
      this.quantiSaltati.set(this.saltati.size);
      this.avanti();
    }
  }

  // ------------------------------------------------------------ impronte

  protected async calcolaImpronte(): Promise<void> {
    try {
      this.impronte.set(await firstValueFrom(this.api.calcolaImpronte()));
      this.seguiImpronte();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Calcolo delle impronte non partito');
    }
  }

  protected annullaImpronte(): void {
    this.api.annullaImpronte().subscribe(() => this.seguiImpronte());
  }

  protected percentualeImpronte(): number {
    const s = this.impronte();
    return s && s.totale ? Math.round((s.fatte / s.totale) * 100) : 0;
  }

  /** Lo stato del calcolo: ogni secondo mentre è in corso; alla fine i gruppi si rileggono. */
  private seguiImpronte(): void {
    clearTimeout(this.improntaTimer);
    this.api.statoImpronte().subscribe({
      next: (s) => {
        const prima = this.impronte();
        this.impronte.set(s);
        if (s.stato === 'IN_CORSO') {
          this.improntaTimer = setTimeout(() => this.seguiImpronte(), 1000);
        } else if (prima?.stato === 'IN_CORSO') {
          this.saltati.clear();
          this.quantiSaltati.set(0);
          void this.carica();
        }
      },
      error: () => {},
    });
  }

  // ------------------------------------------------------------ formati

  protected peso(byte: number): string {
    return byte > 1_048_576 ? `${(byte / 1_048_576).toFixed(1)} MB` : `${Math.round(byte / 1024)} KB`;
  }

  protected rapporto(f: FotoSimile): number {
    return f.larghezza && f.altezza ? Math.min(Math.max(f.larghezza / f.altezza, 0.5), 2) : 1.33;
  }
}

function chiave(g: GruppoSimili): string {
  return g.foto
    .map((f) => f.id)
    .sort()
    .join(',');
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
