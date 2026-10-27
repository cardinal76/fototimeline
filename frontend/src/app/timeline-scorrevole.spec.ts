import { provideZonelessChangeDetection, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Galleria } from './galleria';
import { Filtro, VoceMese } from './modelli';
import { TimelineScorrevole } from './timeline-scorrevole';

describe('TimelineScorrevole', () => {
  const mesi: VoceMese[] = [
    { anno: 2024, mese: 5, conteggio: 10 },
    { anno: 2024, mese: 2, conteggio: 4 },
    { anno: 2021, mese: 8, conteggio: 30 },
    { anno: 2019, mese: 12, conteggio: 7 },
    { anno: 2019, mese: 1, conteggio: 2 },
  ];
  let galleria: { mesi: ReturnType<typeof signal<VoceMese[]>>; filtro: ReturnType<typeof signal<Filtro>>; salta: jasmine.Spy; imposta: jasmine.Spy };
  let fixture: ComponentFixture<TimelineScorrevole>;
  let striscia: HTMLElement;

  beforeEach(async () => {
    galleria = { mesi: signal(mesi), filtro: signal({}), salta: jasmine.createSpy('salta'), imposta: jasmine.createSpy('imposta') };
    TestBed.configureTestingModule({
      imports: [TimelineScorrevole],
      providers: [provideZonelessChangeDetection(), { provide: Galleria, useValue: galleria }],
    });
    fixture = TestBed.createComponent(TimelineScorrevole);
    const host = fixture.nativeElement as HTMLElement;
    host.style.height = '440px';
    document.body.appendChild(host);
    await fixture.whenStable();
    striscia = host.querySelector('.striscia')!;
  });

  afterEach(() => fixture.nativeElement.remove());

  /** Un punto della striscia, da 0 (in cima) a 1 (in fondo). */
  function punto(tipo: string, frazione: number): PointerEvent {
    const r = striscia.querySelector('.binario')!.getBoundingClientRect();
    return new PointerEvent(tipo, { bubbles: true, cancelable: true, pointerId: 1, clientY: r.top + frazione * r.height });
  }

  it('mostra un anno per etichetta, dal più recente', () => {
    const anni = Array.from(striscia.querySelectorAll('.anno')).map((e) => e.textContent!.trim());
    expect(anni).toEqual(['2024', '2021', '2019']);
  });

  it('trascinando mostra il fumetto e lasciando salta al mese sotto il dito', async () => {
    const giu = punto('pointerdown', 0);
    striscia.dispatchEvent(giu);
    expect(giu.defaultPrevented).toBeTrue();
    striscia.dispatchEvent(punto('pointermove', 0.5));
    await fixture.whenStable();
    expect(striscia.querySelector('.fumetto')?.textContent?.trim()).toBe('ago 2021');
    expect(galleria.salta).not.toHaveBeenCalled();

    striscia.dispatchEvent(punto('pointerup', 1));
    await fixture.whenStable();
    expect(galleria.salta).toHaveBeenCalledOnceWith(2019, 1);
    expect(striscia.querySelector('.fumetto')).toBeNull();
  });

  it('in cima torna alle più recenti invece di filtrare', () => {
    striscia.dispatchEvent(punto('pointerdown', 0));
    striscia.dispatchEvent(punto('pointerup', 0));
    expect(galleria.imposta).toHaveBeenCalledOnceWith({ al: undefined });
    expect(galleria.salta).not.toHaveBeenCalled();
  });

  it('un tocco annullato dal sistema non salta', () => {
    striscia.dispatchEvent(punto('pointerdown', 0.7));
    striscia.dispatchEvent(new PointerEvent('pointercancel', { pointerId: 1 }));
    striscia.dispatchEvent(punto('pointerup', 0.7));
    expect(galleria.salta).not.toHaveBeenCalled();
  });

  it('con un solo mese non mostra niente', async () => {
    galleria.mesi.set([mesi[0]]);
    await fixture.whenStable();
    expect(fixture.nativeElement.querySelector('.striscia')).toBeNull();
  });
});
