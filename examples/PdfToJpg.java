import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.OutputFile;
import com.pdfconversionapi.PdfConversionApi;
import java.nio.file.Path;
import java.util.Map;

/** Several outputs: one JPG per page, with parameters. */
public class PdfToJpg {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build();
    ConversionResult result =
        client.convert("pdf", "jpg", Path.of("season-guide.pdf"), Map.of("ImageResolution", 72, "PageRange", "1-2"));

    for (OutputFile page : result.getFiles()) {
      System.out.println(page.getName() + " " + page.getSize() + " bytes");
    }
    result.saveAll(Path.of("pages")); // pages/season-guide-1.jpg, pages/season-guide-2.jpg
  }
}
