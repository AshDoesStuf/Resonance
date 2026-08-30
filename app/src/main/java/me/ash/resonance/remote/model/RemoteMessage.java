package me.ash.resonance.remote.model;

import com.google.gson.annotations.SerializedName;

public record RemoteMessage(@SerializedName("type") String type,
                            @SerializedName("data") Object data) {
  // Server-to-Client Event Types
  public static final String TYPE_CONNECTED = "CONNECTED";
  public static final String TYPE_STATE_UPDATE = "STATE_UPDATE";
  public static final String TYPE_QUEUE_UPDATE = "QUEUE_UPDATE";
  public static final String TYPE_POSITION_UPDATE = "POSITION_UPDATE";
  public static final String TYPE_PLAYLISTS = "PLAYLISTS";
  public static final String TYPE_PLAYLIST_TRACKS = "PLAYLIST_TRACKS";
  public static final String TYPE_STREAM_SOURCE = "STREAM_SOURCE";
  public static final String TYPE_PRELOAD_SOURCE = "PRELOAD_SOURCE";
  public static final String TYPE_ERROR = "ERROR";
  // Client-to-Server Commands
  public static final String CMD_PLAY = "PLAY";
  public static final String CMD_PAUSE = "PAUSE";
  public static final String CMD_TOGGLE_PLAYBACK = "TOGGLE_PLAYBACK";
  public static final String CMD_NEXT = "NEXT";
  public static final String CMD_PREVIOUS = "PREVIOUS";
  public static final String CMD_SEEK = "SEEK";
  public static final String CMD_SET_VOLUME = "SET_VOLUME";
  public static final String CMD_SET_SHUFFLE = "SET_SHUFFLE";
  public static final String CMD_SET_REPEAT = "SET_REPEAT";
  public static final String CMD_PLAY_TRACK = "PLAY_TRACK";
  public static final String CMD_QUEUE_ADD = "QUEUE_ADD";
  public static final String CMD_QUEUE_REMOVE = "QUEUE_REMOVE";
  public static final String CMD_QUEUE_MOVE = "QUEUE_MOVE";
  public static final String CMD_QUEUE_CLEAR = "QUEUE_CLEAR";

  public static final String CMD_GET_PLAYLISTS = "GET_PLAYLISTS";
  public static final String CMD_GET_PLAYLIST_TRACKS = "GET_PLAYLIST_TRACKS";
  public static final String CMD_QUEUE_PLAYLIST = "QUEUE_PLAYLIST";
  public static final String CMD_PLAY_PLAYLIST_TRACK = "PLAY_PLAYLIST_TRACK";

  public static final String CMD_TRACK_ENDED = "TRACK_ENDED";
  public static final String CMD_SET_REMOTE_STREAM_MODE = "SET_REMOTE_STREAM_MODE";
  public static final String CMD_REMOTE_POSITION = "REMOTE_POSITION";
  public static final String CMD_REMOTE_STREAM_ERROR = "REMOTE_STREAM_ERROR";

  @Override
  public String type() {
    return type;
  }

  @Override
  public Object data() {
    return data;
  }
}