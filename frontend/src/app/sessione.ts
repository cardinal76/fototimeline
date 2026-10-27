import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

/** Pagina di Spring che porta al login di Keycloak. */
export const INDIRIZZO_LOGIN = '/oauth2/authorization/keycloak';
/** La pagina di accesso dell'app (accesso.ts), pubblica. */
export const PAGINA_ACCESSO = '/accesso';

/**
 * Un percorso di questa app, o null: niente indirizzi assoluti, niente `//altro` o `/\altro`
 * (il browser li legge come un altro sito), niente a capo, niente pagine del login. È lo stesso
 * controllo del server (PaginaDiAccesso.sicuro).
 */
export function percorsoSicuro(torna: string | null | undefined): string | null {
  if (!torna || !torna.startsWith('/') || torna.startsWith('//') || torna.length > 2000) {
    return null;
  }
  if (/[\\\u0000-\u001f\u007f]/.test(torna)) {
    return null;
  }
  // Le pagine del login stesso: si girerebbe in tondo.
  const vietata = [PAGINA_ACCESSO, '/oauth2', '/login', '/logout', '/api', '/error'].some(
    (p) => torna === p || torna.startsWith(p + '/') || torna.startsWith(p + '?'),
  );
  return vietata ? null : torna;
}

/** "Accedi": il login di Keycloak, che poi torna a `torna` (o alla timeline). */
export function indirizzoAccedi(torna: string | null | undefined): string {
  const sicuro = percorsoSicuro(torna);
  return sicuro && sicuro !== '/' ? `${INDIRIZZO_LOGIN}?torna=${encodeURIComponent(sicuro)}` : INDIRIZZO_LOGIN;
}

/** La pagina di accesso, che poi riporta qui: per la sessione scaduta. */
export function paginaDiAccesso(scaduta: boolean): string {
  const qui = location.pathname + location.search;
  const parametri = new URLSearchParams();
  if (qui !== '/') {
    parametri.set('torna', qui);
  }
  if (scaduta) {
    parametri.set('scaduta', '');
  }
  const query = parametri.toString();
  return PAGINA_ACCESSO + (query ? `?${query}` : '');
}

let giaUscito = false;

/**
 * Sessione scaduta o chiusa (401): la pagina di accesso, con "Accedi" che riporta qui. Una volta
 * sola anche se più chiamate rispondono 401 insieme.
 */
export const sessioneScaduta: HttpInterceptorFn = (richiesta, avanti) =>
  avanti(richiesta).pipe(
    catchError((errore: unknown) => {
      if (errore instanceof HttpErrorResponse && errore.status === 401 && !giaUscito) {
        giaUscito = true;
        window.location.assign(paginaDiAccesso(true));
      }
      return throwError(() => errore);
    }),
  );

/**
 * Uscita: un vero form POST, perché la risposta porta a Keycloak (un'altra origine), che chiude
 * anche la sua sessione e torna a "/": senza sessione, la pagina di accesso.
 */
export function esci(): void {
  const token = document.cookie
    .split('; ')
    .find((c) => c.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length);
  const form = document.createElement('form');
  form.method = 'post';
  form.action = '/logout';
  const campo = document.createElement('input');
  campo.type = 'hidden';
  campo.name = '_csrf';
  campo.value = decodeURIComponent(token ?? '');
  form.appendChild(campo);
  document.body.appendChild(form);
  form.submit();
}
