package it.fototimeline.media;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.springframework.stereotype.Component;

/**
 * Il turno dei lavori lunghi di ffmpeg: la versione compatibile dei video
 * ({@code ConversioneVideo}) e la ricompressione dei video troppo grandi del
 * telefono ({@code VideoGrandi}). Uno alla volta: ognuno conta nella memoria
 * del container (x265 su un 4K tiene in memoria centinaia di MB di
 * fotogrammi) e prende i suoi thread di CPU. I lavori brevi (fotogramma,
 * miniatura) passano da {@code FotoService.unaAllaVolta}, non da qui: un
 * video da ore non deve fermare le importazioni.
 */
@Component
public class TurnoVideo {

    private final Semaphore turno = new Semaphore(1, true);

    /**
     * Aspetta il turno, guardando {@code annullato} ogni secondo. True se
     * l'ha preso (va lasciato con {@link #lascia()}), false se nel frattempo
     * il lavoro è stato annullato.
     */
    public boolean prendi(BooleanSupplier annullato) throws InterruptedException {
        while (!annullato.getAsBoolean()) {
            if (turno.tryAcquire(1, TimeUnit.SECONDS)) {
                return true;
            }
        }
        return false;
    }

    public void lascia() {
        turno.release();
    }

    /** True se un lavoro lungo sta girando adesso. */
    public boolean occupato() {
        return turno.availablePermits() == 0;
    }
}
