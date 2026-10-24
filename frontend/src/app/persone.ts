import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  afterNextRender,
  computed,
  inject,
  input,
  output,
  signal,
  viewChild,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { firstValueFrom } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { PersonaSimile, StatoVolti, TipoPersone, VocePersona, VoceVolto } from './modelli';

const SCHEDE: { tipo: TipoPersone; nome: string }[] = [
  { tipo: 'NOMINATE', nome: 'Con un nome' },
  { tipo: 'GRUPPI', nome: 'Senza nome' },
  { tipo: 'NASCOSTE', nome: 'Nascoste' },
];

/**
 * "Persone": un volto per persona o gruppo, con quante foto. Clic sul volto:
 * le sue foto nella timeline. Da qui si dà un nome, si uniscono i gruppi
 * della stessa persona, si nascondono gli sconosciuti; nel dettaglio si
 * tolgono i volti sbagliati e si confermano le proposte. Esc lo chiude (App).
 */
@Component({
  selector: 'app-persone',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  templateUrl: './persone.html',
  styleUrl: './persone.css',
})
export class Persone {
  private readonly api = inject(FotoApi);
  protected readonly galleria = inject(Galleria);

  readonly admin = input(false);
  readonly chiudi = output<void>();

  protected readonly schede = SCHEDE;
  protected readonly tipo = signal<TipoPersone>('NOMINATE');
  protected readonly elenco = signal<VocePersona[]>([]);
  protected readonly totale = signal(0);
  protected readonly caricando = signal(true);
  protected readonly occupato = signal(false);
  /** "Unisci": le schede scelte, in ordine di clic. */
  protected readonly unendo = signal(false);
  protected readonly scelte = signal<string[]>([]);

  /** Il dettaglio di una persona: i suoi volti, le proposte, i gruppi simili. */
  protected readonly aperta = signal<VocePersona | null>(null);
  protected readonly voltiAperta = signal<VoceVolto[]>([]);
  protected readonly proposteAperta = signal<VoceVolto[]>([]);
  protected readonly similiAperta = signal<PersonaSimile[] | null>(null);
  protected nome = '';

  protected readonly stato = computed<StatoVolti | null>(() => this.galleria.volti());
  protected readonly inCorso = computed(() => this.stato()?.lavoro?.stato === 'IN_CORSO');

  private readonly dialogo = viewChild.required<ElementRef<HTMLElement>>('dialogo');

  constructor() {
    afterNextRender(() => this.dialogo().nativeElement.focus());
    // Senza persone con nome si parte dai gruppi da nominare.
    if (!this.galleria.personeNominate().length) {
      this.tipo.set('GRUPPI');
    }
    void this.carica();
  }

  protected ritaglio(voltoId?: string): string | null {
    return voltoId ? FotoApi.ritaglio(voltoId) : null;
  }

  protected miniatura(fotoId: string): string {
    return `/api/foto/${encodeURIComponent(fotoId)}/miniatura`;
  }

  // ------------------------------------------------------------ elenco

  protected scegliScheda(t: TipoPersone): void {
    this.tipo.set(t);
    this.scelte.set([]);
    void this.carica();
  }

  protected async carica(): Promise<void> {
    this.caricando.set(true);
    try {
      const e = await firstValueFrom(this.api.persone(this.tipo(), 300));
      this.elenco.set(e.persone);
      this.totale.set(e.totale);
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Persone non leggibili');
    } finally {
      this.caricando.set(false);
    }
  }

  /** Clic sul volto: le sue foto. Nel modo "Unisci" lo sceglie. */
  protected clic(p: VocePersona): void {
    if (this.unendo()) {
      this.scelte.update((s) => (s.includes(p.id) ? s.filter((x) => x !== p.id) : [...s, p.id]));
      return;
    }
    this.mostraFoto(p);
  }

  protected mostraFoto(p: VocePersona): void {
    this.galleria.filtraPersona(p);
    this.chiudi.emit();
  }

  protected commutaUnione(): void {
    this.unendo.update((u) => !u);
    this.scelte.set([]);
  }

