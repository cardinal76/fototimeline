export type OrigineData = 'EXIF' | 'FILE' | 'CARTELLA' | 'CARICAMENTO' | 'GOOGLE' | 'MANUALE';

export type OrigineLuogo = 'GPS' | 'MANUALE';

export interface Foto {
  id: string;
  nomeOriginale: string;
  contentType: string;
  dimensione: number;
  /** Come si vede: con l'orientamento EXIF e la rotazione. */
  larghezza?: number;
  altezza?: number;
  /** Rotazione scelta a mano, in senso orario: 0, 90, 180, 270. Il file non cambia. */
  rotazione: number;
  /** Ora locale di scatto, "2024-05-17T14:03:22". */
  scattataIl: string;
  giorno: string;
  origineData: OrigineData;
  caricataIl: string;
  titolo?: string;
  descrizione?: string;
  tag: string[];
  album?: string;
  preferita: boolean;
  /** L'evento della foto (Eventi), se ne ha uno. */
  evento?: string;
  /** True se l'evento (o il non averne) l'ha deciso qualcuno: il ricalcolo non la tocca. */
  eventoFisso?: boolean;
  fotocamera?: string;
  latitudine?: number;
  longitudine?: number;
  /** Dal GPS (GeoNames) o scelto a mano: la città o il comune, "Sperlonga"; manca se a mano si è scelta solo una regione. */
  luogo?: string;
  /** Solo in Italia: "Provincia di Latina". */
  provincia?: string;
  /** La regione; all'estero il primo livello di GeoNames ("Inghilterra", "Baviera"). */
  regione?: string;
  /** In italiano: "Francia". */
  nazione?: string;
  /** ISO: "FR". */
  codiceNazione?: string;
  /** GPS o MANUALE (il calcolo automatico non lo tocca); assente senza luogo. */
  origineLuogo?: OrigineLuogo;
  /** La città scelta a mano: sulla mappa la foto va lì (approssimativa) se non ha il GPS. */
  latitudineLuogo?: number;
  longitudineLuogo?: number;
  /** Dove sta l'originale nell'archivio: "2024/08/15/IMG_0001.jpg". */
  percorso: string;
  /** Un video (MP4, MOV) invece di una foto. */
  video: boolean;
  /** Durata del video, in secondi. */
  durata?: number;
  /** Username di chi l'ha portata (caricata, importata o dal suo telefono); vuoto per le foto di prima. */
  caricataDa?: string;
  /** Codec del video e dell'audio ("hevc", "aac"), per i video. */
  codecVideo?: string;
  codecAudio?: string;
  /** True se ogni browser riproduce l'originale; false se serve la versione compatibile; assente se non si sa ancora. */
  compatibile?: boolean;
  /** La versione compatibile (H.264 in MP4) dei video che ne hanno bisogno. */
  conversione?: StatoConversione;
  erroreConversione?: string;
  /** Quando è finita nel cestino; assente per le foto nell'archivio. */
  eliminataIl?: string;
  /** Persone e gruppi di volti di questa foto (solo id). */
  persone?: string[];
}

export type StatoConversione = 'IN_CODA' | 'IN_CORSO' | 'FATTA' | 'ERRORE';

/** La coda delle versioni compatibili dei video ("Converti video", solo admin). */
export interface StatoConversioni {
  /** False se le conversioni sono spente o sul server manca ffmpeg. */
  attiva: boolean;
  inCorso: boolean;
  /** C'è da fare ma il cloud è smontato. */
  inAttesa: boolean;
  /** In coda, compreso quello in corso. */
  daFare: number;
  /** Finiti dall'ultimo "Converti video". */
  fatti: number;
  errori: number;
  /** Video che hanno la versione compatibile, in tutto. */
  convertiti: number;
  correnteId?: string;
  corrente?: string;
  /** Del video in corso, 0..100. */
  percentuale?: number;
  messaggi: string[];
}

export interface PaginaFoto {
  foto: Foto[];
  totale: number;
  pagina: number;
  altre: boolean;
}

