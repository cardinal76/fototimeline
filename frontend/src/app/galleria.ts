import { Injectable, computed, inject, signal } from '@angular/core';
import { HttpEventType } from '@angular/common/http';
import { Subscription, firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import {
  CaricateDa,
  Caricamento,
  CopiaBackup,
  ElencoLuoghi,
  Filtro,
  Foto,
  Giorno,
  Io,
  LavoroImportazione,
  Modifica,
  Operazione,
  Salute,
  StatoCloud,
  StatoConversioni,
  VoceMese,
} from './modelli';

const DIMENSIONE_PAGINA = 80;
/** Le estensioni che il backend accetta (FotoService.TIPI). */
const FORMATI = /\.(jpe?g|png|gif|bmp|webp|heic|heif|mp4|m4v|mov)$/i;
/** File per richiesta di caricamento: richieste piccole, avanzamento leggibile. */
const FILE_PER_RICHIESTA = 6;

export interface StatoCaricamento {
  totale: number;
  fatti: number;
  caricate: number;
  duplicate: number;
  errori: string[];
  /** Avanzamento in byte del gruppo in corso, 0..1. */
  gruppo: number;
}

/** Lo stato della timeline: filtri, foto caricate finora, mesi, selezione. */
@Injectable({ providedIn: 'root' })
export class Galleria {
  private readonly api = inject(FotoApi);

  readonly filtro = signal<Filtro>({});
  readonly foto = signal<Foto[]>([]);
  readonly totale = signal(0);
  readonly altre = signal(false);
  readonly inCaricamento = signal(false);
  readonly mesi = signal<VoceMese[]>([]);
  readonly tag = signal<string[]>([]);
  readonly album = signal<string[]>([]);
  /** Chi ha portato foto, per il filtro e per "Caricata da Anna". */
  readonly caricateDa = signal<CaricateDa[]>([]);
  /** Luoghi con i conteggi: per il filtro e per "Calcola luoghi". */
  readonly luoghi = signal<ElencoLuoghi | null>(null);
  readonly selezionate = signal<ReadonlySet<string>>(new Set());
  readonly caricamento = signal<StatoCaricamento | null>(null);
  readonly avviso = signal<string | null>(null);
  readonly io = signal<Io | null>(null);
  readonly cloud = signal<StatoCloud | null>(null);
  readonly cambioCloud = signal(false);
  /** Ultimo backup dei metadati (solo per gli admin). */
  readonly ultimoBackup = signal<CopiaBackup | null>(null);
  readonly backupInCorso = signal(false);
  /** L'ultimo controllo della pagina "Salute" (solo per gli admin), per il pallino nella barra. */
  readonly salute = signal<Salute | null>(null);
  /** Importazione da seguire con la barra; null quando non ce n'è una da mostrare. */
  readonly importazione = signal<LavoroImportazione | null>(null);
  /** Coda delle versioni compatibili dei video (solo admin); null quando non c'è niente da mostrare. */
  readonly conversioni = signal<StatoConversioni | null>(null);
  /** Originali raggiungibili: sul PC sempre, sul server solo col cloud montato. */
  readonly archivioDisponibile = computed(() => {
    const c = this.cloud();
    return !c || !c.gestito || c.montato;
  });

  readonly giorni = computed<Giorno[]>(() => {
    const giorni: Giorno[] = [];
    for (const f of this.foto()) {
      const ultimo = giorni.at(-1);
      if (ultimo?.giorno === f.giorno) {
        ultimo.foto.push(f);
      } else {
        giorni.push({ giorno: f.giorno, nuovoMese: ultimo?.giorno.slice(0, 7) !== f.giorno.slice(0, 7), foto: [f] });
      }
    }
    return giorni;
  });

  readonly anni = computed(() => {
    const anni: { anno: number; conteggio: number; mesi: VoceMese[] }[] = [];
    for (const m of this.mesi()) {
      let a = anni.at(-1);
      if (a?.anno !== m.anno) {
        a = { anno: m.anno, conteggio: 0, mesi: [] };
        anni.push(a);
      }
      a.conteggio += m.conteggio;
      a.mesi.push(m);
    }
    return anni;
  });

  readonly totaleArchivio = computed(() => this.mesi().reduce((s, m) => s + m.conteggio, 0));

  private pagina = 0;
  private importazioneTimer?: ReturnType<typeof setTimeout>;
  private conversioniTimer?: ReturnType<typeof setTimeout>;
  /** La barra delle conversioni è stata chiusa: torna quando la coda riparte. */
  private conversioniChiuse = false;
  /** L'ultima importazione già vista finita: non la si riannuncia. */
  private importazioneVista?: string;
  private richiesta?: Subscription;
  private avvisoTimer?: ReturnType<typeof setTimeout>;

  constructor() {
    this.api.io().subscribe((io) => {
      this.io.set(io);
      if (io.admin) {
        this.aggiornaBackup();
        this.seguiConversioni();
        this.seguiSalute();
      }
    });
    this.aggiornaCloud();
    this.ricarica();
    this.aggiornaLuoghi();
    // Quella già finita al caricamento della pagina non si mostra; una in corso sì.
    this.api.importazioneCorrente().subscribe((l) => {
      if (l && l.stato !== 'IN_CORSO') {
        this.importazioneVista = l.id;
      }
      this.seguiImportazione();
    });
  }

  // ------------------------------------------------------------ importazioni in sottofondo

  async avviaImportazione(cartella: string, albumDaCartella: boolean, sposta: boolean): Promise<void> {
    const lavoro = await firstValueFrom(this.api.importa(cartella, albumDaCartella, sposta));
    this.importazione.set(lavoro);
    this.seguiImportazione();
  }

  /** Come un'importazione: stessa barra, e alla fine la timeline si ricarica. */
  async avviaIndicizzazione(): Promise<void> {
    const lavoro = await firstValueFrom(this.api.indicizza());
    this.importazione.set(lavoro);
    this.seguiImportazione();
  }

  /** "Calcola luoghi": stessa barra delle importazioni. */
  async avviaLuoghi(tutte: boolean): Promise<void> {
    const lavoro = await firstValueFrom(this.api.calcolaLuoghi(tutte));
    this.importazione.set(lavoro);
    this.seguiImportazione();
  }

  aggiornaLuoghi(): void {
    this.api.luoghi().subscribe({ next: (l) => this.luoghi.set(l), error: () => {} });
  }

  annullaImportazione(): void {
    this.api.annullaImportazione().subscribe(() => this.seguiImportazione());
  }

  chiudiImportazione(): void {
    const l = this.importazione();
    if (l) {
      this.importazioneVista = l.id;
    }
    this.importazione.set(null);
  }

  /**
   * Chiede lo stato: ogni secondo mentre un'importazione è in corso, ogni
   * minuto altrimenti, per accorgersi di quelle partite dalla cartella automatica.
   */
  private seguiImportazione(): void {
    clearTimeout(this.importazioneTimer);
    this.api.importazioneCorrente().subscribe({
      next: (l) => {
        const prima = this.importazione();
        if (l && (l.stato === 'IN_CORSO' || l.id !== this.importazioneVista)) {
          this.importazione.set(l);
          if (l.stato !== 'IN_CORSO' && prima?.stato === 'IN_CORSO') {
            this.ricarica();
            this.aggiornaLuoghi();
          }
        }
        this.importazioneTimer = setTimeout(() => this.seguiImportazione(), l?.stato === 'IN_CORSO' ? 1000 : 60000);
      },
      error: () => (this.importazioneTimer = setTimeout(() => this.seguiImportazione(), 60000)),
    });
  }

  // ------------------------------------------------------------ conversioni dei video

  async convertiVideo(): Promise<void> {
    this.conversioniChiuse = false;
    this.conversioni.set(await firstValueFrom(this.api.convertiVideo()));
    this.seguiConversioni();
  }

  annullaConversioni(): void {
    this.api.annullaConversioni().subscribe((s) => {
      this.conversioni.set(s);
      this.seguiConversioni();
    });
  }

  chiudiConversioni(): void {
    this.conversioniChiuse = true;
    this.conversioni.set(null);
  }

  /**
   * Come le importazioni: ogni 2 secondi mentre la coda lavora, ogni minuto
   * altrimenti (si accorge dei video entrati in coda con un'importazione).
   */
  private seguiConversioni(): void {
    clearTimeout(this.conversioniTimer);
    this.api.conversioni().subscribe({
      next: (s) => {
        const attiva = s.inCorso || s.daFare > 0;
        if (attiva) {
          this.conversioniChiuse = false;
        }
        if (!this.conversioniChiuse && (attiva || this.conversioni())) {
          this.conversioni.set(s);
        }
        this.conversioniTimer = setTimeout(() => this.seguiConversioni(), s.inCorso && !s.inAttesa ? 2000 : 60000);
      },
      error: () => (this.conversioniTimer = setTimeout(() => this.seguiConversioni(), 60000)),
    });
  }

  /** Il pallino della salute: subito e poi ogni 5 minuti. */
  private seguiSalute(): void {
    this.aggiornaSalute();
    setInterval(() => this.aggiornaSalute(), 5 * 60_000);
  }

  aggiornaSalute(): Promise<Salute | null> {
    return firstValueFrom(this.api.salute()).then(
      (s) => {
        this.salute.set(s);
        return s;
      },
      () => null,
    );
  }

  aggiornaBackup(): void {
    this.api.backup().subscribe({ next: (b) => this.ultimoBackup.set(b[0] ?? null), error: () => {} });
  }

  async faiBackup(): Promise<void> {
    this.backupInCorso.set(true);
    try {
      const copia = await firstValueFrom(this.api.faiBackup());
      this.ultimoBackup.set(copia);
      this.avvisa(`Backup dei metadati fatto (${Math.max(1, Math.round(copia.dimensione / 1024))} KB)`);
    } catch (e) {
      const smontato = (e as { status?: number }).status === 503;
      this.avvisa(smontato ? 'Backup non possibile: il cloud è smontato' : 'Backup non riuscito');
    } finally {
      this.backupInCorso.set(false);
    }
  }

  aggiornaCloud(): void {
    this.api.cloud().subscribe({ next: (c) => this.cloud.set(c), error: () => this.cloud.set(null) });
  }

  async commutaCloud(): Promise<void> {
    const montato = this.cloud()?.montato;
    this.cambioCloud.set(true);
    try {
      const stato = await firstValueFrom(montato ? this.api.smonta() : this.api.monta());
      this.cloud.set(stato);
      this.avvisa(stato.montato ? 'Cloud montato' : 'Cloud smontato: gli originali non sono più raggiungibili');
    } catch {
      this.avvisa(montato ? 'Smontaggio non riuscito' : 'Montaggio non riuscito: controlla rclone');
      this.aggiornaCloud();
    } finally {
      this.cambioCloud.set(false);
    }
  }

  imposta(modifica: Partial<Filtro>): void {
    this.filtro.update((f) => ({ ...f, ...modifica }));
    this.ricarica();
  }

  /** Salta a un mese: la lista riparte dall'ultimo giorno di quel mese. */
  salta(anno: number, mese: number): void {
    const ultimo = new Date(Date.UTC(anno, mese, 0)).toISOString().slice(0, 10);
    this.imposta({ al: ultimo });
  }

  ricarica(): void {
    this.richiesta?.unsubscribe();
    this.pagina = 0;
    this.foto.set([]);
    this.altre.set(false);
    this.selezionate.set(new Set());
    this.prossimaPagina(true);
    this.api.timeline(this.filtro()).subscribe((m) => this.mesi.set(m));
    this.api.tag().subscribe((t) => this.tag.set(t));
    this.api.album().subscribe((a) => this.album.set(a));
    this.api.caricateDa().subscribe((c) => this.caricateDa.set(c));
  }

  /** Il nome di chi ha portato una foto, se è entrato almeno una volta; altrimenti lo username. */
  nomeDi(username: string): string {
    return this.caricateDa().find((c) => c.username === username)?.nome ?? username;
  }

  prossimaPagina(primaPagina = false): void {
    if (this.inCaricamento() || (!primaPagina && !this.altre())) {
      return;
    }
    this.inCaricamento.set(true);
    this.richiesta = this.api.cerca(this.filtro(), this.pagina, DIMENSIONE_PAGINA).subscribe({
      next: (p) => {
        this.foto.update((f) => [...f, ...p.foto]);
        this.totale.set(p.totale);
        this.altre.set(p.altre);
        this.pagina++;
        this.inCaricamento.set(false);
      },
      error: () => {
        this.inCaricamento.set(false);
        this.avvisa('Il server non risponde: è acceso?');
      },
    });
  }

  // ------------------------------------------------------------ modifiche

  async salva(foto: Foto, modifica: Modifica): Promise<Foto> {
    const aggiornata = await firstValueFrom(this.api.modifica(foto.id, modifica));
    const dataCambiata = aggiornata.scattataIl !== foto.scattataIl;
    if (dataCambiata) {
      // Cambia posto nella timeline: si riordina tutto.
      this.ricarica();
    } else {
      this.foto.update((lista) => lista.map((f) => (f.id === foto.id ? aggiornata : f)));
      this.api.tag().subscribe((t) => this.tag.set(t));
      this.api.album().subscribe((a) => this.album.set(a));
    }
    return aggiornata;
  }

  async elimina(foto: Foto): Promise<void> {
    await firstValueFrom(this.api.elimina(foto.id));
    this.togliDallaLista([foto.id]);
  }

  async operazione(operazione: Operazione, valore?: string): Promise<void> {
    const ids = [...this.selezionate()];
    if (!ids.length) {
      return;
    }
    const { modificate } = await firstValueFrom(this.api.multipla(ids, operazione, valore));
    if (operazione === 'ELIMINA') {
      this.togliDallaLista(ids);
      this.avvisa(`${modificate} foto eliminate`);
    } else {
      this.avvisa(`${modificate} foto aggiornate`);
      this.ricarica();
    }
  }

  private togliDallaLista(ids: string[]): void {
    const via = new Set(ids);
    this.foto.update((lista) => lista.filter((f) => !via.has(f.id)));
    this.totale.update((t) => t - ids.length);
    this.selezionate.set(new Set());
    this.api.timeline(this.filtro()).subscribe((m) => this.mesi.set(m));
  }

  // ------------------------------------------------------------ selezione

  commuta(id: string): void {
    this.selezionate.update((s) => {
      const n = new Set(s);
      if (!n.delete(id)) {
        n.add(id);
      }
      return n;
    });
  }

  selezionaGiorno(g: Giorno): void {
    this.selezionate.update((s) => {
      const n = new Set(s);
      const tutte = g.foto.every((f) => n.has(f.id));
      g.foto.forEach((f) => (tutte ? n.delete(f.id) : n.add(f.id)));
      return n;
    });
  }

  deseleziona(): void {
    this.selezionate.set(new Set());
  }

  // ------------------------------------------------------------ caricamento

  async carica(file: File[]): Promise<void> {
    const immagini = file.filter((f) => FORMATI.test(f.name));
    if (!immagini.length) {
      this.avvisa('Nessun file supportato (JPEG, PNG, GIF, BMP, WebP, HEIC, MP4, MOV)');
      return;
    }
    const stato: StatoCaricamento = { totale: immagini.length, fatti: 0, caricate: 0, duplicate: 0, errori: [], gruppo: 0 };
    this.caricamento.set({ ...stato });
    for (let i = 0; i < immagini.length; i += FILE_PER_RICHIESTA) {
      const gruppo = immagini.slice(i, i + FILE_PER_RICHIESTA);
      try {
        const esiti = await this.caricaGruppo(gruppo, stato);
        for (const e of esiti) {
          if (e.esito === 'CARICATA') stato.caricate++;
          else if (e.esito === 'DUPLICATA') stato.duplicate++;
          else stato.errori.push(`${e.nome}: ${e.messaggio ?? 'errore'}`);
        }
      } catch (e) {
        const smontato = (e as { status?: number }).status === 503;
        gruppo.forEach((f) => stato.errori.push(`${f.name}: ${smontato ? 'archivio smontato' : 'invio non riuscito'}`));
      }
      stato.fatti += gruppo.length;
      stato.gruppo = 0;
      this.caricamento.set({ ...stato, errori: [...stato.errori] });
    }
    this.ricarica();
    this.aggiornaLuoghi();
  }

  private caricaGruppo(gruppo: File[], stato: StatoCaricamento): Promise<Caricamento[]> {
    return new Promise((risolvi, rifiuta) => {
      this.api.carica(gruppo, this.filtro().album).subscribe({
        next: (evento) => {
          if (evento.type === HttpEventType.UploadProgress && evento.total) {
            this.caricamento.set({ ...stato, errori: [...stato.errori], gruppo: evento.loaded / evento.total });
          } else if (evento.type === HttpEventType.Response) {
            risolvi(evento.body ?? []);
          }
        },
        error: rifiuta,
      });
    });
  }

  chiudiCaricamento(): void {
    this.caricamento.set(null);
  }

  avvisa(testo: string): void {
    clearTimeout(this.avvisoTimer);
    this.avviso.set(testo);
    this.avvisoTimer = setTimeout(() => this.avviso.set(null), 4000);
  }
}
