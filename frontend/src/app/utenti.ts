import { DatePipe } from '@angular/common';
import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  inject,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { ElencoUtenti, NuovoUtente, Ruolo, VoceUtente } from './modelli';

/** Il nome e cosa può fare, per la tendina. */
export const RUOLI: { valore: Ruolo; nome: string; descrizione: string }[] = [
  { valore: 'UTILIZZATORE', nome: 'Utilizzatore', descrizione: 'guarda le foto: niente download, modifiche né Strumenti' },
  { valore: 'GESTORE', nome: 'Gestore', descrizione: 'carica, modifica, elimina e condivide le foto; niente Strumenti né utenti' },
  { valore: 'AMMINISTRATORE', nome: 'Amministratore', descrizione: 'tutto, compresi gli utenti e Strumenti' },
];

function nuovo(): NuovoUtente {
  return { username: '', nome: '', cognome: '', email: '', ruolo: 'UTILIZZATORE', password: '', invito: false };
}

/**
 * La pagina "Utenti" (Strumenti, solo l'amministratore): gli utenti del realm
 * di Keycloak con il loro ruolo. Si creano (password iniziale o invito per
 * email), si cambia il ruolo, si disattivano, si reimposta la password, si
 * eliminano. Il server non lascia togliere di mezzo l'ultimo amministratore.
 * Esc la chiude (App).
 */
@Component({
  selector: 'app-utenti',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [DatePipe, FormsModule],
  templateUrl: './utenti.html',
  styleUrls: ['./cestino.css', './utenti.css'],
})
export class Utenti {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  readonly chiudi = output<void>();

  protected readonly ruoli = RUOLI;
  protected readonly elenco = signal<ElencoUtenti | null>(null);
  protected readonly occupato = signal(false);
  protected readonly errore = signal<string | null>(null);
  /** Il modulo per un utente nuovo, aperto o no. */
  protected readonly creazione = signal(false);
  protected modulo: NuovoUtente = nuovo();
  /** L'utente a cui si sta dando una password nuova. */
  protected readonly perPassword = signal<VoceUtente | null>(null);
  protected passwordNuova = '';

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    void this.carica();
  }

  protected async carica(): Promise<void> {
    try {
      this.elenco.set(await firstValueFrom(this.api.gestioneUtenti()));
      this.errore.set(null);
    } catch (e) {
      this.errore.set(messaggio(e, 'Elenco degli utenti non disponibile'));
    }
  }

  protected nomeRuolo(r?: Ruolo | null): string {
    return RUOLI.find((x) => x.valore === r)?.nome ?? 'Nessun ruolo';
  }

  protected descrizione(r: Ruolo): string {
    return RUOLI.find((x) => x.valore === r)?.descrizione ?? '';
  }

  protected nomeCompleto(u: VoceUtente): string {
    return [u.nome, u.cognome].filter(Boolean).join(' ') || u.username;
  }

  /** L'utente collegato adesso: cambiando lui, la sua sessione si chiude. */
  protected seiTu(u: VoceUtente): boolean {
    return u.username === this.galleria.io()?.username;
  }

  protected apriCreazione(): void {
    this.modulo = nuovo();
    this.creazione.set(true);
  }

  protected async crea(): Promise<void> {
    const m = this.modulo;
    await this.esegui(async () => {
      const creato = await firstValueFrom(
        this.api.creaUtente({ ...m, password: m.invito ? undefined : m.password, email: m.email?.trim() || undefined }),
      );
      this.creazione.set(false);
      this.galleria.avvisa(
        m.invito
          ? `${creato.username} creato: Keycloak gli ha mandato l'email per scegliere la password`
          : `${creato.username} creato: al primo accesso cambierà la password`,
      );
    });
  }

  protected async cambiaRuolo(u: VoceUtente, ruolo: Ruolo): Promise<void> {
    if (ruolo === u.ruolo) {
      return;
    }
    const tu = this.seiTu(u) ? ' Sei tu: dovrai rientrare.' : '';
    if (!confirm(`${this.nomeCompleto(u)} diventa ${this.nomeRuolo(ruolo).toLowerCase()}: dovrà rientrare.${tu}`)) {
      await this.carica();
      return;
    }
    await this.esegui(async () => {
      await firstValueFrom(this.api.ruoloUtente(u.id, ruolo));
      this.galleria.avvisa(`${u.username}: ${this.nomeRuolo(ruolo).toLowerCase()}`);
    });
  }

  protected async commutaAttivo(u: VoceUtente): Promise<void> {
    if (u.attivo && !confirm(`Disattivare ${this.nomeCompleto(u)}? Non potrà più entrare finché non lo riattivi.`)) {
      return;
    }
    await this.esegui(async () => {
      await firstValueFrom(this.api.attivaUtente(u.id, !u.attivo));
      this.galleria.avvisa(`${u.username} ${u.attivo ? 'disattivato' : 'riattivato'}`);
    });
  }

  protected apriPassword(u: VoceUtente): void {
    this.passwordNuova = '';
    this.perPassword.set(u);
  }

  protected async salvaPassword(invito: boolean): Promise<void> {
    const u = this.perPassword();
    if (!u) {
      return;
    }
    await this.esegui(async () => {
      await firstValueFrom(this.api.passwordUtente(u.id, invito ? null : this.passwordNuova, invito));
      this.perPassword.set(null);
      this.galleria.avvisa(
        invito ? `Email mandata a ${u.email}` : `Password di ${u.username} reimpostata: la cambierà al primo accesso`,
      );
    });
  }

  protected async elimina(u: VoceUtente): Promise<void> {
    if (
      !confirm(
        `Eliminare ${this.nomeCompleto(u)} (${u.username})? Sparisce da Keycloak e non potrà più entrare. ` +
          'Le sue foto restano, con "Caricata da".',
      )
    ) {
      return;
    }
    await this.esegui(async () => {
      await firstValueFrom(this.api.eliminaUtente(u.id));
      this.galleria.avvisa(`${u.username} eliminato`);
    });
  }

  /** Un'operazione alla volta; l'errore del server (ultimo amministratore, Keycloak) resta in vista. */
  private async esegui(azione: () => Promise<void>): Promise<void> {
    this.occupato.set(true);
    this.errore.set(null);
    try {
      await azione();
    } catch (e) {
      this.errore.set(messaggio(e, 'Non riuscito'));
    } finally {
      this.occupato.set(false);
      await this.carica();
    }
  }
}

function messaggio(e: unknown, altrimenti: string): string {
  return (e as { error?: { detail?: string } }).error?.detail ?? altrimenti;
}
