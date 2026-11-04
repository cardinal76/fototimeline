import { Foto } from './modelli';

/**
 * "📅 Data…" sulla selezione: le date nuove calcolate qui solo per
 * l'anteprima, le cambia il server (`POST /api/foto/data`) con lo stesso
 * calcolo (`NuoveDate.java`): un cambio va fatto in tutti e due.
 *
 * Le date sono ore locali senza fuso ("2024-05-17T14:03:22"): i conti si
 * fanno in UTC, così l'ora legale non sposta niente, come LocalDateTime.
 */

export type ModoData = 'STESSO_GIORNO' | 'STESSA_ORA' | 'SPOSTA';

/** Di quanto spostare (anche negativo): prima anni, mesi e giorni, poi ore e minuti. */
export interface Spostamento {
  anni: number;
  mesi: number;
  giorni: number;
  ore: number;
  minuti: number;
}

export interface RichiestaDate {
  modo: ModoData;
  /** STESSO_GIORNO: "1993-06-20". */
  giorno?: string;
  /** STESSO_GIORNO: false mette tutte alle 12, un secondo in più per foto. */
  mantieniOra?: boolean;
  /** STESSA_ORA: "1993-06-20T10:00:00". */
  quando?: string;
  sposta?: Spostamento;
}

export interface EsitoDate {
  modificate: number;
  invariate: number;
  saltate: number;
  errori: { id: string; nome: string; motivo: string }[];
}

export type FotoConData = Pick<Foto, 'id' | 'scattataIl' | 'origineData'>;

/** Come `NuoveDate.PRIMO_ANNO` / `ULTIMO_ANNO`. */
export const PRIMO_ANNO = 1800;
export const ULTIMO_ANNO = 2199;
/** Come `FotoService.MASSIMO_DATE`. */
export const MASSIMO_DATE = 500;

const MINUTO = 60_000;

/** "2024-05-17T14:03:22" (anche senza secondi) in millisecondi "UTC"; NaN se non è una data. */
export function inMillisecondi(testo: string | null | undefined): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:T(\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(testo ?? '');
  if (!m) return NaN;
  const [, a, me, g, o, mi, s] = m;
  return Date.UTC(+a, +me - 1, +g, +(o ?? 0), +(mi ?? 0), +(s ?? 0));
}

/** Millisecondi "UTC" in "2024-05-17T14:03:22", come li scrive il server. */
export function inTesto(ms: number): string {
  return new Date(ms).toISOString().slice(0, 19);
}

/** Se l'ora della foto vale qualcosa: no senza data, né col giorno preso dalla cartella. */
export function haUnOra(f: FotoConData): boolean {
  return !!f.scattataIl && f.origineData !== 'CARTELLA';
}

/** Dalla più vecchia alla più nuova, poi per id; senza data in fondo (come `NuoveDate.ORDINE`). */
export function inOrdine<T extends FotoConData>(foto: readonly T[]): T[] {
  return [...foto].sort((x, y) => {
    const a = inMillisecondi(x.scattataIl);
    const b = inMillisecondi(y.scattataIl);
    if (isNaN(a) !== isNaN(b)) return isNaN(a) ? 1 : -1;
    if (!isNaN(a) && a !== b) return a - b;
    return x.id < y.id ? -1 : x.id > y.id ? 1 : 0;
  });
}

/** Anni e mesi come `LocalDateTime.plusYears/plusMonths`: il 31 gennaio più un mese è il 28 (o 29) febbraio. */
function piuMesi(ms: number, mesi: number): number {
  if (!mesi) return ms;
  const d = new Date(ms);
  const totale = d.getUTCFullYear() * 12 + d.getUTCMonth() + mesi;
  const anno = Math.floor(totale / 12);
  const mese = totale - anno * 12;
  const ultimo = new Date(Date.UTC(anno, mese + 1, 0)).getUTCDate();
  return Date.UTC(anno, mese, Math.min(d.getUTCDate(), ultimo), d.getUTCHours(), d.getUTCMinutes(), d.getUTCSeconds());
}

export function sposta(testo: string, s: Spostamento): string {
  let ms = piuMesi(inMillisecondi(testo), (s.anni || 0) * 12);
  ms = piuMesi(ms, s.mesi || 0);
  ms += (s.giorni || 0) * 1440 * MINUTO + (s.ore || 0) * 60 * MINUTO + (s.minuti || 0) * MINUTO;
  return inTesto(ms);
}

export function spostamentoNullo(s: Spostamento | undefined): boolean {
  return !s || (!s.anni && !s.mesi && !s.giorni && !s.ore && !s.minuti);
}

