import com.pdfconversionapi.ApiConnectionException;
import com.pdfconversionapi.ApiException;
import com.pdfconversionapi.ApiTimeoutException;
import com.pdfconversionapi.BadRequestException;
import com.pdfconversionapi.PdfConversionApi;
import com.pdfconversionapi.PdfConversionApiException;
import com.pdfconversionapi.RateLimitException;
import com.pdfconversionapi.ServiceUnavailableException;
import java.nio.file.Path;
import java.util.Map;

/** Every failure is a PdfConversionApiException; the subclasses say what happened. */
public class ErrorHandling {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build();
    try {
      client.convert("pdf", "jpg", Path.of("season-guide.pdf"), Map.of("PageRange", "3-1"));
    } catch (BadRequestException e) { // 400: change the request
      System.out.println("bad request: " + e.getMessage());
      System.out.println(e); // adds the status and the request id
    } catch (RateLimitException | ServiceUnavailableException e) { // still busy after the retries
      System.out.println("try again in " + e.getRetryAfter() + " seconds: " + e.getMessage());
    } catch (ApiException e) { // any other status
      System.out.println("HTTP " + e.getStatus() + ": " + e.getMessage() + " (request id " + e.getRequestId() + ")");
    } catch (ApiTimeoutException e) { // not retried: the conversion may still be running
      System.out.println("timed out: " + e.getMessage());
    } catch (ApiConnectionException e) { // no response after the retries
      System.out.println("network problem: " + e.getMessage());
    } catch (PdfConversionApiException e) { // anything else from the client
      System.out.println(e);
    }
  }
}
