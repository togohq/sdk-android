package ai.togohq.example.minified;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import org.junit.runners.model.InitializationError;
import org.robolectric.RobolectricTestRunner;

/**
 * Starts a local stand-in for the edge before Robolectric creates the example
 * app, whose {@code Application.onCreate()} sets the SDK up: the endpoint must be
 * known by then, and a test's own {@code @Before} runs after it.
 *
 * <p>The app reads the endpoint from the {@code togo.example.endpoint} system
 * property. Every request body the stand-in receives is put on a queue kept in
 * the system properties under {@code togo.example.received}: a JDK type, so the
 * test, which Robolectric loads in its own class loader, reads the same object.
 */
public final class LocalEdgeRunner extends RobolectricTestRunner {
  static final String RECEIVED = "togo.example.received";

  static {
    try {
      start();
    } catch (IOException e) {
      throw new IllegalStateException("the local edge did not start", e);
    }
  }

  public LocalEdgeRunner(Class<?> testClass) throws InitializationError {
    super(testClass);
  }

  private static void start() throws IOException {
    BlockingQueue<String> received = new LinkedBlockingQueue<>();
    ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
    Thread accept = new Thread(() -> {
      while (true) {
        try (Socket socket = server.accept()) {
          received.add(answer(socket));
        } catch (IOException e) {
          received.add("! " + e);
        }
      }
    }, "local-edge");
    accept.setDaemon(true);
    accept.start();
    System.getProperties().put(RECEIVED, received);
    System.setProperty("togo.example.endpoint", "http://127.0.0.1:" + server.getLocalPort());
  }

  /** Reads one HTTP request, answers 202 as the edge does, and returns "<path>\n<body>". */
  private static String answer(Socket socket) throws IOException {
    InputStream in = socket.getInputStream();
    String requestLine = line(in);
    int length = 0;
    boolean chunked = false;
    for (String header = line(in); !header.isEmpty(); header = line(in)) {
      String lower = header.toLowerCase(Locale.ROOT);
      if (lower.startsWith("content-length:")) length = Integer.parseInt(header.substring(15).trim());
      if (lower.startsWith("transfer-encoding:") && lower.contains("chunked")) chunked = true;
    }
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    if (chunked) {
      for (int size = Integer.parseInt(line(in).trim(), 16); size > 0; size = Integer.parseInt(line(in).trim(), 16)) {
        body.write(in.readNBytes(size));
        line(in);
      }
      line(in);
    } else {
      body.write(in.readNBytes(length));
    }
    byte[] answer = "{\"accepted\":1,\"rejected\":[]}".getBytes(StandardCharsets.UTF_8);
    OutputStream out = socket.getOutputStream();
    out.write(("HTTP/1.1 202 Accepted\r\nContent-Type: application/json\r\nContent-Length: " + answer.length
        + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.write(answer);
    out.flush();
    String path = requestLine.split(" ")[1];
    return path + "\n" + body.toString(StandardCharsets.UTF_8);
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    for (int b = in.read(); b != -1 && b != '\n'; b = in.read()) {
      if (b != '\r') line.write(b);
    }
    return line.toString(StandardCharsets.US_ASCII);
  }
}
