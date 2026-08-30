package me.ash.resonance.songs;

public final class SongContext {
  public enum Type { LIBRARY, PLAYLIST, QUEUE, ALBUM, SEARCH_RESULT, DOWNLOADED }

  public final Type type;
  public final String playlistId; // only meaningful for PLAYLIST
  public final int queueIndex;    // only meaningful for QUEUE

  private SongContext(Type type, String playlistId, int queueIndex) {
    this.type = type;
    this.playlistId = playlistId;
    this.queueIndex = queueIndex;
  }

  public static SongContext library()          { return new SongContext(Type.LIBRARY, null, -1); }
  public static SongContext playlist(String id) { return new SongContext(Type.PLAYLIST, id, -1); }
  public static SongContext queue(int index)    { return new SongContext(Type.QUEUE, null, index); }
  public static SongContext album()             { return new SongContext(Type.ALBUM, null, -1); }
  public static SongContext searchResult()      { return new SongContext(Type.SEARCH_RESULT, null, -1); }
  public static SongContext downloaded()        { return new SongContext(Type.DOWNLOADED, null, -1); }
}
