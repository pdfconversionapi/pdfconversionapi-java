import com.pdfconversionapi.ConvertOptions
import com.pdfconversionapi.NotFoundException
import com.pdfconversionapi.PdfConversionApi
import com.pdfconversionapi.UploadFile
import java.nio.file.Path

fun main() {
    val client = PdfConversionApi.builder().build() // reads PDFCONVERSIONAPI_KEY

    // Getters read as properties, and nullable values are typed as such (requestId is String?).
    val pages = client.convert("pdf", "jpg", Path.of("season-guide.pdf"), mapOf("ImageResolution" to 72))
    for (page in pages.files) println("${page.name} ${page.size} bytes")
    println("request id: ${pages.requestId ?: "none"}")

    val merged = client.convert(
        "pdf", "merge",
        UploadFile.fromPaths(listOf("invoice.pdf", "terms.pdf").map { Path.of(it) }),
        null,
        ConvertOptions.builder().fileName("merged").build(),
    )
    merged.saveAll(Path.of("out"))
    println("wrote out/${merged.file.name}")

    try {
        client.convert("docx", "dwg", Path.of("report.docx"))
    } catch (e: NotFoundException) {
        println("${e.status}: ${e.message}")
    }
}