export interface VoceMese {
  anno: number;
  mese: number;
  conteggio: number;
}

export interface Filtro {
  q?: string;
  tag?: string;
  album?: string;
  preferite?: boolean;
  /** Username: solo le foto portate da questa persona. */
  caricataDa?: string;
  /** Codice ISO della nazione ("IT"); con regione, provincia e luogo come in /api/luoghi. */
  nazione?: string;
  regione?: string;
  provincia?: string;
  luogo?: string;
  /** Id di un evento: solo le sue foto. */
  evento?: string;
  /** Fine del salto nella timeline: si vedono le foto fino a questo giorno. */
  al?: string;
  /**
   * Il filtro per data (Filtri → Data): un anno "2019", un mese "2019-08" o due
   * giorni compresi "2019-08-01..2019-09-15" (un lato vuoto: senza limite).
   * Vale anche per la colonna dei mesi e per la mappa, il salto no.
   */
  periodo?: string;
  /** Cerca q in quello che c'è nella foto ("spiaggia", "cane"), non nei testi. */
  contenuto?: boolean;
  /** Id di una persona o di un gruppo di volti senza nome: solo le sue foto. */
  persona?: string;
}

export type Esito = 'CARICATA' | 'DUPLICATA' | 'ERRORE';

export interface Caricamento {
  nome: string;
  esito: Esito;
  foto?: Foto;
  messaggio?: string;
}

export interface Importazione {
  trovate: number;
  importate: number;
  duplicate: number;
  errori: number;
  /** Originali tolti dalla cartella di origine (importazione con "sposta"). */
  rimossi: number;
  messaggi: string[];
}

/** Chi è collegato. Con il login spento (il PC) si è admin. */
/**
 * Cosa può fare chi è entrato (ConfigurazioneSicurezza): l'amministratore
 * tutto, il gestore le foto (niente Strumenti né utenti), l'utilizzatore
 * solo guardarle. Il server lo controlla comunque: qui serve a nascondere.
 */
export type Ruolo = 'AMMINISTRATORE' | 'GESTORE' | 'UTILIZZATORE';

export interface Io {
  login: boolean;
  /** Quello che finisce in "caricata da"; vuoto con il login spento. */
  username?: string;
  nome?: string;
  /** Null: è del realm ma senza ruolo, vede solo "chiedi l'accesso". */
  ruolo?: Ruolo | null;
  admin: boolean;
  /** Può cambiare le foto e scaricare gli originali (gestore o amministratore). */
  gestore: boolean;
  /** Sotto quale cartella si può importare; null = ovunque (il PC). */
  radiceImportazione?: string;
  /** Cartella svuotata da sola nell'archivio, se impostata. */
  cartellaAutomatica?: string;
}

export type StatoImportazione = 'IN_CORSO' | 'FINITA' | 'ANNULLATA' | 'FALLITA';

/**
 * Lavoro in sottofondo: importazione (manuale o dalla cartella automatica),
 * "Indicizza archivio" o "Calcola luoghi" (lì importate = con un luogo,
 * duplicate = senza, cioè in mare aperto).
 */
/** Un lavoro del giro notturno (GiroNotturno.Risultato). */
export interface RisultatoNotte {
  passo: 'INDICIZZA' | 'LUOGHI' | 'EVENTI' | 'CONTENUTO' | 'VOLTI' | 'IMPRONTE';
  esito: 'FATTO' | 'SPENTO' | 'ANNULLATO' | 'FALLITO';
  quanti: number;
  cosa: string;
  errori: number;
  nota?: string | null;
  iniziatoIl: string;
  finitoIl: string;
}

export interface GiroNotte {
  esito: 'IN_CORSO' | 'FINITO' | 'SALTATO' | 'ANNULLATO' | 'FALLITO';
  aMano: boolean;
  chi?: string | null;
  iniziatoIl: string;
  finitoIl?: string | null;
  passo?: RisultatoNotte['passo'] | null;
  messaggio?: string | null;
  passi: RisultatoNotte[];
}

