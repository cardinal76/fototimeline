import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

import { esci, indirizzoAccedi, percorsoSicuro } from './sessione';

/**
 * La pagina di accesso, in due modi:
 * - `accedi` (/accesso, pubblica): nome, logo, una frase e "Accedi", che porta al login di
 *   Keycloak e poi torna alla pagina da cui si veniva (`?torna=`, ConfigurazioneSicurezza).
 *   main.ts la avvia al posto dell'app: nessuna chiamata privata, solo `/api/io` per vedere se
 *   la sessione c'è già (o se il login è spento, sul PC) e in quel caso tornare subito all'app;
 * - `chiedi` (dentro l'app): entrato su Keycloak ma senza nessun ruolo. Stesso aspetto, e
 *   "Esci e cambia utente".
 */
@Component({
  selector: 'app-accesso',
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './accesso.html',
  styleUrl: './accesso.css',
})
export class Accesso {
  readonly modo = input<'accedi' | 'chiedi'>('accedi');
  readonly nome = input<string | undefined>();
  readonly username = input<string | undefined>();

  private readonly parametri = new URLSearchParams(location.search);
  /** Dove tornare dopo il login; il server lo ricontrolla (solo percorsi di questa app). */
  private readonly torna = this.parametri.get('torna');
  protected readonly scaduta = this.parametri.has('scaduta');
  /** Keycloak ha rifiutato o annullato il login (Spring torna qui con ?error). */
  protected readonly errore = this.parametri.has('error');
  protected readonly accedi = indirizzoAccedi(this.torna);
  /** Le tessere dello sfondo: richiamano una griglia di foto, senza nessuna foto. */
  protected readonly tessere = Array.from({ length: 18 }, (_, i) => i);
  protected readonly saluto = computed(() => {
    const n = this.nome();
    return n ? `Ciao ${n}` : 'Ciao';
  });

  constructor() {
    // Solo la pagina /accesso (main.ts), non "Chiedi l'accesso" dentro l'app, che è già entrata.
    if (location.pathname === '/accesso') {
      void this.giaDentro();
    }
  }

  /** Sessione già aperta (o login spento): niente pagina di accesso, si torna all'app. */
  private async giaDentro(): Promise<void> {
    try {
      const r = await fetch('/api/io', { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (r.ok) {
        location.replace(percorsoSicuro(this.torna) ?? '/');
      }
    } catch {
      // Senza rete o server spento: resta la pagina, "Accedi" dirà il resto.
    }
  }

  protected esci(): void {
    esci();
  }
}
