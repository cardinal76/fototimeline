// Service worker di FotoTimeline: rende l'app installabile, mostra una pagina
// chiara quando manca la rete e riceve le foto condivise dal telefono
// ("Condividi → FotoTimeline", share_target nel manifest). Non mette in cache
// foto né API: sono private e dietro login, e le miniature le tiene già la
// cache del browser.
const PAGINA_OFFLINE = '/offline.html';
const CACHE = 'fototimeline-v2';

// I file condivisi aspettano qui finché l'app non li carica (condivisi-in-attesa.ts legge
// lo stesso database: nome, versione e campi vanno cambiati insieme). Non in
// Cache Storage: 'activate' cancella le cache con un altro nome.
const DB = 'fototimeline-condivisi';
const VERSIONE_DB = 1;
const ARCHIVIO = 'file';
const RICEVI = '/ricevi-condivisi';
/** Quelli mai caricati né annullati dopo un giorno si buttano. */
const SCADENZA_MS = 24 * 60 * 60 * 1000;

self.addEventListener('install', (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.add(PAGINA_OFFLINE)));
  self.skipWaiting();
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil(
    Promise.all([
      caches.keys().then((chiavi) => Promise.all(chiavi.filter((c) => c !== CACHE).map((c) => caches.delete(c)))),
      togliScaduti().catch(() => {}),
    ]),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (evento) => {
  const indirizzo = new URL(evento.request.url);
  if (evento.request.method === 'POST' && indirizzo.origin === self.location.origin && indirizzo.pathname === RICEVI) {
    evento.respondWith(ricevi(evento.request));
    return;
  }
  // Solo l'apertura delle pagine: senza rete, la pagina offline.
  if (evento.request.mode === 'navigate') {
    evento.respondWith(fetch(evento.request).catch(() => caches.match(PAGINA_OFFLINE)));
  }
});

/**
 * La condivisione da Android: i file restano qui, sul telefono, e l'app li
 * carica col suo caricamento normale (login, CSRF, stessi limiti). Così non
 * importa se la sessione è scaduta: dopo il login l'app li ritrova.
 */
async function ricevi(richiesta) {
  try {
    const dati = await richiesta.formData();
    const file = dati.getAll('file').filter((f) => f instanceof File && f.size > 0);
    if (!file.length) {
      return Response.redirect('/?condivisi=vuoto', 303);
    }
    const lotto = Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 8);
    const adesso = Date.now();
    const db = await apri();
    try {
      await new Promise((risolvi, rifiuta) => {
        const tx = db.transaction(ARCHIVIO, 'readwrite');
        const archivio = tx.objectStore(ARCHIVIO);
        file.forEach((f, i) =>
          archivio.put({
            id: `${lotto}-${i}`,
            lotto,
            nome: f.name || `condiviso-${i + 1}`,
            tipo: f.type,
            dimensione: f.size,
            ultimaModifica: f.lastModified || null,
            ricevutoIl: adesso,
            file: f,
          }),
        );
        tx.oncomplete = risolvi;
        tx.onerror = () => rifiuta(tx.error);
        tx.onabort = () => rifiuta(tx.error);
      });
    } finally {
      db.close();
    }
    await togliScaduti().catch(() => {});
    return Response.redirect(`/?condivisi=${lotto}`, 303);
  } catch {
    // Per esempio lo spazio del telefono finito: l'app lo dice.
    return Response.redirect('/?condivisi=errore', 303);
  }
}

function apri() {
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

async function togliScaduti() {
  const db = await apri();
  const limite = Date.now() - SCADENZA_MS;
  try {
    await new Promise((risolvi, rifiuta) => {
      const tx = db.transaction(ARCHIVIO, 'readwrite');
      const cursore = tx.objectStore(ARCHIVIO).openCursor();
      cursore.onsuccess = () => {
        const c = cursore.result;
        if (c) {
          if (!(c.value.ricevutoIl > limite)) {
            c.delete();
          }
          c.continue();
        }
      };
      tx.oncomplete = risolvi;
      tx.onerror = () => rifiuta(tx.error);
    });
  } finally {
    db.close();
  }
}