/** Il giro notturno (GET /api/notte, admin): acceso o no, quando, l'ultimo. */
export interface StatoNotte {
  attivo: boolean;
  cron?: string | null;
  fuso: string;
  /** "ogni notte alle 02:30"; null se spento. */
  orario?: string | null;
  prossimo?: string | null;
  inCorso: boolean;
  ultimo?: GiroNotte | null;
}

export interface LavoroImportazione {
  id: string;
  cartella: string;
  origine: 'MANUALE' | 'AUTOMATICA' | 'INDICIZZAZIONE' | 'LUOGHI' | 'EVENTI';
  stato: StatoImportazione;
  iniziatoIl: string;
  finitoIl?: string;
  trovate: number;
  fatte: number;
  importate: number;
  duplicate: number;
  errori: number;
  rimossi: number;
  messaggi: string[];
  errore?: string;
  /** Solo cartella automatica: file già falliti uguali in un giro prima, non riletti. */
  saltate?: number;
  /** Solo cartella automatica: file ancora in copia, lasciati al giro dopo. */
  inArrivo?: number;
  /** Si stanno ancora cercando i file: `trovate` sono quelli trovati finora. */
  ricerca?: boolean;
  /** Durante la ricerca, la cartella dove si è arrivati (relativa a `cartella`). */
  cercaIn?: string;
  /** Annulla premuto: finiscono i file già cominciati, nessuno nuovo. */
  annullamento?: boolean;
  /** Aspetta che l'importazione automatica, annullata per fargli posto, finisca i suoi file. */
  inAttesa?: boolean;
  /** I file che si stanno leggendo adesso, con la fase e da quanto (vuoto se non è in corso). */
  inLavorazione?: FileInLavorazione[];
}

/** Un file che un lavoro di importazione sta leggendo: oltre il tempo massimo si abbandona. */
export interface FileInLavorazione {
  /** Percorso relativo alla radice delle importazioni, per esempio `telefono/VID_1.mp4`. */
  file: string;
  /** "lettura e impronta", "metadati", "miniatura", "spostamento o copia", ... */
  fase: string;
  /** Da quanti secondi lo si lavora. */
  secondi: number;
  /** Da quanti secondi è in questa fase. */
  secondiFase: number;
}

/** Un file della cartella automatica fallito per colpa sua: non si rilegge finché non cambia. */
export interface FileScartato {
  percorso: string;
  dimensione: number;
  modificatoIl?: string;
  motivo: string;
  quando: string;
}

export interface StatoCloud {
  /** False sul PC: l'archivio è un disco, sempre disponibile. */
  gestito: boolean;
  montato: boolean;
  remoto?: string;
  errore?: string;
}

export interface Cartella {
  percorso: string;
  padre?: string;
  sottocartelle: string[];
  immagini: number;
}

export interface Modifica {
  titolo?: string;
  descrizione?: string;
  tag: string[];
  album?: string;
  preferita: boolean;
  scattataIl?: string;
}

export type Operazione =
  | 'ELIMINA'
  | 'AGGIUNGI_TAG'
  | 'TOGLI_TAG'
  | 'IMPOSTA_ALBUM'
  | 'PREFERITA'
  | 'NON_PREFERITA'
  /** valore: la chiave di un Luogo, vuoto per "nessun luogo". */
  | 'IMPOSTA_LUOGO'
  | 'LUOGO_DA_GPS';

/** Giorno della timeline: le foto scattate quel giorno, in ordine. */
export interface Giorno {
  giorno: string;
  /** Prima voce di un nuovo mese: la timeline ci mette l'intestazione del mese. */
  nuovoMese: boolean;
  foto: Foto[];
}

/** Una foto sulla mappa. */
export interface PuntoMappa {
  id: string;
  lat: number;
  lon: number;
  giorno: string;
  titolo?: string;
  video: boolean;
  luogo?: string;
  /** Senza GPS (o col GPS lontano): alle coordinate della città scelta a mano. */
  approssimativa: boolean;
  /** Rotazione scelta a mano: va nell'indirizzo della miniatura. */
  rotazione: number;
}

