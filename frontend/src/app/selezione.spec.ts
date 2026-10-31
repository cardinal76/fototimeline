import { TrascinaSelezione, conIntervallo, indiciPerId, velocitaBordo } from './selezione';

describe('Selezione a intervalli', () => {
  const foto = ['a', 'b', 'c', 'd', 'e', 'f'].map((id) => ({ id }));
  const indice = indiciPerId(foto);

  it('indiciPerId dà la posizione di ogni foto', () => {
    expect(indice.get('a')).toBe(0);
    expect(indice.get('f')).toBe(5);
    expect(indice.size).toBe(6);
  });

  it("sceglie l'intervallo compreso, in avanti e all'indietro", () => {
    expect([...conIntervallo(new Set(['b']), foto, indice, 'b', 'e', true)!].sort()).toEqual(['b', 'c', 'd', 'e']);
    expect([...conIntervallo(new Set(['e']), foto, indice, 'e', 'b', true)!].sort()).toEqual(['b', 'c', 'd', 'e']);
  });

  it('tiene le altre già scelte fuori dall\'intervallo', () => {
    const n = conIntervallo(new Set(['a', 'f']), foto, indice, 'c', 'd', true)!;
    expect([...n].sort()).toEqual(['a', 'c', 'd', 'f']);
  });

  it('toglie l\'intervallo quando la foto di partenza non è scelta', () => {
    const n = conIntervallo(new Set(['a', 'b', 'c', 'd', 'e']), foto, indice, 'b', 'd', false)!;
    expect([...n].sort()).toEqual(['a', 'e']);
  });

  it('non cambia la selezione di partenza', () => {
    const base = new Set(['a']);
    conIntervallo(base, foto, indice, 'a', 'f', true);
    expect([...base]).toEqual(['a']);
  });

  it('null se una foto non è fra quelle caricate', () => {
    expect(conIntervallo(new Set(), foto, indice, 'a', 'zz', true)).toBeNull();
  });

  it('velocitaBordo: ferma al centro, verso l\'alto in cima, verso il basso in fondo', () => {
    expect(velocitaBordo(500, 100, 900)).toBe(0);
    expect(velocitaBordo(110, 100, 900)).toBeLessThan(0);
    expect(velocitaBordo(890, 100, 900)).toBeGreaterThan(0);
    // Fuori dal contenitore (sopra la barra) la velocità massima, non di più.
    expect(velocitaBordo(0, 100, 900)).toBe(-24);
    expect(velocitaBordo(890, 100, 900)).toBeLessThan(velocitaBordo(899, 100, 900));
  });
});

