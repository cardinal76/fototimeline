import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  Caricamento,
  Cartella,
  CopiaBackup,
  Filtro,
  Foto,
  Io,
  LavoroImportazione,
  Modifica,
  Operazione,
  PaginaFoto,
  PuntoMappa,
  Ricordo,
  StatoCloud,
  VoceMese,
} from './modelli';

@Injectable({ providedIn: 'root' })
export class FotoApi {
  private readonly http = inject(HttpClient);

  cerca(filtro: Filtro, pagina: number, dimensione = 60): Observable<PaginaFoto> {
    return this.http.get<PaginaFoto>('/api/foto', { params: parametri(filtro).set('pagina', pagina).set('dimensione', dimensione) });
  }

  timeline(filtro: Filtro): Observable<VoceMese[]> {
    const { al: _, ...senzaSalto } = filtro;
    return this.http.get<VoceMese[]>('/api/timeline', { params: parametri(senzaSalto) });
  }

  mappa(filtro: Filtro): Observable<PuntoMappa[]> {
    const { al: _, ...senzaSalto } = filtro;
    return this.http.get<PuntoMappa[]>('/api/mappa', { params: parametri(senzaSalto) });
  }

  ricordi(): Observable<Ricordo[]> {
    return this.http.get<Ricordo[]>('/api/ricordi');
  }

  foto(id: string): Observable<Foto> {
    return this.http.get<Foto>(`/api/foto/${id}`);
  }

  tag(): Observable<string[]> {
    return this.http.get<string[]>('/api/tag');
  }

  album(): Observable<string[]> {
    return this.http.get<string[]>('/api/album');
  }

  carica(file: File[], album?: string): Observable<HttpEvent<Caricamento[]>> {
    const dati = new FormData();
    file.forEach((f) => dati.append('file', f, f.name));
    if (album) {
      dati.append('album', album);
    }
    return this.http.post<Caricamento[]>('/api/foto', dati, { reportProgress: true, observe: 'events' });
  }

  /** Avvia l'importazione in sottofondo. */
  importa(cartella: string, albumDaCartella: boolean, sposta: boolean): Observable<LavoroImportazione> {
    return this.http.post<LavoroImportazione>('/api/importa', { cartella, albumDaCartella, sposta });
  }

  /** L'importazione in corso o l'ultima finita; null se non ce ne sono state. */
  importazioneCorrente(): Observable<LavoroImportazione | null> {
    return this.http.get<LavoroImportazione | null>('/api/importazioni/corrente');
  }

  annullaImportazione(): Observable<void> {
    return this.http.post<void>('/api/importazioni/annulla', {});
  }

  cartelle(percorso?: string): Observable<Cartella> {
    return this.http.get<Cartella>('/api/cartelle', { params: percorso ? { percorso } : {} });
  }

  io(): Observable<Io> {
    return this.http.get<Io>('/api/io');
  }

  backup(): Observable<CopiaBackup[]> {
    return this.http.get<CopiaBackup[]>('/api/backup');
  }

  faiBackup(): Observable<CopiaBackup> {
    return this.http.post<CopiaBackup>('/api/backup', {});
  }

  cloud(): Observable<StatoCloud> {
    return this.http.get<StatoCloud>('/api/cloud');
  }

  monta(): Observable<StatoCloud> {
    return this.http.post<StatoCloud>('/api/cloud/monta', {});
  }

  smonta(): Observable<StatoCloud> {
    return this.http.post<StatoCloud>('/api/cloud/smonta', {});
  }

  modifica(id: string, modifica: Modifica): Observable<Foto> {
    return this.http.put<Foto>(`/api/foto/${id}`, modifica);
  }

  elimina(id: string): Observable<void> {
    return this.http.delete<void>(`/api/foto/${id}`);
  }

  multipla(ids: string[], operazione: Operazione, valore?: string): Observable<{ modificate: number }> {
    return this.http.post<{ modificate: number }>('/api/foto/multiple', { ids, operazione, valore });
  }

  static miniatura(f: Foto): string {
    return `/api/foto/${f.id}/miniatura`;
  }

  /** Quello che il browser sa mostrare: per gli HEIC un JPEG, per il resto l'originale. */
  static vista(f: Foto): string {
    return `/api/foto/${f.id}/vista`;
  }

  static originale(f: Foto, scarica = false): string {
    return `/api/foto/${f.id}/file${scarica ? '?scarica=true' : ''}`;
  }
}

function parametri(filtro: Filtro): HttpParams {
  let p = new HttpParams();
  for (const [chiave, valore] of Object.entries(filtro)) {
    if (valore !== undefined && valore !== null && valore !== '' && valore !== false) {
      p = p.set(chiave, String(valore));
    }
  }
  return p;
}