/** I luoghi delle foto con quante ce ne sono (GET /api/luoghi), i più fotografati prima. */
export interface ElencoLuoghi {
  /** Il dataset di GeoNames c'è sul server. */
  disponibile: boolean;
  /** Foto con GPS a cui il luogo non è ancora stato cercato. */
  daCalcolare: number;
  nazioni: VoceNazione[];
}

export interface VoceNazione {
  codice: string;
  nome: string;
  conteggio: number;
  regioni: VoceRegione[];
  /** Altri nomi in italiano, per la ricerca: "Olanda" per i Paesi Bassi. */
  altriNomi: string[];
}

export interface VoceRegione {
  /** Assente se GeoNames non la sa. */
  nome?: string;
  conteggio: number;
  /** In Italia le province della regione; vuote all'estero. */
  province: VoceProvincia[];
  /** Le città della regione, anche quelle dentro le province. */
  luoghi: VoceLuogo[];
}

export interface VoceProvincia {
  nome: string;
  conteggio: number;
}

export interface VoceLuogo {
  nome: string;
  provincia?: string;
  conteggio: number;
}

/** Un luogo di GeoNames da scegliere a mano (GET /api/luoghi/cerca): città, provincia, regione o nazione. */
export interface Luogo {
  /** "citta:2643123", "provincia:IT.17.TN", "regione:IT.17", "nazione:NL". */
  chiave: string;
  livello: 'CITTA' | 'PROVINCIA' | 'REGIONE' | 'NAZIONE';
  luogo?: string;
  provincia?: string;
  regione?: string;
  nazione?: string;
  codiceNazione?: string;
  latitudine?: number;
  longitudine?: number;
}

/** "Accadde oggi": le foto di un anno passato nello stesso giorno. */
export interface Ricordo {
  anno: number;
  anniFa: number;
  foto: Foto[];
}

/** Un backup dei metadati, nella cartella .backup dell'archivio. */
export interface CopiaBackup {
  nome: string;
  dimensione: number;
  fattoIl: string;
}

/** Com'è andato un giro della sincronizzazione del telefono. */
export interface GiroTelefono {
  iniziatoIl: string;
  finitoIl?: string;
  /** Vuoto mentre il giro è in corso. */
  esito?: 'OK' | 'ERRORE';
  messaggio?: string;
  /** File di pCloud nei formati dell'archivio. */
  trovati: number;
  copiati: number;
  giaCopiati: number;
  /** Tolti da pCloud perché già importati. */
  cancellati: number;
  /** Copie a metà dei giri vecchi tolte dalla cartella automatica (e ricopiate intere). */
  aMetaTolte?: number;
  errori: number;
  messaggiErrori: string[];
  /** Video troppo grandi per il cloud ricompressi e importati, con l'originale spostato, in questo giro. */
  ricompressi?: number;
  /** Video troppo grandi ancora da finire (in coda, in corso, rimandati, in attesa dell'importazione). */
  inRicompressione?: number;
  /** Una riga per video troppo grande: prima e dopo, dove è l'originale. */
  videoGrandi?: string[];
}

/** Un video del telefono troppo grande per il cloud, ricompresso in H.265 sul PC (o sul server). */
export interface VideoGrande {
  id: string;
  nome: string;
  percorso: string;
  stato:
    | 'IN_CODA'
    | 'IN_CORSO'
    | 'RIMANDATO'
    | 'CARICATO'
    | 'FATTO'
    | 'ERRORE'
    | 'ANNULLATO'
    | 'DA_RICOMPRIMERE_PC'
    | 'RICOMPRESSO_PC';
  /** Solo per quello in corso: "ricomprimo in H.265 (CRF 24)". */
  fase?: string;
  percentuale?: number;
  dimensione: number;
  dimensioneRicompresso?: number;
  crf?: number;
  /** Il ricompresso nella cartella automatica. */
  destinazione?: string;
  /** Dove è finito l'originale su pCloud. */
  originaleIn?: string;
  /** Perché si ricomprime: la soglia o l'errore del cloud. */
  motivo?: string;
  messaggio?: string;
  creatoIl: string;
  aggiornatoIl: string;
  /** Ricompresso sul PC (deploy/ricomprimi-video-grandi.sh). */
  dalPc?: boolean;
  /** Quello che il PC sta facendo: "sul PC: ricomprimo in H.265 (CRF 24) 37%". */
  sulPc?: string;
}

