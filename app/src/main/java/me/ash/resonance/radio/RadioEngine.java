package me.ash.resonance.radio;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;

import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

public class RadioEngine {

  private final RadioSession session;

  public RadioEngine(RadioSession session) {
    this.session = session;
  }

  @OptIn(markerClass = UnstableApi.class)
  public void fetchNext(Consumer<List<YtTrack>> callback) {

    List<String> queries = session.queries();
    List<YtTrack> result = new ArrayList<>();

    AtomicInteger remaining = new AtomicInteger(queries.size());

    for (String q : queries) {

      YtMusicService.get().search(q, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, new YtMusicService.SearchCallback() {

        @Override
        public void onResults(List<YtTrack> tracks) {

          synchronized (result) {
            for (YtTrack t : tracks) {

              if (!session.isSeen(t.videoId)) {
                float score = score(t);

                if (score > 0.3f) {
                  result.add(t);
                }
              }
            }
          }

          if (remaining.decrementAndGet() == 0) {
            result.sort((a, b) -> Float.compare(score(b), score(a)));
            callback.accept(result);
          }
        }

        @Override
        public void onError(Exception e) {
          if (remaining.decrementAndGet() == 0) {
            callback.accept(result);
          }
        }
      });
    }
  }

  private float score(YtTrack t) {
    float score = 0.5f;

    if (t.title.contains(session.queries().get(0))) {
      score += 0.3f;
    }

//    if (t.duration > 120 && t.duration < 600) {
//      score += 0.2f;
//    }

    return score * session.getBias();
  }
}