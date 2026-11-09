import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.PdfConversionApi;
import java.nio.file.Path;

/** The quick start: report.docx to out/report.pdf. */
public class QuickStart {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build(); // reads PDFCONVERSIONAPI_KEY
    ConversionResult result = client.convertFile(Path.of("report.docx"), "pdf");
    result.saveAll(Path.of("out")); // writes out/report.pdf

    System.out.println("wrote out/" + result.getFile().getName() + ", " + result.getFile().getSize() + " bytes");
  }
}
