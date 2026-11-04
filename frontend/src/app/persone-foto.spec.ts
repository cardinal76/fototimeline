import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { of } from 'rxjs';

import { FotoApi } from './foto-api';
import { Galleria } from './galleria';
import { Foto, VocePersona, VoltoInFoto } from './modelli';
import { PersoneFoto, nomiDaProporre } from './persone-foto';

const anna: VocePersona = { id: 'p-anna', nome: 'Anna', nascosta: false, volti: 3, proposte: 0 };
const bruno: VocePersona = { id: 'p-bruno', nome: 'bruno', nascosta: false, volti: 2, proposte: 0 };
const sconosciuti: VocePersona = { id: 'p-sc', nome: 'Sconosciuti', nascosta: true, volti: 9, proposte: 0 };

describe('nomiDaProporre', () => {
  it('in ordine alfabetico, senza la persona da cui il volto si sposta', () => {
    expect(nomiDaProporre([bruno, anna], [], 'p-anna').map((p) => p.nome)).toEqual(['bruno']);
    expect(nomiDaProporre([bruno, anna], []).map((p) => p.nome)).toEqual(['Anna', 'bruno']);
  });

  it('le nascoste solo se gliele si passano (admin), una volta sola', () => {
    expect(nomiDaProporre([anna], [sconosciuti, sconosciuti]).map((p) => [p.nome, p.nascosta])).toEqual([
      ['Anna', false],
      ['Sconosciuti', true],
    ]);
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

  async function crea(gestore: boolean, admin = false): Promise<HTMLElement> {
    api = jasmine.createSpyObj<FotoApi>('FotoApi', [
      'voltiDellaFoto',
      'nominaVolto',
      'togliVolto',
      'scartaVolto',
      'assegnaVolto',
      'rifiutaProposta',
      'persone',
    ]);
    api.voltiDellaFoto.and.returnValue(of(volti));
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
    expect([...host.querySelectorAll('datalist option')].map((o) => (o as HTMLOptionElement).value)).toEqual([
      'Anna',
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
});