/** Un telefono della famiglia (pCloud → cartella automatica). */
export interface StatoTelefono {
  id: string;
  /** "Telefono di Anna". */
  nome: string;
  /** Username di chi ha il telefono; vuoto solo per quello migrato, finché un admin non entra. */
  proprietario?: string;
  attiva: boolean;
  /** Remote di rclone e cartella: "pcloud:Automatic Upload". */
  sorgente: string;
  intervalloOre: number;
  /** 0 = non togliere mai da pCloud. */
  giorniPrimaDiCancellare: number;
  /** Quanti file si copiano insieme (1–32). */
  copieInParallelo: number;
  /** Un giro in coda o in corso. */
  inCorso: boolean;
  /** In coda: aspetta che finisca il giro di un altro telefono. */
  inCoda: boolean;
  ultimoGiro?: GiroTelefono;
  prossimoGiroIl?: string;
  /** I contatori del giro che sta girando (esito ancora vuoto). */
  giroInCorso?: GiroTelefono;
  /** I video troppo grandi: in corso, andati male e quelli fatti negli ultimi 30 giorni. */
  videoGrandi?: VideoGrande[];
}

/** "Indicizza contenuto": manda a visione le foto che non sono ancora nell'indice. */
export interface LavoroContenuto {
  id: string;
  stato: 'IN_CORSO' | 'FINITO' | 'ANNULLATO' | 'FALLITO';
  iniziatoIl: string;
  finitoIl?: string;
  /** Le foto senza indice quando è partito. */
  daFare: number;
  fatte: number;
  errori: number;
  messaggi: string[];
  errore?: string;
}

/** Ricerca per contenuto: se c'è (servizio visione configurato) e quante foto mancano all'indice. */
export interface StatoContenuto {
  attiva: boolean;
  foto: number;
  indicizzate: number;
  mancanti: number;
  /** Vettori nell'indice di visione; assente se visione non risponde. */
  nellIndice?: number;
  lavoro?: LavoroContenuto;
}

export interface LavoroVolti {
  id: string;
  stato: 'IN_CORSO' | 'FINITO' | 'ANNULLATO' | 'FALLITO';
  iniziatoIl: string;
  finitoIl?: string;
  /** Le foto senza volti cercati quando è partito. */
  daFare: number;
  fatte: number;
  volti: number;
  errori: number;
  messaggi: string[];
  errore?: string;
  ricostruisci: boolean;
}

/** Riconoscimento dei volti: se c'è e a che punto è. */
export interface StatoVolti {
  attiva: boolean;
  foto: number;
  cercate: number;
  mancanti: number;
  volti: number;
  /** Volti nell'indice di visione; assente se visione non risponde. */
  nellIndice?: number;
  /** Le persone con un nome. */
  persone: number;
  lavoro?: LavoroVolti;
}

export type TipoPersone = 'NOMINATE' | 'GRUPPI' | 'NASCOSTE';

/** Una persona (con nome) o un gruppo di volti senza nome. */
export interface VocePersona {
  id: string;
  nome?: string;
  nascosta: boolean;
  volti: number;
  proposte: number;
  /** Id del volto da mostrare: /api/volti/{copertina}/ritaglio. */
  copertina?: string;
}

export interface ElencoPersone {
  persone: VocePersona[];
  totale: number;
}

export interface VoceVolto {
  id: string;
  fotoId: string;
  punteggio: number;
  /** Con la persona (o con la proposta): da -1 a 1. */
  somiglianza?: number;
  manuale: boolean;
}

export interface PersonaSimile {
  persona: VocePersona;
  somiglianza: number;
  vicini: number;
}

