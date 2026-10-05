export type OrigineData = 'EXIF' | 'FILE' | 'CARTELLA' | 'CARICAMENTO' | 'MANUALE';

export interface Foto {
  id: string;
  nomeOriginale: string;
  contentType: string;
  dimensione: number;
  larghezza?: number;
  altezza?: number;
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
  fotocamera?: string;
  latitudine?: number;
  longitudine?: number;
  /** Dove sta l'originale nell'archivio: "2024/08/15/IMG_0001.jpg". */
  percorso: string;
  /** Un video (MP4, MOV) invece di una foto. */
  video: boolean;
  /** Durata del video, in secondi. */
  durata?: number;
  /** Codec del video e dell'audio ("hevc", "aac"), per i video. */
  codecVideo?: string;
  codecAudio?: string;
  /** True se ogni browser riproduce l'originale; false se serve la versione compatibile; assente se non si sa ancora. */
  compatibile?: boolean;
  /** La versione compatibile (H.264 in MP4) dei video che ne hanno bisogno. */
  conversione?: StatoConversione;
  erroreConversione?: string;
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
  /** Fine del salto nella timeline: si vedono le foto fino a questo giorno. */
  al?: string;
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
export interface Io {
  login: boolean;
  nome?: string;
  admin: boolean;
  /** Sotto quale cartella si può importare; null = ovunque (il PC). */
  radiceImportazione?: string;
  /** Cartella svuotata da sola nell'archivio, se impostata. */
  cartellaAutomatica?: string;
}

export type StatoImportazione = 'IN_CORSO' | 'FINITA' | 'ANNULLATA' | 'FALLITA';

/** Lavoro in sottofondo: importazione (manuale o dalla cartella automatica) o "Indicizza archivio". */
export interface LavoroImportazione {
  id: string;
  cartella: string;
  origine: 'MANUALE' | 'AUTOMATICA' | 'INDICIZZAZIONE';
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

export type Operazione = 'ELIMINA' | 'AGGIUNGI_TAG' | 'TOGLI_TAG' | 'IMPOSTA_ALBUM' | 'PREFERITA' | 'NON_PREFERITA';

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
  errori: number;
  messaggiErrori: string[];
}

/** Sincronizzazione del telefono (pCloud → cartella automatica), solo admin. */
export interface StatoTelefono {
  attiva: boolean;
  /** Remote di rclone e cartella: "pcloud:Automatic Upload". */
  sorgente: string;
  intervalloOre: number;
  /** 0 = non togliere mai da pCloud. */
  giorniPrimaDiCancellare: number;
  /** Quanti file si copiano insieme (1–32). */
  copieInParallelo: number;
  disponibile: boolean;
  /** Perché non si può attivare, se non disponibile. */
  motivo?: string;
  /** Dove finiscono le copie: "lifetime:telefono". */
  destinazione?: string;
  inCorso: boolean;
  ultimoGiro?: GiroTelefono;
  prossimoGiroIl?: string;
  /** I contatori del giro che sta girando (esito ancora vuoto). */
  giroInCorso?: GiroTelefono;
}

export interface ModificaTelefono {
  attiva: boolean;
  sorgente: string;
  intervalloOre: number;
  giorniPrimaDiCancellare: number;
  copieInParallelo: number;
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
