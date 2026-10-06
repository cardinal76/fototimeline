import { DatePipe, DecimalPipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  computed,
  effect,
  inject,
  input,
  output,
  signal,
} from '@angular/core';
import { FormsModule } from '@angular/forms';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { durata } from './formati';
import { Foto } from './modelli';

interface Bozza {
  titolo: string;
  descrizione: string;
  tag: string;
  album: string;
  scattataIl: string;
  preferita: boolean;
}

const ORIGINI: Record<Foto['origineData'], string> = {
  EXIF: 'dai dati della fotocamera',
  FILE: 'dalla data del file',
  CARTELLA: "dalla cartella dell'archivio",
  CARICAMENTO: 'dal momento del caricamento',
  GOOGLE: 'da Google Foto',
  MANUALE: 'impostata a mano',
};

/** Dove si ricorda se il pannello dei dettagli resta aperto. */
const CHIAVE_DETTAGLI = 'fototimeline.visore.dettagli';
/** Dopo quanto, in schermo intero, i comandi spariscono se non ci si muove. */
const RIPOSO_MS = 3000;
/** Quanto deve scorrere il dito, in orizzontale, per cambiare foto. */
const SWIPE_PX = 50;

/** Foto a tutto schermo, con scorrimento, schermo intero, dettagli e modifica. */
@Component({
  selector: 'app-visore',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, FormsModule],
  host: {
    role: 'dialog',
    'aria-modal': 'true',
    '[attr.aria-label]': 'foto().titolo || foto().nomeOriginale',
    '(document:keydown)': 'tasto($event)',
    '(document:fullscreenchange)': 'cambioSchermoIntero()',
    '(document:webkitfullscreenchange)': 'cambioSchermoIntero()',
    '(pointermove)': 'risveglia()',
    '(pointerdown)': 'risveglia()',
    '[class.a-riposo]': 'aRiposo()',
  },
  templateUrl: './visore.html',
  styleUrl: './visore.css',
})
export class Visore {
  private readonly galleria = inject(Galleria);
  private readonly elemento = inject<ElementRef<HTMLElement>>(ElementRef);

  readonly foto = input.required<Foto>();
  readonly chiudi = output<void>();
  readonly cambia = output<Foto>();

  /** I dettagli si aprono a richiesta: di base si vede solo la foto. */
  protected readonly pannello = signal(dettagliAperti());
  protected readonly schermoIntero = signal(false);
  /** In schermo intero, senza movimento da un po': comandi nascosti. */
  protected readonly aRiposo = signal(false);
  /** Lo schermo intero non c'è su tutti i telefoni (iPhone): lì il pulsante non compare. */
  protected readonly puoSchermoIntero =
    typeof document !== 'undefined' &&
    !!(document.fullscreenEnabled || (document as DocumentoWebkit).webkitFullscreenEnabled);
  private timerRiposo?: ReturnType<typeof setTimeout>;
  private inizioTocco: { x: number; y: number } | null = null;
  protected readonly salvataggio = signal(false);
  protected readonly modificata = signal(false);
  protected readonly caricata = signal(false);
  /** Il browser non è riuscito a riprodurre il video. */
  protected readonly videoIllegibile = signal(false);
  protected bozza: Bozza = vuota();

  /** Le foto dalla "vista" (gli HEIC diventano JPEG), i video dalla versione compatibile se c'è. */
  protected readonly src = computed(() => (this.foto().video ? FotoApi.video(this.foto()) : FotoApi.vista(this.foto())));
  /** Un video che non tutti i browser riproducono e la cui versione compatibile non c'è ancora. */
  protected readonly senzaCompatibile = computed(() => {
    const f = this.foto();
    return f.video && f.compatibile === false && f.conversione !== 'FATTA';
  });
  protected readonly scarica = computed(() => FotoApi.originale(this.foto(), true));
  protected readonly anteprima = computed(() => FotoApi.miniatura(this.foto()));
  protected readonly origine = computed(() => ORIGINI[this.foto().origineData]);
  /** "Anna": il nome di chi l'ha portata; null per le foto di prima. */
  protected readonly caricataDa = computed(() => {
    const chi = this.foto().caricataDa;
    return chi ? this.galleria.nomeDi(chi) : null;
  });
  protected readonly indice = computed(() => this.galleria.foto().findIndex((f) => f.id === this.foto().id));
  protected readonly album = this.galleria.album;
  protected readonly tagNoti = this.galleria.tag;
  /** "Sperlonga, Lazio · Italia"; senza ripetere (Tokyo, Tokyo). */
  protected readonly luogo = computed(() => {
    const f = this.foto();
    if (!f.luogo) return null;
    const dove = f.regione && f.regione !== f.luogo ? `${f.luogo}, ${f.regione}` : f.luogo;
    return f.nazione ? `${dove} · ${f.nazione}` : dove;
  });
  protected readonly mappa = computed(() => {
    const f = this.foto();
    return f.latitudine != null && f.longitudine != null
      ? `https://www.openstreetmap.org/?mlat=${f.latitudine}&mlon=${f.longitudine}#map=16/${f.latitudine}/${f.longitudine}`
      : null;
  });

