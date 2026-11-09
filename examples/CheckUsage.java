import com.pdfconversionapi.PdfConversionApi;
import com.pdfconversionapi.Usage;

/** Your plan and this month's count. Doesn't use a conversion. */
public class CheckUsage {
  public static void main(String[] args) {
    PdfConversionApi client = PdfConversionApi.builder().build();
    Usage usage = client.usage();

    System.out.println(usage.getPlan() + " plan: " + usage.getUsed() + " of " + usage.getIncluded()
        + " conversions used, " + usage.getRemaining() + " left until " + usage.getPeriodEnd());
    System.out.println(usage.getConcurrentLimit() + " at a time, uploads up to " + usage.getMaxUploadMb() + " MB");
  }
}
