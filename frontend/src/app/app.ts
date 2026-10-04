import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Cartella, Foto, Importazione } from './modelli';
import { esci } from './sessione';
import { TimelineNav } from './timeline-nav';
import { Visore } from './visore';

/** Altezza di riferimento delle righe della griglia, in pixel. */
const ALTEZZA_RIGA = 210;

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule, TimelineNav, Visore],
  host: {
    '(document:dragover)': 'trascina($event)',
    '(document:dragleave)': 'esci($event)',
    '(document:drop)': 'rilascia($event)',
  },
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly galleria = inject(Galleria);
  private readonly api = inject(FotoApi);

  protected readonly aperta = signal<Foto | null>(null);
  protected readonly selezione = signal(false);
  private readonly meseInCima = signal<string | null>(null);
  /** Mese visibile in cima alla lista, "yyyy-MM"; prima di scorrere è quello della prima foto. */
  protected readonly meseAttivo = computed(() => this.meseInCima() ?? this.galleria.foto()[0]?.giorno.slice(0, 7) ?? null);
  protected readonly trascinando = signal(false);
  protected readonly dialogoImporta = signal(false);
  protected readonly importazione = signal<Importazione | null>(null);
  protected readonly importando = signal(false);
  protected readonly cartellaAperta = signal<Cartella | null>(null);
  protected albumDaCartella = true;
  /** Sul server (cartella del cloud) si sposta, come deciso; sul PC si copia. */
  protected sposta = false;
  protected ricerca = '';

  private readonly scorrimento = viewChild.required<ElementRef<HTMLElement>>('scorrimento');
  private readonly fondo = viewChild.required<ElementRef<HTMLElement>>('fondo');
  private ricercaTimer?: ReturnType<typeof setTimeout>;
  private spiaInAttesa = false;

  constructor() {
    afterNextRender(() => {
      // Scorrimento infinito: quando il fondo si avvicina, la pagina dopo.
      new IntersectionObserver(
        (voci) => voci.some((v) => v.isIntersecting) && this.galleria.prossimaPagina(),
        { root: this.scorrimento().nativeElement, rootMargin: '1200px' },
      ).observe(this.fondo().nativeElement);
    });
  }

  // ------------------------------------------------------------ griglia

  protected rapporto(f: Foto): number {
    return f.larghezza && f.altezza ? Math.min(Math.max(f.larghezza / f.altezza, 0.4), 3) : 1.5;
  }

  protected base(f: Foto): number {
    return this.rapporto(f) * ALTEZZA_RIGA;
  }

  protected miniatura(f: Foto): string {
    return FotoApi.miniatura(f);
  }

  protected clic(f: Foto, e: MouseEvent): void {
    if (this.selezione() || e.ctrlKey || e.metaKey) {
      this.selezione.set(true);
      this.galleria.commuta(f.id);
    } else {
      this.aperta.set(f);
    }
  }

  /** Aggiorna il mese evidenziato nella timeline guardando quale giorno è in cima. */
  protected scorri(): void {
    if (this.spiaInAttesa) {
      return;
    }
    this.spiaInAttesa = true;
    requestAnimationFrame(() => {
      this.spiaInAttesa = false;
      const contenitore = this.scorrimento().nativeElement;
      const cima = contenitore.getBoundingClientRect().top + 80;
      let attivo: string | null = null;
      for (const el of Array.from(contenitore.querySelectorAll<HTMLElement>('[data-giorno]'))) {
        if (el.getBoundingClientRect().top > cima) {
          break;
        }
        attivo = el.dataset['giorno']!.slice(0, 7);
      }
      this.meseInCima.set(attivo);
    });
  }

  protected inizio(): void {
    this.scorrimento().nativeElement.scrollTo({ top: 0 });
  }

  // ------------------------------------------------------------ filtri

  protected cerca(): void {
    clearTimeout(this.ricercaTimer);
    this.ricercaTimer = setTimeout(() => this.galleria.imposta({ q: this.ricerca.trim() || undefined }), 300);
  }

  protected scegliTag(tag: string): void {
    this.galleria.imposta({ tag: tag || undefined });
  }

  protected scegliAlbum(album: string): void {
    this.galleria.imposta({ album: album || undefined });
  }

  protected azzera(): void {
    this.ricerca = '';
    this.galleria.filtro.set({});
    this.galleria.ricarica();
  }

  protected filtriAttivi(): boolean {
    const f = this.galleria.filtro();
    return !!(f.q || f.tag || f.album || f.preferite || f.al);
  }

  // ------------------------------------------------------------ selezione

  protected esciSelezione(): void {
    this.selezione.set(false);
    this.galleria.deseleziona();
  }

  protected async operazione(op: 'AGGIUNGI_TAG' | 'TOGLI_TAG' | 'IMPOSTA_ALBUM' | 'PREFERITA' | 'ELIMINA'): Promise<void> {
    const n = this.galleria.selezionate().size;
    let valore: string | undefined;
    if (op === 'AGGIUNGI_TAG' || op === 'TOGLI_TAG') {
      const r = prompt(op === 'AGGIUNGI_TAG' ? 'Tag da aggiungere (separati da virgola)' : 'Tag da togliere');
      if (!r?.trim()) return;
      valore = r;
    } else if (op === 'IMPOSTA_ALBUM') {
      const r = prompt(`Album per ${n} foto (vuoto per toglierle dall'album)`, this.galleria.filtro().album ?? '');
      if (r === null) return;
      valore = r;
    } else if (op === 'ELIMINA' && !confirm(`Eliminare ${n} foto? I file vengono cancellati dal disco.`)) {
      return;
    }
    try {
      await this.galleria.operazione(op, valore);
      this.selezione.set(false);
    } catch {
      this.galleria.avvisa('Operazione non riuscita');
    }
  }

  // ------------------------------------------------------------ caricamento

  protected scegliFile(input: HTMLInputElement): void {
    if (input.files?.length) {
      void this.galleria.carica(Array.from(input.files));
    }
    input.value = '';
  }

  protected trascina(e: DragEvent): void {
    if (e.dataTransfer?.types.includes('Files')) {
      e.preventDefault();
      this.trascinando.set(true);
    }
  }

  protected esci(e: DragEvent): void {
    // Solo quando si esce dalla finestra, non passando da un elemento all'altro.
    if (!e.relatedTarget) {
      this.trascinando.set(false);
    }
  }

  protected rilascia(e: DragEvent): void {
    e.preventDefault();
    this.trascinando.set(false);
    const file = Array.from(e.dataTransfer?.files ?? []);
    if (file.length) {
      void this.galleria.carica(file);
    }
  }

  protected percentuale(): number {
    const c = this.galleria.caricamento();
    if (!c) return 0;
    const inCorso = Math.min(6, c.totale - c.fatti) * c.gruppo;
    return Math.round(((c.fatti + inCorso) / c.totale) * 100);
  }

  // ------------------------------------------------------------ importazione

  protected apriImporta(): void {
    this.sposta = !!this.galleria.io()?.radiceImportazione;
    this.importazione.set(null);
    this.dialogoImporta.set(true);
    this.naviga();
  }

  protected naviga(percorso?: string): void {
    this.api.cartelle(percorso).subscribe({
      next: (c) => this.cartellaAperta.set(c),
      error: (e: unknown) => this.galleria.avvisa(dettaglio(e) ?? 'Cartella non leggibile'),
    });
  }

  protected entra(nome: string): void {
    const c = this.cartellaAperta();
    if (c) {
      this.naviga(`${c.percorso.replace(/[\\/]$/, '')}/${nome}`);
    }
  }

  protected async importa(): Promise<void> {
    const cartella = this.cartellaAperta()?.percorso;
    if (!cartella) return;
    if (this.sposta && !confirm(`Le foto di "${cartella}" verranno spostate nell'archivio e tolte da lì. Continuare?`)) {
      return;
    }
    this.importando.set(true);
    this.importazione.set(null);
    try {
      this.importazione.set(await firstValueFrom(this.api.importa(cartella, this.albumDaCartella, this.sposta)));
      this.galleria.ricarica();
      this.naviga(cartella);
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Importazione non riuscita');
    } finally {
      this.importando.set(false);
    }
  }

  protected chiudiImporta(): void {
    this.dialogoImporta.set(false);
    this.importazione.set(null);
  }

  protected logout(): void {
    esci();
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
