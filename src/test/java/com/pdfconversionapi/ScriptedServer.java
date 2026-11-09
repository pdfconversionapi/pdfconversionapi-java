package com.pdfconversionapi;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An in-process HTTP server that answers with a script of replies and records each request. */
final class ScriptedServer implements AutoCloseable {
  /** One reply. */
  static final class Reply {
    final int status;
    final Map<String, String> headers = new LinkedHashMap<>();
    byte[] body = new byte[0];
    long delayMs;

    Reply(int status) {
      this.status = status;
    }

    Reply header(String name, String value) {
      headers.put(name, value);
      return this;
    }

    Reply body(String text) {
      body = text.getBytes(StandardCharsets.UTF_8);
      return this;
    }

    Reply body(byte[] bytes) {
      body = bytes;
      return this;
    }

    Reply delay(long millis) {
      delayMs = millis;
      return this;
    }
  }

  /** One request as received. */
  static final class Request {
    final String method;
    final String path;
    final String query;
    final Map<String, List<String>> headers;
    final byte[] body;

    Request(String method, String path, String query, Map<String, List<String>> headers, byte[] body) {
      this.method = method;
      this.path = path;
      this.query = query;
      this.headers = headers;
      this.body = body;
    }

    String header(String name) {
      for (Map.Entry<String, List<String>> e : headers.entrySet()) {
        if (e.getKey().equalsIgnoreCase(name)) {
          return e.getValue().get(0);
        }
      }
      return null;
    }
  }

  static Reply reply(int status) {
    return new Reply(status);
  }

  static Reply json(int status, String body) {
    return new Reply(status).header("Content-Type", "application/json").header("x-request-id", "req-" + status).body(body);
  }

  static Reply error(int status, String message) {
    return json(status, "{\"Code\":" + status + ",\"Message\":\"" + message + "\"}");
  }

  static Reply ok(String name, String content) {
    String b64 = java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8));
    int dot = name.lastIndexOf('.');
    return json(
        200,
        "{\"ConversionCost\":1,\"ConversionTime\":0.012,\"Files\":[{\"FileName\":\"" + name + "\",\"FileExt\":\""
            + name.substring(dot + 1) + "\",\"FileSize\":" + content.getBytes(StandardCharsets.UTF_8).length
            + ",\"FileData\":\"" + b64 + "\"}]}");
  }

  private final HttpServer server;
  private final Deque<Reply> script = new ArrayDeque<>();
  private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());

  ScriptedServer() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext("/", this::handle);
    server.setExecutor(
        java.util.concurrent.Executors.newCachedThreadPool(
            runnable -> {
              Thread thread = new Thread(runnable, "scripted-server");
              thread.setDaemon(true);
              return thread;
            }));
    server.start();
  }

  String url() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  synchronized ScriptedServer then(Reply... replies) {
    Collections.addAll(script, replies);
    return this;
  }

  List<Request> requests() {
    return requests;
  }

  private void handle(HttpExchange exchange) throws IOException {
    byte[] body = exchange.getRequestBody().readAllBytes();
    requests.add(
        new Request(
            exchange.getRequestMethod(),
            exchange.getRequestURI().getRawPath(),
            exchange.getRequestURI().getRawQuery(),
            new LinkedHashMap<>(exchange.getRequestHeaders()),
            body));
    Reply reply;
    synchronized (this) {
      reply = script.isEmpty() ? error(599, "the script ran out") : script.poll();
    }
    if (reply.delayMs > 0) {
      try {
        Thread.sleep(reply.delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    reply.headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
    try {
      if (reply.body.length == 0) {
        exchange.sendResponseHeaders(reply.status, -1);
      } else {
        exchange.sendResponseHeaders(reply.status, reply.body.length);
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(reply.body);
        }
      }
    } catch (IOException e) {
      // The client gave up (a timeout test); nothing to do.
    } finally {
      exchange.close();
    }
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
