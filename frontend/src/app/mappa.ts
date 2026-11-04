import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, inject, output, signal, viewChild } from '@angular/core';
import * as L from 'leaflet';

import { rotazione } from './formati';
import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { PuntoMappa } from './modelli';
import { ETICHETTA_SFOCATA, Sfocate } from './sfocate';

/**
 * Le foto con posizione GPS su OpenStreetMap, raggruppate quando sono vicine.
 * Si carica solo quando la si apre (@defer in app.html): Leaflet non pesa
 * sull'avvio dell'app.
 */
@Component({
  selector: 'app-mappa',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div #contenitore class="mappa"></div>
    @if (caricata() && !punti()) {
      <p class="vuota">Nessuna foto con la posizione GPS{{ filtrata() ? ' con questi filtri' : '' }}.</p>
    }
  `,
  styles: `
    :host {
      position: relative;
      display: block;
      height: 100%;
    }
    .mappa {
      height: 100%;
      background: var(--superficie-2);
    }
    .vuota {
      position: absolute;
      top: 1rem;
      left: 50%;
      translate: -50% 0;
      z-index: 500;
      margin: 0;
      padding: 0.6rem 1rem;
      border-radius: 10px;
      background: var(--superficie);
      border: 1px solid var(--bordo);
    }
    :host ::ng-deep .segno {
      width: 44px;
      height: 44px;
      border-radius: 50%;
      border: 2px solid #fff;
      box-shadow: 0 2px 6px rgb(0 0 0 / 0.5);
      background: var(--superficie-2) center / cover no-repeat;
    }
    :host ::ng-deep .segno.video {
      position: relative;
    }
    /* Senza GPS, al posto della città scelta a mano: bordo tratteggiato, un po' trasparente. */
    :host ::ng-deep .segno.approssimativa {
      border-style: dashed;
      opacity: 0.8;
    }
    :host ::ng-deep .segno.video::after {
      content: '▶';
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      padding-left: 2px;
      font-size: 16px;
      color: #fff;
      text-shadow: 0 1px 3px rgb(0 0 0 / 0.8);
      background: rgb(0 0 0 / 0.25);
      border-radius: 50%;
    }
    /* Privata non ancora toccata: la miniatura sfocata sotto, il bordo e il lucchetto nitidi. */
    :host ::ng-deep .segno.privata {
      position: relative;
      overflow: hidden;
    }
    :host ::ng-deep .segno.privata::before {
      content: '';
      position: absolute;
      inset: -12px;
      background: inherit;
      border: 0;
      filter: blur(10px);
    }
    :host ::ng-deep .segno .lucchetto-segno {
      position: absolute;
      inset: 0;
      display: grid;
      place-items: center;
      font-size: 14px;
      text-shadow: 0 1px 3px rgb(0 0 0 / 0.8);
    }
    :host ::ng-deep .leaflet-popup-content {
      margin: 8px;
      text-align: center;
    }
    :host ::ng-deep .cornice {
      position: relative;
      display: block;
      margin: 0 auto;
      padding: 0;
      border: 0;
      border-radius: 6px;
      overflow: hidden;
      background: var(--superficie-2);
      cursor: zoom-in;
    }
    /* A sinistra: a destra c'è la × del popup. */
    :host ::ng-deep .cornice .lucchetto {
      position: absolute;
      top: 5px;
      left: 6px;
      font-size: 0.8rem;
      text-shadow: 0 1px 3px rgb(0 0 0 / 0.7);
    }
    :host ::ng-deep .anteprima {
      display: block;
      width: 200px;
      height: 150px;
      object-fit: cover;
      transition: filter 0.25s, transform 0.25s;
    }
  `,
})
export class Mappa implements OnDestroy {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);
  private readonly sfocate = inject(Sfocate);

  /** L'id della foto da aprire nel visore. */
  readonly apri = output<string>();

  protected readonly caricata = signal(false);
  protected readonly punti = signal(0);
  protected readonly filtrata = signal(false);

  private readonly contenitore = viewChild.required<ElementRef<HTMLElement>>('contenitore');
  private mappa?: L.Map;
  private gruppo?: L.MarkerClusterGroup;

  constructor() {
    afterNextRender(async () => {
      // markercluster si aggancia alla L globale: va messa prima di caricarlo.
      (window as unknown as { L: typeof L }).L = L;
      await import('leaflet.markercluster');
      this.mappa = L.map(this.contenitore().nativeElement, { worldCopyJump: true }).setView([42.5, 12.5], 5);
      L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
      }).addTo(this.mappa);
      this.gruppo = L.markerClusterGroup({ showCoverageOnHover: false, maxClusterRadius: 50 });
      this.mappa.addLayer(this.gruppo);
      this.carica();
    });
    // Filtri cambiati nella barra: si ricaricano i punti.
    effect(() => {
      this.galleria.filtro();
      if (this.mappa) {
        this.carica();
      }
    });
  }

  private carica(): void {
    const filtro = this.galleria.filtro();
    this.filtrata.set(!!(filtro.q || filtro.tag || filtro.album || filtro.preferite || filtro.nazione || filtro.regione || filtro.luogo));
    this.api.mappa(filtro).subscribe((punti) => this.mostra(punti));
  }

  private mostra(punti: PuntoMappa[]): void {
    if (!this.mappa || !this.gruppo) return;
    this.gruppo.clearLayers();
    this.gruppo.addLayers(punti.map((p) => this.segno(p)));
    this.punti.set(punti.length);
    this.caricata.set(true);
    if (punti.length) {
      this.mappa.fitBounds(L.latLngBounds(punti.map((p) => [p.lat, p.lon] as L.LatLngTuple)), {
        padding: [40, 40],
        maxZoom: 15,
      });
    }
  }

  private segno(p: PuntoMappa): L.Marker {
    const miniatura = `/api/foto/${p.id}/miniatura${rotazione(p, '?')}`;
    const sfocata = this.sfocate.sfocata(p);
    const segno = L.marker([p.lat, p.lon], {
      icon: L.divIcon({
        className: '',
        html:
          `<div class="segno${p.video ? ' video' : ''}${p.approssimativa ? ' approssimativa' : ''}${sfocata ? ' privata' : ''}" style="background-image:url('${miniatura}')">` +
          (sfocata ? '<span class="lucchetto-segno" aria-hidden="true">🔒</span>' : '') +
          '</div>',
        iconSize: [44, 44],
        iconAnchor: [22, 22],
      }),
      title: sfocata ? ETICHETTA_SFOCATA : (p.titolo ?? p.giorno) + (p.approssimativa ? ' (posizione approssimativa)' : ''),
    });
    const contenuto = document.createElement('div');
    // Un pulsante: anche da tastiera (Invio) si svela e poi si apre.
    const cornice = document.createElement('button');
    cornice.type = 'button';
    cornice.className = 'cornice';
    const img = document.createElement('img');
    img.className = 'anteprima' + (sfocata ? ' sfocata' : '');
    img.src = miniatura;
    img.alt = '';
    cornice.setAttribute('aria-label', sfocata ? ETICHETTA_SFOCATA : p.titolo || 'Apri la foto');
    cornice.append(img);
    if (p.privata) {
      const lucchetto = document.createElement('span');
      lucchetto.className = 'lucchetto';
      lucchetto.title = 'Nel tuo Privato: la vedi solo tu';
      lucchetto.textContent = '🔒';
      lucchetto.setAttribute('aria-hidden', 'true');
      cornice.append(lucchetto);
    }
    cornice.addEventListener('click', () => {
      // Privata e sfocata: il primo clic la mostra (anche il segno sulla mappa), il secondo la apre.
      if (this.sfocate.svelaAlPrimoClic(p)) {
        img.classList.remove('sfocata');
        segno.getElement()?.querySelector('.segno')?.classList.remove('privata');
        cornice.setAttribute('aria-label', p.titolo || 'Apri la foto');
        return;
      }
      this.apri.emit(p.id);
    });
    const didascalia = document.createElement('div');
    didascalia.textContent = [
      p.video ? '▶ Video' : null,
      p.titolo,
      p.approssimativa ? `${p.luogo ?? ''} (posizione approssimativa, luogo scelto a mano)` : p.luogo,
      new Date(p.giorno).toLocaleDateString('it-IT', { dateStyle: 'long' }),
    ]
      .filter(Boolean)
      .join(' · ');
    contenuto.append(cornice, didascalia);
    segno.bindPopup(contenuto);
    return segno;
  }

  ngOnDestroy(): void {
    this.mappa?.remove();
  }
}
