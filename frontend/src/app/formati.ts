/** Da quanto dura qualcosa, a parole: 35 → "35 s", 840 → "14 min", 7500 → "2 h 5 min". */
export function daQuanto(secondi: number): string {
  const s = Math.max(0, Math.round(secondi));
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.floor(s / 60)} min`;
  return `${Math.floor(s / 3600)} h ${Math.floor((s % 3600) / 60)} min`;
}

/** 75 → "1:15", 3725 → "1:02:05". */
export function durata(secondi?: number): string {
  if (secondi == null) return '';
  const s = Math.round(secondi);
  const ore = Math.floor(s / 3600);
  const min = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return ore ? `${ore}:${String(min).padStart(2, '0')}:${sec}` : `${min}:${sec}`;
}