/** Cosa manca alla richiesta per partire (come `NuoveDate.Richiesta.valida`), o null se va. */
export function problema(r: RichiestaDate): string | null {
  if (r.modo === 'STESSO_GIORNO') {
    if (isNaN(inMillisecondi(r.giorno))) return 'Scegli il giorno';
    return annoFuori(+r.giorno!.slice(0, 4));
  }
  if (r.modo === 'STESSA_ORA') {
    if (isNaN(inMillisecondi(r.quando)) || !r.quando!.includes('T')) return "Scegli il giorno e l'ora";
    return annoFuori(+r.quando!.slice(0, 4));
  }
  return spostamentoNullo(r.sposta) ? 'Di quanto spostarle?' : null;
}

function annoFuori(anno: number): string | null {
  return anno < PRIMO_ANNO || anno > ULTIMO_ANNO ? `L'anno va tra ${PRIMO_ANNO} e ${ULTIMO_ANNO}` : null;
}

/**
 * La data nuova di ogni foto, per id, dalla più vecchia. Con SPOSTA quelle
 * senza data non ci sono: non c'è niente da spostare.
 */
export function nuoveDate(foto: readonly FotoConData[], r: RichiestaDate): Map<string, string> {
  const nuove = new Map<string, string>();
  let senzaOra = 0;
  inOrdine(foto).forEach((f, i) => {
    let nuova: string | null = null;
    if (r.modo === 'STESSO_GIORNO') {
      const giorno = r.giorno!.slice(0, 10);
      if (r.mantieniOra !== false && haUnOra(f)) {
        nuova = `${giorno}T${inTesto(inMillisecondi(f.scattataIl)).slice(11)}`;
      } else {
        // Le 12, e un secondo in più per foto: così l'ordine resta.
        nuova = inTesto(inMillisecondi(`${giorno}T12:00:00`) + 1000 * senzaOra++);
      }
    } else if (r.modo === 'STESSA_ORA') {
      nuova = inTesto(inMillisecondi(r.quando) + 1000 * i);
    } else if (f.scattataIl) {
      nuova = sposta(f.scattataIl, r.sposta!);
    }
    if (nuova) nuove.set(f.id, nuova);
  });
  return nuove;
}

/** "20/06/1993 10:00", o coi secondi "20/06/1993 10:00:04". */
export function leggibile(testo: string, secondi = false): string {
  const d = inTesto(inMillisecondi(testo));
  return `${d.slice(8, 10)}/${d.slice(5, 7)}/${d.slice(0, 4)} ${d.slice(11, secondi ? 19 : 16)}`;
}

/**
 * La riga prima di confermare: "5 foto: dal 20/06/1993 10:00 al 20/06/1993
 * 10:00", più quelle che restano com'erano e se l'ordine cambia.
 * `scelte` sono tutte le selezionate (anche quelle non caricate in pagina).
 */
export function anteprima(foto: readonly FotoConData[], r: RichiestaDate, scelte = foto.length): string {
  const errore = problema(r);
  if (errore) return errore;
  const nuove = nuoveDate(foto, r);
  const valori = [...nuove.values()].sort();
  const parti: string[] = [];
  if (valori.length) {
    const primo = valori[0];
    const ultimo = valori[valori.length - 1];
    // Nello stesso minuto (un secondo l'una dall'altra) servono i secondi.
    const secondi = primo !== ultimo && primo.slice(0, 16) === ultimo.slice(0, 16);
    parti.push(
      `${valori.length} foto: ` +
        (primo === ultimo ? 'il ' + leggibile(primo) : `dal ${leggibile(primo, secondi)} al ${leggibile(ultimo, secondi)}`),
    );
    const fuori = valori.find((v) => +v.slice(0, 4) < PRIMO_ANNO || +v.slice(0, 4) > ULTIMO_ANNO);
    if (fuori) parti.push(`attenzione: qualcuna finisce nel ${fuori.slice(0, 4)}, quelle restano com'erano`);
  } else {
    parti.push('Nessuna foto da cambiare');
  }
  const senzaData = foto.length - nuove.size;
  if (senzaData) parti.push(`${senzaData} senza data restano così`);
  if (scelte > foto.length) parti.push(`altre ${scelte - foto.length} non caricate qui, con lo stesso calcolo`);
  if (cambiaOrdine(foto, nuove)) parti.push("l'ordine fra le foto cambia");
  return parti.join(' · ');
}

/** True se dopo il cambio la timeline le mostra in un altro ordine (stesso giorno con le ore di giorni diversi). */
function cambiaOrdine(foto: readonly FotoConData[], nuove: Map<string, string>): boolean {
  const prima = inOrdine(foto.filter((f) => nuove.has(f.id))).map((f) => f.id);
  const dopo = inOrdine(prima.map((id) => ({ id, scattataIl: nuove.get(id)!, origineData: 'MANUALE' as const }))).map(
    (f) => f.id,
  );
  return prima.some((id, i) => id !== dopo[i]);
}
