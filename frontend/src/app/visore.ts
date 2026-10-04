import { DatePipe, DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
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
  CARICAMENTO: 'dal momento del caricamento',
  MANUALE: 'impostata a mano',
};

/** Foto a schermo intero, con scorrimento, informazioni e modifica. */
@Component({
  selector: 'app-visore',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, DecimalPipe, FormsModule],
  host: {
    role: 'dialog',
    'aria-modal': 'true',
    '[attr.aria-label]': 'foto().titolo || foto().nomeOriginale',
    '(document:keydown)': 'tasto($event)',
  },
  templateUrl: './visore.html',
  styleUrl: './visore.css',
})
export class Visore {
  private readonly galleria = inject(Galleria);

  readonly foto = input.required<Foto>();
  readonly chiudi = output<void>();
  readonly cambia = output<Foto>();

  protected readonly pannello = signal(true);
  protected readonly salvataggio = signal(false);
  protected readonly modificata = signal(false);
  protected readonly caricata = signal(false);
  protected bozza: Bozza = vuota();

  protected readonly src = computed(() => FotoApi.originale(this.foto()));
  protected readonly scarica = computed(() => FotoApi.originale(this.foto(), true));
  protected readonly anteprima = computed(() => FotoApi.miniatura(this.foto()));
  protected readonly origine = computed(() => ORIGINI[this.foto().origineData]);
  protected readonly indice = computed(() => this.galleria.foto().findIndex((f) => f.id === this.foto().id));
  protected readonly album = this.galleria.album;
  protected readonly tagNoti = this.galleria.tag;
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
    });
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
        this.pannello.update((p) => !p);
        break;
      case 'f':
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

  protected peso(byte: number): string {
    return byte > 1_048_576 ? `${(byte / 1_048_576).toFixed(1)} MB` : `${Math.round(byte / 1024)} KB`;
  }
}

function vuota(): Bozza {
  return { titolo: '', descrizione: '', tag: '', album: '', scattataIl: '', preferita: false };
}
