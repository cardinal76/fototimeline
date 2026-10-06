import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import {
  CollegamentoGoogle,
  ModificaTakeout,
  SceltaGoogle,
  StatoTakeout,
  CaricateDa,
  Caricamento,
  Cartella,
  Condivisione,
  CopiaBackup,
  ElencoTelefoni,
  ElencoLuoghi,
  Filtro,
  Foto,
  Io,
  LavoroContenuto,
  LavoroImportazione,
  Modifica,
  ModificaTelefono,
  NuovaCondivisione,
  Operazione,
  PaginaFoto,
  PaginaGruppi,
  PuntoMappa,
  ImpostazioniRicordiTelegram,
  Ricordo,
  RicordiTelegram,
  Salute,
  StatoCloud,
  StatoContenuto,
  StatoConversioni,
  StatoImpronte,
  StatoTelefono,
  Utente,
  VoceMese,
} from './modelli';

@Injectable({ providedIn: 'root' })
export class FotoApi {
  private readonly http = inject(HttpClient);

  cerca(filtro: Filtro, pagina: number, dimensione = 60): Observable<PaginaFoto> {
    return this.http.get<PaginaFoto>('/api/foto', { params: parametri(filtro).set('pagina', pagina).set('dimensione', dimensione) });
  }

  /** Ricerca per contenuto: q è quello che c'è nella foto; le foto arrivano dalla più simile. */
  cercaContenuto(filtro: Filtro, pagina: number, dimensione = 60): Observable<PaginaFoto> {
    return this.http.get<PaginaFoto>('/api/foto/cerca-contenuto', {
      params: parametri(filtro).set('pagina', pagina).set('dimensione', dimensione),
    });
  }

  timeline(filtro: Filtro): Observable<VoceMese[]> {
    const { al: _, ...senzaSalto } = soloTesti(filtro);
    return this.http.get<VoceMese[]>('/api/timeline', { params: parametri(senzaSalto) });
  }

