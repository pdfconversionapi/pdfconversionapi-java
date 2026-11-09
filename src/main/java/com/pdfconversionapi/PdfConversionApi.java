package com.pdfconversionapi;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpTimeoutException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The client for the <a href="https://pdfconversionapi.com/docs">PDF Conversion API</a>.
 *
 * <pre>{@code
 * PdfConversionApi client = PdfConversionApi.builder().build();   // reads PDFCONVERSIONAPI_KEY
 * ConversionResult result = client.convertFile(Path.of("report.docx"), "pdf");
 * result.saveAll(Path.of("out"));                                  // writes out/report.pdf
 * }</pre>
 *
 * <p>Every call waits for its result. Network failures before a response, 429 and 503 answers
 * with a short {@code Retry-After}, and a first 500 are retried with backoff (see {@link
 * Builder#maxRetries(int)}); everything else is thrown at once as a {@link
 * PdfConversionApiException}. The whole response is held in memory: for very large outputs, use
 * {@link ConvertOptions.Builder#storeFile(boolean)} and {@link OutputFile#save(Path)}, which
 * streams the file to disk.
 *
 * <p>A client is immutable and safe to share between threads. Make one and reuse it.
 */
public final class PdfConversionApi {
  /** This library's version. */
  public static final String VERSION = "0.1.0";

  /** The API's address, used unless the builder or {@code PDFCONVERSIONAPI_BASE_URL} sets one. */
  public static final String DEFAULT_BASE_URL = "https://api.pdfconversionapi.com";

  /** How long one attempt may take, for the whole response: 240 seconds. */
  public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(240);

  /** How long opening a connection may take: 10 seconds. */
  public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

  /** Retries after the first attempt: 4, so 5 attempts at most. */
  public static final int DEFAULT_MAX_RETRIES = 4;

  /** The longest {@code Retry-After} the client waits for before retrying: 60 seconds. */
  public static final Duration DEFAULT_MAX_RETRY_WAIT = Duration.ofSeconds(60);

  /** The environment variable read for the key when the builder sets none. */
  public static final String API_KEY_ENV = "PDFCONVERSIONAPI_KEY";

  /** The environment variable read for the base URL when the builder sets none. */
  public static final String BASE_URL_ENV = "PDFCONVERSIONAPI_BASE_URL";

  private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9-]{1,20}");
  private static final Pattern FILE_ID = Pattern.compile("[0-9A-Fa-f]{32}");

  private final @Nullable String apiKey;
  private final String baseUrl;
  private final Duration timeout;
  private final int maxRetries;
  private final Duration maxRetryWait;
  private final HttpClient http;
  private final Sleeper sleeper;
  private final String userAgent;

  private PdfConversionApi(Builder builder, @Nullable String apiKey, String baseUrl, HttpClient http) {
    this.apiKey = apiKey;
    this.baseUrl = baseUrl;
    this.timeout = builder.timeout;
    this.maxRetries = builder.maxRetries;
    this.maxRetryWait = builder.maxRetryWait;
    this.http = http;
    this.sleeper = builder.sleeper;
    String javaVersion = System.getProperty("java.version");
    this.userAgent =
        "pdfconversionapi-java/" + VERSION + " java/" + (javaVersion == null ? "unknown" : javaVersion);
  }

  /**
   * Starts building a client. With nothing set, the key comes from {@code PDFCONVERSIONAPI_KEY}
   * and the defaults apply.
   *
   * @return a new builder
   */
  public static Builder builder() {
    return new Builder();
  }

  /**
   * The base URL requests go to, without a trailing slash.
   *
   * @return the base URL
   */
  public String getBaseUrl() {
    return baseUrl;
  }

  /**
   * Whether the client has an API key. The key itself can't be read back.
   *
   * @return true if a key is set
   */
  public boolean hasApiKey() {
    return apiKey != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Conversions

  /**
   * Converts one file, reading its format from the file name's extension: {@code report.docx}
   * is sent to {@code docx/to/{to}}.
   *
   * @param path the file to convert
   * @param to the format or tool to convert to, such as {@code pdf} or {@code compress}
   * @return the result
   * @throws IllegalArgumentException if the file name has no extension, or a format is invalid
   * @throws UncheckedIOException if the file can't be read
   * @throws PdfConversionApiException if the API or the network fails
   */
  public ConversionResult convertFile(Path path, String to) {
    return convertFile(path, to, null, null);
  }

  /**
   * Converts one file with parameters, reading its format from the file name's extension.
   *
   * @param path the file to convert
   * @param to the format or tool to convert to
   * @param params parameters such as {@code ImageResolution}, or null; see {@link
   *     #convert(String, String, List, Map, ConvertOptions)}
   * @return the result
   */
  public ConversionResult convertFile(
      Path path, String to, @Nullable Map<String, ? extends @Nullable Object> params) {
    return convertFile(path, to, params, null);
  }

  /**
   * Converts one file with parameters and options, reading its format from the file name's
   * extension.
   *
   * @param path the file to convert
   * @param to the format or tool to convert to
   * @param params parameters, or null
   * @param options typed options, or null
   * @return the result
   */
  public ConversionResult convertFile(
      Path path,
      String to,
      @Nullable Map<String, ? extends @Nullable Object> params,
      @Nullable ConvertOptions options) {
    Objects.requireNonNull(path, "path");
    UploadFile upload = UploadFile.fromPath(path);
    String name = upload.getName();
    int dot = name.lastIndexOf('.');
    if (dot <= 0 || dot == name.length() - 1) {
      throw new IllegalArgumentException(
          "can't tell the format of " + name + " from its name; use convert(from, to, ...)");
    }
    return convert(name.substring(dot + 1), to, Collections.singletonList(upload), params, options);
  }

  /**
   * Converts one file on disk.
   *
   * @param from the file's format, such as {@code docx}
   * @param to the format or tool to convert to
   * @param file the file
   * @return the result
   */
  public ConversionResult convert(String from, String to, Path file) {
    return convert(from, to, file, null, null);
  }

  /**
   * Converts one file on disk with parameters.
   *
   * @param from the file's format
   * @param to the format or tool to convert to
   * @param file the file
   * @param params parameters, or null
   * @return the result
   */
  public ConversionResult convert(
      String from, String to, Path file, @Nullable Map<String, ? extends @Nullable Object> params) {
    return convert(from, to, file, params, null);
  }

  /**
   * Converts one file on disk with parameters and options.
   *
   * @param from the file's format
   * @param to the format or tool to convert to
   * @param file the file
   * @param params parameters, or null
   * @param options typed options, or null
   * @return the result
   */
  public ConversionResult convert(
      String from,
      String to,
      Path file,
      @Nullable Map<String, ? extends @Nullable Object> params,
      @Nullable ConvertOptions options) {
    Objects.requireNonNull(file, "file");
    return convert(from, to, Collections.singletonList(UploadFile.fromPath(file)), params, options);
  }

  /**
   * Converts one file.
   *
   * @param from the file's format
   * @param to the format or tool to convert to
   * @param file the file, from {@link UploadFile#fromPath}, {@link UploadFile#fromBytes} or
   *     {@link UploadFile#fromStream}
   * @return the result
   */
  public ConversionResult convert(String from, String to, UploadFile file) {
    return convert(from, to, file, null, null);
  }

  /**
   * Converts one file with parameters.
   *
   * @param from the file's format
   * @param to the format or tool to convert to
   * @param file the file
   * @param params parameters, or null
   * @return the result
   */
  public ConversionResult convert(
      String from, String to, UploadFile file, @Nullable Map<String, ? extends @Nullable Object> params) {
    return convert(from, to, file, params, null);
  }

  /**
   * Converts one file with parameters and options.
   *
   * @param from the file's format
   * @param to the format or tool to convert to
   * @param file the file
   * @param params parameters, or null
   * @param options typed options, or null
   * @return the result
   */
  public ConversionResult convert(
      String from,
      String to,
      UploadFile file,
      @Nullable Map<String, ? extends @Nullable Object> params,
      @Nullable ConvertOptions options) {
    Objects.requireNonNull(file, "file");
    return convert(from, to, Collections.singletonList(file), params, options);
  }

  /**
   * Converts several files, for a conversion that takes many such as {@code pdf/to/merge}.
   *
   * @param from the files' format
   * @param to the format or tool to convert to
   * @param files the files, in order
   * @return the result
   */
  public ConversionResult convert(String from, String to, List<UploadFile> files) {
    return convert(from, to, files, null, null);
  }

  /**
   * Converts several files with parameters.
   *
   * @param from the files' format
   * @param to the format or tool to convert to
   * @param files the files, in order
   * @param params parameters, or null
   * @return the result
   */
  public ConversionResult convert(
      String from,
      String to,
      List<UploadFile> files,
      @Nullable Map<String, ? extends @Nullable Object> params) {
    return convert(from, to, files, params, null);
  }

  /**
   * Converts without a file, from parameters alone, such as {@code data/to/qrcode} with {@code
   * Data}. A conversion that needs a file answers {@link BadRequestException}.
   *
   * @param from the source, such as {@code data}
   * @param to the format or tool to convert to
   * @param params parameters, or null
   * @return the result
   */
  public ConversionResult convert(
      String from, String to, @Nullable Map<String, ? extends @Nullable Object> params) {
    return convert(from, to, Collections.emptyList(), params, null);
  }

  /**
   * Converts without a file, from parameters alone, with options.
   *
   * @param from the source, such as {@code data}
   * @param to the format or tool to convert to
   * @param params parameters, or null
   * @param options typed options, or null
   * @return the result
   */
  public ConversionResult convert(
      String from,
      String to,
      @Nullable Map<String, ? extends @Nullable Object> params,
      @Nullable ConvertOptions options) {
    return convert(from, to, Collections.emptyList(), params, options);
  }

  /**
   * Converts files: the method every other {@code convert} overload calls.
   *
   * <p>One file is sent in the {@code File} field, two or more in repeated {@code Files} fields
   * in the order given, and none (null or an empty list) sends parameters only. Whether a
   * conversion takes several files, or none, is the API's call: it answers 400 when it doesn't.
   *
   * <p>Parameter values may be a {@link String}, a number (sent in plain decimal, never with an
   * exponent), a {@link Boolean} ({@code true}/{@code false}), null (not sent), or a file as an
   * {@link UploadFile}, {@link Path} or {@link java.io.File}, which is sent as a file field with
   * the parameter's name (such as {@code ImageFile}). Fields are sent in the map's iteration
   * order. Names and values aren't checked here; the API answers 400 with a clear message.
   *
   * @param from the input format, such as {@code docx}; 1 to 20 letters, digits or hyphens, any
   *     case
   * @param to the output format or tool, such as {@code pdf}, {@code jpg} or {@code merge}
   * @param files the files, in order; null or empty for none
   * @param params parameters, or null
   * @param options typed options ({@code fileName}, {@code storeFile}, a timeout), or null; they
   *     win over {@code FileName} or {@code StoreFile} in {@code params}
   * @return the result
   * @throws IllegalArgumentException if a format is invalid, or a parameter value has an
   *     unsupported type
   * @throws MissingApiKeyException if the client has no API key (nothing is sent)
   * @throws UncheckedIOException if an input file can't be read
   * @throws PdfConversionApiException if the API or the network fails; see the subclasses
   */
  public ConversionResult convert(
      String from,
      String to,
      @Nullable List<UploadFile> files,
      @Nullable Map<String, ? extends @Nullable Object> params,
      @Nullable ConvertOptions options) {
    String fromFormat = format("from", from);
    String toFormat = format("to", to);
    List<UploadFile> uploads = new ArrayList<>(files == null ? 0 : files.size());
    if (files != null) {
      for (UploadFile file : files) {
        if (file == null) {
          throw new IllegalArgumentException("files must not contain null");
        }
        uploads.add(file);
      }
    }
    Map<String, ? extends @Nullable Object> parameters = params == null ? Collections.emptyMap() : params;
    for (String name : parameters.keySet()) {
      if (name == null) {
        throw new IllegalArgumentException("a parameter name is null");
      }
    }
    ConvertOptions opts = options == null ? ConvertOptions.defaults() : options;
    Multipart body = Multipart.forConversion(uploads, parameters, opts);
    body.contentLength(); // fails now, before anything is sent, if an input file is missing
    String key = requireKey();
    URI uri = URI.create(baseUrl + "/v2/convert/" + fromFormat + "/to/" + toFormat);
    Duration callTimeout = opts.getTimeout() != null ? opts.getTimeout() : timeout;
    HttpResponse<Body> response =
        execute(
            localError ->
                request(uri, callTimeout)
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .header("Content-Type", body.contentType())
                    .POST(body.publisher(localError))
                    .build(),
            BYTES,
            callTimeout);
    byte[] bytes = response.body().bytes();
    String requestId = ApiErrors.requestId(response.headers());
    if (response.statusCode() != 200) {
      throw ApiErrors.fromResponse(response.statusCode(), response.headers(), bytes);
    }
    return Responses.conversion(bytes, requestId, this);
  }

  // ---------------------------------------------------------------------------------------------
  // Account, conversions list, stored files

  /**
   * Your plan, its limits and this month's count, from {@code GET /v2/usage}. Doesn't use a
   * conversion.
   *
   * @return the usage
   * @throws MissingApiKeyException if the client has no API key
   * @throws PdfConversionApiException if the API or the network fails
   */
  public Usage usage() {
    String key = requireKey();
    URI uri = URI.create(baseUrl + "/v2/usage");
    HttpResponse<Body> response =
        execute(
            localError ->
                request(uri, timeout)
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
            BYTES,
            timeout);
    byte[] bytes = response.body().bytes();
    if (response.statusCode() != 200) {
      throw ApiErrors.fromResponse(response.statusCode(), response.headers(), bytes);
    }
    return Responses.usage(bytes, ApiErrors.requestId(response.headers()));
  }

  /**
   * Every conversion the API offers, from {@code GET /v2/info}. Needs no API key.
   *
   * @return an unmodifiable list
   * @throws PdfConversionApiException if the API or the network fails
   */
  public List<Conversion> conversions() {
    URI uri = URI.create(baseUrl + "/v2/info");
    HttpResponse<Body> response =
        execute(
            localError -> request(uri, timeout).header("Accept", "application/json").GET().build(),
            BYTES,
            timeout);
    byte[] bytes = response.body().bytes();
    if (response.statusCode() != 200) {
      throw ApiErrors.fromResponse(response.statusCode(), response.headers(), bytes);
    }
    return Responses.conversions(bytes, ApiErrors.requestId(response.headers()));
  }

  /**
   * Downloads a stored file into memory. The API key is not sent: the link is the credential.
   *
   * @param url the {@link OutputFile#getUrl()} of a stored file, as returned
   * @return the file's bytes
   * @throws IllegalArgumentException if the URL isn't an absolute http or https URL
   * @throws NotFoundException once the link expired or the file was deleted
   * @throws PdfConversionApiException if the network fails
   */
  public byte[] download(String url) {
    URI uri = downloadUri(url);
    HttpResponse<Body> response =
        execute(localError -> request(uri, timeout).GET().build(), BYTES, timeout);
    byte[] bytes = response.body().bytes();
    if (response.statusCode() != 200) {
      throw ApiErrors.fromResponse(response.statusCode(), response.headers(), bytes);
    }
    return bytes;
  }

  /**
   * Downloads a stored file straight to disk, replacing what is there. The file appears only
   * once it is complete. The API key is not sent. Missing parent folders are created.
   *
   * @param url the {@link OutputFile#getUrl()} of a stored file, as returned
   * @param path the file to write (not a folder)
   * @return the path written
   * @throws IllegalArgumentException if the URL is invalid or the path is a folder
   * @throws UncheckedIOException if writing fails
   * @throws NotFoundException once the link expired or the file was deleted
   */
  public Path downloadTo(String url, Path path) {
    URI uri = downloadUri(url);
    Objects.requireNonNull(path, "path");
    if (Files.isDirectory(path)) {
      throw new IllegalArgumentException(path + " is a folder; pass the path of the file to write");
    }
    Path temp;
    try {
      FileNames.createParents(path);
      Path dir = path.toAbsolutePath().getParent();
      // Not Files.createTempFile: its owner-only permissions would stay on the finished file.
      // createFile uses the usual permissions (the umask), like a file written in place.
      temp = Files.createFile(dir.resolve(".pdfconversionapi-" + Long.toHexString(ThreadLocalRandom.current().nextLong()) + ".part"));
    } catch (IOException e) {
      throw new UncheckedIOException("can't write next to " + path, e);
    }
    boolean done = false;
    try {
      BodyHandler<Body> toFile =
          info ->
              info.statusCode() == 200
                  ? BodySubscribers.mapping(
                      BodySubscribers.ofFile(
                          temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING),
                      file -> new Body(null, file))
                  : BYTES.apply(info);
      HttpResponse<Body> response =
          execute(localError -> request(uri, timeout).GET().build(), toFile, timeout);
      if (response.statusCode() != 200) {
        throw ApiErrors.fromResponse(response.statusCode(), response.headers(), response.body().bytes());
      }
      try {
        Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
      }
      done = true;
      return path;
    } catch (IOException e) {
      throw new UncheckedIOException("writing " + path + " failed", e);
    } finally {
      if (!done) {
        try {
          Files.deleteIfExists(temp);
        } catch (IOException ignored) {
          // Best effort: a leftover .part file is harmless.
        }
      }
    }
  }

  /**
   * Deletes a stored file before its link expires.
   *
   * @param fileId the {@link OutputFile#getFileId()}: 32 hex characters
   * @return true if it was deleted, false if there was nothing to delete (unknown id, already
   *     deleted or expired, or another account's file)
   * @throws IllegalArgumentException if the id isn't 32 hex characters
   * @throws MissingApiKeyException if the client has no API key
   * @throws PdfConversionApiException if the API or the network fails
   */
  public boolean deleteStoredFile(String fileId) {
    Objects.requireNonNull(fileId, "fileId");
    if (!FILE_ID.matcher(fileId).matches()) {
      throw new IllegalArgumentException("a stored file id is 32 hex characters, got \"" + fileId + "\"");
    }
    String key = requireKey();
    URI uri = URI.create(baseUrl + "/v2/d/" + fileId);
    HttpResponse<Body> response =
        execute(
            localError ->
                request(uri, timeout)
                    .header("Authorization", "Bearer " + key)
                    .header("Accept", "application/json")
                    .DELETE()
                    .build(),
            BYTES,
            timeout);
    int status = response.statusCode();
    if (status == 204) {
      return true;
    }
    if (status == 404) {
      return false;
    }
    throw ApiErrors.fromResponse(status, response.headers(), response.body().bytes());
  }

  @Override
  public String toString() {
    return "PdfConversionApi{baseUrl=" + baseUrl + ", apiKey=" + (apiKey == null ? "none" : "set")
        + ", timeout=" + timeout + ", maxRetries=" + maxRetries + ", maxRetryWait=" + maxRetryWait
        + "}";
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP and retries

  private static String format(String which, String format) {
    Objects.requireNonNull(format, which);
    if (!FORMAT.matcher(format).matches()) {
      throw new IllegalArgumentException(
          "the \"" + which + "\" format must be 1 to 20 letters, digits or hyphens, got \"" + format + "\"");
    }
    return format.toLowerCase(Locale.ROOT);
  }

  private static URI downloadUri(String url) {
    Objects.requireNonNull(url, "url");
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("not a valid URL: " + url, e);
    }
    String scheme = uri.getScheme();
    if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
        || uri.getHost() == null) {
      throw new IllegalArgumentException("a download link is an absolute http or https URL, got " + url);
    }
    return uri;
  }

  private String requireKey() {
    String key = apiKey;
    if (key == null) {
      throw new MissingApiKeyException();
    }
    return key;
  }

  private HttpRequest.Builder request(URI uri, Duration requestTimeout) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("User-Agent", userAgent);
    if ("http".equalsIgnoreCase(uri.getScheme())) {
      builder.version(HttpClient.Version.HTTP_1_1); // no h2c upgrade attempts on plain HTTP
    }
    return builder;
  }

  /** A response body: the bytes, or for a download to disk, the file they were written to. */
  static final class Body {
    private final byte @Nullable [] bytes;
    private final @Nullable Path file;

    Body(byte @Nullable [] bytes, @Nullable Path file) {
      this.bytes = bytes;
      this.file = file;
    }

    byte[] bytes() {
      return bytes == null ? new byte[0] : bytes;
    }

    @Nullable Path file() {
      return file;
    }
  }

  private static final BodyHandler<Body> BYTES =
      info -> BodySubscribers.mapping(BodySubscribers.ofByteArray(), bytes -> new Body(bytes, null));

  /** Builds the request for one attempt; a local file error while sending lands in localError. */
  @FunctionalInterface
  private interface RequestFactory {
    HttpRequest create(AtomicReference<@Nullable IOException> localError);
  }

  /** The retry loop of SPEC section 8, shared by every call. */
  private HttpResponse<Body> execute(RequestFactory factory, BodyHandler<Body> handler, Duration callTimeout) {
    int maxAttempts = maxRetries + 1;
    boolean retried500 = false;
    for (int attempt = 1; ; attempt++) {
      boolean attemptsLeft = attempt < maxAttempts;
      AtomicReference<@Nullable IOException> localError = new AtomicReference<>();
      HttpRequest request = factory.create(localError);
      AtomicBoolean responseStarted = new AtomicBoolean();
      BodyHandler<Body> tracked =
          info -> {
            responseStarted.set(true);
            return handler.apply(info);
          };
      HttpResponse<Body> response;
      try {
        response = sendOnce(request, tracked, callTimeout);
      } catch (IOException e) {
        IOException local = localError.get();
        if (local != null) {
          throw new UncheckedIOException("reading an input file failed while sending it", local);
        }
        boolean timedOut = e instanceof HttpTimeoutException && !(e instanceof HttpConnectTimeoutException);
        if (timedOut) {
          throw new ApiTimeoutException(
              "no complete response within " + describe(callTimeout) + " (" + request.method() + " "
                  + request.uri().getPath() + "). Not retried: a conversion may still be running on"
                  + " the server, and it counts toward this month's conversions if it succeeds."
                  + " Raise the limit with timeout(...) on the builder or in ConvertOptions.",
              e);
        }
        if (responseStarted.get()) {
          throw new ApiConnectionException(
              "the connection dropped while the response was arriving (" + request.method() + " "
                  + request.uri().getPath() + "): " + e + ". Not retried: the request may already"
                  + " have succeeded and counted.",
              e);
        }
        if (attemptsLeft) {
          sleep(jitter(attempt));
          continue;
        }
        throw new ApiConnectionException(
            "no response from " + request.uri().getHost() + " after " + attempt
                + (attempt == 1 ? " attempt: " : " attempts: ") + e,
            e);
      }

      int status = response.statusCode();
      if (status == 429 || status == 503) {
        long retryAfter = ApiErrors.retryAfterOrDefault(response.headers());
        if (attemptsLeft && Duration.ofSeconds(retryAfter).compareTo(maxRetryWait) <= 0) {
          discard(response);
          sleep(Duration.ofSeconds(retryAfter).plus(jitter(attempt)));
          continue;
        }
        return response;
      }
      if (status == 500 && !retried500 && attemptsLeft) {
        retried500 = true;
        discard(response);
        sleep(Duration.ofSeconds(1).plus(jitter(attempt)));
        continue;
      }
      return response;
    }
  }

  private static void discard(HttpResponse<Body> response) {
    Path file = response.body().file();
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException ignored) {
        // Best effort.
      }
    }
  }

  private HttpResponse<Body> sendOnce(HttpRequest request, BodyHandler<Body> handler, Duration callTimeout)
      throws IOException {
    CompletableFuture<HttpResponse<Body>> future = http.sendAsync(request, handler);
    try {
      return future.get(saturatedNanos(callTimeout), TimeUnit.NANOSECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      HttpTimeoutException timeout = new HttpTimeoutException("request timed out");
      timeout.initCause(e);
      throw timeout;
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException) {
        throw (IOException) cause;
      }
      if (cause instanceof RuntimeException) {
        throw (RuntimeException) cause;
      }
      if (cause instanceof Error) {
        throw (Error) cause;
      }
      throw new PdfConversionApiException("the request failed: " + cause, cause);
    } catch (InterruptedException e) {
      future.cancel(true);
      Thread.currentThread().interrupt();
      throw new PdfConversionApiException("interrupted while waiting for the response", e);
    }
  }

  private static long saturatedNanos(Duration duration) {
    try {
      return duration.toNanos();
    } catch (ArithmeticException e) {
      return Long.MAX_VALUE;
    }
  }

  private void sleep(Duration duration) {
    try {
      sleeper.sleep(duration);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PdfConversionApiException("interrupted while waiting to retry", e);
    }
  }

  /** A uniformly random wait in [0, 2^attempt) seconds. */
  static Duration jitter(int attempt) {
    double bound = Math.pow(2, Math.min(attempt, 30));
    double seconds = ThreadLocalRandom.current().nextDouble() * bound;
    return Duration.ofNanos((long) (seconds * 1_000_000_000L));
  }

  private static String describe(Duration duration) {
    long seconds = duration.getSeconds();
    if (duration.getNano() == 0) {
      return seconds == 1 ? "1 second" : seconds + " seconds";
    }
    return duration.toMillis() + " ms";
  }

  // ---------------------------------------------------------------------------------------------
  // Builder

  /**
   * Builds a {@link PdfConversionApi}. Every setting has a default; see each method.
   *
   * <pre>{@code
   * PdfConversionApi client = PdfConversionApi.builder()
   *     .apiKey(System.getenv("PDFCONVERSIONAPI_KEY"))
   *     .timeout(Duration.ofMinutes(5))
   *     .maxRetries(2)
   *     .build();
   * }</pre>
   */
  public static final class Builder {
    private @Nullable String apiKey;
    private @Nullable String baseUrl;
    private Duration timeout = DEFAULT_TIMEOUT;
    private Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;
    private int maxRetries = DEFAULT_MAX_RETRIES;
    private Duration maxRetryWait = DEFAULT_MAX_RETRY_WAIT;
    private @Nullable HttpClient httpClient;
    private Sleeper sleeper = Sleeper.SYSTEM;
    private Function<String, @Nullable String> environment = System::getenv;

    private Builder() {}

    /**
     * The API key, sent as {@code Authorization: Bearer <key>}. Without one (or with null), the
     * key comes from the {@code PDFCONVERSIONAPI_KEY} environment variable. A client without a
     * key can still list conversions and download stored files; other calls throw {@link
     * MissingApiKeyException}. Get a key at https://app.pdfconversionapi.com.
     *
     * @param apiKey the key, or null to read the environment
     * @return this builder
     */
    public Builder apiKey(@Nullable String apiKey) {
      this.apiKey = apiKey;
      return this;
    }

    /**
     * Where to send requests. Without one, {@code PDFCONVERSIONAPI_BASE_URL} from the
     * environment, else {@value PdfConversionApi#DEFAULT_BASE_URL}. A trailing slash is ignored.
     *
     * @param baseUrl an absolute http or https URL, or null for the default
     * @return this builder
     */
    public Builder baseUrl(@Nullable String baseUrl) {
      this.baseUrl = baseUrl;
      return this;
    }

    /**
     * How long one attempt may take, for the whole response. Default 240 seconds, which covers
     * the API's own limits. A timeout is never retried: the conversion may still be running on
     * the server.
     *
     * @param timeout a positive duration
     * @return this builder
     */
    public Builder timeout(Duration timeout) {
      this.timeout = positive("timeout", timeout);
      return this;
    }

    /**
     * How long opening a connection may take. Default 10 seconds. A connect timeout is retried
     * like a refused connection. Ignored when you pass your own {@link HttpClient}.
     *
     * @param connectTimeout a positive duration
     * @return this builder
     */
    public Builder connectTimeout(Duration connectTimeout) {
      this.connectTimeout = positive("connectTimeout", connectTimeout);
      return this;
    }

    /**
     * How many times to retry a call after the first attempt. Default 4. 0 turns retries off.
     *
     * @param maxRetries 0 or more
     * @return this builder
     */
    public Builder maxRetries(int maxRetries) {
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries must be 0 or more, got " + maxRetries);
      }
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * The longest {@code Retry-After} worth waiting for. Default 60 seconds. A 429 or 503 that
     * asks for a longer wait (a used-up month, paused conversions) is thrown at once.
     *
     * @param maxRetryWait zero or a positive duration
     * @return this builder
     */
    public Builder maxRetryWait(Duration maxRetryWait) {
      Objects.requireNonNull(maxRetryWait, "maxRetryWait");
      if (maxRetryWait.isNegative()) {
        throw new IllegalArgumentException("maxRetryWait must not be negative, got " + maxRetryWait);
      }
      this.maxRetryWait = maxRetryWait;
      return this;
    }

    /**
     * Your own {@link HttpClient}, for a proxy, an executor or TLS settings of your choice. It
     * must not follow redirects ({@link HttpClient.Redirect#NEVER}, the JDK's default), so the
     * key is never sent to another host.
     *
     * @param httpClient the client
     * @return this builder
     */
    public Builder httpClient(HttpClient httpClient) {
      Objects.requireNonNull(httpClient, "httpClient");
      if (httpClient.followRedirects() != HttpClient.Redirect.NEVER) {
        throw new IllegalArgumentException(
            "the HttpClient must use HttpClient.Redirect.NEVER, so the API key never follows a"
                + " redirect to another host");
      }
      this.httpClient = httpClient;
      return this;
    }

    /**
     * Replaces the wait between retries, mainly for tests: record the waits and return at once.
     *
     * @param sleeper the sleeper; {@link Sleeper#SYSTEM} really sleeps
     * @return this builder
     */
    public Builder sleeper(Sleeper sleeper) {
      this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
      return this;
    }

    /** Replaces the environment variables the builder reads, for tests. */
    Builder environment(Function<String, @Nullable String> environment) {
      this.environment = Objects.requireNonNull(environment, "environment");
      return this;
    }

    /**
     * Builds the client.
     *
     * @return the client
     * @throws IllegalArgumentException if the base URL or the key is malformed
     */
    public PdfConversionApi build() {
      String key = apiKey != null ? apiKey : environment.apply(API_KEY_ENV);
      key = key == null ? null : key.trim();
      if (key != null && key.isEmpty()) {
        key = null;
      }
      if (key != null) {
        for (int i = 0; i < key.length(); i++) {
          char c = key.charAt(i);
          if (c <= ' ' || c == 0x7F || c > 0x7E) {
            throw new IllegalArgumentException(
                "the API key contains a space, a control character or a non-ASCII character;"
                    + " check how it was copied");
          }
        }
      }
      String url = baseUrl;
      if (url == null) {
        String fromEnv = environment.apply(BASE_URL_ENV);
        url = fromEnv == null || fromEnv.trim().isEmpty() ? DEFAULT_BASE_URL : fromEnv.trim();
      }
      String base = normalizeBaseUrl(url);
      HttpClient client = httpClient;
      if (client == null) {
        client =
            HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
      }
      return new PdfConversionApi(this, key, base, client);
    }

    private static Duration positive(String name, Duration value) {
      Objects.requireNonNull(value, name);
      if (value.isZero() || value.isNegative()) {
        throw new IllegalArgumentException(name + " must be positive, got " + value);
      }
      return value;
    }

    static String normalizeBaseUrl(String url) {
      String trimmed = url.trim();
      while (trimmed.endsWith("/")) {
        trimmed = trimmed.substring(0, trimmed.length() - 1);
      }
      URI uri;
      try {
        uri = new URI(trimmed);
      } catch (URISyntaxException e) {
        throw new IllegalArgumentException("the base URL isn't a valid URL", e);
      }
      String scheme = uri.getScheme();
      if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))
          || uri.getHost() == null) {
        throw new IllegalArgumentException("the base URL must be an absolute http or https URL, like "
            + DEFAULT_BASE_URL);
      }
      if (uri.getRawUserInfo() != null) {
        throw new IllegalArgumentException("the base URL must not contain a user name or password");
      }
      if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
        throw new IllegalArgumentException("the base URL must not have a query string or a fragment");
      }
      return trimmed;
    }

    @Override
    public String toString() {
      return "PdfConversionApi.Builder{baseUrl=" + baseUrl + ", apiKey="
          + (apiKey == null ? "from environment" : "set") + ", timeout=" + timeout + "}";
    }
  }
}
