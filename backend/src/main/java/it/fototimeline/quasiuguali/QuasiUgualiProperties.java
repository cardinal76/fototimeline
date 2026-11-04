package it.fototimeline.quasiuguali;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Le soglie delle quasi uguali sono quelle della sensibilità scelta nella
 * pagina ({@link Confronto.Sensibilita}); qui solo il tempo e CLIP.
 *
 * @param finestraRaffica foto con data EXIF scattate entro questo tempo
 *                        possono essere una raffica (soglie più larghe); zero =
 *                        niente raffiche
 * @param finestraVicine  foto scattate entro questo tempo (qualunque data)
 *                        hanno soglie un po' più larghe; zero = mai
 * @param sogliaRaffica   bit di pHash per dire "simili" ai Ricordi su Telegram
 *                        (non entra nelle quasi uguali)
 * @param contenuto       se c'è visione, il contenuto (CLIP) conferma le coppie
 */
@ConfigurationProperties("fototimeline.quasi-uguali")
public record QuasiUgualiProperties(Duration finestraRaffica, Duration finestraVicine, Integer sogliaRaffica,
        Boolean contenuto) {

    public QuasiUgualiProperties {
        finestraRaffica = finestraRaffica == null ? Duration.ofSeconds(10) : finestraRaffica;
        finestraVicine = finestraVicine == null ? Duration.ofHours(6) : finestraVicine;
        sogliaRaffica = sogliaRaffica == null ? 12 : Math.clamp(sogliaRaffica, 0, 32);
        contenuto = contenuto == null || contenuto;
    }
}