/** Un volto nel visore; il riquadro è in frazioni dell'immagine. */
export interface VoltoInFoto {
  id: string;
  riquadro: { x: number; y: number; larghezza: number; altezza: number };
  personaId?: string;
  nome?: string;
  nascosta: boolean;
  proposta?: string;
  nomeProposta?: string;
}

/** I telefoni visibili (l'admin tutti, gli altri il proprio) e quello che vale per tutti. */
export interface ElencoTelefoni {
  disponibile: boolean;
  /** Perché qui la sincronizzazione non può girare, se non disponibile. */
  motivo?: string;
  /** Dove finiscono le copie: "lifetime:telefono". */
  destinazione?: string;
  telefoni: StatoTelefono[];
}

export interface ModificaTelefono {
  nome: string;
  proprietario: string;
  attiva: boolean;
  sorgente: string;
  intervalloOre: number;
  giorniPrimaDiCancellare: number;
  copieInParallelo: number;
}

/** Un utente entrato almeno una volta (solo per gli admin). */
export interface Utente {
  username: string;
  nome: string;
  email?: string;
  primoAccesso: string;
  ultimoAccesso: string;
  admin: boolean;
}

/** Una voce del filtro "Caricate da". */
export interface CaricateDa {
  username: string;
  nome: string;
  conteggio: number;
}

/** Una foto come la vede chi ha un link di condivisione: niente nome del file, percorso, tag. */
export interface FotoCondivisa {
  id: string;
  video: boolean;
  larghezza?: number;
  altezza?: number;
  scattataIl: string;
  durata?: number;
  titolo?: string;
  /** Solo se chi ha creato il link ha scelto "includi posizione". */
  latitudine?: number;
  longitudine?: number;
  /** Rotazione scelta a mano (0, 90, 180, 270): i video si girano col CSS. */
  rotazione: number;
}

/** La galleria pubblica di un link (/c/<token>). */
export interface GalleriaCondivisa {
  titolo: string;
  scadeIl?: string;
  download: boolean;
  /** False col cloud smontato: si vedono solo le miniature. */
  archivioDisponibile: boolean;
  foto: FotoCondivisa[];
}

/** Per creare un link: le foto da una selezione, da un album o da un intervallo di giorni. */
export interface NuovaCondivisione {
  titolo?: string;
  ids?: string[];
  album?: string;
  dal?: string;
  al?: string;
  /** 1, 7, 30; null = non scade. */
  giorni: number | null;
  download: boolean;
  posizione: boolean;
}

/** Un link nell'elenco "Le mie condivisioni". */
export interface Condivisione {
  id: string;
  token: string;
  titolo: string;
  foto: number;
  origine: 'SELEZIONE' | 'ALBUM' | 'DATE';
  descrizioneOrigine?: string;
  creataDa: string;
  creataIl: string;
  scadeIl?: string;
  download: boolean;
  posizione: boolean;
  visite: number;
  ultimoAccesso?: string;
  revocata: boolean;
  scaduta: boolean;
}

/** Una foto di un gruppo di quasi uguali. */
export interface FotoSimile {
  id: string;
  /** "/api/foto/<id>/miniatura" */
  miniatura: string;
  nomeOriginale: string;
  larghezza?: number;
  altezza?: number;
  dimensione: number;
  scattataIl?: string;
  fotocamera?: string;
  preferita: boolean;
  /** Quella che il server consiglia di tenere: risoluzione, peso, EXIF, data. */
  suggerita: boolean;
  /** Da tenere all'inizio: la suggerita e le preferite. */
  tieni: boolean;
}

/** Una foto del cestino. */
export interface VoceCestino {
  id: string;
  /** "/api/foto/<id>/miniatura" */
  miniatura: string;
  nomeOriginale: string;
  video: boolean;
  larghezza?: number;
  altezza?: number;
  scattataIl?: string;
  eliminataIl: string;
  /** Quando se ne andrà da sola; assente se il cestino non si svuota da solo. */
  siEliminaIl?: string;
  /** Giorni interi che mancano (0 = al prossimo controllo). */
  giorniRimasti?: number;
}

