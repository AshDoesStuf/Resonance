package me.ash.resonance.remote;

import org.java_websocket.WebSocket;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConnectionManager {
  private final Set<WebSocket> connections = Collections.newSetFromMap(new ConcurrentHashMap<>());

  public void addConnection(WebSocket conn) {
    connections.add(conn);
  }

  public void removeConnection(WebSocket conn) {
    connections.remove(conn);
  }

  public Set<WebSocket> getConnections() {
    return connections;
  }

  public void broadcast(String message) {
    for (WebSocket conn : connections) {
      if (conn.isOpen()) {
        conn.send(message);
      }
    }
  }
}
