import { DatePipe, NgTemplateOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  Injector,
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
import { GoogleFoto } from './google-foto';
import {
  Cartella,
  CollegamentoGoogle,
  Condivisione,
  ElencoTelefoni,
  Foto,
  ImpostazioniRicordiTelegram,
  ModificaTelefono,
  RicordiTelegram,
  StatoContenuto,
  StatoSalute,
  StatoTelefono,
  Utente,
  FileScartato,
  LavoroImportazione,
} from './modelli';
import { durata } from './formati';
import { esci } from './sessione';
import { Mappa } from './mappa';
import { QuasiUguali } from './quasi-uguali';
import { inAttesa } from './condivisi-in-attesa';
import { RiceviCondivisi } from './ricevi-condivisi';
import { Ricordi } from './ricordi';
import { TimelineNav } from './timeline-nav';
import { Visore } from './visore';

/** I menu a tendina della barra: uno aperto alla volta. */
type Menu = 'filtri' | 'strumenti' | 'utente';

/** Altezza di riferimento delle righe della griglia, in pixel. */
const ALTEZZA_RIGA = 210;

@Component({
  selector: 'app-root',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FiltroLuogo, FormsModule, GoogleFoto, Mappa, NgTemplateOutlet, QuasiUguali, RiceviCondivisi, Ricordi, TimelineNav, Visore],
  host: {
    '(document:dragover)': 'trascina($event)',
    '(document:dragleave)': 'esci($event)',
    '(document:drop)': 'rilascia($event)',
    '(document:keydown.escape)': 'esc()',
    '(document:click)': 'fuoriMenu($event)',
  },
  templateUrl: './app.html',
  styleUrls: ['./app.css', './barra.css'],
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
  /** "Carica N foto dal telefono": file arrivati con "Condividi → FotoTimeline" (sw.js). */
  protected readonly dialogoDalTelefono = signal(false);
  /** "Google Foto": il bottone c'è se il server ha le credenziali di Google (o per l'admin, il Takeout). */
  protected readonly google = signal<CollegamentoGoogle | null>(null);
  protected readonly dialogoGoogle = signal(false);

  /** Il menu della barra aperto (Filtri, Strumenti, utente), o null. */
  protected readonly menu = signal<Menu | null>(null);
  /** Quanti filtri sono attivi, per il numerino sul pulsante Filtri (la ricerca ha il suo campo). */
  protected readonly quantiFiltri = computed(() => {
    const f = this.galleria.filtro();
    return [f.tag, f.album, f.caricataDa, f.nazione || f.regione || f.luogo, f.preferite].filter(Boolean).length;
  });
  /** Un problema da vedere a colpo d'occhio sul pulsante Strumenti: cloud smontato o salute non a posto. */
  protected readonly avvisoSistema = computed<{ stato: StatoSalute; testo: string } | null>(() => {
    const c = this.galleria.cloud();
    const s = this.galleria.io()?.admin ? this.galleria.salute()?.stato : undefined;
    if (c?.gestito && !c.montato) {
      return { stato: 'ERRORE', testo: 'Cloud smontato' + (s && s !== 'OK' ? ' · salute: ' + this.nomeStato(s) : '') };
    }
    return s && s !== 'OK' ? { stato: s, testo: 'Salute: ' + this.nomeStato(s) } : null;
  });

  /** "MC" per Marco Cardinali: il menu utente quando la barra è stretta. */
  protected readonly iniziali = computed(() =>
    (this.galleria.io()?.nome ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((p) => p[0].toUpperCase())
      .join(''),
  );

  private readonly barra = viewChild.required<ElementRef<HTMLElement>>('barra');
  private readonly injector = inject(Injector);
  /** Il pulsante che ha aperto il menu: ci torna il focus quando si chiude. */
  private innesco: HTMLElement | null = null;
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
    void this.condivisiDalTelefono();
    this.ritornoDaGoogle();
    this.api.google().subscribe({ next: (g) => this.google.set(g), error: () => this.google.set(null) });
  }

  /** Dopo il consenso Google torna a /?google=collegato (o negato, errore): si riapre il dialogo. */
  private ritornoDaGoogle(): void {
    const parametri = new URLSearchParams(location.search);
    const esito = parametri.get('google');
    if (esito === null) {
      return;
    }
    parametri.delete('google');
    const resto = parametri.toString();
    history.replaceState(history.state, '', location.pathname + (resto ? `?${resto}` : '') + location.hash);
    this.galleria.avvisa(
      esito === 'collegato'
        ? 'Google collegato: ora puoi scegliere le foto'
        : esito === 'negato'
          ? 'Collegamento a Google annullato'
          : 'Collegamento a Google non riuscito: riprova',
    );
    this.apriGoogle();
  }

  protected apriGoogle(): void {
    if (this.galleria.io()?.admin && this.galleria.io()?.login && !this.utenti().length) {
      this.api.utenti().subscribe({ next: (u) => this.utenti.set(u), error: () => this.utenti.set([]) });
    }
    this.dialogoGoogle.set(true);
  }

  // ------------------------------------------------------------ dal telefono

  /**
   * Il service worker manda a /?condivisi=<lotto> dopo aver messo da parte i
   * file; senza service worker il server manda a /?condivisi=senza-app. Il
   * dialogo si apre anche senza parametro se ci sono file in attesa (per
   * esempio dopo un login o un "Più tardi").
   */
  private async condivisiDalTelefono(): Promise<void> {
    const parametri = new URLSearchParams(location.search);
    const condivisi = parametri.get('condivisi');
    if (condivisi !== null) {
      parametri.delete('condivisi');
      const resto = parametri.toString();
      history.replaceState(history.state, '', location.pathname + (resto ? `?${resto}` : '') + location.hash);
      if (condivisi === 'senza-app') {
        this.galleria.avvisa("La condivisione non è arrivata all'app: riapri FotoTimeline e riprova a condividere");
      } else if (condivisi === 'errore') {
        this.galleria.avvisa('Il telefono non è riuscito a tenere i file condivisi (spazio pieno?)');
      } else if (condivisi === 'vuoto') {
        this.galleria.avvisa('Nessun file arrivato dalla condivisione');
      }
    }
    try {
      if ((await inAttesa()).length) {
        this.dialogoDalTelefono.set(true);
      }
    } catch {
      // Senza IndexedDB (navigazione privata di alcuni browser): niente da caricare.
    }
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
    // L'interruttore "per contenuto" è un modo di cercare, non un filtro: resta com'è.
    this.galleria.filtro.set({ contenuto: this.galleria.filtro().contenuto });
    this.galleria.ricarica();
  }

  protected segnaposto(): string {
    return this.galleria.filtro().contenuto ? 'Cosa c’è nella foto: spiaggia, cane, neve…' : 'Cerca titolo, tag, album, file…';
  }

  /** Da testi a contenuto e ritorno, con quello che c'è già scritto nel campo. */
  protected commutaContenuto(): void {
    clearTimeout(this.ricercaTimer);
    this.galleria.imposta({ contenuto: !this.galleria.filtro().contenuto || undefined, q: this.ricerca.trim() || undefined });
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
    if (this.menu()) {
      this.chiudiMenu();
      return;
    }
    if (this.dialogoDalTelefono()) {
      // Lo gestisce RiceviCondivisi (non durante l'invio).
      return;
    }
    if (this.dialogoGoogle()) {
      this.dialogoGoogle.set(false);
    } else if (this.dialogoCondividi()) {
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

  // ------------------------------------------------------------ menu della barra

  /** Apre o chiude un menu; aprendolo il focus va sulla prima voce (o sull'ultima con la freccia su). */
  protected commutaMenu(m: Menu, evento: Event, ultima = false): void {
    if (this.menu() === m) {
      this.chiudiMenu();
      return;
    }
    this.innesco = evento.currentTarget as HTMLElement;
    this.menu.set(m);
    afterNextRender(
      () => {
        const voci = this.vociMenu();
        (ultima ? voci[voci.length - 1] : voci[0])?.focus();
      },
      { injector: this.injector },
    );
  }

  /** Chiude il menu aperto e riporta il focus sul suo pulsante. */
  protected chiudiMenu(): void {
    if (!this.menu()) {
      return;
    }
    this.menu.set(null);
    this.innesco?.focus();
    this.innesco = null;
  }

  /** Frecce su e giù sul pulsante di un menu: lo aprono come il clic. */
  protected tastoInnesco(m: Menu, e: KeyboardEvent): void {
    if ((e.key === 'ArrowDown' || e.key === 'ArrowUp') && this.menu() !== m) {
      e.preventDefault();
      this.commutaMenu(m, e, e.key === 'ArrowUp');
    }
  }

  /** Dentro un menu: frecce, Home e Fine spostano tra le voci; Tab lo chiude e lascia andare il focus. */
  protected muoviNelMenu(e: KeyboardEvent): void {
    const voci = this.vociMenu();
    const i = voci.indexOf(document.activeElement as HTMLElement);
    const dove: Record<string, number> = { ArrowDown: i + 1, ArrowUp: i - 1, Home: 0, End: voci.length - 1 };
    if (e.key === 'Tab') {
      this.menu.set(null);
      this.innesco = null;
    } else if (e.key in dove && voci.length) {
      e.preventDefault();
      voci[(dove[e.key] + voci.length) % voci.length].focus();
    }
  }

  /** Clic fuori dal menu aperto (e dal suo pulsante): si chiude. */
  protected fuoriMenu(e: Event): void {
    const m = this.menu();
    // composedPath e non contains: la voce cliccata può essere già sparita dal DOM.
    if (m && !e.composedPath().some((n) => n instanceof HTMLElement && n.dataset['menu'] === m)) {
      this.menu.set(null);
      this.innesco = null;
    }
  }

  /** Il focus esce dal menu (Tab, o un clic altrove): si chiude senza riprendersi il focus. */
  protected focusFuori(e: FocusEvent): void {
    const verso = e.relatedTarget as Node | null;
    if (verso && !(e.currentTarget as HTMLElement).contains(verso)) {
      this.menu.set(null);
      this.innesco = null;
    }
  }

  /** Le voci raggiungibili del menu aperto: per il pannello Filtri i suoi controlli. */
  private vociMenu(): HTMLElement[] {
    const tendina = this.barra().nativeElement.querySelector('.tendina');
    if (!tendina) {
      return [];
    }
    const selettore = tendina.getAttribute('role') === 'menu'
      ? '[role^="menuitem"]:not([disabled]):not([aria-disabled="true"])'
      : 'select, button:not([disabled]), input';
    return [...tendina.querySelectorAll<HTMLElement>(selettore)].filter((v) => v.offsetParent !== null);
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

  // ------------------------------------------------------------ ricerca per contenuto

  protected async indicizzaContenuto(c: StatoContenuto): Promise<void> {
    const daCapo = c.mancanti === 0;
    const domanda = daCapo
      ? `Tutte le ${c.foto} foto sono già nell'indice. Rifarlo da capo? Serve solo se l'indice di visione è andato perso.`
      : `Manda ${c.mancanti} foto alla ricerca per contenuto. Con tante foto ci vogliono ore; ` +
        'va avanti in sottofondo e, se il server riparte, riprende da dove era arrivato.';
    if (!confirm(domanda)) {
      return;
    }
    try {
      await this.galleria.avviaIndiceContenuto(daCapo);
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Indicizzazione del contenuto non partita');
    }
  }

  protected percentualeContenuto(): number {
    const l = this.galleria.contenuto()?.lavoro;
    return l && l.daFare ? Math.round(((l.fatte + l.errori) / l.daFare) * 100) : 0;
  }

  protected titoloContenuto(c: StatoContenuto): string {
    const righe = [`Ricerca per contenuto: ${c.indicizzate} foto su ${c.foto} nell'indice`];
    if (c.nellIndice === undefined || c.nellIndice === null) {
      righe.push('Il servizio visione non risponde');
    }
    const l = c.lavoro;
    if (l?.stato === 'IN_CORSO') {
      righe.push(`In corso: ${l.fatte} su ${l.daFare}` + (l.errori ? `, ${l.errori} errori` : ''));
    } else if (l?.stato === 'FALLITO') {
      righe.push(`Ultimo giro non riuscito: ${l.errore ?? ''}`);
    } else if (l?.errori) {
      righe.push(`Ultimo giro: ${l.errori} foto non indicizzate (${l.messaggi[0] ?? ''})`);
    }
    return righe.join('\n');
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

  /** L'elenco degli errori del lavoro mostrato, aperto con un clic su "N errori". */
  protected readonly erroriAperti = signal<string | null>(null);
  /** I file già falliti che la cartella automatica non rilegge; null finché non si chiedono. */
  protected readonly scartati = signal<FileScartato[] | null>(null);

  protected commutaErrori(l: LavoroImportazione): void {
    this.erroriAperti.set(this.erroriAperti() === l.id ? null : l.id);
  }

  protected commutaScartati(): void {
    if (this.scartati()) {
      this.scartati.set(null);
      return;
    }
    this.api.scartati().subscribe({
      next: (s) => this.scartati.set(s),
      error: (e: unknown) => this.galleria.avvisa(dettaglio(e) ?? 'Elenco non leggibile'),
    });
  }

  protected riprovaScartati(): void {
    if (!confirm('Al prossimo giro la cartella automatica rilegge anche i file già falliti. Va bene?')) {
      return;
    }
    this.api.riprovaScartati().subscribe({
      next: (r) => {
        this.scartati.set(null);
        this.galleria.avvisa(`${r.dimenticati} file da rileggere al prossimo giro`);
      },
      error: (e: unknown) => this.galleria.avvisa(dettaglio(e) ?? 'Non riuscito'),
    });
  }

  /** Il nome breve per l'indicatore nella barra. */
  protected nomeLavoro(l: LavoroImportazione): string {
    switch (l.origine) {
      case 'AUTOMATICA':
        return 'Importazione automatica';
      case 'INDICIZZAZIONE':
        return 'Indicizzazione';
      case 'LUOGHI':
        return 'Luoghi';
      default:
        return 'Importazione';
    }
  }

  /** Il nome del file di un percorso, per l'elenco dei già falliti. */
  protected nomeFile(percorso: string): string {
    return percorso.substring(percorso.lastIndexOf('/') + 1);
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
