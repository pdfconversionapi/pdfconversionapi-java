# PDF Conversion API for Java and Kotlin

The official Java client for the [PDF Conversion API](https://pdfconversionapi.com/docs): convert
documents, spreadsheets, images and PDFs with one call, and get typed results and typed errors back.

- Java 11 or later, and Kotlin. No runtime dependencies: it uses `java.net.http`.
- One method call is one API request; every call waits for its result.
- Retries with backoff for busy servers and dropped connections, built in.

[Docs](https://pdfconversionapi.com/docs) · [Get an API key](https://app.pdfconversionapi.com) ·
[Pricing](https://pdfconversionapi.com/pricing)

## Install

Maven:

```xml
<dependency>
  <groupId>com.pdfconversionapi</groupId>
  <artifactId>pdfconversionapi</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("com.pdfconversionapi:pdfconversionapi:0.1.0")
```

## The API key

Sign in at [app.pdfconversionapi.com](https://app.pdfconversionapi.com) to get a key, and keep it in
the `PDFCONVERSIONAPI_KEY` environment variable, which the client reads by default:

```bash
export PDFCONVERSIONAPI_KEY="paste-your-key-here"
```

Keep the key on your server. Never put it in a web page or an app you ship to users.

## Quick start

```java
PdfConversionApi client = PdfConversionApi.builder().build(); // reads PDFCONVERSIONAPI_KEY
ConversionResult result = client.convertFile(Path.of("report.docx"), "pdf");
result.saveAll(Path.of("out")); // writes out/report.pdf
```

`convertFile` reads the format from the file name (`docx`), sends the file to `docx/to/pdf`, and
`saveAll` writes every output into a folder under its own name. Make one client and reuse it: it is
immutable and safe to share between threads.

## Convert a file

`convert(from, to, file, params, options)` is the general form. The file can be a `Path`, or an
`UploadFile` made from a path, bytes or a stream:

```java
UploadFile.fromPath(Path.of("report.docx"));       // sent as report.docx
UploadFile.fromBytes(bytes, "report.docx");         // bytes in memory, with a name
UploadFile.fromStream(inputStream, "report.docx");  // read to its end once, so retries can resend it
```

The API names the output after the upload (`report.docx` comes back as `report.pdf`); bytes and
streams without a name are sent as `file`. Inputs are always a `Path`, bytes or a stream: a
`String` is never read as a file's content, and file paths are given as `Path`.

## Several outputs: PDF to JPG

Page images come back as one file per page, in page order:

```java
PdfConversionApi client = PdfConversionApi.builder().build();
ConversionResult result =
    client.convert("pdf", "jpg", Path.of("season-guide.pdf"), Map.of("ImageResolution", 72, "PageRange", "1-2"));

for (OutputFile page : result.getFiles()) {
  System.out.println(page.getName() + " " + page.getSize() + " bytes");
}
result.saveAll(Path.of("pages")); // pages/season-guide-1.jpg, pages/season-guide-2.jpg
```

```text
season-guide-1.jpg 65272 bytes
season-guide-2.jpg 66627 bytes
```

## Parameters

Parameters go in a `Map`, sent in its iteration order (use a `LinkedHashMap` when order matters to
you):

- strings are sent as they are: `"PageRange", "1-2"`;
- numbers in plain decimal, never with an exponent: `72`, `1.5`;
- booleans as `true` or `false`;
- a `null` value is left out;
- a `Path`, `File` or `UploadFile` is sent as a file field with that name (see below).

The client doesn't check names or values: the API answers `400` with a message that names the
problem. Each conversion's page on [pdfconversionapi.com](https://pdfconversionapi.com/docs) lists
its parameters.

## Several inputs: merge PDFs

Two or more files are sent in order. `ConvertOptions` names the output:

```java
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
```

```text
october-billing.pdf 107308 bytes
```

`pdf/to/merge`, `images/to/pdf`, `images/to/join` and `file/to/zip` take several files; the API
answers `400` when a conversion takes one. Conversions such as `data/to/qrcode` take no file at
all: `client.convert("data", "qrcode", Map.of("Data", "https://example.com"))`.

## A named file: image watermark

A parameter whose value is a file is sent as a file field with the parameter's name, here
`ImageFile`:

```java
PdfConversionApi client = PdfConversionApi.builder().build();
ConversionResult result =
    client.convert(
        "pdf", "image-watermark", Path.of("invoice.pdf"), Map.of("ImageFile", Path.of("logo.png"), "Opacity", 30));

Path saved = result.getFile().save(Path.of("invoice-stamped.pdf"));
System.out.println(saved + " " + result.getFile().getSize() + " bytes");
```

## Stored files: download and delete

With `storeFile(true)`, each output is kept for 3 hours behind a download link instead of coming
back as data. `save` downloads it (the link needs no key) and streams it to disk:

```java
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
```

```text
season-guide-1.jpg 65272 bytes; deleted: true
season-guide-2.jpg 66627 bytes; deleted: true
```

- `file.download()` returns the bytes, and `client.downloadTo(url, path)` writes any link to a file.
- `file.getData()` on a stored file throws `StoredFileException`: there is no data in the response.
- `deleteStoredFile` returns `false` when there was nothing to delete. After a delete or after 3
  hours, the link answers `404` (`NotFoundException`).
- Treat `getUrl()` like a password: anyone who has it can download the file.

## Large files

The whole response is held in memory, and base64 makes it a third larger than the files in it. For
large outputs, convert with `storeFile(true)` and save each file: `save` and `downloadTo` stream the
download to disk.

## Usage

Your plan, its limits and this month's count. It doesn't use a conversion:

```java
PdfConversionApi client = PdfConversionApi.builder().build();
Usage usage = client.usage();

System.out.println(usage.getPlan() + " plan: " + usage.getUsed() + " of " + usage.getIncluded()
    + " conversions used, " + usage.getRemaining() + " left until " + usage.getPeriodEnd());
System.out.println(usage.getConcurrentLimit() + " at a time, uploads up to " + usage.getMaxUploadMb() + " MB");
```

```text
Free plan: 12 of 250 conversions used, 238 left until 2026-12-01T00:00:00Z
1 at a time, uploads up to 10 MB
```

`client.conversions()` lists every conversion the API offers (`from`, `to`, `title`, `category`
and how many files it takes), and needs no key.

## Errors

Every exception is unchecked. API and network problems are a `PdfConversionApiException`:

| Exception | When |
|---|---|
| `MissingApiKeyException` | No key for a call that needs one. Nothing was sent. |
| `ApiException` | The API answered with an error status: `getStatus()`, `getMessage()`, `getRequestId()`, `getRetryAfter()`, `getBody()`. |
| ├ `BadRequestException` | 400: change the request, for example a parameter value. |
| ├ `AuthenticationException` | 401: the key is missing, unknown or replaced. |
| ├ `PermissionDeniedException` | 403: the key's plan isn't set up; write to support. |
| ├ `NotFoundException` | 404: an unsupported pair, or an expired or deleted stored file. |
| ├ `PayloadTooLargeException` | 413: the request, an image or a page is over a limit. |
| ├ `UnprocessableEntityException` | 422: the file can't be read or converted as asked, such as a password-protected one. |
| ├ `RateLimitException` | 429: rate limit, conversions at once, or the month's conversions. |
| ├ `InternalServerException` | 500. |
| ├ `ServiceUnavailableException` | 503: busy, or conversions on the plan are paused. |
| └ `GatewayTimeoutException` | 504: the conversion ran out of time on the server. |
| `ApiTimeoutException` | The client's own timeout ran out. |
| `ApiConnectionException` | No response, after the retries. |
| `InvalidResponseException` | A successful status with a body the client can't use. |
| `StoredFileException` | `getData()` on a stored file. |

Bad arguments, such as a format that isn't 1 to 20 letters, digits or hyphens, throw
`IllegalArgumentException` before anything is sent. A missing input file throws
`UncheckedIOException`, also before anything is sent.

```java
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
```

```text
bad request: PageRange '3-1' is not valid (example: 1-3,5)
com.pdfconversionapi.BadRequestException: HTTP 400: PageRange '3-1' is not valid (example: 1-3,5) (request id: 60a50d18b1a64365)
```

`getMessage()` is the API's own message; branch on the exception class or `getStatus()`, since
message texts can change. Quote `getRequestId()` when you write to support. No message ever
contains your key.

### Retries

Every call, downloads included, retries on its own, up to 4 times (5 attempts):

- **No response** (refused, reset or timed-out connection): after a random wait of up to 2, 4, 8
  and 16 seconds.
- **429 and 503:** after the `Retry-After` seconds plus that random wait, when `Retry-After` is 60
  seconds or less. A longer wait, such as a used-up month or paused conversions, is thrown at once
  with `getRetryAfter()` set.
- **500:** once, after about a second.
- **Never:** 400, 401, 403, 404, 413, 422 and 504, the client's own timeout, and a connection that
  drops after the response started (the conversion may already have counted).

Retrying is safe: a conversion changes nothing on the server, and only successful attempts count
toward the month.

## Timeouts and configuration

Each attempt may take 240 seconds by default, for the whole response, which covers the API's own
limits. A timeout throws `ApiTimeoutException` and is never retried, because the conversion may
still be running on the server and counts toward the month if it succeeds. Raise it for the client
or for one call:

```java
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
```

| Builder method | Default |
|---|---|
| `apiKey(String)` | `PDFCONVERSIONAPI_KEY` from the environment |
| `baseUrl(String)` | `PDFCONVERSIONAPI_BASE_URL` from the environment, else `https://api.pdfconversionapi.com` |
| `timeout(Duration)` | 240 seconds per attempt |
| `connectTimeout(Duration)` | 10 seconds |
| `maxRetries(int)` | 4 |
| `maxRetryWait(Duration)` | 60 seconds |
| `httpClient(HttpClient)` | the client's own; pass yours for a proxy or an executor. It must not follow redirects. |
| `sleeper(Sleeper)` | real sleep; replace it in tests to record the waits between retries |

## Kotlin

Getters read as properties, values that can be missing are typed as nullable (`requestId` is
`String?`), and Kotlin maps and lists work as parameters:

```kotlin
val client = PdfConversionApi.builder().build() // reads PDFCONVERSIONAPI_KEY

val pages = client.convert("pdf", "jpg", Path.of("season-guide.pdf"), mapOf("ImageResolution" to 72))
for (page in pages.files) println("${page.name} ${page.size} bytes")
println("request id: ${pages.requestId ?: "none"}")

try {
    client.convert("docx", "dwg", Path.of("report.docx"))
} catch (e: NotFoundException) {
    println("${e.status}: ${e.message}")
}
```

```text
season-guide-1.jpg 65272 bytes
season-guide-2.jpg 66627 bytes
request id: e57a20ed6e28b1d4
404: conversion from docx to dwg is not supported
```

## Examples

[`examples/`](examples) has every snippet above as a program. Put the input files in the current
folder and run one with the library on the class path:

```bash
java -cp target/classes examples/QuickStart.java
```

## Development

```bash
mvn test                  # unit tests, the shared conformance suite (needs Node.js), live tests
mvn test -Dtest=LiveTest  # the live tests only
mvn -Pkotlin test         # also compiles and runs the Kotlin tests
mvn package               # the jar
```

The conformance suite is read from `-Dconformance.dir=...`, else `PDFCONVERSIONAPI_CONFORMANCE_DIR`,
else `../conformance`, else `./conformance`. Live tests call the real API with the files in
`conformance/samples/`. They use the base URL and key in `conformance/.local-api.env` when it
exists, else `PDFCONVERSIONAPI_LIVE_BASE_URL` and `PDFCONVERSIONAPI_KEY` from the environment, and
skip without either (a key alone doesn't start them); `-Dpdfconversionapi.skipLive=true` skips
them too. A run makes about 5 conversions.

## License

MIT. See [LICENSE](LICENSE).
