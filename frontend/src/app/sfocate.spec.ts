import { Sfocate } from './sfocate';

describe('Sfocate', () => {
  let sfocate: Sfocate;
  const privata = { id: 'p1', privataDi: 'anna' };
  const altraPrivata = { id: 'p2', privataDi: 'anna' };
  const diTutti = { id: 'd1' };

  beforeEach(() => {
    sfocate = new Sfocate();
  });

  it('sfoca solo le private, dalla scheda o dal punto della mappa', () => {
    expect(sfocate.sfocata(privata)).toBeTrue();
    expect(sfocate.sfocata({ id: 'm1', privata: true })).toBeTrue();
    expect(sfocate.sfocata(diTutti)).toBeFalse();
    expect(sfocate.sfocata({ id: 'm2', privata: false })).toBeFalse();
  });

  it('il primo clic svela senza aprire, il secondo apre', () => {
    expect(sfocate.svelaAlPrimoClic(privata)).toBeTrue();
    expect(sfocate.sfocata(privata)).toBeFalse();
    expect(sfocate.svelaAlPrimoClic(privata)).toBeFalse();
  });

  it('una foto di tutti si apre al primo clic', () => {
    expect(sfocate.svelaAlPrimoClic(diTutti)).toBeFalse();
  });

  it('svelarne una non svela le altre', () => {
    sfocate.svela(privata.id);
    expect(sfocate.sfocata(altraPrivata)).toBeTrue();
  });

  it('resta solo in memoria: un servizio nuovo (pagina ricaricata) le sfoca di nuovo', () => {
    sfocate.svela(privata.id);
    expect(new Sfocate().sfocata(privata)).toBeTrue();
  });
});
