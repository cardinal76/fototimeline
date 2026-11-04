import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Foto, VocePersona, VoltoInFoto } from './modelli';
import { PersoneFoto, nomiDaProporre, personeDaEscludere } from './persone-foto';

const anna: VocePersona = { id: 'p-anna', nome: 'Anna', nascosta: false, volti: 3, proposte: 0 };
const bruno: VocePersona = { id: 'p-bruno', nome: 'bruno', nascosta: false, volti: 2, proposte: 0 };
const sconosciuti: VocePersona = { id: 'p-sc', nome: 'Sconosciuti', nascosta: true, volti: 9, proposte: 0 };

describe('nomiDaProporre', () => {
  it('in ordine alfabetico, senza la persona da cui il volto si sposta', () => {
    expect(nomiDaProporre([bruno, anna], [], 'p-anna').map((p) => p.nome)).toEqual(['bruno']);
    expect(nomiDaProporre([bruno, anna], []).map((p) => p.nome)).toEqual(['Anna', 'bruno']);
  });

  it('mai quelle già nella foto', () => {
    expect(nomiDaProporre([bruno, anna], [sconosciuti], ['p-anna', 'p-sc']).map((p) => p.nome)).toEqual(['bruno']);
  });

  it('le nascoste solo se gliele si passano (admin), una volta sola', () => {
    expect(nomiDaProporre([anna], [sconosciuti, sconosciuti]).map((p) => [p.nome, p.nascosta])).toEqual([
      ['Anna', false],
      ['Sconosciuti', true],
    ]);
  });
});

describe('personeDaEscludere', () => {
  const foto: VoltoInFoto[] = [
    { id: 'v1', riquadro: { x: 0, y: 0, larghezza: 0.2, altezza: 0.2 }, personaId: 'p-anna', nome: 'Anna' },
    { id: 'v2', riquadro: { x: 0.5, y: 0, larghezza: 0.2, altezza: 0.2 }, personaId: 'p-gruppo' },
    { id: 'v3', personaId: 'p-bruno', nome: 'bruno', aggiunto: true },
    { id: 'v4', riquadro: { x: 0.8, y: 0, larghezza: 0.1, altezza: 0.1 } },
  ];

  it('per "+ Aggiungi persona" tutte quelle della foto', () => {
    expect(personeDaEscludere(foto)).toEqual(['p-anna', 'p-gruppo', 'p-bruno']);
  });

  it('per un volto quelle sugli altri volti veri: una aggiunta senza volto si può spostare lì', () => {
    expect(personeDaEscludere(foto, foto[3])).toEqual(['p-anna', 'p-gruppo']);
    // Un volto con nome non si propone a sé stesso; un gruppo senza nome sì (gli si dà il nome).
    expect(personeDaEscludere(foto, foto[0])).toEqual(['p-anna', 'p-gruppo']);
    expect(personeDaEscludere(foto, foto[1])).toEqual(['p-anna']);
  });
});

