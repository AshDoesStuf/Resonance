package me.ash.resonance.remote;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;

public class WebSocketServerManager extends WebSocketServer {

  private ServerListener listener;

  public WebSocketServerManager(int port) {
    super(new InetSocketAddress(port));
  }

  public void setListener(ServerListener listener) {
    this.listener = listener;
  }

  @Override
  public void onOpen(WebSocket conn, ClientHandshake handshake) {
    if (listener != null) listener.onOpen(conn, handshake);
  }

  @Override
  public void onClose(WebSocket conn, int code, String reason, boolean remote) {
    if (listener != null) listener.onClose(conn, code, reason, remote);
  }

  @Override
  public void onMessage(WebSocket conn, String message) {
    if (listener != null) listener.onMessage(conn, message);
  }

  @Override
  public void onError(WebSocket conn, Exception ex) {
    if (listener != null) listener.onError(conn, ex);
  }

  @Override
  public void onStart() {
    if (listener != null) listener.onStart();
  }

  public interface ServerListener {
    void onOpen(WebSocket conn, ClientHandshake handshake);

    void onClose(WebSocket conn, int code, String reason, boolean remote);

    void onMessage(WebSocket conn, String message);

    void onError(WebSocket conn, Exception ex);

    void onStart();
  }
}
