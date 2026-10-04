import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';

/** Pagina di Spring che porta al login di Keycloak. */
export const INDIRIZZO_LOGIN = '/oauth2/authorization/keycloak';

/**
 * Sessione scaduta (401): si torna al login. Keycloak di solito ricorda
 * l'utente, quindi è un giro di redirect e si rientra senza password.
 */
export const sessioneScaduta: HttpInterceptorFn = (richiesta, avanti) =>
  avanti(richiesta).pipe(
    catchError((errore: unknown) => {
      if (errore instanceof HttpErrorResponse && errore.status === 401) {
        window.location.href = INDIRIZZO_LOGIN;
      }
      return throwError(() => errore);
    }),
  );

/** Uscita: un vero form POST, perché la risposta porta a Keycloak (un'altra origine). */
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
