import com.pdfconversionapi.ConversionResult;
import com.pdfconversionapi.ConvertOptions;
import com.pdfconversionapi.OutputFile;
import com.pdfconversionapi.PdfConversionApi;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Stored files: get links instead of data, stream each one to disk, then delete the stored copy. */
public class StoredFiles {
  public static void main(String[] args) throws IOException {
    PdfConversionApi client = PdfConversionApi.builder().build();
    ConversionResult result =
        client.convert(
            "pdf",
            "jpg",
            Path.of("season-guide.pdf"),
            Map.of("ImageResolution", 72),
            ConvertOptions.builder().storeFile(true).build());

    for (OutputFile file : result.getFiles()) {
      Path saved = file.save(Path.of(".")); // downloads the link (no key sent) and streams it to disk
      boolean deleted = client.deleteStoredFile(file.getFileId()); // or let it expire after 3 hours
      System.out.println(saved.getFileName() + " " + Files.size(saved) + " bytes; deleted: " + deleted);
    }
  }
}
