import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.ConvertOptions;
import com.pdfconversionapi.PdfConversionApi;
import com.pdfconversionapi.UploadFile;
import java.nio.file.Path;

/** Several inputs: three PDFs merged into one, named by the FileName option. */
public class MergePdfs {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build();
    ConversionResult result =
        client.convert(
            "pdf",
            "merge",
            UploadFile.fromPaths(Path.of("invoice.pdf"), Path.of("terms.pdf"), Path.of("timesheet.pdf")),
            null,
            ConvertOptions.builder().fileName("october-billing").build());

    Path saved = result.getFile().save(Path.of(".")); // ./october-billing.pdf
    System.out.println(saved.getFileName() + " " + result.getFile().getSize() + " bytes");
  }
}
