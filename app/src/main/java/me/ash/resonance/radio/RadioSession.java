package me.ash.resonance.radio;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import me.ash.resonance.yt.YtTrack;

public class RadioSession {

  private final YtTrack seed;

  private final Set<String> seen = new HashSet<>();
  private float bias = 1.0f;

  public RadioSession(YtTrack seed) {
    this.seed = seed;
  }

  public List<String> queries() {
    return Arrays.asList(
            seed.title,
            seed.artist,
            seed.title + " mix",
            seed.artist + " radio",
            seed.title + " playlist"
    );
  }

  public boolean isSeen(String id) {
    return seen.contains(id);
  }

  public void markSeen(String id) {
    seen.add(id);
  }

  public void markSkipped(String id) {
    bias *= 0.97f;
  }

  public void markLiked(String id) {
    bias *= 1.05f;
  }

  public float getBias() {
    return bias;
  }
}