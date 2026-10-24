package it.fototimeline.telefono;

import java.nio.file.Path;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * I video del telefono troppo grandi per il cloud ({@link VideoGrandi}):
 * LifetimeCloud rifiuta i file oltre 20480 MB ("File too large", 403) e il
 * suo nginx a volte anche sotto (413). Si ricomprimono in H.265 e l'originale
 * resta su pCloud, in una cartella sua. Dove si ricomprimono lo dice
 * {@code dove}: sul PC di Marco (predefinito: server2 non ha lo spazio per un
 * video da 20 GB e la CPU serve ad altro) o sul server.
 *
 * @param attiva             false: i video troppo grandi restano errori, come prima
 * @param massimoMb          oltre questi MB (MiB) un video non si prova nemmeno a
 *                           copiare: si ricomprime subito. Poco sotto il limite di
 *                           LifetimeCloud (20480 MB); è anche il tetto del risultato
 * @param cartellaOriginali  dove vanno gli originali, dalla radice del remote del
 *                           telefono ({@code pcloud:Video originali troppo grandi}):
 *                           la sincronizzazione non la guarda mai
 * @param cartella           dove l'app scarica l'originale e ffmpeg scrive il
 *                           risultato: sul disco del server, non nel cloud né in /tmp
 * @param cartellaRclone     la stessa cartella vista dal container di rclone, che
 *                           ci scarica; vuota = uguale a {@code cartella}
 * @param riserva            spazio da lasciare libero oltre a originale e risultato
 * @param crf                il CRF della prima prova (24: quello provato a mano)
 * @param crfMassimo         l'ultimo CRF da provare se il risultato è ancora troppo grande
 * @param passoCrf           di quanto si alza il CRF a ogni prova
 * @param preset             il preset di x265
 * @param thread             thread di ffmpeg (decodifica e pool di x265)
 * @param parametriX265      altri parametri di x265: quanti fotogrammi tiene in
 *                           memoria (DEPLOY.md, "Video troppo grandi")
 * @param memoriaMassima     quanta memoria può prendere ffmpeg nel container: un
 *                           video che secondo la stima ne vuole di più (8K, 4K a
 *                           10 bit) non si ricomprime, è un errore che lo dice
 * @param tolleranzaDurata   di quanto può differire la durata del risultato da
 *                           quella dell'originale
 * @param attesaRimandati    dopo quanto si riprova un video rimandato (spazio, rclone)
 * @param tentativi          quante volte si rimanda prima di dirlo errore
 * @param attesaImportazione per quanto un ricompresso sparito dalla cartella
 *                           automatica senza una scheda si aspetta, prima di dirlo errore
 * @param controllo          ogni quanto la coda guarda se c'è da fare (e se il PC ha
 *                           lasciato un ricompresso da prendere)
 * @param dove               {@link Dove#PC}: il server mette in coda e aspetta il PC
 *                           ({@code deploy/ricomprimi-video-grandi.sh}), niente download né
 *                           ffmpeg qui; {@link Dove#SERVER}: ricomprime lui, come prima
 * @param cartellaDalPc      dove il PC carica i ricompressi, dalla radice del remote del
 *                           cloud ({@code lifetime:video-grandi-dal-pc/<id>.mp4}): fuori dalla
 *                           cartella automatica, così l'importazione non li legge a metà
 */
@ConfigurationProperties("fototimeline.telefono.video-grandi")
public record VideoGrandiProperties(Boolean attiva, Long massimoMb, String cartellaOriginali, Path cartella,
        String cartellaRclone, DataSize riserva, Integer crf, Integer crfMassimo, Integer passoCrf, String preset,
        Integer thread, String parametriX265, DataSize memoriaMassima, Duration tolleranzaDurata, Duration attesaRimandati, Integer tentativi,
        Duration attesaImportazione, Duration controllo, Dove dove, String cartellaDalPc) {

    /** Dove si ricomprime. */
    public enum Dove {
        /** Sul PC, con {@code deploy/ricomprimi-video-grandi.sh}: il server mette in coda e prende il risultato. */
        PC,
        /** Sul server, come prima della ricompressione sul PC. */
        SERVER
    }

    public static final long MASSIMO_MB = 20_000;
    public static final String CARTELLA_DAL_PC = "video-grandi-dal-pc";
    public static final String CARTELLA_ORIGINALI = "Video originali troppo grandi";
    public static final String PARAMETRI_X265 = "frame-threads=1:rc-lookahead=5:bframes=2:ref=2:lookahead-slices=0";

    public VideoGrandiProperties {
        if (attiva == null) {
            attiva = true;
        }
        if (massimoMb == null || massimoMb <= 0) {
            massimoMb = MASSIMO_MB;
        }
        cartellaOriginali = cartellaOriginali == null ? "" : cartellaOriginali.strip().replaceAll("^/+|/+$", "");
        if (cartellaOriginali.isEmpty()) {
            cartellaOriginali = CARTELLA_ORIGINALI;
        }
        if (cartella == null) {
            cartella = Path.of(System.getProperty("java.io.tmpdir"), "fototimeline-video-grandi");
        }
        if (cartellaRclone == null || cartellaRclone.isBlank()) {
            cartellaRclone = cartella.toString();
        }
        if (riserva == null) {
            riserva = DataSize.ofGigabytes(2);
        }
        if (crf == null || crf < 0 || crf > 51) {
            crf = 24;
        }
        if (crfMassimo == null || crfMassimo < crf || crfMassimo > 51) {
            crfMassimo = Math.min(51, Math.max(crf, 32));
        }
        if (passoCrf == null || passoCrf < 1) {
            passoCrf = 4;
        }
        if (preset == null || preset.isBlank()) {
            preset = "fast";
        }
        if (thread == null || thread < 1) {
            thread = 2;
        }
        if (parametriX265 == null) {
            parametriX265 = PARAMETRI_X265;
        }
        if (memoriaMassima == null) {
            memoriaMassima = DataSize.ofMegabytes(1100);
        }
        if (tolleranzaDurata == null || tolleranzaDurata.isNegative()) {
            tolleranzaDurata = Duration.ofSeconds(1);
        }
        if (attesaRimandati == null || attesaRimandati.isNegative() || attesaRimandati.isZero()) {
            attesaRimandati = Duration.ofHours(1);
        }
        if (tentativi == null || tentativi < 1) {
            tentativi = 5;
        }
        if (attesaImportazione == null || attesaImportazione.isNegative()) {
            attesaImportazione = Duration.ofDays(1);
        }
        if (controllo == null || controllo.isNegative() || controllo.isZero()) {
            controllo = Duration.ofMinutes(5);
        }
        if (dove == null) {
            dove = Dove.PC;
        }
        cartellaDalPc = cartellaDalPc == null ? "" : cartellaDalPc.strip().replaceAll("^/+|/+$", "");
        if (cartellaDalPc.isEmpty()) {
            cartellaDalPc = CARTELLA_DAL_PC;
        }
    }

    /** {@link #massimoMb} in byte. */
    public long massimo() {
        return massimoMb * 1024 * 1024;
    }

    /** I valori predefiniti, per i test e dove non c'è configurazione. */
    public static VideoGrandiProperties predefinite() {
        return new VideoGrandiProperties(null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null);
    }
}