  constructor() {
    effect(() => {
      const f = this.foto();
      this.bozza = {
        titolo: f.titolo ?? '',
        descrizione: f.descrizione ?? '',
        tag: f.tag.join(', '),
        album: f.album ?? '',
        scattataIl: f.scattataIl.slice(0, 19),
        preferita: f.preferita,
      };
      this.modificata.set(false);
      this.caricata.set(false);
      this.videoIllegibile.set(false);
    });
    effect(() => {
      const aperto = this.pannello();
      try {
        localStorage.setItem(CHIAVE_DETTAGLI, aperto ? '1' : '0');
      } catch {
        // senza localStorage vale solo per questa visita
      }
    });
    effect(() => {
      if (this.schermoIntero()) {
        this.risveglia();
      } else {
        clearTimeout(this.timerRiposo);
        this.aRiposo.set(false);
      }
    });
    inject(DestroyRef).onDestroy(() => {
      clearTimeout(this.timerRiposo);
      if (document.fullscreenElement === this.elemento.nativeElement) {
        void document.exitFullscreen?.().catch(() => {});
      }
    });
  }

  protected dettagli(): void {
    this.pannello.update((p) => !p);
  }

  /** Schermo intero sul visore; dove non c'è (o il browser rifiuta) non succede nulla. */
  protected async alternaSchermoIntero(): Promise<void> {
    const d = document as DocumentoWebkit;
    try {
      if (document.fullscreenElement || d.webkitFullscreenElement) {
        await (document.exitFullscreen?.() ?? d.webkitExitFullscreen?.());
      } else {
        const el = this.elemento.nativeElement as ElementoWebkit;
        await (el.requestFullscreen?.() ?? el.webkitRequestFullscreen?.());
      }
    } catch {
      // schermo intero non disponibile: si resta come prima
    }
  }

  protected cambioSchermoIntero(): void {
    const d = document as DocumentoWebkit;
    this.schermoIntero.set(!!(document.fullscreenElement || d.webkitFullscreenElement));
  }

  /** Mouse o dito: i comandi tornano e, in schermo intero, ripartono i secondi. */
  protected risveglia(): void {
    this.aRiposo.set(false);
    clearTimeout(this.timerRiposo);
    if (this.schermoIntero()) {
      this.timerRiposo = setTimeout(() => this.aRiposo.set(true), RIPOSO_MS);
    }
  }

  /** Un clic sullo sfondo chiude; in schermo intero fa solo ricomparire i comandi. */
  protected sfondo(): void {
    if (!this.schermoIntero()) {
      this.chiudi.emit();
    }
  }

  protected toccoInizio(e: TouchEvent): void {
    const t = e.touches[0];
    this.inizioTocco =
      e.touches.length === 1 && !(e.target as HTMLElement).closest('video') ? { x: t.clientX, y: t.clientY } : null;
  }

  /** Scorrimento orizzontale del dito: foto precedente o successiva. */
  protected toccoFine(e: TouchEvent): void {
    const inizio = this.inizioTocco;
    this.inizioTocco = null;
    const t = e.changedTouches[0];
    if (!inizio || !t) return;
    const dx = t.clientX - inizio.x;
    const dy = t.clientY - inizio.y;
    if (Math.abs(dx) > SWIPE_PX && Math.abs(dx) > Math.abs(dy) * 1.5) {
      this.vai(dx < 0 ? 1 : -1);
    }
  }

