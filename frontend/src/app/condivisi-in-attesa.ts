/**
 * Le foto condivise dal telefono ("Condividi → FotoTimeline") che aspettano di
 * essere caricate. Le scrive il service worker (public/sw.js) in IndexedDB:
 * nome del database, versione e campi devono restare uguali ai suoi.
 */
const DB = 'fototimeline-condivisi';
const VERSIONE_DB = 1;
const ARCHIVIO = 'file';
/** Come nel service worker: dopo un giorno si buttano. */
const SCADENZA_MS = 24 * 60 * 60 * 1000;

export interface FileCondiviso {
  id: string;
  lotto: string;
  nome: string;
  tipo: string;
  dimensione: number;
  ultimaModifica: number | null;
  ricevutoIl: number;
  file: Blob;
}

/** Estensioni per i file senza (alcune gallerie condividono "image123" e basta): il server guarda quella. */
const ESTENSIONI: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/gif': 'gif',
  'image/bmp': 'bmp',
  'image/webp': 'webp',
  'image/heic': 'heic',
  'image/heif': 'heif',
  'video/mp4': 'mp4',
  'video/quicktime': 'mov',
  'video/x-m4v': 'm4v',
};

function apri(): Promise<IDBDatabase> {
  return new Promise((risolvi, rifiuta) => {
    const r = indexedDB.open(DB, VERSIONE_DB);
    r.onupgradeneeded = () => {
      if (!r.result.objectStoreNames.contains(ARCHIVIO)) {
        r.result.createObjectStore(ARCHIVIO, { keyPath: 'id' });
      }
    };
    r.onsuccess = () => risolvi(r.result);
    r.onerror = () => rifiuta(r.error);
  });
}

async function transazione<T>(
  modo: IDBTransactionMode,
  lavoro: (archivio: IDBObjectStore) => IDBRequest<T> | void,
): Promise<T | undefined> {
  const db = await apri();
  try {
    return await new Promise<T | undefined>((risolvi, rifiuta) => {
      const tx = db.transaction(ARCHIVIO, modo);
      const r = lavoro(tx.objectStore(ARCHIVIO));
      tx.oncomplete = () => risolvi(r ? r.result : undefined);
      tx.onerror = () => rifiuta(tx.error);
      tx.onabort = () => rifiuta(tx.error);
    });
  } finally {
    db.close();
  }
}

/** Quelli in attesa e non scaduti, dal più vecchio; gli scaduti si tolgono. */
export async function inAttesa(): Promise<FileCondiviso[]> {
  if (typeof indexedDB === 'undefined') return [];
  const tutti = (await transazione<FileCondiviso[]>('readonly', (a) => a.getAll() as IDBRequest<FileCondiviso[]>)) ?? [];
  const limite = Date.now() - SCADENZA_MS;
  const scaduti = tutti.filter((f) => !(f.ricevutoIl > limite));
  if (scaduti.length) {
    await togli(scaduti.map((f) => f.id));
  }
  return tutti.filter((f) => f.ricevutoIl > limite).sort((a, b) => a.ricevutoIl - b.ricevutoIl || a.id.localeCompare(b.id));
}

export async function togli(ids: string[]): Promise<void> {
  if (!ids.length) return;
  await transazione('readwrite', (a) => {
    ids.forEach((id) => a.delete(id));
  });
}

/** Il File da mandare: col nome originale, un'estensione se manca, la data di modifica se c'era. */
export function comeFile(f: FileCondiviso): File {
  let nome = f.nome;
  const estensione = ESTENSIONI[f.tipo.toLowerCase()];
  if (estensione && !/\.[a-z0-9]{2,5}$/i.test(nome)) {
    nome = `${nome}.${estensione}`;
  }
  return new File([f.file], nome, { type: f.tipo, lastModified: f.ultimaModifica ?? undefined });
}
