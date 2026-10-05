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
  ModificaTelefono,
  Operazione,
  PaginaFoto,
  PuntoMappa,
  Ricordo,
  StatoCloud,
  StatoConversioni,
  StatoTelefono,
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

  /** "Indicizza archivio": dà una scheda alle foto già nell'archivio che l'app non conosce (solo admin). */
  indicizza(): Observable<LavoroImportazione> {
    return this.http.post<LavoroImportazione>('/api/archivio/indicizza', {});
  }

  /** La coda delle versioni compatibili dei video. */
  conversioni(): Observable<StatoConversioni> {
    return this.http.get<StatoConversioni>('/api/archivio/video');
  }

  /** "Converti video": mette in coda i video già in archivio che ne hanno bisogno (solo admin). */
  convertiVideo(): Observable<StatoConversioni> {
    return this.http.post<StatoConversioni>('/api/archivio/video/converti', {});
  }

  annullaConversioni(): Observable<StatoConversioni> {
    return this.http.post<StatoConversioni>('/api/archivio/video/annulla', {});
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

  /** Sincronizzazione del telefono da pCloud (solo admin). */
  telefono(): Observable<StatoTelefono> {
    return this.http.get<StatoTelefono>('/api/telefono');
  }

  salvaTelefono(modifica: ModificaTelefono): Observable<StatoTelefono> {
    return this.http.put<StatoTelefono>('/api/telefono', modifica);
  }

  sincronizzaTelefono(): Observable<StatoTelefono> {
    return this.http.post<StatoTelefono>('/api/telefono/sincronizza', {});
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

  /**
   * Il video da riprodurre: il server dà la versione compatibile se c'è.
   * Con la compatibile l'indirizzo cambia (c=1): il browser può avere in
   * cache l'originale servito prima della conversione.
   */
  static video(f: Foto): string {
    return `/api/foto/${f.id}/file${f.conversione === 'FATTA' ? '?c=1' : ''}`;
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