  /** Dal luogo della foto al filtro della timeline. */
  protected filtraLuogo(): void {
    const f = this.foto();
    this.galleria.imposta({ nazione: f.codiceNazione, regione: f.regione, luogo: f.luogo });
    this.chiudi.emit();
  }

  protected vai(passo: number): void {
    const lista = this.galleria.foto();
    const i = this.indice() + passo;
    if (i >= 0 && i < lista.length) {
      this.cambia.emit(lista[i]);
      // Vicino alla fine: carica la pagina successiva per poter continuare.
      if (i > lista.length - 5) {
        this.galleria.prossimaPagina();
      }
    }
  }

  protected haPrecedente(): boolean {
    return this.indice() > 0;
  }

  protected haSuccessiva(): boolean {
    return this.indice() < this.galleria.foto().length - 1;
  }

  protected async salva(): Promise<void> {
    this.salvataggio.set(true);
    try {
      const b = this.bozza;
      const aggiornata = await this.galleria.salva(this.foto(), {
        titolo: b.titolo,
        descrizione: b.descrizione,
        tag: b.tag.split(','),
        album: b.album,
        preferita: b.preferita,
        scattataIl: b.scattataIl.length === 16 ? `${b.scattataIl}:00` : b.scattataIl,
      });
      this.cambia.emit(aggiornata);
      this.galleria.avvisa('Salvato');
    } catch {
      this.galleria.avvisa('Salvataggio non riuscito');
    } finally {
      this.salvataggio.set(false);
    }
  }

  protected async preferita(): Promise<void> {
    this.bozza.preferita = !this.foto().preferita;
    const f = this.foto();
    const aggiornata = await this.galleria.salva(f, {
      titolo: f.titolo,
      descrizione: f.descrizione,
      tag: f.tag,
      album: f.album,
      preferita: !f.preferita,
    });
    this.cambia.emit(aggiornata);
  }

  protected async elimina(): Promise<void> {
    if (!confirm(`Eliminare "${this.foto().nomeOriginale}" dall'archivio? Il file viene cancellato dal disco.`)) {
      return;
    }
    const lista = this.galleria.foto();
    const i = this.indice();
    const prossima = lista[i + 1] ?? lista[i - 1];
    await this.galleria.elimina(this.foto());
    if (prossima) {
      this.cambia.emit(prossima);
    } else {
      this.chiudi.emit();
    }
  }

  protected tasto(e: KeyboardEvent): void {
    const bersaglio = e.target as HTMLElement;
    this.risveglia();
    if (e.ctrlKey || e.metaKey || e.altKey) {
      return;
    }
    if (bersaglio.closest('input, textarea, select')) {
      if (e.key === 'Escape') {
        bersaglio.blur();
      }
      return;
    }
    switch (e.key) {
      case 'Escape':
        this.chiudi.emit();
        break;
      case 'ArrowLeft':
        this.vai(-1);
        break;
      case 'ArrowRight':
        this.vai(1);
        break;
      case 'i':
        this.dettagli();
        break;
      case 'f':
        void this.alternaSchermoIntero();
        break;
      case 'p':
        void this.preferita();
        break;
      default:
        return;
    }
    e.preventDefault();
  }

  protected megapixel(f: Foto): number | null {
    return f.larghezza && f.altezza ? (f.larghezza * f.altezza) / 1_000_000 : null;
  }

  protected durata(secondi?: number): string {
    return durata(secondi);
  }

  protected peso(byte: number): string {
    return byte > 1_048_576 ? `${(byte / 1_048_576).toFixed(1)} MB` : `${Math.round(byte / 1024)} KB`;
  }
}

interface DocumentoWebkit extends Document {
  webkitFullscreenEnabled?: boolean;
  webkitFullscreenElement?: Element | null;
  webkitExitFullscreen?: () => Promise<void> | void;
}

interface ElementoWebkit extends HTMLElement {
  webkitRequestFullscreen?: () => Promise<void> | void;
}

function dettagliAperti(): boolean {
  try {
    return localStorage.getItem(CHIAVE_DETTAGLI) === '1';
  } catch {
    return false;
  }
}

function vuota(): Bozza {
  return { titolo: '', descrizione: '', tag: '', album: '', scattataIl: '', preferita: false };
}