describe('TrascinaSelezione', () => {
  const ids = ['a', 'b', 'c', 'd', 'e'];
  const foto = ids.map((id) => ({ id }));
  const indice = indiciPerId(foto);
  let contenitore: HTMLElement;
  let selezionate: ReadonlySet<string>;
  let inSelezione: boolean;
  let trascina: TrascinaSelezione;

  beforeEach(() => {
    contenitore = document.createElement('main');
    contenitore.style.cssText = 'position:fixed;top:0;left:0;width:600px;height:400px;display:flex;gap:10px;overflow:auto';
    for (const id of ids) {
      const t = document.createElement('button');
      t.dataset['id'] = id;
      t.style.cssText = 'width:100px;height:100px;flex:none;padding:0';
      t.innerHTML = '<span class="spunta" style="display:block;width:20px;height:20px"></span>';
      contenitore.appendChild(t);
    }
    document.body.appendChild(contenitore);
    selezionate = new Set();
    inSelezione = false;
    trascina = new TrascinaSelezione({
      contenitore: () => contenitore,
      puo: () => true,
      inSelezione: () => inSelezione,
      entra: () => (inSelezione = true),
      scelta: (id) => selezionate.has(id),
      selezionate: () => selezionate,
      applica: (base, da, a, scegli) => (selezionate = conIntervallo(base, foto, indice, da, a, scegli)!),
      ancora: () => undefined,
    });
  });

  afterEach(() => {
    trascina.annulla();
    contenitore.remove();
  });

  function tassello(id: string): HTMLElement {
    return contenitore.querySelector<HTMLElement>(`[data-id="${id}"]`)!;
  }

  function evento(tipo: string, el: HTMLElement, tocco = false): PointerEvent {
    const r = el.getBoundingClientRect();
    return new PointerEvent(tipo, {
      bubbles: true,
      cancelable: true,
      pointerId: 1,
      pointerType: tocco ? 'touch' : 'mouse',
      button: 0,
      clientX: r.left + r.width / 2,
      clientY: r.top + r.height / 2,
    });
  }

  function premi(id: string, dalCerchio = false, tocco = false): void {
    const el = dalCerchio ? tassello(id).querySelector<HTMLElement>('.spunta')! : tassello(id);
    const e = evento('pointerdown', el, tocco);
    Object.defineProperty(e, 'target', { value: el });
    trascina.premi(id, e);
  }

  const fotogramma = () => new Promise((r) => requestAnimationFrame(() => r(null)));

  it('col mouse dal cerchio entra in selezione e sceglie da b a d', async () => {
    premi('b', true);
    document.dispatchEvent(evento('pointermove', tassello('c')));
    await fotogramma();
    document.dispatchEvent(evento('pointermove', tassello('d')));
    document.dispatchEvent(evento('pointerup', tassello('d')));
    expect(inSelezione).toBeTrue();
    expect([...selezionate].sort()).toEqual(['b', 'c', 'd']);
    expect(trascina.assorbiClic()).toBeTrue();
    expect(trascina.assorbiClic()).toBeFalse();
  });

  it("tornando indietro l'intervallo si accorcia", async () => {
    inSelezione = true;
    premi('a');
    document.dispatchEvent(evento('pointermove', tassello('d')));
    await fotogramma();
    document.dispatchEvent(evento('pointermove', tassello('b')));
    document.dispatchEvent(evento('pointerup', tassello('b')));
    expect([...selezionate].sort()).toEqual(['a', 'b']);
  });

  it('partendo da una scelta toglie quelle attraversate', () => {
    inSelezione = true;
    selezionate = new Set(['a', 'b', 'c', 'd', 'e']);
    premi('b');
    document.dispatchEvent(evento('pointermove', tassello('d')));
    document.dispatchEvent(evento('pointerup', tassello('d')));
    expect([...selezionate].sort()).toEqual(['a', 'e']);
  });

  it('col mouse fuori dalla selezione e non dal cerchio non fa nulla (si apre il visore)', () => {
    premi('b');
    document.dispatchEvent(evento('pointermove', tassello('d')));
    document.dispatchEvent(evento('pointerup', tassello('d')));
    expect(inSelezione).toBeFalse();
    expect(selezionate.size).toBe(0);
    expect(trascina.assorbiClic()).toBeFalse();
  });

  it('un clic senza movimento resta un clic', () => {
    inSelezione = true;
    premi('c');
    document.dispatchEvent(evento('pointerup', tassello('c')));
    expect(selezionate.size).toBe(0);
    expect(trascina.assorbiClic()).toBeFalse();
  });

  it('col dito: la pressione lunga entra in selezione, poi il dito sceglie', () => {
    jasmine.clock().install();
    try {
      premi('a', false, true);
      jasmine.clock().tick(500);
      expect(inSelezione).toBeTrue();
      expect([...selezionate]).toEqual(['a']);
      document.dispatchEvent(evento('pointermove', tassello('c'), true));
      document.dispatchEvent(evento('pointerup', tassello('c'), true));
      expect([...selezionate].sort()).toEqual(['a', 'b', 'c']);
    } finally {
      jasmine.clock().uninstall();
    }
  });

  it('col dito: se si muove prima della pressione lunga è uno scorrimento', () => {
    jasmine.clock().install();
    try {
      premi('a', false, true);
      document.dispatchEvent(evento('pointermove', tassello('c'), true));
      jasmine.clock().tick(500);
      expect(inSelezione).toBeFalse();
      expect(selezionate.size).toBe(0);
    } finally {
      jasmine.clock().uninstall();
    }
  });
});
