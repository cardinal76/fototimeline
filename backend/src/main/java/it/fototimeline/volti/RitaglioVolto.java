package it.fototimeline.volti;

import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.springframework.stereotype.Component;

import it.fototimeline.dominio.Foto;
import it.fototimeline.service.ArchivioFile;
import it.fototimeline.service.FotoService;
import it.fototimeline.volti.Volto.Riquadro;

/**
 * Il volto ritagliato per la griglia delle persone: un quadrato intorno al
 * riquadro (con un po' di margine), dalla miniatura sul disco del server,
 * mai dall'originale. La miniatura ha 480 px di lato: piccola da leggere,
 * e i riquadri sono in frazioni proprio di lei.
 */
@Component
public class RitaglioVolto {

    static final int LATO = 160;
    /** Quanto più grande del riquadro: il volto con un po' di capelli e di mento. */
    private static final double MARGINE = 1.35;

    private final ArchivioFile archivio;
    private final FotoService fotoService;

    public RitaglioVolto(ArchivioFile archivio, FotoService fotoService) {
        this.archivio = archivio;
        this.fotoService = fotoService;
    }

    public byte[] jpeg(Volto volto) {
        Path miniatura = archivio.miniatura(volto.getFotoId());
        if (!Files.exists(miniatura)) {
            // Rifatta dall'originale: serve il cloud montato, se no 503 come le miniature.
            Foto foto = fotoService.trova(volto.getFotoId())
                    .orElseThrow(() -> new IllegalArgumentException("La foto del volto non c'è più"));
            miniatura = fotoService.fileMiniaturaDritta(foto);
        }
        try {
            BufferedImage immagine = ImageIO.read(miniatura.toFile());
            if (immagine == null) {
                throw new IOException("miniatura illeggibile");
            }
            return jpeg(ritaglia(immagine, volto.getRiquadro()));
        } catch (IOException e) {
            throw new UncheckedIOException("Ritaglio del volto non riuscito: " + e.getMessage(), e);
        }
    }

    static BufferedImage ritaglia(BufferedImage immagine, Riquadro r) {
        int l = immagine.getWidth();
        int a = immagine.getHeight();
        double cx = (r.x() + r.larghezza() / 2) * l;
        double cy = (r.y() + r.altezza() / 2) * a;
        int lato = (int) Math.round(Math.max(r.larghezza() * l, r.altezza() * a) * MARGINE);
        lato = Math.max(8, Math.min(lato, Math.min(l, a)));
        int x = (int) Math.round(Math.clamp(cx - lato / 2.0, 0, l - lato));
        int y = (int) Math.round(Math.clamp(cy - lato / 2.0, 0, a - lato));
        BufferedImage uscita = new BufferedImage(LATO, LATO, BufferedImage.TYPE_INT_RGB);
        var g = uscita.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(immagine, 0, 0, LATO, LATO, x, y, x + lato, y + lato, null);
        } finally {
            g.dispose();
        }
        return uscita;
    }

    private static byte[] jpeg(BufferedImage immagine) throws IOException {
        var scrittore = ImageIO.getImageWritersByFormatName("jpg").next();
        var out = new ByteArrayOutputStream();
        try (var flusso = new MemoryCacheImageOutputStream(out)) {
            scrittore.setOutput(flusso);
            ImageWriteParam parametri = scrittore.getDefaultWriteParam();
            parametri.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            parametri.setCompressionQuality(0.85f);
            scrittore.write(null, new IIOImage(immagine, null, null), parametri);
        } finally {
            scrittore.dispose();
        }
        return out.toByteArray();
    }
}
