package co.edu.corhuila.telemedia.workflow.adapter.out.participants;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A throwaway HTTP server: answers the queued responses in order (the last one repeats) and records every request. */
final class StubServer implements AutoCloseable {

    record Answer(int status, String body) {
    }

    record Seen(String method, String path, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().get(0))
                    .findFirst().orElse(null);
        }
    }

    private final HttpServer server;
    private final Deque<Answer> answers = new ArrayDeque<>();
    private final List<Seen> seen = new CopyOnWriteArrayList<>();

    private StubServer(HttpServer server) {
        this.server = server;
    }

    static StubServer start() throws IOException {
        StubServer stub = new StubServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        stub.server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            stub.seen.add(new Seen(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    Map.copyOf(exchange.getRequestHeaders()), body));
            Answer answer = stub.next();
            byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) {
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        stub.server.start();
        return stub;
    }

    StubServer answer(int status, String body) {
        synchronized (answers) {
            answers.add(new Answer(status, body));
        }
        return this;
    }

    private Answer next() {
        synchronized (answers) {
            return answers.size() > 1 ? answers.poll() : answers.peek();
        }
    }

    URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    List<Seen> seen() {
        return List.copyOf(seen);
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
