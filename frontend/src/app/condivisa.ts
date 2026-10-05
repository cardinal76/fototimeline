import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, signal } from '@angular/core';

import { durata } from './formati';
import { FotoCondivisa, GalleriaCondivisa } from './modelli';

/**
 * La pagina pubblica di un link di condivisione (/c/<token>): solo la
 * galleria, senza menu né login. main.ts la avvia al posto dell'app, così
 * non parte nessuna chiamata alle API private (e quindi nessun redirect a Keycloak).
 */
@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe],
  host: { '(document:keydown)': 'tasto($event)' },
  templateUrl: './condivisa.html',
  styleUrl: './condivisa.css',
})
export class Condivisa {
  private readonly token = decodeURIComponent(location.pathname.split('/')[2] ?? '');
  private readonly base = `/api/condivise/${encodeURIComponent(this.token)}`;

  protected readonly galleria = signal<GalleriaCondivisa | null>(null);
  protected readonly errore = signal<string | null>(null);
  protected readonly aperta = signal<number | null>(null);
  /** La vista della foto aperta non è arrivata (cloud smontato): resta la miniatura. */
  protected readonly vistaMancante = signal(false);
  protected readonly caricata = signal(false);
  protected readonly zip = `${this.base}/zip`;

  protected readonly foto = computed(() => {
    const i = this.aperta();
    return i === null ? null : (this.galleria()?.foto[i] ?? null);
  });

  private inizioTocco: { x: number; y: number } | null = null;

  constructor() {
    void this.carica();
    effect(() => {
      this.foto();
      this.vistaMancante.set(false);
      this.caricata.set(false);
    });
  }

  private async carica(): Promise<void> {
    try {
      const r = await fetch(this.base, { credentials: 'omit' });
      if (r.ok) {
        const g = (await r.json()) as GalleriaCondivisa;
        this.galleria.set(g);
        document.title = g.titolo;
        return;
      }
      const dettaglio = ((await r.json().catch(() => ({}))) as { detail?: string }).detail;
      this.errore.set(
        r.status === 410 && dettaglio
          ? dettaglio
          : r.status === 429
            ? 'Troppe richieste: riprova fra qualche minuto.'
            : 'Questo link non esiste. Controlla di averlo copiato per intero.',
      );
    } catch {
      this.errore.set('Il server non risponde: riprova più tardi.');
    }
  }

  protected miniatura(f: FotoCondivisa): string {
    return `${this.base}/foto/${f.id}/miniatura`;
  }

  protected vista(f: FotoCondivisa): string {
    return `${this.base}/foto/${f.id}/${f.video ? 'video' : 'vista'}`;
  }

  protected originale(f: FotoCondivisa): string {
    return `${this.base}/foto/${f.id}/originale`;
  }

  protected rapporto(f: FotoCondivisa): number {
    return f.larghezza && f.altezza ? Math.min(Math.max(f.larghezza / f.altezza, 0.4), 3) : 1.5;
  }

  protected durata(secondi?: number): string {
    return durata(secondi);
  }

  protected mappa(f: FotoCondivisa): string | null {
    return f.latitudine != null && f.longitudine != null
      ? `https://www.openstreetmap.org/?mlat=${f.latitudine}&mlon=${f.longitudine}#map=16/${f.latitudine}/${f.longitudine}`
      : null;
  }

  protected vai(passo: number): void {
    const i = this.aperta();
    const totale = this.galleria()?.foto.length ?? 0;
    if (i !== null && i + passo >= 0 && i + passo < totale) {
      this.aperta.set(i + passo);
    }
  }

  protected tasto(e: KeyboardEvent): void {
    if (this.aperta() === null) return;
    if (e.key === 'Escape') this.aperta.set(null);
    else if (e.key === 'ArrowLeft') this.vai(-1);
    else if (e.key === 'ArrowRight') this.vai(1);
    else return;
    e.preventDefault();
  }

  protected tocco(e: TouchEvent): void {
    const t = e.changedTouches[0];
    this.inizioTocco = t ? { x: t.clientX, y: t.clientY } : null;
  }

  /** Scorrimento col dito: orizzontale cambia foto, verso il basso chiude. */
  protected rilascio(e: TouchEvent): void {
    const t = e.changedTouches[0];
    const inizio = this.inizioTocco;
    this.inizioTocco = null;
    if (!t || !inizio) return;
    const dx = t.clientX - inizio.x;
    const dy = t.clientY - inizio.y;
    if (Math.abs(dx) > 50 && Math.abs(dx) > Math.abs(dy)) {
      this.vai(dx < 0 ? 1 : -1);
    } else if (dy > 90 && Math.abs(dy) > Math.abs(dx)) {
      this.aperta.set(null);
    }
  }
}
