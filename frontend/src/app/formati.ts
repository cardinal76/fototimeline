/** 75 → "1:15", 3725 → "1:02:05". */
export function durata(secondi?: number): string {
  if (secondi == null) return '';
  const s = Math.round(secondi);
  const ore = Math.floor(s / 3600);
  const min = Math.floor((s % 3600) / 60);
  const sec = String(s % 60).padStart(2, '0');
  return ore ? `${ore}:${String(min).padStart(2, '0')}:${sec}` : `${min}:${sec}`;
}
