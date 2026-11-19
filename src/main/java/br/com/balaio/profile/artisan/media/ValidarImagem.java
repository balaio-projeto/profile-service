package br.com.balaio.profile.artisan.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;

@Component
public class ValidarImagem {
    public static final int MAX_BYTES = 2 * 1024 * 1024;

    public String tipo(byte[] bytes) {
        if (bytes == null || bytes.length == 0) throw invalida();
        if (bytes.length > MAX_BYTES) throw new MidiaException(MidiaException.Motivo.ARQUIVO_MUITO_GRANDE);
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new MidiaException(MidiaException.Motivo.FORMATO_NAO_SUPORTADO);
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                String mime = switch (reader.getFormatName().toLowerCase(Locale.ROOT)) {
                    case "jpeg", "jpg" -> "image/jpeg";
                    case "png" -> "image/png";
                    case "webp" -> "image/webp";
                    default -> throw new MidiaException(MidiaException.Motivo.FORMATO_NAO_SUPORTADO);
                };
                // Bound decoding memory before allocating a raster from untrusted input.
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels < 1 || pixels > 20_000_000 || reader.read(0) == null) throw invalida();
                return mime;
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException e) { throw invalida(); }
    }

    private MidiaException invalida() { return new MidiaException(MidiaException.Motivo.ARQUIVO_INVALIDO); }
}
