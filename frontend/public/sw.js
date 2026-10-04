// Service worker di FotoTimeline: serve solo a rendere l'app installabile e a
// mostrare una pagina chiara quando manca la rete. Non mette in cache foto né
// API: sono private e dietro login, e le miniature le tiene già la cache del
// browser.
const PAGINA_OFFLINE = '/offline.html';
const CACHE = 'fototimeline-v1';

self.addEventListener('install', (evento) => {
  evento.waitUntil(caches.open(CACHE).then((cache) => cache.add(PAGINA_OFFLINE)));
  self.skipWaiting();
});

self.addEventListener('activate', (evento) => {
  evento.waitUntil(
    caches.keys().then((chiavi) => Promise.all(chiavi.filter((c) => c !== CACHE).map((c) => caches.delete(c)))),
  );
  self.clients.claim();
});

self.addEventListener('fetch', (evento) => {
  // Solo l'apertura delle pagine: senza rete, la pagina offline.
  if (evento.request.mode === 'navigate') {
    evento.respondWith(fetch(evento.request).catch(() => caches.match(PAGINA_OFFLINE)));
  }
});
