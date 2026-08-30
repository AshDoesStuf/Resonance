package me.ash.resonance.remote;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.media3.common.MediaItem;

import java.io.BufferedOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import me.ash.resonance.services.MusicService;
import me.ash.resonance.yt.StreamData;
import me.ash.resonance.yt.YtMusicService;
import okhttp3.Request;
import okhttp3.Response;

@androidx.media3.common.util.UnstableApi
public class LocalMediaServer {
    private static final String TAG = "LocalMediaServer";
    private final Context context;
    private final int port;
    private final ExecutorService executor;
    private ServerSocket serverSocket;
    private boolean running;

    public LocalMediaServer(Context context, int port) {
        this.context = context.getApplicationContext();
        this.port = port;
        this.executor = Executors.newFixedThreadPool(4);
    }

    public void start() {
        if (running) return;
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                Log.i(TAG, "Local media server started on port " + port);
                while (running) {
                    Socket socket = serverSocket.accept();
                    executor.execute(() -> handleRequest(socket));
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "Error in local media server", e);
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
            Log.e(TAG, "Error stopping local media server", e);
        }
    }

    private void handleRequest(Socket socket) {
        try {
            socket.setSoTimeout(10000);
        } catch (Exception ignored) {
        }
        try (InputStream in = socket.getInputStream();
             OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(in)
            );
            String line = reader.readLine();
            if (line == null) return;

            String rangeHeader = null;
            String headerLine;
            while ((headerLine = reader.readLine()) != null && !headerLine.isEmpty()) {
                if (headerLine.toLowerCase().startsWith("range:")) {
                    rangeHeader = headerLine.substring(6).trim();
                }
            }

            if (line.startsWith("GET /artwork/")) {
                handleArtwork(line, out);
            } else if (line.startsWith("GET /stream/")) {
                handleStream(line, rangeHeader, out);
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

    private void handleArtwork(String requestLine, OutputStream out) throws Exception {
        String path = requestLine.split(" ")[1];
        String albumId = path.substring("/artwork/".length());
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
    }

    private void handleStream(String requestLine, String rangeHeader, OutputStream out) throws Exception {
        String path = requestLine.split(" ")[1];
        String id = path.substring("/stream/".length());
        if (id.contains("?")) {
            id = id.substring(0, id.indexOf("?"));
        }
        final String trackId = id;

        MusicService musicService = MusicService.getInstance();
        if (musicService == null) {
            out.write("HTTP/1.1 503 Service Unavailable\r\n\r\n".getBytes());
            return;
        }

        // Use URI scheme detection as requested
        // Search for the track in the current player queue to get its URI
        final MediaItem[] targetItemArr = new MediaItem[1];
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                androidx.media3.common.Player player = musicService.getPlayer();
                if (player != null) {
                    for (int i = 0; i < player.getMediaItemCount(); i++) {
                        MediaItem item = player.getMediaItemAt(i);
                        if (Objects.equals(trackId, item.mediaId)) {
                            targetItemArr[0] = item;
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error accessing player on main thread", e);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                Log.w(TAG, "Timeout waiting for player lookup");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        MediaItem targetItem = targetItemArr[0];

        if (targetItem == null || targetItem.localConfiguration == null) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            return;
        }

        Uri uri = targetItem.localConfiguration.uri;
        if ("ytmusic".equals(uri.getScheme())) {
            handleYoutubeStream(trackId, rangeHeader, out);
        } else {
            handleLocalStream(uri, rangeHeader, out);
        }
    }

    private void handleLocalStream(Uri uri, String rangeHeader, OutputStream out) throws Exception {
        try (android.os.ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(uri, "r");
             InputStream is = new android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            
            if (pfd == null) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }

            long totalLength = pfd.getStatSize();
            long start = 0;
            long end = totalLength - 1;

            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                String[] parts = rangeHeader.substring(6).split("-");
                try {
                    start = Long.parseLong(parts[0]);
                    if (parts.length > 1 && !parts[1].isEmpty()) {
                        end = Long.parseLong(parts[1]);
                    }
                } catch (NumberFormatException ignored) {}
            }

            if (start >= totalLength) {
                out.write("HTTP/1.1 416 Range Not Satisfiable\r\n\r\n".getBytes());
                return;
            }

            long contentLength = end - start + 1;
            out.write("HTTP/1.1 206 Partial Content\r\n".getBytes());
            out.write(("Content-Type: audio/mpeg\r\n").getBytes());
            out.write(("Content-Length: " + contentLength + "\r\n").getBytes());
            out.write(("Content-Range: bytes " + start + "-" + end + "/" + totalLength + "\r\n").getBytes());
            out.write("Access-Control-Allow-Origin: *\r\n".getBytes());
            out.write("Accept-Ranges: bytes\r\n".getBytes());
            out.write("\r\n".getBytes());

            is.skip(start);
            byte[] buffer = new byte[16384];
            long remaining = contentLength;
            while (remaining > 0) {
                int toRead = (int) Math.min(buffer.length, remaining);
                int read = is.read(buffer, 0, toRead);
                if (read == -1) break;
                out.write(buffer, 0, read);
                remaining -= read;
            }
        } catch (Exception e) {
            Log.e(TAG, "Local stream error", e);
            out.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".getBytes());
        }
    }

    private void handleYoutubeStream(String videoId, String rangeHeader, OutputStream out) throws Exception {
        long startTime = System.currentTimeMillis();
        MusicService musicService = MusicService.getInstance();
        if (musicService == null) return;

        StreamData streamData = musicService.getSongUrlCache(videoId);
        if (streamData == null) {
            // Try to resolve it if missing
            try {
                streamData = YtMusicService.get().resolveStreamUrlBlocking(videoId);
            } catch (Exception e) {
                out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
                return;
            }
        }

        if (streamData == null) {
            out.write("HTTP/1.1 404 Not Found\r\n\r\n".getBytes());
            return;
        }

        Request.Builder rb = new Request.Builder()
                .url(streamData.url())
                .header("User-Agent", streamData.userAgent());

        if (rangeHeader != null) {
            rb.header("Range", "bytes=" + rangeHeader);
        }

        try (Response response = YtMusicService.get().http.newCall(rb.build()).execute()) {
            long ttfb = System.currentTimeMillis() - startTime;
            Log.d(TAG, "YT Proxy TTFB for " + videoId + ": " + ttfb + "ms");

            out.write(("HTTP/1.1 " + response.code() + " " + response.message() + "\r\n").getBytes());
            for (String name : response.headers().names()) {
                if (name.equalsIgnoreCase("Content-Type") || 
                    name.equalsIgnoreCase("Content-Length") || 
                    name.equalsIgnoreCase("Content-Range") ||
                    name.equalsIgnoreCase("Accept-Ranges")) {
                    out.write((name + ": " + response.header(name) + "\r\n").getBytes());
                }
            }
            out.write("Access-Control-Allow-Origin: *\r\n".getBytes());
            out.write("\r\n".getBytes());

            InputStream is = response.body().byteStream();
            byte[] buffer = new byte[16384];
            int read;
            while ((read = is.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            Log.e(TAG, "YT proxy error", e);
            out.write("HTTP/1.1 500 Internal Server Error\r\n\r\n".getBytes());
        }
    }
}
