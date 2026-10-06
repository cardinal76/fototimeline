import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnDestroy, OnInit, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { CollegamentoGoogle, ModificaTakeout, SceltaGoogle, StatoTakeout, Utente, ZipTakeout } from './modelli';

/**
 * "Google Foto": per tutti "Scegli da Google Foto" (Picker, col proprio
 * account Google), per l'amministratore anche "Importa da Google Takeout"
 * (tutta la libreria dagli zip su Drive). Esc lo chiude (App).
 */
@Component({
  selector: 'app-google-foto',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, FormsModule],
  templateUrl: './google-foto.html',
  styleUrl: './google-foto.css',
})
export class GoogleFoto implements OnInit, OnDestroy {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

  readonly admin = input(false);
  /** Col login acceso, per scegliere a chi dare le foto del Takeout. */
  readonly utenti = input<Utente[]>([]);
  readonly chiudi = output<void>();
  /** Il collegamento è cambiato (collegato/scollegato): App aggiorna il bottone. */
  readonly cambiato = output<CollegamentoGoogle>();

  protected readonly google = signal<CollegamentoGoogle | null>(null);
  protected readonly scelta = signal<SceltaGoogle | null>(null);
  protected readonly occupato = signal(false);
  protected readonly takeout = signal<StatoTakeout | null>(null);
  protected readonly salvando = signal(false);
  protected modulo: ModificaTakeout = { sorgente: 'gdrive:Takeout', proprietario: '', giorniPrimaDiCancellare: 0 };

  private sceltaTimer?: ReturnType<typeof setTimeout>;
  private takeoutTimer?: ReturnType<typeof setTimeout>;
  /** Le foto nuove si vedono nella timeline solo quando la scelta finisce. */
  private sceltaInCorso = false;

  ngOnInit(): void {
    this.aggiornaGoogle();
    if (this.admin()) {
      this.aggiornaTakeout(true);
    }
  }

  ngOnDestroy(): void {
    clearTimeout(this.sceltaTimer);
    clearTimeout(this.takeoutTimer);
  }

  // ------------------------------------------------------------ Scegli da Google Foto

  private aggiornaGoogle(): void {
    this.api.google().subscribe({
      next: (g) => {
        this.google.set(g);
        this.mostraScelta(g.scelta ?? null);
      },
      error: () => this.google.set({ configurato: false, collegato: false }),
    });
  }

  /** Al consenso di Google si va navigando: si torna all'app con ?google=collegato. */
  protected collega(): void {
    location.assign('/api/google/collega');
  }

  protected async scollega(): Promise<void> {
    if (!confirm("Scollegare Google? L'app non potrà più leggere le foto che scegli finché non lo ricolleghi.")) {
      return;
    }
    try {
      await firstValueFrom(this.api.scollegaGoogle());
      this.galleria.avvisa('Google scollegato');
      const g = { configurato: true, collegato: false };
      this.google.set(g);
      this.cambiato.emit(g);
    } catch {
      this.galleria.avvisa('Non riesco a scollegare Google');
    }
  }

  /**
   * "Scegli foto": la finestra si apre subito (dentro il clic, così il
   * browser non la blocca) e prende l'indirizzo di Google appena arriva.
   */
  protected async scegli(): Promise<void> {
    const finestra = window.open('', '_blank');
    this.occupato.set(true);
    try {
      const s = await firstValueFrom(this.api.nuovaSceltaGoogle());
      if (finestra) {
        finestra.location.href = s.pickerUri;
      } else {
        window.open(s.pickerUri, '_blank');
      }
      this.mostraScelta(s);
    } catch (e: unknown) {
      finestra?.close();
      this.galleria.avvisa(dettaglio(e) ?? 'Google Foto non risponde');
      this.aggiornaGoogle();
    } finally {
      this.occupato.set(false);
    }
  }

  protected riapri(s: SceltaGoogle): void {
    window.open(s.pickerUri, '_blank');
  }

  protected async annullaScelta(): Promise<void> {
    await firstValueFrom(this.api.annullaSceltaGoogle()).catch(() => undefined);
    this.seguiScelta();
  }

  protected async chiudiScelta(): Promise<void> {
    await firstValueFrom(this.api.chiudiSceltaGoogle()).catch(() => undefined);
    this.scelta.set(null);
  }

