import { HttpClient, HttpEvent, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { rotazione } from './formati';
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
  ElencoEventi,
  ElencoLuoghi,
  Evento,
  Filtro,
  Foto,
  Io,
  LavoroContenuto,
  Luogo,
  FileScartato,
  LavoroImportazione,
  Modifica,
  ModificaTelefono,
  NuovaCondivisione,
  Operazione,
  PaginaFoto,
  PaginaCestino,
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
  StatoVolti,
  LavoroVolti,
  ElencoPersone,
  PersonaSimile,
  TipoPersone,
  VocePersona,
  VoceVolto,
  VoltoInFoto,
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

  /** Città, province, regioni e nazioni per nome, per scegliere il luogo a mano ("Manch" → Manchester). */
  cercaLuoghi(q: string): Observable<Luogo[]> {
    return this.http.get<Luogo[]>('/api/luoghi/cerca', { params: { q } });
  }

  /** Il luogo scelto a mano (chiave di cercaLuoghi), null per "nessun luogo". */
  impostaLuogo(id: string, chiave: string | null): Observable<Foto> {
    return this.http.put<Foto>(`/api/foto/${id}/luogo`, { chiave });
  }

  /** Torna al luogo calcolato dal GPS. */
  luogoDaGps(id: string): Observable<Foto> {
    return this.http.delete<Foto>(`/api/foto/${id}/luogo`);
  }

  /** "Calcola luoghi" in sottofondo (solo admin); tutte = anche quelle che il luogo ce l'hanno. */
  calcolaLuoghi(tutte: boolean): Observable<LavoroImportazione> {
    return this.http.post<LavoroImportazione>('/api/archivio/luoghi', {}, { params: tutte ? { tutte } : {} });
  }

  // ------------------------------------------------------------ eventi

  /** Gli eventi dal più recente; i nascosti solo se chiesti. */
  eventi(nascosti = false): Observable<ElencoEventi> {
    return this.http.get<ElencoEventi>('/api/eventi', { params: nascosti ? { nascosti } : {} });
  }

  evento(id: string): Observable<Evento> {
    return this.http.get<Evento>(`/api/eventi/${encodeURIComponent(id)}`);
  }

  /** I nomi degli eventi non nascosti tra questi id. */
  nomiEventi(ids: string[]): Observable<Record<string, string>> {
    return this.http.get<Record<string, string>>('/api/eventi/nomi', { params: { ids: ids.join(',') } });
  }

  /** Un evento nuovo dalle foto scelte; senza nome, quello automatico. */
  creaEvento(ids: string[], nome?: string): Observable<Evento> {
    return this.http.post<Evento>('/api/eventi', { nome, ids });
  }

  /** nome vuoto = torna quello automatico. */
  modificaEvento(id: string, modifica: { nome?: string; nascosto?: boolean }): Observable<Evento> {
    return this.http.put<Evento>(`/api/eventi/${encodeURIComponent(id)}`, modifica);
  }

  unisciEventi(ids: string[]): Observable<Evento> {
    return this.http.post<Evento>('/api/eventi/unisci', { ids });
  }

  /** La foto e quelle dopo vanno in un evento nuovo. */
  dividiEvento(id: string, foto: string): Observable<Evento[]> {
    return this.http.post<Evento[]>(`/api/eventi/${encodeURIComponent(id)}/dividi`, { foto });
  }

  aggiungiAEvento(id: string, ids: string[]): Observable<{ modificate: number }> {
    return this.http.post<{ modificate: number }>(`/api/eventi/${encodeURIComponent(id)}/foto`, { ids });
  }

  togliDaEvento(ids: string[]): Observable<{ modificate: number }> {
    return this.http.post<{ modificate: number }>('/api/eventi/togli', { ids });
  }

  /** "Torna automatico": l'evento si scioglie, il ricalcolo rifà da sé. */
  sciogliEvento(id: string): Observable<void> {
    return this.http.delete<void>(`/api/eventi/${encodeURIComponent(id)}`);
  }

  /** "Calcola eventi" in sottofondo (solo admin): riprende da dove si era fermato, salvo daCapo. */
  calcolaEventi(daCapo: boolean): Observable<LavoroImportazione> {
    return this.http.post<LavoroImportazione>('/api/archivio/eventi', {}, { params: daCapo ? { daCapo } : {} });
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

  /** I file della cartella automatica che non si rileggono finché non cambiano. */
  scartati(): Observable<FileScartato[]> {
    return this.http.get<FileScartato[]>('/api/importazioni/scartati');
  }

  /** "Riprova" (admin): al prossimo giro si rileggono. */
  riprovaScartati(): Observable<{ dimenticati: number }> {
    return this.http.post<{ dimenticati: number }>('/api/importazioni/scartati/riprova', {});
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

  /** Ferma la ricompressione di un video troppo grande (admin); l'originale resta su pCloud. */
  annullaVideoGrande(id: string): Observable<void> {
    return this.http.post<void>(`/api/telefoni/video-grandi/${encodeURIComponent(id)}/annulla`, {});
  }

  /** Rimette in coda un video troppo grande in errore, annullato o rimandato (admin). */
  riprovaVideoGrande(id: string): Observable<void> {
    return this.http.post<void>(`/api/telefoni/video-grandi/${encodeURIComponent(id)}/riprova`, {});
  }

  /** Gruppi di foto quasi uguali, dal più numeroso. */
  quasiUguali(pagina: number, dimensione = 20): Observable<PaginaGruppi> {
    return this.http.get<PaginaGruppi>('/api/quasi-uguali', { params: { pagina, dimensione } });
  }

  /** Elimina le foto "togli" di un gruppo; quelle tenute (se più d'una) non si propongono più insieme. */
  risolvi(tieni: string[], togli: string[]): Observable<{ eliminate: number }> {
    return this.http.post<{ eliminate: number }>('/api/quasi-uguali/risolvi', { tieni, togli });
  }

  /** Il cestino: dall'ultima eliminata. */
  cestino(pagina: number, dimensione = 60): Observable<PaginaCestino> {
    return this.http.get<PaginaCestino>('/api/cestino', { params: { pagina, dimensione } });
  }

  /** Rimette nell'archivio le foto del cestino; 503 col cloud smontato. */
  ripristina(ids: string[]): Observable<{ fatte: number }> {
    return this.http.post<{ fatte: number }>('/api/cestino/ripristina', { ids });
  }

  /** Elimina per sempre, file compresi, le foto del cestino; 503 col cloud smontato. */
  eliminaPerSempre(ids: string[]): Observable<{ fatte: number }> {
    return this.http.post<{ fatte: number }>('/api/cestino/elimina', { ids });
  }

  svuotaCestino(): Observable<{ fatte: number }> {
    return this.http.post<{ fatte: number }>('/api/cestino/svuota', {});
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

  // ------------------------------------------------------------ volti e persone

  /** Se il riconoscimento dei volti c'è, e a che punto è. */
  volti(): Observable<StatoVolti> {
    return this.http.get<StatoVolti>('/api/volti');
  }

  /** "Riconosci volti" in sottofondo (solo admin); ricostruisci = prima rifà l'indice dei vicini di visione. */
  riconosciVolti(ricostruisci = false): Observable<LavoroVolti> {
    return this.http.post<LavoroVolti>('/api/volti/riconosci', {}, { params: ricostruisci ? { ricostruisci } : {} });
  }

  annullaVolti(): Observable<void> {
    return this.http.post<void>('/api/volti/annulla', {});
  }

  persone(tipo: TipoPersone = 'NOMINATE', limite = 500): Observable<ElencoPersone> {
    return this.http.get<ElencoPersone>('/api/persone', { params: { tipo, limite } });
  }

  persona(id: string): Observable<VocePersona> {
    return this.http.get<VocePersona>(`/api/persone/${encodeURIComponent(id)}`);
  }

  /** Nome vuoto: torna un gruppo senza nome. 409 se un'altra ha già quel nome (error.persona). */
  nominaPersona(id: string, nome: string): Observable<VocePersona> {
    return this.http.put<VocePersona>(`/api/persone/${encodeURIComponent(id)}`, { nome });
  }

  nascondiPersona(id: string, nascosta: boolean): Observable<VocePersona> {
    return this.http.post<VocePersona>(`/api/persone/${encodeURIComponent(id)}/nascondi`, { nascosta });
  }

  /** Le persone "da" passano in "in". */
  unisciPersone(dentro: string, da: string[]): Observable<VocePersona> {
    return this.http.post<VocePersona>('/api/persone/unisci', { in: dentro, da });
  }

  voltiPersona(id: string, pagina = 0, dimensione = 100): Observable<VoceVolto[]> {
    return this.http.get<VoceVolto[]>(`/api/persone/${encodeURIComponent(id)}/volti`, { params: { pagina, dimensione } });
  }

  proposte(id: string): Observable<VoceVolto[]> {
    return this.http.get<VoceVolto[]>(`/api/persone/${encodeURIComponent(id)}/proposte`);
  }

  confermaProposte(id: string): Observable<{ confermate: number }> {
    return this.http.post<{ confermate: number }>(`/api/persone/${encodeURIComponent(id)}/proposte/conferma`, {});
  }

  simili(id: string): Observable<PersonaSimile[]> {
    return this.http.get<PersonaSimile[]>(`/api/persone/${encodeURIComponent(id)}/simili`);
  }

  copertina(id: string, volto: string): Observable<void> {
    return this.http.post<void>(`/api/persone/${encodeURIComponent(id)}/copertina`, { volto });
  }

  voltiDellaFoto(id: string): Observable<VoltoInFoto[]> {
    return this.http.get<VoltoInFoto[]>(`/api/foto/${encodeURIComponent(id)}/volti`);
  }

  togliVolto(id: string): Observable<void> {
    return this.http.post<void>(`/api/volti/${encodeURIComponent(id)}/togli`, {});
  }

  assegnaVolto(id: string, persona: string): Observable<void> {
    return this.http.post<void>(`/api/volti/${encodeURIComponent(id)}/assegna`, { persona });
  }

  /** "Chi è?": una persona che c'è già, il nome al gruppo del volto, o una persona nuova. */
  nominaVolto(id: string, nome: string): Observable<VocePersona> {
    return this.http.post<VocePersona>(`/api/volti/${encodeURIComponent(id)}/nome`, { nome });
  }

  rifiutaProposta(id: string): Observable<void> {
    return this.http.post<void>(`/api/volti/${encodeURIComponent(id)}/rifiuta`, {});
  }

  static ritaglio(voltoId: string): string {
    return `/api/volti/${encodeURIComponent(voltoId)}/ritaglio`;
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

  /** Gira la foto di 90 (destra) o -90 (sinistra) gradi in più: si salva nella scheda, il file non cambia. */
  ruota(id: string, gradi: number): Observable<Foto> {
    return this.http.post<Foto>(`/api/foto/${id}/ruota`, { gradi });
  }

  /** "Ruota" sulla selezione; quelle nel cestino no. */
  ruotaMultiple(ids: string[], gradi: number): Observable<{ modificate: number }> {
    return this.http.post<{ modificate: number }>('/api/foto/ruota', { ids, gradi });
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
    return `/api/foto/${f.id}/miniatura${rotazione(f, '?')}`;
  }

  /** Quello che il browser sa mostrare: per gli HEIC un JPEG, per il resto l'originale. */
  static vista(f: Foto): string {
    return `/api/foto/${f.id}/vista${rotazione(f, '?')}`;
  }

  /** Da scaricare: un JPEG girato a mano arriva girato (senza perdita); gli altri come sono. */
  static originale(f: Foto, scarica = false): string {
    return `/api/foto/${f.id}/file${scarica ? '?scarica=true' + rotazione(f, '&') : ''}`;
  }

  /** I byte dell'archivio, senza la rotazione. */
  static originaleNonRuotato(f: Foto): string {
    return `/api/foto/${f.id}/file?scarica=true&originale=true`;
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
