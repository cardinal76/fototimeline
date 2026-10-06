import { DecimalPipe } from '@angular/common';
import { HttpErrorResponse, HttpEventType } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnDestroy, computed, inject, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { FileCondiviso, comeFile, inAttesa, togli } from './condivisi-in-attesa';
import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Caricamento } from './modelli';

type Stato = 'attesa' | 'invio' | 'nuova' | 'gia' | 'errore';

interface Voce {
  f: FileCondiviso;
  /** Miniatura locale (URL.createObjectURL), null per quello che il browser non sa mostrare (HEIC). */
  anteprima: string | null;
  video: boolean;
  stato: Stato;
  messaggio?: string;
  /** Errore d'invio (non del file): è ancora in attesa sul telefono e si può riprovare. */
  daRiprovare?: boolean;
}

/**
 * "Carica N foto dal telefono": i file arrivati con "Condividi → FotoTimeline"
 * (li tiene il service worker in IndexedDB). Si caricano uno alla volta con
 * POST /api/foto, come "Carica foto": la rete del telefono non si satura e
 * quelli già arrivati si tolgono subito, così un'interruzione non perde niente.
 */
@Component({
  selector: 'app-ricevi-condivisi',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DecimalPipe, FormsModule],
  host: { '(document:keydown.escape)': 'esc()' },
  templateUrl: './ricevi-condivisi.html',
  styleUrl: './ricevi-condivisi.css',
})
export class RiceviCondivisi implements OnDestroy {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  readonly chiudi = output<void>();

  protected readonly voci = signal<Voce[]>([]);
  protected readonly fase = signal<'leggo' | 'scelta' | 'invio' | 'finito'>('leggo');
  /** Avanzamento in byte del file in invio, 0..1. */
  protected readonly inviato = signal(0);
  /** Perché l'invio si è fermato (cloud smontato, rete): i file restano in attesa. */
  protected readonly fermato = signal<string | null>(null);
  protected album = '';

  protected readonly totale = computed(() => this.voci().length);
  protected readonly peso = computed(() => this.voci().reduce((s, v) => s + v.f.dimensione, 0));
  protected readonly fatti = computed(() => this.voci().filter((v) => v.stato !== 'attesa' && v.stato !== 'invio').length);
  protected readonly nuove = computed(() => this.voci().filter((v) => v.stato === 'nuova').length);
  protected readonly gia = computed(() => this.voci().filter((v) => v.stato === 'gia').length);
  protected readonly errori = computed(() => this.voci().filter((v) => v.stato === 'errore'));
  /** Quelli ancora sul telefono, da riprovare. */
  protected readonly rimasti = computed(
    () => this.voci().filter((v) => v.stato === 'attesa' || v.stato === 'invio' || v.daRiprovare).length,
  );
  protected readonly percentuale = computed(() =>
    this.totale() ? Math.round(((this.fatti() + this.inviato()) / this.totale()) * 100) : 0,
  );

  constructor() {
    void this.leggi();
  }

  private async leggi(): Promise<void> {
    try {
      const file = await inAttesa();
      this.voci.set(
        file.map((f) => {
          const video = f.tipo.startsWith('video/');
          const mostrabile = video || (f.tipo.startsWith('image/') && !/hei[cf]/i.test(f.tipo));
          return { f, video, anteprima: mostrabile ? URL.createObjectURL(f.file) : null, stato: 'attesa' };
        }),
      );
    } catch {
      this.galleria.avvisa('Non riesco a leggere le foto condivise');
    }
    if (!this.voci().length) {
      this.chiudi.emit();
      return;
    }
    this.fase.set('scelta');
  }

  /** Esc come "Più tardi" (i file restano in attesa), ma non a metà invio. */
  protected esc(): void {
    if (this.fase() !== 'invio') {
      this.chiudi.emit();
    }
  }

  ngOnDestroy(): void {
    this.voci().forEach((v) => v.anteprima && URL.revokeObjectURL(v.anteprima));
  }

  protected async carica(): Promise<void> {
    this.fase.set('invio');
    this.fermato.set(null);
    const album = this.album.trim() || undefined;
    for (const v of this.voci()) {
      if (v.stato !== 'attesa') continue;
      this.aggiorna(v, { stato: 'invio', daRiprovare: false });
      this.inviato.set(0);
      try {
        const esito = await this.invia(v.f, album);
        // Arrivato (anche se doppione o rifiutato dal server, che non cambierebbe idea): fuori dall'attesa.
        await togli([v.f.id]);
        this.aggiorna(v, {
          stato: esito.esito === 'CARICATA' ? 'nuova' : esito.esito === 'DUPLICATA' ? 'gia' : 'errore',
          messaggio: esito.messaggio,
        });
      } catch (e) {
        const stato = e instanceof HttpErrorResponse ? e.status : -1;
        if (stato === 503 || stato === 0 || stato === 401) {
          // Si riprova dopo: questo e i successivi restano sul telefono.
          this.aggiorna(v, { stato: 'attesa' });
          this.fermato.set(
            stato === 503
              ? 'Il cloud è smontato: le foto non si possono salvare adesso.'
              : stato === 0
                ? 'Connessione persa.'
                : 'La sessione è scaduta.',
          );
          break;
        }
        // Un errore di quel file (troppo grande, illeggibile...): resta in attesa per un altro tentativo.
        this.aggiorna(v, { stato: 'errore', messaggio: dettaglio(e) ?? 'invio non riuscito', daRiprovare: true });
      }
    }
    this.inviato.set(0);
    this.fase.set('finito');
    if (this.nuove()) {
      this.galleria.ricarica();
      this.galleria.aggiornaLuoghi();
    }
  }

  /** Di nuovo quelli rimasti e quelli finiti in errore per un problema d'invio (sono ancora sul telefono). */
  protected async riprova(): Promise<void> {
    this.voci.update((l) =>
      l.map((v) => (v.daRiprovare ? { ...v, stato: 'attesa' as Stato, messaggio: undefined, daRiprovare: false } : v)),
    );
    await this.carica();
  }

  /** "Annulla" o "Lascia stare": i file non caricati si tolgono dal telefono (quelli arrivati sono già tolti). */
  protected async scarta(): Promise<void> {
    try {
      await togli(this.voci().map((v) => v.f.id));
    } finally {
      this.chiudi.emit();
    }
  }

  private invia(f: FileCondiviso, album?: string): Promise<Caricamento> {
    return new Promise((risolvi, rifiuta) => {
      this.api.carica([comeFile(f)], album).subscribe({
        next: (evento) => {
          if (evento.type === HttpEventType.UploadProgress && evento.total) {
            this.inviato.set(evento.loaded / evento.total);
          } else if (evento.type === HttpEventType.Response) {
            const esito = evento.body?.[0];
            if (esito) {
              risolvi(esito);
            } else {
              rifiuta(new Error('risposta vuota'));
            }
          }
        },
        error: rifiuta,
      });
    });
  }

  private aggiorna(v: Voce, modifica: Partial<Voce>): void {
    Object.assign(v, modifica);
    this.voci.update((l) => [...l]);
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