describe('PersoneFoto', () => {
  const volti: VoltoInFoto[] = [
    { id: 'v1', riquadro: { x: 0, y: 0, larghezza: 0.2, altezza: 0.2 }, personaId: 'p-anna', nome: 'Anna' },
    { id: 'v2', riquadro: { x: 0.5, y: 0, larghezza: 0.2, altezza: 0.2 } },
  ];
  let api: jasmine.SpyObj<FotoApi>;
  let galleria: {
    volti: ReturnType<typeof signal<{ attiva: boolean }>>;
    puoGestire: ReturnType<typeof signal<boolean>>;
    admin: ReturnType<typeof signal<boolean>>;
    personeNominate: ReturnType<typeof signal<VocePersona[]>>;
    aggiornaPersone: jasmine.Spy;
    filtraPersona: jasmine.Spy;
    avvisa: jasmine.Spy;
  };
  let fixture: ComponentFixture<PersoneFoto>;

  async function crea(gestore: boolean, admin = false, diQuesta = volti): Promise<HTMLElement> {
    api = jasmine.createSpyObj<FotoApi>('FotoApi', [
      'voltiDellaFoto',
      'aggiungiPersona',
      'nominaVolto',
      'togliVolto',
      'scartaVolto',
      'assegnaVolto',
      'rifiutaProposta',
      'persone',
    ]);
    api.voltiDellaFoto.and.returnValue(of(diQuesta));
    api.togliVolto.and.returnValue(of(undefined));
    api.scartaVolto.and.returnValue(of(undefined));
    api.persone.and.returnValue(of({ persone: [sconosciuti], totale: 1 }));
    galleria = {
      volti: signal({ attiva: true }),
      puoGestire: signal(gestore),
      admin: signal(admin),
      personeNominate: signal([anna, bruno]),
      aggiornaPersone: jasmine.createSpy('aggiornaPersone'),
      filtraPersona: jasmine.createSpy('filtraPersona'),
      avvisa: jasmine.createSpy('avvisa'),
    };
    TestBed.configureTestingModule({
      imports: [PersoneFoto],
      providers: [
        provideZonelessChangeDetection(),
        { provide: FotoApi, useValue: api },
        { provide: Galleria, useValue: galleria },
      ],
    });
    fixture = TestBed.createComponent(PersoneFoto);
    fixture.componentRef.setInput('foto', { id: 'f1' } as Foto);
    await fixture.whenStable();
    return fixture.nativeElement as HTMLElement;
  }

  function pulsante(host: HTMLElement, testo: string, dentro = host): HTMLButtonElement {
    const b = [...dentro.querySelectorAll('button')].find((x) => x.textContent?.trim() === testo);
    expect(b).withContext(testo).toBeDefined();
    return b as HTMLButtonElement;
  }

  async function apriMenu(host: HTMLElement, riga: number): Promise<HTMLElement> {
    host.querySelectorAll<HTMLButtonElement>('button.matita')[riga].click();
    await fixture.whenStable();
    return host.querySelector('.menu') as HTMLElement;
  }

  it("chi guarda soltanto non ha la matita", async () => {
    const host = await crea(false);
    expect(host.querySelectorAll('button.matita').length).toBe(0);
    expect(host.textContent).toContain('Anna');
  });

  it('su una persona: un altro nome, togli, non è un volto', async () => {
    const host = await crea(true);
    const menu = await apriMenu(host, 0);
    expect([...menu.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual([
      "È un'altra persona…",
      'Non è nessuno / togli',
      'Non è un volto',
      'Annulla',
    ]);
  });

  it("su un volto senza nessuno non c'è \"togli\"", async () => {
    const host = await crea(true);
    const menu = await apriMenu(host, 1);
    expect([...menu.querySelectorAll('button')].map((b) => b.textContent?.trim())).toEqual([
      'Chi è?',
      'Non è un volto',
      'Annulla',
    ]);
  });

  it("\"È un'altra persona…\" propone gli altri nomi e sposta il volto", async () => {
    const host = await crea(true);
    const menu = await apriMenu(host, 0);
    pulsante(host, "È un'altra persona…", menu).click();
    await fixture.whenStable();
    const campo = host.querySelector('input') as HTMLInputElement;
    expect(campo.placeholder).toBe('Chi è davvero?');
    expect(document.activeElement).toBe(campo);
    // Anna, da cui si sposta, non è fra i suggerimenti.
    expect([...host.querySelectorAll('datalist option')].map((o) => (o as HTMLOptionElement).value)).toEqual(['bruno']);
    expect(api.persone).not.toHaveBeenCalled();

    api.nominaVolto.and.returnValue(of(bruno));
    campo.value = 'bruno';
    pulsante(host, 'OK').click();
    await fixture.whenStable();
    expect(api.nominaVolto).toHaveBeenCalledWith('v1', 'bruno');
    expect(galleria.aggiornaPersone).toHaveBeenCalled();
    expect(api.voltiDellaFoto).toHaveBeenCalledTimes(2);
    expect(host.querySelector('input')).toBeNull();
  });

  it("all'admin anche le nascoste, e dice quando il volto si nasconde con lei", async () => {
    const host = await crea(true, true);
    const menu = await apriMenu(host, 1);
    pulsante(host, 'Chi è?', menu).click();
    await fixture.whenStable();
    expect(api.persone).toHaveBeenCalledWith('NASCOSTE');
    // Anna no: è già sull'altro volto della foto.
    expect([...host.querySelectorAll('datalist option')].map((o) => (o as HTMLOptionElement).value)).toEqual([
      'bruno',
      'Sconosciuti',
    ]);
    api.nominaVolto.and.returnValue(of(sconosciuti));
    (host.querySelector('input') as HTMLInputElement).value = 'Sconosciuti';
    pulsante(host, 'OK').click();
    await fixture.whenStable();
    expect(galleria.avvisa).toHaveBeenCalledWith(jasmine.stringMatching('nascosta'));
  });

  it('"Non è nessuno" toglie senza chiedere, "Non è un volto" chiede prima', async () => {
    const host = await crea(true);
    pulsante(host, 'Non è nessuno / togli', await apriMenu(host, 0)).click();
    await fixture.whenStable();
    expect(api.togliVolto).toHaveBeenCalledWith('v1');
    expect(host.querySelector('.menu')).toBeNull();

    const conferma = spyOn(window, 'confirm').and.returnValue(false);
    pulsante(host, 'Non è un volto', await apriMenu(host, 1)).click();
    await fixture.whenStable();
    expect(conferma).toHaveBeenCalled();
    expect(api.scartaVolto).not.toHaveBeenCalled();
    conferma.and.returnValue(true);
    pulsante(host, 'Non è un volto', host.querySelector('.menu') as HTMLElement).click();
    await fixture.whenStable();
    expect(api.scartaVolto).toHaveBeenCalledWith('v2');
  });

  const conAggiunta: VoltoInFoto[] = [...volti, { id: 'v3', personaId: 'p-bruno', nome: 'bruno', aggiunto: true }];

  function suggeriti(host: HTMLElement): string[] {
    return [...host.querySelectorAll('datalist option')].map((o) => (o as HTMLOptionElement).value);
  }

  it('una persona aggiunta a mano: niente ritaglio né ✎, × la toglie dalla foto', async () => {
    const host = await crea(true, false, conAggiunta);
    const righe = host.querySelectorAll('li');
    expect(righe.length).toBe(3);
    expect(righe[2].querySelector('img')).toBeNull();
    expect(righe[2].querySelector('.senza-volto')?.textContent?.trim()).toBe('B');
    expect(righe[2].querySelector('button.matita')).toBeNull();
    (righe[2].querySelector('button.togli') as HTMLButtonElement).click();
    await fixture.whenStable();
    expect(api.togliVolto).toHaveBeenCalledWith('v3');
  });

  it('chi guarda soltanto non aggiunge e non toglie', async () => {
    const host = await crea(false, false, conAggiunta);
    expect(host.textContent).not.toContain('Aggiungi persona');
    expect(host.querySelectorAll('button.togli').length).toBe(0);
    expect(host.textContent).toContain('bruno');
  });

  it('"Chi è?" non propone chi è già su un altro volto, ma sì chi è aggiunto senza volto', async () => {
    const host = await crea(true, false, conAggiunta);
    pulsante(host, 'Chi è?', await apriMenu(host, 1)).click();
    await fixture.whenStable();
    expect(suggeriti(host)).toEqual(['bruno']);
  });

  it('"+ Aggiungi persona" propone chi non è nella foto e resta aperto per la prossima', async () => {
    const host = await crea(true);
    pulsante(host, '+ Aggiungi persona').click();
    await fixture.whenStable();
    const campo = host.querySelector('form.aggiungi input') as HTMLInputElement;
    expect(document.activeElement).toBe(campo);
    expect(suggeriti(host)).toEqual(['bruno']);

    api.aggiungiPersona.and.returnValue(of(bruno));
    campo.value = ' bruno ';
    pulsante(host, 'Aggiungi').click();
    await fixture.whenStable();
    expect(api.aggiungiPersona).toHaveBeenCalledWith('f1', 'bruno');
    expect(galleria.aggiornaPersone).toHaveBeenCalled();
    expect(api.voltiDellaFoto).toHaveBeenCalledTimes(2);
    expect(campo.value).toBe('');
    expect(host.querySelector('form.aggiungi')).not.toBeNull();

    pulsante(host, 'Fine').click();
    await fixture.whenStable();
    expect(host.querySelector('form.aggiungi')).toBeNull();
  });

  it('se è già nella foto lo dice il server, e il nome resta scritto', async () => {
    const host = await crea(true);
    pulsante(host, '+ Aggiungi persona').click();
    await fixture.whenStable();
    api.aggiungiPersona.and.returnValue(throwError(() => ({ error: { detail: 'Anna è già in questa foto' } })));
    const campo = host.querySelector('form.aggiungi input') as HTMLInputElement;
    campo.value = 'Anna';
    pulsante(host, 'Aggiungi').click();
    await fixture.whenStable();
    expect(galleria.avvisa).toHaveBeenCalledWith('Anna è già in questa foto');
    expect(campo.value).toBe('Anna');
  });

  it('senza volti trovati: al gestore il pannello con "+ Aggiungi persona", a chi guarda niente', async () => {
    let host = await crea(true, false, []);
    expect(host.querySelector('ul')).toBeNull();
    pulsante(host, '+ Aggiungi persona');
    TestBed.resetTestingModule();
    host = await crea(false, false, []);
    expect(host.querySelector('section')).toBeNull();
  });
});