  /** Con la ricerca per contenuto la mappa mostra le foto degli altri filtri. */
  mappa(filtro: Filtro): Observable<PuntoMappa[]> {
    const { al: _, ...senzaSalto } = soloTesti(filtro);
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

  /** Chi ha portato foto e quante, per il filtro "Caricate da". */
  caricateDa(): Observable<CaricateDa[]> {
    return this.http.get<CaricateDa[]>('/api/caricate-da');
  }

  /** Gli utenti entrati almeno una volta (solo admin). */
  utenti(): Observable<Utente[]> {
    return this.http.get<Utente[]>('/api/utenti');
  }

  /** Nazioni, regioni e luoghi con i conteggi, per il filtro "Luogo". */
  luoghi(): Observable<ElencoLuoghi> {
    return this.http.get<ElencoLuoghi>('/api/luoghi');
  }

  /** "Calcola luoghi" in sottofondo (solo admin); tutte = anche quelle che il luogo ce l'hanno. */
  calcolaLuoghi(tutte: boolean): Observable<LavoroImportazione> {
    return this.http.post<LavoroImportazione>('/api/archivio/luoghi', {}, { params: tutte ? { tutte } : {} });
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

  /** I telefoni della famiglia: l'admin li vede tutti, gli altri il proprio. */
  telefoni(): Observable<ElencoTelefoni> {
    return this.http.get<ElencoTelefoni>('/api/telefoni');
  }

  creaTelefono(modifica: ModificaTelefono): Observable<StatoTelefono> {
    return this.http.post<StatoTelefono>('/api/telefoni', modifica);
  }

  salvaTelefono(id: string, modifica: ModificaTelefono): Observable<StatoTelefono> {
    return this.http.put<StatoTelefono>(`/api/telefoni/${encodeURIComponent(id)}`, modifica);
  }

  eliminaTelefono(id: string): Observable<void> {
    return this.http.delete<void>(`/api/telefoni/${encodeURIComponent(id)}`);
  }

  sincronizzaTelefono(id: string): Observable<StatoTelefono> {
    return this.http.post<StatoTelefono>(`/api/telefoni/${encodeURIComponent(id)}/sincronizza`, {});
  }

  /** Gruppi di foto quasi uguali, dal più numeroso. */
  quasiUguali(pagina: number, dimensione = 20): Observable<PaginaGruppi> {
    return this.http.get<PaginaGruppi>('/api/quasi-uguali', { params: { pagina, dimensione } });
  }

  /** Elimina le foto "togli" di un gruppo; quelle tenute (se più d'una) non si propongono più insieme. */
  risolvi(tieni: string[], togli: string[]): Observable<{ eliminate: number }> {
    return this.http.post<{ eliminate: number }>('/api/quasi-uguali/risolvi', { tieni, togli });
  }

  /** "Non sono doppioni". */
  ignora(ids: string[]): Observable<void> {
    return this.http.post<void>('/api/quasi-uguali/ignora', { ids });
  }

  statoImpronte(): Observable<StatoImpronte> {
    return this.http.get<StatoImpronte>('/api/quasi-uguali/calcola');
  }

  /** "Calcola impronte" per le foto che non l'hanno (solo admin). */
  calcolaImpronte(): Observable<StatoImpronte> {
    return this.http.post<StatoImpronte>('/api/quasi-uguali/calcola', {});
  }

  annullaImpronte(): Observable<void> {
    return this.http.post<void>('/api/quasi-uguali/calcola/annulla', {});
  }

  /** La pagina "Salute" (solo admin). */
  salute(): Observable<Salute> {
    return this.http.get<Salute>('/api/salute');
  }

  /** Un messaggio di prova su Telegram: 409 se non è configurato. */
  provaTelegram(): Observable<void> {
    return this.http.post<void>('/api/salute/prova', {});
  }

  /** "Ricordi su Telegram" (solo admin). */
  ricordiTelegram(): Observable<RicordiTelegram> {
    return this.http.get<RicordiTelegram>('/api/ricordi-telegram');
  }

  salvaRicordiTelegram(impostazioni: ImpostazioniRicordiTelegram): Observable<RicordiTelegram> {
    return this.http.put<RicordiTelegram>('/api/ricordi-telegram', impostazioni);
  }

  /** I ricordi di oggi subito, anche se già mandati: 409 se Telegram non è configurato. */
  provaRicordiTelegram(): Observable<{ messaggio: string }> {
    return this.http.post<{ messaggio: string }>('/api/ricordi-telegram/prova', {});
  }

  // ------------------------------------------------------------ Google Foto

  /** La funzione c'è? Sono collegato? La mia scelta in corso. */
  google(): Observable<CollegamentoGoogle> {
    return this.http.get<CollegamentoGoogle>('/api/google');
  }

  /** Una nuova scelta col Picker: la pagina di Google da aprire è in pickerUri. */
  nuovaSceltaGoogle(): Observable<SceltaGoogle> {
    return this.http.post<SceltaGoogle>('/api/google/scelta', {});
  }

  /** La scelta in corso o l'ultima; null se non ce n'è. */
  sceltaGoogle(): Observable<SceltaGoogle | null> {
    return this.http.get<SceltaGoogle | null>('/api/google/scelta');
  }

  annullaSceltaGoogle(): Observable<void> {
    return this.http.post<void>('/api/google/scelta/annulla', {});
  }

  chiudiSceltaGoogle(): Observable<void> {
    return this.http.post<void>('/api/google/scelta/chiudi', {});
  }

  scollegaGoogle(): Observable<void> {
    return this.http.post<void>('/api/google/scollega', {});
  }

  /** "Importa da Google Takeout" (solo admin). */
  takeout(): Observable<StatoTakeout> {
    return this.http.get<StatoTakeout>('/api/google/takeout');
  }

  salvaTakeout(modifica: ModificaTakeout): Observable<StatoTakeout> {
    return this.http.put<StatoTakeout>('/api/google/takeout', modifica);
  }

  avviaTakeout(): Observable<unknown> {
    return this.http.post('/api/google/takeout/avvia', {});
  }

  annullaTakeout(): Observable<void> {
    return this.http.post<void>('/api/google/takeout/annulla', {});
  }

  riprovaZip(id: string): Observable<void> {
    return this.http.post<void>(`/api/google/takeout/zip/${encodeURIComponent(id)}/riprova`, {});
  }

  /** Se la ricerca per contenuto c'è, e a che punto è l'indice. */
  contenuto(): Observable<StatoContenuto> {
    return this.http.get<StatoContenuto>('/api/contenuto');
  }

  /** "Indicizza contenuto" in sottofondo (solo admin). */
  indicizzaContenuto(daCapo = false): Observable<LavoroContenuto> {
    return this.http.post<LavoroContenuto>('/api/contenuto/indicizza', {}, { params: daCapo ? { daCapo } : {} });
  }

  annullaContenuto(): Observable<void> {
    return this.http.post<void>('/api/contenuto/annulla', {});
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

  /** Link pubblici: si creano, si elencano (i propri; l'admin tutti) e si revocano. */
  creaCondivisione(nuova: NuovaCondivisione): Observable<Condivisione> {
    return this.http.post<Condivisione>('/api/condivisioni', nuova);
  }

  condivisioni(): Observable<Condivisione[]> {
    return this.http.get<Condivisione[]>('/api/condivisioni');
  }

  revocaCondivisione(id: string): Observable<void> {
    return this.http.delete<void>(`/api/condivisioni/${id}`);
  }

  /** L'indirizzo da mandare: la pagina pubblica, che non chiede il login. */
  static linkCondivisione(c: Condivisione): string {
    return `${location.origin}/c/${c.token}`;
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

/** Timeline e mappa cercano q nei testi: con la ricerca per contenuto q non vale per loro. */
function soloTesti(filtro: Filtro): Filtro {
  return filtro.contenuto ? { ...filtro, q: undefined, contenuto: undefined } : filtro;
}

function parametri(filtro: Filtro): HttpParams {
  let p = new HttpParams();
  // "contenuto" sceglie l'API, non è un filtro.
  const { contenuto: _, ...filtri } = filtro;
  for (const [chiave, valore] of Object.entries(filtri)) {
    if (valore !== undefined && valore !== null && valore !== '' && valore !== false) {
      p = p.set(chiave, String(valore));
    }
  }
  return p;
}
