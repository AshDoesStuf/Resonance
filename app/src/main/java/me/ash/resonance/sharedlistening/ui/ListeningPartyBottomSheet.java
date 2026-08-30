package me.ash.resonance.sharedlistening.ui;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.R;
import me.ash.resonance.services.MusicService;
import me.ash.resonance.sharedlistening.session.ConnectedListener;
import me.ash.resonance.sharedlistening.session.SessionManager;

public class ListeningPartyBottomSheet extends BottomSheetDialogFragment {

  private static final int PERMISSION_REQUEST_CODE = 202;

  private TextView tvSessionStatus, tvSessionCode, tvListenersHeader, tvInstructions;
  private View cardSessionInfo;
  private RecyclerView rvListeners;
  private MaterialButton btnToggleSession;
  private SessionManager sessionManager;
  private ListenerAdapter adapter;

  public static ListeningPartyBottomSheet newInstance() {
    return new ListeningPartyBottomSheet();
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.bottom_sheet_listening_party, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    tvSessionStatus = view.findViewById(R.id.tvSessionStatus);
    tvSessionCode = view.findViewById(R.id.tvSessionCode);
    tvListenersHeader = view.findViewById(R.id.tvListenersHeader);
    tvInstructions = view.findViewById(R.id.tvInstructions);
    cardSessionInfo = view.findViewById(R.id.cardSessionInfo);
    rvListeners = view.findViewById(R.id.rvListeners);
    btnToggleSession = view.findViewById(R.id.btnToggleSession);

    if (MusicService.getInstance() != null) {
      sessionManager = MusicService.getInstance().getSessionManager();
    }

    setupRecyclerView();
    updateUI();

    if (sessionManager != null) {
      sessionManager.setSessionListener(new SessionManager.SessionListener() {
        @Override
        public void onListenersChanged(java.util.Map<String, ConnectedListener> listeners) {
          view.post(() -> updateUI());
        }

        @Override
        public void onSessionStateChanged(boolean isActive) {
          view.post(() -> updateUI());
        }
      });
    }

    btnToggleSession.setOnClickListener(v -> {
      if (sessionManager == null) return;

      if (sessionManager.isSessionActive()) {
        sessionManager.endSession();
        updateUI();
      } else {
        startSessionIfPermitted();
      }
    });
  }

  @Override
  public void onDestroyView() {
    super.onDestroyView();
    if (sessionManager != null) {
      sessionManager.setSessionListener(null);
    }
  }

  // ── Permissions ──────────────────────────────────────────────────────────
  // Nearby Connections needs these granted at runtime before advertising will
  // succeed. Requested lazily here, right when the user actually starts a
  // party, rather than at app launch where it has nothing to do with normal
  // playback.

  private String[] requiredNearbyPermissions() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      return new String[]{
              Manifest.permission.BLUETOOTH_SCAN,
              Manifest.permission.BLUETOOTH_ADVERTISE,
              Manifest.permission.BLUETOOTH_CONNECT,
              Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.NEARBY_WIFI_DEVICES
      };
    } else {
      return new String[]{
              Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.ACCESS_COARSE_LOCATION
      };
    }
  }

  private boolean hasNearbyPermissions() {
    for (String permission : requiredNearbyPermissions()) {
      if (ContextCompat.checkSelfPermission(requireContext(), permission)
              != PackageManager.PERMISSION_GRANTED) {
        return false;
      }
    }
    return true;
  }

  private void startSessionIfPermitted() {
    if (hasNearbyPermissions()) {
      String deviceName = android.os.Build.MODEL;
      sessionManager.startSession(deviceName);
      updateUI();
    } else {
      requestPermissions(requiredNearbyPermissions(), PERMISSION_REQUEST_CODE);
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSION_REQUEST_CODE) {
      if (hasNearbyPermissions()) {
        String deviceName = android.os.Build.MODEL;
        sessionManager.startSession(deviceName);
        updateUI();
      } else {
        tvInstructions.setText("Perms required lil bro");
      }
    }
  }

  private void setupRecyclerView() {
    rvListeners.setLayoutManager(new LinearLayoutManager(getContext()));
    adapter = new ListenerAdapter();
    rvListeners.setAdapter(adapter);
  }

  private void updateUI() {
    if (sessionManager == null) return;

    boolean active = sessionManager.isSessionActive();
    tvSessionStatus.setText(active ? R.string.session_active : R.string.session_inactive);
    tvSessionStatus.setTextColor(getResources().getColor(active ? R.color.accent : R.color.text_muted, null));

    cardSessionInfo.setVisibility(active ? View.VISIBLE : View.GONE);
    tvSessionCode.setText(sessionManager.getSessionId());

    btnToggleSession.setText(active ? R.string.stop_listening_party : R.string.start_listening_party);
    btnToggleSession.setIconResource(active ? R.drawable.ic_close : R.drawable.ic_auto_awesome);

    tvInstructions.setVisibility(active ? View.GONE : View.VISIBLE);

    List<ConnectedListener> listeners = new ArrayList<>(sessionManager.getListeners().values());
    adapter.setListeners(listeners);

    boolean hasListeners = !listeners.isEmpty();
    tvListenersHeader.setVisibility(hasListeners ? View.VISIBLE : View.GONE);
    rvListeners.setVisibility(hasListeners ? View.VISIBLE : View.GONE);
  }

  private static class ListenerAdapter extends RecyclerView.Adapter<ListenerAdapter.ViewHolder> {
    private List<ConnectedListener> listeners = new ArrayList<>();

    public void setListeners(List<ConnectedListener> listeners) {
      this.listeners = listeners;
      notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
      View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_listener, parent, false);
      return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
      ConnectedListener listener = listeners.get(position);
      holder.tvName.setText(listener.getDisplayName());
      holder.tvDetail.setText(listener.getEndpointId());
    }

    @Override
    public int getItemCount() {
      return listeners.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
      TextView tvName, tvDetail;

      public ViewHolder(@NonNull View itemView) {
        super(itemView);
        tvName = itemView.findViewById(R.id.tvListenerName);
        tvDetail = itemView.findViewById(R.id.tvListenerDetail);
      }
    }
  }
}