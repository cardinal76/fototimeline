export type OrigineData = 'EXIF' | 'FILE' | 'CARICAMENTO' | 'MANUALE';

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

/** Importazione in sottofondo, manuale o dalla cartella automatica. */
export interface LavoroImportazione {
  id: string;
  cartella: string;
  origine: 'MANUALE' | 'AUTOMATICA';
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
}

/** "Accadde oggi": le foto di un anno passato nello stesso giorno. */
export interface Ricordo {
  anno: number;
  anniFa: number;
  foto: Foto[];
}
