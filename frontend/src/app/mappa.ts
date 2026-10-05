import { ChangeDetectionStrategy, Component, ElementRef, OnDestroy, afterNextRender, effect, inject, output, signal, viewChild } from '@angular/core';
import * as L from 'leaflet';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { PuntoMappa } from './modelli';

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
    :host ::ng-deep .leaflet-popup-content {
      margin: 8px;
      text-align: center;
    }
    :host ::ng-deep .anteprima {
      display: block;
      width: 200px;
      height: 150px;
      object-fit: cover;
      border-radius: 6px;
      cursor: zoom-in;
    }
  `,
})
export class Mappa implements OnDestroy {
  private readonly api = inject(FotoApi);
  private readonly galleria = inject(Galleria);

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
    this.filtrata.set(!!(filtro.q || filtro.tag || filtro.album || filtro.preferite || filtro.nazione || filtro.luogo));
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
    const miniatura = `/api/foto/${p.id}/miniatura`;
    const segno = L.marker([p.lat, p.lon], {
      icon: L.divIcon({
        className: '',
        html: `<div class="segno${p.video ? ' video' : ''}" style="background-image:url('${miniatura}')"></div>`,
        iconSize: [44, 44],
        iconAnchor: [22, 22],
      }),
      title: p.titolo ?? p.giorno,
    });
    const contenuto = document.createElement('div');
    const img = document.createElement('img');
    img.className = 'anteprima';
    img.src = miniatura;
    img.alt = p.titolo ?? '';
    img.addEventListener('click', () => this.apri.emit(p.id));
    const didascalia = document.createElement('div');
    didascalia.textContent = [p.video ? '▶ Video' : null, p.titolo, p.luogo, new Date(p.giorno).toLocaleDateString('it-IT', { dateStyle: 'long' })]
      .filter(Boolean)
      .join(' · ');
    contenuto.append(img, didascalia);
    segno.bindPopup(contenuto);
    return segno;
  }

  ngOnDestroy(): void {
    this.mappa?.remove();
  }
}