  protected async unisci(): Promise<void> {
    const scelte = this.elenco().filter((p) => this.scelte().includes(p.id));
    if (scelte.length < 2) return;
    // Resta quella col nome (la prima scelta, se più d'una ce l'ha).
    const dentro = scelte.find((p) => p.nome) ?? scelte[0];
    const altre = scelte.filter((p) => p.id !== dentro.id);
    const conNome = scelte.filter((p) => p.nome).map((p) => p.nome);
    const avviso =
      conNome.length > 1 ? ` Resta il nome «${dentro.nome}»: ${conNome.filter((n) => n !== dentro.nome).join(', ')} sparisce.` : '';
    if (!confirm(`Unire ${scelte.length} ${scelte.length === 1 ? 'gruppo' : 'gruppi'} in ${dentro.nome ? '«' + dentro.nome + '»' : 'un gruppo solo'}?${avviso}`)) {
      return;
    }
    await this.fai(async () => {
      await firstValueFrom(this.api.unisciPersone(dentro.id, altre.map((p) => p.id)));
      this.unendo.set(false);
      this.scelte.set([]);
      this.galleria.aggiornaPersone();
      await this.carica();
    }, 'Unione non riuscita');
  }

  // ------------------------------------------------------------ dettaglio

  protected async apri(p: VocePersona): Promise<void> {
    this.aperta.set(p);
    this.nome = p.nome ?? '';
    this.voltiAperta.set([]);
    this.proposteAperta.set([]);
    this.similiAperta.set(null);
    try {
      this.voltiAperta.set(await firstValueFrom(this.api.voltiPersona(p.id, 0, 200)));
      if (p.nome && p.proposte) {
        this.proposteAperta.set(await firstValueFrom(this.api.proposte(p.id)));
      }
      this.similiAperta.set(await firstValueFrom(this.api.simili(p.id)));
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? 'Volti non leggibili');
    }
  }

  protected async torna(): Promise<void> {
    this.aperta.set(null);
    await this.carica();
  }

  private async ricaricaAperta(): Promise<void> {
    const p = this.aperta();
    if (p) {
      await this.apri(await firstValueFrom(this.api.persona(p.id)));
    }
  }

  protected async salvaNome(): Promise<void> {
    const p = this.aperta();
    if (!p || this.nome.trim() === (p.nome ?? '')) return;
    this.occupato.set(true);
    try {
      this.aperta.set(await firstValueFrom(this.api.nominaPersona(p.id, this.nome.trim())));
      this.galleria.aggiornaPersone();
      this.galleria.avvisa(this.nome.trim() ? `Ora è «${this.nome.trim()}»` : 'Tornato un gruppo senza nome');
    } catch (e: unknown) {
      const altra = (e as { status?: number; error?: { persona?: string; nome?: string } }).error;
      if ((e as { status?: number }).status === 409 && altra?.persona) {
        if (confirm(`C'è già «${altra.nome}». Unire questo gruppo a lei/lui?`)) {
          await firstValueFrom(this.api.unisciPersone(altra.persona, [p.id]));
          this.galleria.aggiornaPersone();
          this.aperta.set(null);
          this.tipo.set('NOMINATE');
          await this.carica();
        }
      } else {
        this.galleria.avvisa(dettaglio(e) ?? 'Nome non salvato');
      }
    } finally {
      this.occupato.set(false);
    }
  }

  protected async nascondi(nascosta: boolean): Promise<void> {
    const p = this.aperta();
    if (!p) return;
    await this.fai(async () => {
      this.aperta.set(await firstValueFrom(this.api.nascondiPersona(p.id, nascosta)));
      this.galleria.aggiornaPersone();
      this.galleria.avvisa(nascosta ? 'Nascosto: non si vede più tra le persone' : 'Di nuovo tra le persone');
    }, 'Non riuscito');
  }

  protected async togli(v: VoceVolto): Promise<void> {
    await this.fai(async () => {
      await firstValueFrom(this.api.togliVolto(v.id));
      this.voltiAperta.update((l) => l.filter((x) => x.id !== v.id));
      this.aperta.update((p) => (p ? { ...p, volti: p.volti - 1 } : p));
      this.galleria.ricarica();
    }, 'Volto non tolto');
  }

  protected async usaCopertina(v: VoceVolto): Promise<void> {
    const p = this.aperta();
    if (!p) return;
    await this.fai(async () => {
      await firstValueFrom(this.api.copertina(p.id, v.id));
      this.aperta.set({ ...p, copertina: v.id });
    }, 'Non riuscito');
  }

  protected async conferma(v: VoceVolto): Promise<void> {
    const p = this.aperta();
    if (!p) return;
    await this.fai(async () => {
      await firstValueFrom(this.api.assegnaVolto(v.id, p.id));
      this.proposteAperta.update((l) => l.filter((x) => x.id !== v.id));
      this.aperta.update((a) => (a ? { ...a, volti: a.volti + 1, proposte: Math.max(0, a.proposte - 1) } : a));
    }, 'Non riuscito');
  }

  protected async rifiuta(v: VoceVolto): Promise<void> {
    await this.fai(async () => {
      await firstValueFrom(this.api.rifiutaProposta(v.id));
      this.proposteAperta.update((l) => l.filter((x) => x.id !== v.id));
      this.aperta.update((a) => (a ? { ...a, proposte: Math.max(0, a.proposte - 1) } : a));
    }, 'Non riuscito');
  }

  protected async confermaTutte(): Promise<void> {
    const p = this.aperta();
    if (!p) return;
    await this.fai(async () => {
      const { confermate } = await firstValueFrom(this.api.confermaProposte(p.id));
      this.galleria.avvisa(`${confermate} volti dati a «${p.nome}»`);
      await this.ricaricaAperta();
    }, 'Non riuscito');
  }

  /** Un gruppo simile entra in quella aperta. */
  protected async unisciSimile(s: PersonaSimile): Promise<void> {
    const p = this.aperta();
    if (!p) return;
    const chi = s.persona.nome ? `«${s.persona.nome}»` : `il gruppo di ${s.persona.volti} volti`;
    if (!confirm(`Unire ${chi} a ${p.nome ? '«' + p.nome + '»' : 'questo gruppo'}?`)) return;
    await this.fai(async () => {
      this.aperta.set(await firstValueFrom(this.api.unisciPersone(p.id, [s.persona.id])));
      this.galleria.aggiornaPersone();
      await this.ricaricaAperta();
    }, 'Unione non riuscita');
  }

  // ------------------------------------------------------------ il lavoro (admin)

  protected async riconosci(ricostruisci = false): Promise<void> {
    const s = this.stato();
    if (!s) return;
    const domanda = ricostruisci
      ? `L'indice dei volti di visione ne ha ${s.nellIndice ?? 0} su ${s.volti}: rimetterli tutti? Poi cerca i volti nelle foto che mancano.`
      : `Cerca i volti in ${s.mancanti} foto. Con tante foto ci vogliono ore; va avanti in sottofondo e, se il server riparte, riprende da dove era arrivato.`;
    if (!confirm(domanda)) return;
    await this.fai(() => this.galleria.avviaVolti(ricostruisci), '"Riconosci volti" non partito');
  }

  protected percentuale(): number {
    const l = this.stato()?.lavoro;
    return l && l.daFare ? Math.round(((l.fatte + l.errori) / l.daFare) * 100) : 0;
  }

  protected indicePerso(): boolean {
    const s = this.stato();
    return !!s && s.nellIndice !== undefined && s.nellIndice !== null && s.nellIndice < s.volti;
  }

  // ------------------------------------------------------------ aiuti

  protected etichetta(p: VocePersona): string {
    return p.nome ?? 'Senza nome';
  }

  protected percento(x?: number): string {
    return x === undefined || x === null ? '' : `${Math.round(x * 100)}%`;
  }

  private async fai(azione: () => Promise<unknown>, errore: string): Promise<void> {
    this.occupato.set(true);
    try {
      await azione();
    } catch (e: unknown) {
      this.galleria.avvisa(dettaglio(e) ?? errore);
    } finally {
      this.occupato.set(false);
    }
  }
}

function dettaglio(e: unknown): string | undefined {
  return (e as { error?: { detail?: string } }).error?.detail;
}