export interface PaginaCestino {
  foto: VoceCestino[];
  totale: number;
  pagina: number;
  altre: boolean;
  /** Giorni del cestino; 0 = le foto restano finché non le si elimina a mano. */
  giorni: number;
}

export interface GruppoSimili {
  foto: FotoSimile[];
}

export interface PaginaGruppi {
  gruppi: GruppoSimili[];
  totale: number;
  pagina: number;
  altre: boolean;
}

/** "Calcola impronte": stato vuoto se dall'avvio del server non è mai partito. */
export interface StatoImpronte {
  stato?: 'IN_CORSO' | 'FINITO' | 'ANNULLATO' | 'FALLITO';
  iniziatoIl?: string;
  finitoIl?: string;
  totale: number;
  fatte: number;
  calcolate: number;
  errori: number;
  errore?: string;
  /** Foto (non video) ancora senza impronta: non entrano nel confronto. */
  senzaImpronta: number;
}

/** Pallino della pagina "Salute": verde, giallo, rosso. */
export type StatoSalute = 'OK' | 'ATTENZIONE' | 'ERRORE';

export interface VoceSalute {
  chiave: string;
  titolo: string;
  stato: StatoSalute;
  messaggio: string;
  dettagli: string[];
}

/** GET /api/salute (solo admin). */
export interface Salute {
  stato: StatoSalute;
  voci: VoceSalute[];
  controllatoIl: string;
  /** Token e chat di Telegram impostati sul server. */
  avvisiTelegram: boolean;
}

/** Quello che si cambia di "Ricordi su Telegram". */
export interface ImpostazioniRicordiTelegram {
  attivo: boolean;
  /** "HH:mm", nel fuso del server. */
  ora: string;
  /** Foto al massimo, 1–10. */
  foto: number;
  /** Nei giorni senza foto manda "Nessun ricordo oggi". */
  nessunRicordo: boolean;
}

/** GET /api/ricordi-telegram (solo admin): "Accadde oggi" ogni mattina su Telegram. */
export interface RicordiTelegram extends ImpostazioniRicordiTelegram {
  telegramConfigurato: boolean;
  /** Una chat solo per i ricordi (FOTOTIMELINE_RICORDI_CHAT), non quella degli avvisi. */
  chatSeparata: boolean;
  fuso: string;
  /** L'ultimo giorno fatto, "yyyy-MM-dd". */
  ultimoGiorno?: string;
  ultimoInvioIl?: string;
  ultimoEsito?: string;
}

// ------------------------------------------------------------ Google Foto

export type FaseScelta = 'ATTESA_SCELTA' | 'SCARICO' | 'FINITA' | 'ANNULLATA' | 'SCADUTA' | 'FALLITA';

/** "Scegli da Google Foto": una scelta col Picker, in corso o l'ultima. */
export interface SceltaGoogle {
  id: string;
  fase: FaseScelta;
  inCorso: boolean;
  /** La pagina di Google dove scegliere (con /autoclose). */
  pickerUri: string;
  iniziataIl: string;
  finitaIl?: string;
  totali: number;
  fatte: number;
  nuove: number;
  giaPresenti: number;
  errori: number;
  messaggi: string[];
  errore?: string;
}

/** Il collegamento a Google di chi usa l'app; configurato = la funzione c'è sul server. */
export interface CollegamentoGoogle {
  configurato: boolean;
  collegato: boolean;
  collegatoIl?: string;
  scelta?: SceltaGoogle | null;
}

export type FaseTakeout = 'ELENCO' | 'SCARICO' | 'IMPORTO' | 'ATTESA_CLOUD' | 'PULIZIA' | 'FINITO' | 'ANNULLATO' | 'FALLITO';

export interface AvanzamentoTakeout {
  fase: FaseTakeout;
  inCorso: boolean;
  iniziatoIl: string;
  finitoIl?: string;
  zip?: string;
  zipIndice: number;
  zipTotali: number;
  scaricati: number;
  daScaricare: number;
  fileFatti: number;
  fileTotali: number;
  nuove: number;
  giaPresenti: number;
  senzaJson: number;
  saltati: number;
  errori: number;
  errore?: string;
  messaggi: string[];
}

