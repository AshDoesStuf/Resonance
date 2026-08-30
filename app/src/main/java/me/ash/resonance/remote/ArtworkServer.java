package me.ash.resonance.remote;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ArtworkServer {
  private static final String TAG = "ArtworkServer";
  private final Context context;
  private final int port;
  private final ExecutorService executor;
  private ServerSocket serverSocket;
  private boolean running;

  public ArtworkServer(Context context, int port) {
    this.context = context.getApplicationContext();
    this.port = port;
    this.executor = Executors.newFixedThreadPool(2);
  }

  public void start() {
    if (running) return;
    running = true;
    new Thread(() -> {
      try {
        serverSocket = new ServerSocket(port);
        Log.i(TAG, "Artwork server started on port " + port);
        while (running) {
          Socket socket = serverSocket.accept();
          executor.execute(() -> handleRequest(socket));
        }
      } catch (Exception e) {
        if (running) {
          Log.e(TAG, "Error in artwork server", e);
        }
      }
    }).start();
  }

  public void stop() {
    running = false;
    try {
      if (serverSocket != null) {
        serverSocket.close();
      }
      executor.shutdown();
    } catch (Exception e) {
      Log.e(TAG, "Error stopping artwork server", e);
    }
  }

  private void handleRequest(Socket socket) {
    try {
      socket.setSoTimeout(5000); // Prevent blocking indefinitely
    } catch (Exception ignored) {
    }
    try (InputStream in = socket.getInputStream();
         OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

      // Very basic HTTP GET parsing
      // With this — reads the full request line, then drains remaining headers:
      java.io.BufferedReader reader = new java.io.BufferedReader(
              new java.io.InputStreamReader(in)
      );
      String line = reader.readLine(); // e.g. "GET /artwork/123 HTTP/1.1"
      if (line == null) return;

      // Drain all remaining headers until blank line
      String headerLine;
      while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
        // consume headers — ignore them, but must read to unblock the client
      }

      if (line.startsWith("GET /artwork/")) {
        String path = line.split(" ")[1];
        String albumId = path.substring("/artwork/".length());

        // Remove any query params if present
        if (albumId.contains("?")) {
          albumId = albumId.substring(0, albumId.indexOf("?"));
        }

        Uri artworkUri = Uri.parse("content://media/external/audio/albumart/" + albumId);

        try (InputStream is = context.getContentResolver().openInputStream(artworkUri)) {
          out.write("HTTP/1.1 200 OK\r\n".getBytes());
          out.write("Content-Type: image/jpeg\r\n".getBytes());
          out.write("Access-Control-Allow-Origin: *\r\n".getBytes());
          out.write("\r\n".getBytes());

          byte[] buffer = new byte[8192];
          int read;
          while ((read = is.read(buffer)) != -1) {
            out.write(buffer, 0, read);
          }
        } catch (Exception e) {
          out.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n".getBytes());
        }
      } else {
        out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes());
      }
      out.flush();
    } catch (Exception e) {
      Log.e(TAG, "Error handling request", e);
    } finally {
      try {
        socket.close();
      } catch (Exception ignored) {
      }
    }
  }
}
