package me.ash.resonance.remote.model;

import java.util.List;

public class PlaybackStateModel {

  public static class PlaybackSlice {
    public boolean isPlaying;
    public int volume;
    public long position;
    public long duration;
    public String repeatMode; // "NONE" | "ALL" | "ONE"
    public boolean shuffleEnabled;
  }

  public static class RemoteStreamSlice {
    public boolean enabled;
    public String streamUrl;
  }

  public static class TrackSlice {
    public String trackId;
    public String title;
    public String artist;
    public String album;
    public String artworkUrl;
  }

  public static class QueueSlice {
    public List<QueueItemModel> items;
    public int currentIndex;
  }

  // Combined snapshot used for the CONNECTED handshake
  public static class ConnectedSnapshot {
    public PlaybackSlice playback;
    public TrackSlice track;
    public QueueSlice queue;
    public RemoteStreamSlice remoteStream;
  }

  public static class StateUpdate {
    public PlaybackSlice playback;
    public TrackSlice track;
    public RemoteStreamSlice remoteStream;
  }
}