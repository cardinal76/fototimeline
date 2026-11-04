import { FotoConData, anteprima, inOrdine, nuoveDate, problema, sposta } from './date-multiple';

describe('Date di più foto ("📅 Data…")', () => {
  const foto = (id: string, scattataIl: string, origineData: FotoConData['origineData'] = 'EXIF'): FotoConData => ({
    id,
    scattataIl,
    origineData,
  });

  it("ordina dalla più vecchia, poi per id, e mette in fondo quelle senza data", () => {
    const elenco = [
      foto('c', '2022-01-03T08:00:00'),
      foto('z', ''),
      foto('b', '2020-05-01T10:00:00'),
      foto('a', '2020-05-01T10:00'),
    ];
    expect(inOrdine(elenco).map((f) => f.id)).toEqual(['a', 'b', 'c', 'z']);
  });

  it("stesso giorno: ognuna tiene la sua ora, quelle senza un'ora vera vanno alle 12", () => {
    const nuove = nuoveDate(
      [foto('a', '2024-08-15T09:15:30'), foto('b', '2024-08-16T18:40:00'), foto('c', '2024-08-17T00:00:00', 'CARTELLA')],
      { modo: 'STESSO_GIORNO', giorno: '1993-06-20', mantieniOra: true },
    );
    expect(nuove.get('a')).toBe('1993-06-20T09:15:30');
    expect(nuove.get('b')).toBe('1993-06-20T18:40:00');
    expect(nuove.get('c')).toBe('1993-06-20T12:00:00');
  });

  it("stesso giorno senza tenere l'ora: tutte alle 12, un secondo l'una dall'altra", () => {
    const nuove = nuoveDate([foto('b', '2024-08-15T20:00:00'), foto('a', '2024-08-15T09:00:00')], {
      modo: 'STESSO_GIORNO',
      giorno: '1993-06-20',
      mantieniOra: false,
    });
    expect([...nuove]).toEqual([
      ['a', '1993-06-20T12:00:00'],
      ['b', '1993-06-20T12:00:01'],
    ]);
  });

  it("stessa data e ora: un secondo in più per foto nell'ordine di prima, anche a cavallo del minuto", () => {
    const nuove = nuoveDate(
      [foto('c', '2022-01-03T08:00:00'), foto('a', '2020-05-01T10:00:00'), foto('b', '2021-01-01T00:00:00')],
      { modo: 'STESSA_ORA', quando: '1993-06-20T10:00:59' },
    );
    expect([...nuove]).toEqual([
      ['a', '1993-06-20T10:00:59'],
      ['b', '1993-06-20T10:01:00'],
      ['c', '1993-06-20T10:01:01'],
    ]);
  });

  it('sposta: le distanze restano, i mesi si fermano a fine mese come in Java, l\'ora legale non conta', () => {
    expect(sposta('2024-12-31T23:30:00', { anni: -1, mesi: 0, giorni: -1, ore: 0, minuti: -30 })).toBe(
      '2023-12-30T23:00:00',
    );
    expect(sposta('2024-01-31T10:00:00', { anni: 0, mesi: 1, giorni: 0, ore: 0, minuti: 0 })).toBe('2024-02-29T10:00:00');
    expect(sposta('2024-02-29T10:00:00', { anni: 1, mesi: 0, giorni: 0, ore: 0, minuti: 0 })).toBe('2025-02-28T10:00:00');
    // L'ultima domenica di marzo in Italia: con i conti in UTC un'ora è un'ora.
    expect(sposta('2024-03-31T01:30:00', { anni: 0, mesi: 0, giorni: 0, ore: 1, minuti: 0 })).toBe('2024-03-31T02:30:00');
    const nuove = nuoveDate([foto('a', '2024-12-31T23:30:00'), foto('b', '2025-01-02T07:05:00'), foto('z', '')], {
      modo: 'SPOSTA',
      sposta: { anni: -1, mesi: 0, giorni: -1, ore: 0, minuti: -30 },
    });
    expect(nuove.has('z')).toBeFalse();
    const ms = (t: string) => Date.parse(t + 'Z');
    expect(ms(nuove.get('b')!) - ms(nuove.get('a')!)).toBe(ms('2025-01-02T07:05:00') - ms('2024-12-31T23:30:00'));
  });

  it('dice cosa manca prima di partire', () => {
    expect(problema({ modo: 'STESSO_GIORNO' })).toBe('Scegli il giorno');
    expect(problema({ modo: 'STESSO_GIORNO', giorno: '1700-01-01' })).toContain('1800');
    expect(problema({ modo: 'STESSA_ORA', quando: '1993-06-20' })).toBe("Scegli il giorno e l'ora");
    expect(problema({ modo: 'SPOSTA', sposta: { anni: 0, mesi: 0, giorni: 0, ore: 0, minuti: 0 } })).toBe(
      'Di quanto spostarle?',
    );
    expect(problema({ modo: 'STESSA_ORA', quando: '1993-06-20T10:00' })).toBeNull();
  });

  it("l'anteprima dice da quando a quando, quante restano così e se l'ordine cambia", () => {
    const elenco = [
      foto('a', '2024-08-15T10:00:00'),
      foto('b', '2024-08-15T10:00:05'),
      foto('c', '2024-08-16T10:00:10'),
      foto('d', '2024-08-16T10:00:15'),
      foto('e', '2024-08-17T10:00:20'),
    ];
    expect(anteprima(elenco, { modo: 'STESSA_ORA', quando: '1993-06-20T10:00' })).toBe(
      '5 foto: dal 20/06/1993 10:00:00 al 20/06/1993 10:00:04',
    );
    expect(anteprima(elenco, { modo: 'SPOSTA', sposta: { anni: 0, mesi: 0, giorni: 1, ore: 2, minuti: 0 } })).toBe(
      '5 foto: dal 16/08/2024 12:00 al 18/08/2024 12:00',
    );
    // Il giorno con le ore di giorni diversi: quella del 16 alle 9 ora viene prima di quella del 15 alle 10.
    const mescolate = [foto('a', '2024-08-15T10:00:00'), foto('b', '2024-08-16T09:00:00'), foto('z', '')];
    expect(anteprima(mescolate, { modo: 'STESSO_GIORNO', giorno: '1993-06-20', mantieniOra: true }, 4)).toBe(
      "3 foto: dal 20/06/1993 09:00 al 20/06/1993 12:00 · altre 1 non caricate qui, con lo stesso calcolo · l'ordine fra le foto cambia",
    );
    expect(anteprima(mescolate, { modo: 'SPOSTA', sposta: { anni: 1, mesi: 0, giorni: 0, ore: 0, minuti: 0 } })).toBe(
      '2 foto: dal 15/08/2025 10:00 al 16/08/2025 09:00 · 1 senza data restano così',
    );
    expect(anteprima(elenco.slice(0, 1), { modo: 'STESSA_ORA', quando: '1993-06-20T10:00' })).toBe('1 foto: il 20/06/1993 10:00');
    expect(anteprima(elenco, { modo: 'STESSO_GIORNO' })).toBe('Scegli il giorno');
  });
});
