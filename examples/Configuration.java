import com.pdfconversionapi.Conversion;
import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.ConvertOptions;
import com.pdfconversionapi.PdfConversionApi;
import com.pdfconversionapi.UploadFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Every client setting, a per-call timeout, input from a stream, and the list of conversions. */
public class Configuration {
  public static void main(String[] args) throws IOException {
    PdfConversionApi client =
        PdfConversionApi.builder()
            .apiKey(System.getenv("PDFCONVERSIONAPI_KEY")) // the default; null also reads the variable
            .timeout(Duration.ofMinutes(5)) // per attempt, for the whole response (default 240 s)
            .connectTimeout(Duration.ofSeconds(10)) // default 10 s
            .maxRetries(2) // default 4; 0 turns retries off
            .maxRetryWait(Duration.ofSeconds(30)) // longest Retry-After worth waiting for (default 60 s)
            .build();

    ConversionResult result;
    try (InputStream in = Files.newInputStream(Path.of("report.docx"))) {
      UploadFile upload = UploadFile.fromStream(in, "report.docx"); // read into memory once, for retries
      ConvertOptions options = ConvertOptions.builder().timeout(Duration.ofMinutes(10)).build(); // this call only
      result = client.convert("docx", "pdf", upload, null, options);
    }
    System.out.println(result.getFile().getName() + " in " + result.getConversionTime() + " s on the server");

    List<Conversion> conversions = client.conversions(); // needs no key
    System.out.println(conversions.size() + " conversions, for example " + conversions.get(0).getTitle());
  }
}
