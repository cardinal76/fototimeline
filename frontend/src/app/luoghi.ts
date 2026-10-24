import { Filtro, Foto, Luogo } from './modelli';

/** Il filtro per luogo, sempre con tutti e quattro i campi: un livello più largo toglie quelli sotto. */
export function filtroLuogo(
  nazione?: string,
  regione?: string,
  provincia?: string,
  luogo?: string,
): Pick<Filtro, 'nazione' | 'regione' | 'provincia' | 'luogo'> {
  return { nazione, regione, provincia, luogo };
}

/** Un livello del luogo di una foto, come un tag: si clicca e filtra la timeline. */
export interface LivelloFoto {
  testo: string;
  filtro: Pick<Filtro, 'nazione' | 'regione' | 'provincia' | 'luogo'>;
}

/** I livelli del luogo di una foto, dal più piccolo: Rovereto, Provincia di Trento, Trentino-Alto Adige, Italia. */
export function livelliFoto(f: Foto): LivelloFoto[] {
  const livelli: LivelloFoto[] = [];
  if (f.luogo) {
    livelli.push({ testo: f.luogo, filtro: filtroLuogo(f.codiceNazione, f.regione, f.provincia, f.luogo) });
  }
  if (f.provincia) {
    livelli.push({ testo: f.provincia, filtro: filtroLuogo(f.codiceNazione, f.regione, f.provincia) });
  }
  // Senza ripetere (Tokyo, Tokyo).
  if (f.regione && f.regione !== f.luogo) {
    livelli.push({ testo: f.regione, filtro: filtroLuogo(f.codiceNazione, f.regione) });
  }
  if (f.nazione) {
    livelli.push({ testo: f.nazione, filtro: filtroLuogo(f.codiceNazione) });
  }
  return livelli;
}

/** Il nome del livello scelto: la città, o la provincia, la regione, la nazione. */
export function nomeLuogo(l: Luogo): string {
  return l.luogo ?? l.provincia ?? l.regione ?? l.nazione ?? '';
}

/** Dove sta: "Inghilterra, Regno Unito" per Manchester. */
export function doveLuogo(l: Luogo): string {
  return [l.luogo, l.provincia, l.regione, l.nazione].filter(Boolean).slice(1).join(', ');
}

export const LIVELLI: Record<Luogo['livello'], string> = {
  CITTA: 'città',
  PROVINCIA: 'provincia',
  REGIONE: 'regione',
  NAZIONE: 'nazione',
};

/** Senza maiuscole né accenti: "parigi" trova "Parigi", "ile" trova "Île-de-France". */
export function semplice(testo: string): string {
  return testo.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}