  private mostraScelta(s: SceltaGoogle | null): void {
    this.scelta.set(s);
    clearTimeout(this.sceltaTimer);
    if (s?.inCorso) {
      this.sceltaInCorso = true;
      this.sceltaTimer = setTimeout(() => this.seguiScelta(), s.fase === 'ATTESA_SCELTA' ? 3000 : 1500);
    } else if (s && this.sceltaInCorso) {
      this.sceltaInCorso = false;
      if (s.nuove) {
        this.galleria.ricarica();
      }
    }
  }

  private seguiScelta(): void {
    this.api.sceltaGoogle().subscribe({
      next: (s) => this.mostraScelta(s),
      error: () => (this.sceltaTimer = setTimeout(() => this.seguiScelta(), 5000)),
    });
  }

  protected percentualeScelta(s: SceltaGoogle): number {
    return s.totali ? Math.round((s.fatte / s.totali) * 100) : 0;
  }

  // ------------------------------------------------------------ Importa da Google Takeout

  private aggiornaTakeout(prima = false): void {
    clearTimeout(this.takeoutTimer);
    this.api.takeout().subscribe({
      next: (t) => {
        const eraInCorso = this.takeout()?.lavoro?.inCorso;
        this.takeout.set(t);
        if (prima) {
          this.modulo = {
            sorgente: t.sorgente,
            proprietario: t.proprietario ?? '',
            giorniPrimaDiCancellare: t.giorniPrimaDiCancellare,
          };
        }
        if (t.lavoro?.inCorso) {
          this.takeoutTimer = setTimeout(() => this.aggiornaTakeout(), 2000);
        } else if (eraInCorso && t.lavoro?.nuove) {
          this.galleria.ricarica();
        }
      },
      error: (e: unknown) => {
        if (prima) {
          this.galleria.avvisa(dettaglio(e) ?? 'Stato del Takeout non leggibile');
        } else {
          this.takeoutTimer = setTimeout(() => this.aggiornaTakeout(), 5000);
        }
      },
    });
  }

  protected async salvaTakeout(): Promise<void> {
    this.salvando.set(true);
    try {
      this.takeout.set(await firstValueFrom(this.api.salvaTakeout(this.modulo)));
      this.galleria.avvisa('Impostazioni del Takeout salvate');
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Impostazioni non salvate');
    } finally {
      this.salvando.set(false);
    }
  }

  protected async avviaTakeout(): Promise<void> {
    const t = this.takeout();
    if (
      !confirm(
        `Importa nell'archivio le foto degli zip di Google Takeout in ${t?.sorgente ?? 'Drive'}, uno alla volta. ` +
          'Può richiedere molte ore; puoi chiudere la pagina, va avanti sul server.',
      )
    ) {
      return;
    }
    try {
      await firstValueFrom(this.api.avviaTakeout());
      this.aggiornaTakeout();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Importazione non partita');
    }
  }

  protected async annullaTakeout(): Promise<void> {
    await firstValueFrom(this.api.annullaTakeout()).catch(() => undefined);
    this.aggiornaTakeout();
  }

  protected async riprova(z: ZipTakeout): Promise<void> {
    if (!confirm(`Rifare ${z.nome}? Si riscarica da Drive; le foto già entrate si saltano.`)) {
      return;
    }
    try {
      await firstValueFrom(this.api.riprovaZip(z.id));
      this.galleria.avvisa(`${z.nome}: si rifà al prossimo Avvia`);
      this.aggiornaTakeout();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Non riesco');
    }
  }

  protected percentualeTakeout(t: StatoTakeout): number {
    const l = t.lavoro;
    if (!l) return 0;
    if (l.fase === 'SCARICO' && l.daScaricare) return Math.round((l.scaricati / l.daScaricare) * 100);
    return l.fileTotali ? Math.round((l.fileFatti / l.fileTotali) * 100) : 0;
  }

  protected gb(byte: number): string {
    return byte >= 1e9 ? `${(byte / 1e9).toLocaleString('it-IT', { maximumFractionDigits: 1 })} GB`
      : `${Math.round(byte / 1e6).toLocaleString('it-IT')} MB`;
  }

  protected nomeStatoZip(z: ZipTakeout): string {
    switch (z.stato) {
      case 'FATTO':
        return 'fatto';
      case 'CON_ERRORI':
        return 'con errori';
      case 'FALLITO':
        return 'non importato';
      default:
        return 'a metà';
    }
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
