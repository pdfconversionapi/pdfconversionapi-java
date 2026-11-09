import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.PdfConversionApi;
import java.nio.file.Path;
import java.util.Map;

/** A named file field: ImageFile is stamped on every page at 30% opacity. */
public class ImageWatermark {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build();
    ConversionResult result =
        client.convert(
            "pdf", "image-watermark", Path.of("invoice.pdf"), Map.of("ImageFile", Path.of("logo.png"), "Opacity", 30));

    Path saved = result.getFile().save(Path.of("invoice-stamped.pdf"));
    System.out.println(saved + " " + result.getFile().getSize() + " bytes");
  }
}
