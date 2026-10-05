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

import { FiltroLuogo } from './filtro-luogo';
import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import {
  Cartella,
  Condivisione,
  ElencoTelefoni,
  Foto,
  ImpostazioniRicordiTelegram,
  ModificaTelefono,
  RicordiTelegram,
  StatoSalute,
  StatoTelefono,
  Utente,
} from './modelli';
import { durata } from './formati';
import { esci } from './sessione';
import { Mappa } from './mappa';
import { QuasiUguali } from './quasi-uguali';
import { Ricordi } from './ricordi';
import { TimelineNav } from './timeline-nav';
import { Visore } from './visore';

/** Altezza di riferimento delle righe della griglia, in pixel. */
const ALTEZZA_RIGA = 210;

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FiltroLuogo, FormsModule, Mappa, QuasiUguali, Ricordi, TimelineNav, Visore],
  host: {
    '(document:dragover)': 'trascina($event)',
    '(document:dragleave)': 'esci($event)',
    '(document:drop)': 'rilascia($event)',
    '(document:keydown.escape)': 'esc()',
  },
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly galleria = inject(Galleria);
  private readonly api = inject(FotoApi);

  protected readonly aperta = signal<Foto | null>(null);
  protected readonly vista = signal<'timeline' | 'mappa'>('timeline');
  protected readonly selezione = signal(false);
  private readonly meseInCima = signal<string | null>(null);
  /** Mese visibile in cima alla lista, "yyyy-MM"; prima di scorrere è quello della prima foto. */
  protected readonly meseAttivo = computed(() => this.meseInCima() ?? this.galleria.foto()[0]?.giorno.slice(0, 7) ?? null);
  protected readonly trascinando = signal(false);
  protected readonly dialogoImporta = signal(false);
  protected readonly importando = signal(false);
  protected readonly cartellaAperta = signal<Cartella | null>(null);
  protected albumDaCartella = true;
  /** Sul server (cartella del cloud) si sposta, come deciso; sul PC si copia. */
  protected sposta = false;
  protected ricerca = '';
  protected readonly dialogoTelefoni = signal(false);
  protected readonly telefoni = signal<ElencoTelefoni | null>(null);
  /** Gli utenti visti finora, per scegliere il proprietario (solo admin). */
  protected readonly utenti = signal<Utente[]>([]);
  /** Il telefono nel sotto-dialogo di modifica: un id, "nuovo" per aggiungerne uno, null se chiuso. */
  protected readonly telefonoInModifica = signal<string | null>(null);
  protected readonly salvandoTelefono = signal(false);
  protected moduloTelefono: ModificaTelefono = nuovoTelefono();
  private telefoniTimer?: ReturnType<typeof setTimeout>;
  protected readonly dialogoQuasiUguali = signal(false);
  /** "Condividi": le foto scelte (selezione o album) e, dopo la creazione, il link. */
  protected readonly dialogoCondividi = signal<{ ids?: string[]; album?: string; descrizione: string } | null>(null);
  protected moduloCondividi = { titolo: '', giorni: 7 as number | null, download: false, posizione: false };
  protected readonly creandoLink = signal(false);
  protected readonly linkCreato = signal<string | null>(null);
  protected readonly dialogoCondivisioni = signal(false);
  protected readonly condivisioni = signal<Condivisione[] | null>(null);
  protected readonly dialogoSalute = signal(false);
  protected readonly controlloSalute = signal(false);
  protected readonly provaInCorso = signal(false);
  /** "Ricordi su Telegram", nel dialogo della salute. */
  protected readonly ricordiTelegram = signal<RicordiTelegram | null>(null);
  protected moduloRicordi: ImpostazioniRicordiTelegram = { attivo: true, ora: '08:00', foto: 6, nessunRicordo: false };
  protected readonly salvandoRicordi = signal(false);
  protected readonly provaRicordiInCorso = signal(false);

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

  /** Dalla mappa arriva solo l'id. */
  protected apriDaId(id: string): void {
    this.api.foto(id).subscribe((f) => this.aperta.set(f));
  }

  protected durata(secondi?: number): string {
    return durata(secondi);
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

  protected scegliCaricataDa(username: string): void {
    this.galleria.imposta({ caricataDa: username || undefined });
  }

  protected azzera(): void {
    this.ricerca = '';
    this.galleria.filtro.set({});
    this.galleria.ricarica();
  }

  protected filtriAttivi(): boolean {
    const f = this.galleria.filtro();
    return !!(f.q || f.tag || f.album || f.preferite || f.al || f.caricataDa || f.nazione || f.regione || f.luogo);
  }

  // ------------------------------------------------------------ selezione

  /**
   * Esc chiude quello che è aperto sopra la timeline: il dialogo di
   * importazione, dei telefoni, delle quasi uguali o della salute, altrimenti la selezione. Il visore gestisce il suo Esc da sé.
   */
  protected esc(): void {
    if (this.aperta()) {
      return;
    }
    if (this.dialogoCondividi()) {
      this.dialogoCondividi.set(null);
    } else if (this.dialogoCondivisioni()) {
      this.dialogoCondivisioni.set(false);
    } else if (this.dialogoImporta()) {
      this.chiudiImporta();
    } else if (this.telefonoInModifica()) {
      this.telefonoInModifica.set(null);
    } else if (this.dialogoTelefoni()) {
      this.chiudiTelefoni();
    } else if (this.dialogoQuasiUguali()) {
      this.dialogoQuasiUguali.set(false);
    } else if (this.dialogoSalute()) {
      this.chiudiSalute();
    } else if (this.selezione()) {
      this.esciSelezione();
    }
  }

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
    try {
      await this.galleria.avviaImportazione(cartella, this.albumDaCartella, this.sposta);
      // Va avanti in sottofondo: la barra resta in basso a destra.
      this.chiudiImporta();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Importazione non riuscita');
    } finally {
      this.importando.set(false);
    }
  }

  protected async indicizza(): Promise<void> {
    if (!confirm("Cerca nell'archivio le foto che l'app non conosce e le aggiunge. Può richiedere tempo.")) {
      return;
    }
    try {
      await this.galleria.avviaIndicizzazione();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Indicizzazione non riuscita');
    }
  }

  /**
   * "Calcola luoghi": le foto col GPS senza luogo; se non ce ne sono, chiede
   * se rifarle tutte (per esempio dopo un aggiornamento del dataset).
   */
  protected async calcolaLuoghi(): Promise<void> {
    const daCalcolare = this.galleria.luoghi()?.daCalcolare ?? 0;
    const tutte = daCalcolare === 0;
    if (tutte && !confirm('Tutte le foto col GPS hanno già il luogo. Ricalcolarle tutte?')) {
      return;
    }
    try {
      await this.galleria.avviaLuoghi(tutte);
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Calcolo dei luoghi non partito');
    }
  }

  protected async convertiVideo(): Promise<void> {
    if (
      !confirm(
        'Prepara una versione compatibile (H.264) dei video che non tutti i browser sanno riprodurre, ' +
          "come gli HEVC dell'iPhone. Gira in sottofondo, un video alla volta: può richiedere ore.",
      )
    ) {
      return;
    }
    try {
      await this.galleria.convertiVideo();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Conversione dei video non partita');
    }
  }

  protected percentualeConversioni(): number {
    const s = this.galleria.conversioni();
    return s && s.fatti + s.errori + s.daFare ? Math.round(((s.fatti + s.errori) / (s.fatti + s.errori + s.daFare)) * 100) : 0;
  }

  protected percentualeImportazione(): number {
    const l = this.galleria.importazione();
    return l && l.trovate ? Math.round((l.fatte / l.trovate) * 100) : 0;
  }

  protected chiudiImporta(): void {
    this.dialogoImporta.set(false);
  }

  // ------------------------------------------------------------ telefoni

  protected apriTelefoni(): void {
    this.dialogoTelefoni.set(true);
    this.telefonoInModifica.set(null);
    this.telefoni.set(null);
    if (this.galleria.io()?.admin && this.galleria.io()?.login) {
      this.api.utenti().subscribe({ next: (u) => this.utenti.set(u), error: () => this.utenti.set([]) });
    }
    this.aggiornaTelefoni(true);
  }

  /** Chiede l'elenco; mentre un giro è in coda o in corso lo riguarda ogni 3 secondi. */
  private aggiornaTelefoni(prima = false): void {
    clearTimeout(this.telefoniTimer);
    this.api.telefoni().subscribe({
      next: (t) => {
        if (!this.dialogoTelefoni()) return;
        this.telefoni.set(t);
        if (t.telefoni.some((s) => s.inCorso)) {
          this.telefoniTimer = setTimeout(() => this.aggiornaTelefoni(), 3000);
        }
      },
      error: (e: unknown) => {
        if (prima) {
          this.galleria.avvisa(dettaglio(e) ?? 'Telefoni non leggibili');
          this.chiudiTelefoni();
        } else if (this.dialogoTelefoni()) {
          this.telefoniTimer = setTimeout(() => this.aggiornaTelefoni(), 3000);
        }
      },
    });
  }

  /** Il nome di un utente visto finora, per l'elenco; altrimenti lo username. */
  protected nomeUtente(username?: string): string {
    if (!username) return 'nessuno';
    if (username === this.galleria.io()?.username) return this.galleria.io()?.nome ?? username;
    return this.utenti().find((u) => u.username === username)?.nome ?? this.galleria.nomeDi(username);
  }

  protected aggiungiTelefono(): void {
    this.moduloTelefono = nuovoTelefono();
    this.telefonoInModifica.set('nuovo');
  }

  protected modificaTelefono(t: StatoTelefono): void {
    this.moduloTelefono = {
      nome: t.nome,
      proprietario: t.proprietario ?? '',
      attiva: t.attiva,
      sorgente: t.sorgente,
      intervalloOre: t.intervalloOre,
      giorniPrimaDiCancellare: t.giorniPrimaDiCancellare,
      copieInParallelo: t.copieInParallelo,
    };
    this.telefonoInModifica.set(t.id);
  }

  protected async salvaTelefono(): Promise<void> {
    const id = this.telefonoInModifica();
    if (!id) return;
    this.salvandoTelefono.set(true);
    try {
      await firstValueFrom(
        id === 'nuovo' ? this.api.creaTelefono(this.moduloTelefono) : this.api.salvaTelefono(id, this.moduloTelefono),
      );
      this.galleria.avvisa(id === 'nuovo' ? `${this.moduloTelefono.nome} aggiunto` : 'Impostazioni del telefono salvate');
      this.telefonoInModifica.set(null);
      this.aggiornaTelefoni();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Salvataggio non riuscito');
    } finally {
      this.salvandoTelefono.set(false);
    }
  }

  protected async eliminaTelefono(t: StatoTelefono): Promise<void> {
    if (
      !confirm(
        `Togliere "${t.nome}"? Le foto già arrivate restano; le copie restano registrate, ` +
          'così se lo rimetti con la stessa cartella non ricopia niente.',
      )
    ) {
      return;
    }
    try {
      await firstValueFrom(this.api.eliminaTelefono(t.id));
      this.galleria.avvisa(`${t.nome} tolto`);
      this.aggiornaTelefoni();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Non riesco a toglierlo');
    }
  }

  protected async sincronizzaOra(t: StatoTelefono): Promise<void> {
    try {
      await firstValueFrom(this.api.sincronizzaTelefono(t.id));
      this.aggiornaTelefoni();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Sincronizzazione non partita');
    }
  }

  /** Il prossimo giro, o "entro pochi minuti" se l'ora è già passata (il controllo è ogni 5 minuti). */
  protected prossimoGiro(t: StatoTelefono): string | null {
    if (!t.prossimoGiroIl) return null;
    const quando = new Date(t.prossimoGiroIl);
    return quando.getTime() <= Date.now()
      ? 'entro pochi minuti'
      : quando.toLocaleString('it-IT', { day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' });
  }

  protected chiudiTelefoni(): void {
    clearTimeout(this.telefoniTimer);
    this.telefonoInModifica.set(null);
    this.dialogoTelefoni.set(false);
  }

  // ------------------------------------------------------------ condivisione

  protected apriCondividiSelezione(): void {
    const ids = [...this.galleria.selezionate()];
    this.apriCondividi({ ids, descrizione: `${ids.length} foto selezionate` }, '');
  }

  protected apriCondividiAlbum(): void {
    const album = this.galleria.filtro().album;
    if (album) {
      this.apriCondividi({ album, descrizione: `tutte le foto dell'album "${album}"` }, album);
    }
  }

  private apriCondividi(scelta: { ids?: string[]; album?: string; descrizione: string }, titolo: string): void {
    this.moduloCondividi = { titolo, giorni: 7, download: false, posizione: false };
    this.linkCreato.set(null);
    this.dialogoCondividi.set(scelta);
  }

  protected async creaLink(): Promise<void> {
    const scelta = this.dialogoCondividi();
    if (!scelta) return;
    this.creandoLink.set(true);
    try {
      const c = await firstValueFrom(
        this.api.creaCondivisione({ ...this.moduloCondividi, ids: scelta.ids, album: scelta.album }),
      );
      this.linkCreato.set(FotoApi.linkCondivisione(c));
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Link non creato');
    } finally {
      this.creandoLink.set(false);
    }
  }

  protected async copia(link: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(link);
      this.galleria.avvisa('Link copiato');
    } catch {
      // Senza HTTPS o senza permesso: lo si copia a mano dal campo.
      this.galleria.avvisa('Copia non riuscita: seleziona il link e copialo a mano');
    }
  }

  protected chiudiCondividi(): void {
    if (this.linkCreato() && this.selezione()) {
      this.esciSelezione();
    }
    this.dialogoCondividi.set(null);
  }

  protected apriCondivisioni(): void {
    this.dialogoCondivisioni.set(true);
    this.condivisioni.set(null);
    this.api.condivisioni().subscribe({
      next: (c) => this.condivisioni.set(c),
      error: () => {
        this.galleria.avvisa('Condivisioni non leggibili');
        this.dialogoCondivisioni.set(false);
      },
    });
  }

  protected link(c: Condivisione): string {
    return FotoApi.linkCondivisione(c);
  }

  protected async revoca(c: Condivisione): Promise<void> {
    if (!confirm(`Revocare "${c.titolo}"? Chi ha il link non vedrà più le foto.`)) return;
    try {
      await firstValueFrom(this.api.revocaCondivisione(c.id));
      this.condivisioni.update((l) => l?.map((x) => (x.id === c.id ? { ...x, revocata: true } : x)) ?? null);
    } catch {
      this.galleria.avvisa('Revoca non riuscita');
    }
  }

  // ------------------------------------------------------------ salute

  protected apriSalute(): void {
    this.dialogoSalute.set(true);
    void this.aggiornaSalute();
    this.api.ricordiTelegram().subscribe({ next: (r) => this.mostraRicordi(r), error: () => this.ricordiTelegram.set(null) });
  }

  private mostraRicordi(r: RicordiTelegram): void {
    this.ricordiTelegram.set(r);
    this.moduloRicordi = { attivo: r.attivo, ora: r.ora, foto: r.foto, nessunRicordo: r.nessunRicordo };
  }

  protected async salvaRicordi(): Promise<void> {
    this.salvandoRicordi.set(true);
    try {
      this.mostraRicordi(await firstValueFrom(this.api.salvaRicordiTelegram(this.moduloRicordi)));
      this.galleria.avvisa('Ricordi su Telegram salvati');
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Impostazioni dei ricordi non salvate');
    } finally {
      this.salvandoRicordi.set(false);
    }
  }

  protected async provaRicordi(): Promise<void> {
    this.provaRicordiInCorso.set(true);
    try {
      const esito = await firstValueFrom(this.api.provaRicordiTelegram());
      this.galleria.avvisa(esito.messaggio);
      this.api.ricordiTelegram().subscribe({ next: (r) => this.ricordiTelegram.set(r), error: () => {} });
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Ricordi non mandati');
    } finally {
      this.provaRicordiInCorso.set(false);
    }
  }

  protected async aggiornaSalute(): Promise<void> {
    this.controlloSalute.set(true);
    try {
      if (!(await this.galleria.aggiornaSalute())) {
        this.galleria.avvisa('Salute non leggibile');
      }
    } finally {
      this.controlloSalute.set(false);
    }
  }

  protected async provaTelegram(): Promise<void> {
    this.provaInCorso.set(true);
    try {
      await firstValueFrom(this.api.provaTelegram());
      this.galleria.avvisa('Messaggio di prova mandato su Telegram');
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Messaggio di prova non mandato');
    } finally {
      this.provaInCorso.set(false);
    }
  }

  protected chiudiSalute(): void {
    this.dialogoSalute.set(false);
  }

  protected nomeStato(s: StatoSalute): string {
    return s === 'OK' ? 'tutto a posto' : s === 'ATTENZIONE' ? 'da guardare' : 'qualcosa non va';
  }

  protected logout(): void {
    esci();
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}

function nuovoTelefono(): ModificaTelefono {
  return {
    nome: '',
    proprietario: '',
    attiva: false,
    sorgente: 'pcloud:Automatic Upload',
    intervalloOre: 6,
    giorniPrimaDiCancellare: 7,
    copieInParallelo: 6,
  };
}