export type StatoZip = 'IN_CORSO' | 'FATTO' | 'CON_ERRORI' | 'FALLITO';

/** Uno zip del registro di Google Takeout. */
export interface ZipTakeout {
  id: string;
  sorgente: string;
  nome: string;
  dimensione: number;
  modificatoIl?: string;
  stato: StatoZip;
  iniziatoIl?: string;
  finitoIl?: string;
  file: number;
  fatti: number;
  nuove: number;
  giaPresenti: number;
  senzaJson: number;
  saltati: number;
  errori: number;
  messaggi: string[];
  errore?: string;
  cancellatoIl?: string;
}

export interface StatoTakeout {
  disponibile: boolean;
  motivo?: string;
  /** Remote di rclone e cartella: "gdrive:Takeout". */
  sorgente: string;
  /** "Caricate da"; vuoto = chi preme Avvia. */
  proprietario?: string;
  /** 0 = non togliere mai gli zip da Drive. */
  giorniPrimaDiCancellare: number;
  /** Dove si scarica uno zip alla volta, sul server. */
  cartella: string;
  lavoro?: AvanzamentoTakeout | null;
  registro: ZipTakeout[];
}

export interface ModificaTakeout {
  sorgente: string;
  proprietario: string;
  giorniPrimaDiCancellare: number;
}

/** Un evento: foto vicine nel tempo (e nel posto) con un nome. */
export interface Evento {
  id: string;
  nome: string;
  /** "2019-08-10T10:00:00". */
  inizio: string;
  fine: string;
  /** "2019-08-10". */
  primoGiorno: string;
  ultimoGiorno: string;
  conteggio: number;
  /** La foto della copertina (id). */
  copertina?: string;
  luogo?: string;
  /** Toccato a mano: il ricalcolo non lo cambia. */
  manuale: boolean;
  nascosto: boolean;
  /** Creato a mano per date: primo e ultimo giorno ("2019-08-10"); ha tutte le foto di quei giorni. */
  dal?: string;
  al?: string;
}

/** Dove è arrivato "Calcola eventi". */
export interface StatoCalcoloEventi {
  /** La fine dell'ultimo gruppo fatto, se il calcolo si è fermato a metà. */
  fattoFinoA?: string;
  /** Fermato a metà: il prossimo riprende da fattoFinoA. */
  interrotto: boolean;
  iniziatoIl?: string;
  finitoIl?: string;
}

export interface ElencoEventi {
  eventi: Evento[];
  /** Assente se "Calcola eventi" non è mai partito. */
  calcolo?: StatoCalcoloEventi;
  /** Giorni con foto nuove o tolte che aspettano il ricalcolo leggero. */
  giorniDaRifare: number;
  minimoFoto: number;
}

// ------------------------------------------------------------ pagina Utenti (solo amministratore)

/** Un utente del realm di Keycloak, come lo dà /api/gestione-utenti. */
export interface VoceUtente {
  /** L'id di Keycloak. */
  id: string;
  username: string;
  nome?: string;
  cognome?: string;
  email?: string;
  attivo: boolean;
  /** Null = nessuno dei tre ruoli: entra ma non vede niente. */
  ruolo?: Ruolo | null;
  ultimoAccesso?: string;
}

export interface ElencoUtenti {
  /** False se la pagina non è configurata (manca il segreto del client): motivo dice cosa fare. */
  disponibile: boolean;
  motivo?: string;
  utenti: VoceUtente[];
}

export interface NuovoUtente {
  username: string;
  nome?: string;
  cognome?: string;
  email?: string;
  ruolo: Ruolo;
  /** La password iniziale (Keycloak la fa cambiare al primo accesso); vuota con l'invito. */
  password?: string;
  /** Niente password: Keycloak manda l'email per sceglierla. */
  invito: boolean;
}
