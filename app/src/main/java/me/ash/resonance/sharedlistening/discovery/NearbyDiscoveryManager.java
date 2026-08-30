package me.ash.resonance.sharedlistening.discovery;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.Strategy;

public class NearbyDiscoveryManager implements DiscoveryManager {
  private static final String TAG = "NearbyDiscoveryManager";
  private static final String SERVICE_ID = "me.ash.resonance.sharedlistening";

  private final Context context;
  private final ConnectionsClient connectionsClient;
  private Listener listener;
  private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
    @Override
    public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo connectionInfo) {
      Log.d(TAG, "Connection initiated from " + endpointId + " (" + connectionInfo.getEndpointName() + ")");
      if (listener != null) {
        listener.onConnectionRequested(endpointId, connectionInfo.getEndpointName(), new ConnectionResponseCallback() {
          @Override
          public void accept() {
            // NearbyTransport handles the PayloadCallback, but we need to accept here
            // In a real implementation, we might want to pass the PayloadCallback from Transport
          }

          @Override
          public void reject() {
            connectionsClient.rejectConnection(endpointId);
          }
        });
      }
    }

    @Override
    public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
      switch (result.getStatus().getStatusCode()) {
        case ConnectionsStatusCodes.STATUS_OK:
          Log.d(TAG, "Connected to " + endpointId);
          if (listener != null) {
            listener.onConnectionEstablished(endpointId);
          }
          break;
        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
          Log.d(TAG, "Connection rejected by " + endpointId);
          break;
        case ConnectionsStatusCodes.STATUS_ERROR:
          Log.e(TAG, "Connection error with " + endpointId);
          break;
        default:
          Log.d(TAG, "Unknown connection result: " + result.getStatus().getStatusCode());
      }
    }

    @Override
    public void onDisconnected(@NonNull String endpointId) {
      Log.d(TAG, "Disconnected from " + endpointId);
      if (listener != null) {
        listener.onConnectionLost(endpointId);
      }
    }
  };

  public NearbyDiscoveryManager(Context context) {
    this.context = context.getApplicationContext();
    this.connectionsClient = Nearby.getConnectionsClient(this.context);
  }

  @Override
  public void startAdvertising(String displayName, String sessionId) {
    AdvertisingOptions advertisingOptions = new AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .build();

    // Include the sessionId in the endpoint name so discoverers can filter by it
    String endpointName = displayName + ":" + sessionId;

    connectionsClient.startAdvertising(
                    endpointName,
                    SERVICE_ID,
                    connectionLifecycleCallback,
                    advertisingOptions
            ).addOnSuccessListener(unused -> Log.d(TAG, "Advertising started successfully with session " + sessionId))
            .addOnFailureListener(e -> Log.e(TAG, "Advertising failed", e));
  }

  @Override
  public void stopAdvertising() {
    connectionsClient.stopAdvertising();
    Log.d(TAG, "Advertising stopped");
  }

  @Override
  public void setListener(Listener listener) {
    this.listener = listener;
  }

  // Helper method for Transport to accept connection with its callback
  public void acceptConnection(String endpointId, com.google.android.gms.nearby.connection.PayloadCallback callback) {
    connectionsClient.acceptConnection(endpointId, callback);
  }
}
