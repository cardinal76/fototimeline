import { registerLocaleData } from '@angular/common';
import localeIt from '@angular/common/locales/it';
import { LOCALE_ID, provideBrowserGlobalErrorListeners, provideZonelessChangeDetection } from '@angular/core';
import { bootstrapApplication } from '@angular/platform-browser';

import { appConfig } from './app/app.config';
import { App } from './app/app';

if (location.pathname.startsWith('/c/')) {
  // Link di condivisione: solo la galleria pubblica, senza l'app (che chiederebbe il login).
  registerLocaleData(localeIt);
  import('./app/condivisa')
    .then(({ Condivisa }) =>
      bootstrapApplication(Condivisa, {
        providers: [
          provideBrowserGlobalErrorListeners(),
          provideZonelessChangeDetection(),
          { provide: LOCALE_ID, useValue: 'it' },
        ],
      }),
    )
    .catch((err) => console.error(err));
} else {
  bootstrapApplication(App, appConfig).catch((err) => console.error(err));

  // App installabile sul telefono (PWA): vedi public/sw.js.
  if ('serviceWorker' in navigator) {
    navigator.serviceWorker.register('/sw.js').catch(() => {
      // Senza service worker l'app funziona lo stesso: solo non si installa.
    });
  }
}
