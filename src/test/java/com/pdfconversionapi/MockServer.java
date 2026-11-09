package com.pdfconversionapi;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs conformance/mock-server.mjs on a random port for the length of a test class. */
final class MockServer implements AutoCloseable {
  private final Process process;
  private final String url;
  private final HttpClient http = HttpClient.newHttpClient();

  private MockServer(Process process, String url) {
    this.process = process;
    this.url = url;
  }

  /**
   * The conformance folder: -Dconformance.dir (passed on as a system property), else
   * PDFCONVERSIONAPI_CONFORMANCE_DIR, else ../conformance, else ./conformance.
   */
  static Path conformanceDir() {
    List<Path> candidates = new ArrayList<>();
    String property = System.getProperty("pdfconversionapi.conformanceDir");
    String env = System.getenv("PDFCONVERSIONAPI_CONFORMANCE_DIR");
    if (property != null && !property.trim().isEmpty()) {
      candidates.add(Paths.get(property.trim()));
    } else if (env != null && !env.trim().isEmpty()) {
      candidates.add(Paths.get(env.trim()));
    } else {
      candidates.add(Paths.get("..", "conformance"));
      candidates.add(Paths.get("conformance"));
    }
    for (Path candidate : candidates) {
      Path dir = candidate.toAbsolutePath().normalize();
      if (Files.isRegularFile(dir.resolve("cases.json"))) {
        return dir;
      }
    }
    throw new IllegalStateException(
        "the conformance suite isn't at " + candidates + "; set PDFCONVERSIONAPI_CONFORMANCE_DIR or"
            + " -Dconformance.dir=<path to conformance/>");
  }

  static MockServer start() throws IOException {
    Path script = conformanceDir().resolve("mock-server.mjs");
    String node = System.getenv().getOrDefault("NODE", "node");
    Process process =
        new ProcessBuilder(node, script.toString(), "--port", "0")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
    BufferedReader out =
        new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
    String line = out.readLine();
    if (line == null || !line.startsWith("LISTENING ")) {
      process.destroyForcibly();
      throw new IOException("the mock server didn't start: " + line);
    }
    // Keep draining stdout so the server never blocks on a full pipe.
    Thread drain =
        new Thread(
            () -> {
              try {
                while (out.readLine() != null) {
                  // discard
                }
              } catch (IOException ignored) {
                // The process ended.
              }
            },
            "mock-server-stdout");
    drain.setDaemon(true);
    drain.start();
    return new MockServer(process, line.substring("LISTENING ".length()).trim());
  }

  String url() {
    return url;
  }

  /** How many requests carrying this MockId the mock has seen. */
  long attempts(String mockId) throws IOException, InterruptedException {
    HttpResponse<byte[]> response =
        http.send(
            HttpRequest.newBuilder(
                    URI.create(url + "/__mock/attempts/" + URLEncoder.encode(mockId, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(10))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    try {
      Map<?, ?> body = (Map<?, ?>) Json.parse(response.body());
      return ((Json.Num) body.get("attempts")).longValue();
    } catch (Json.JsonException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void close() {
    process.destroy();
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly();
      }
    } catch (InterruptedException e) {
      process.destroyForcibly();
      Thread.currentThread().interrupt();
    }
  }
}
