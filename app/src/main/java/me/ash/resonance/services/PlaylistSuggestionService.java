package me.ash.resonance.services;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import me.ash.resonance.yt.YtMusicService;
import me.ash.resonance.yt.YtTrack;

public class PlaylistSuggestionService {

  private final YtMusicService yt;

  public PlaylistSuggestionService() {
    yt = YtMusicService.get();
  }

  public void suggest(
          List<YtTrack> playlist,
          int limit,
          YtMusicService.SearchCallback callback
  ) {

    ExecutorService pool =
            Executors.newFixedThreadPool(6);

    ConcurrentHashMap<String, Integer>
            scores =
            new ConcurrentHashMap<>();

    ConcurrentHashMap<String, YtTrack>
            songs =
            new ConcurrentHashMap<>();

    CountDownLatch latch =
            new CountDownLatch(
                    playlist.size()
            );

    Set<String> existing =
            playlist.stream()
                    .map(t -> t.videoId)
                    .collect(
                            Collectors.toSet()
                    );

    for (YtTrack seed :
            playlist) {

      pool.submit(() -> {

        yt.fetchRelatedTracks(
                seed.videoId,

                new YtMusicService.SearchCallback() {

                  @Override
                  public void onResults(
                          List<YtTrack> related
                  ) {

                    for (
                            YtTrack s :
                            related
                    ) {

                      if (
                              existing.contains(
                                      s.videoId
                              )
                      )
                        continue;

                      songs.put(
                              s.videoId,
                              s
                      );

                      scores.merge(
                              s.videoId,
                              score(
                                      seed,
                                      s
                              ),
                              Integer::sum
                      );
                    }

                    latch.countDown();
                  }

                  @Override
                  public void onError(
                          Exception e
                  ) {
                    latch.countDown();
                  }
                }
        );

      });

    }

    pool.submit(() -> {

      try {

        latch.await();

        List<YtTrack> result =
                scores.entrySet()
                        .stream()

                        .sorted(
                                (a, b)
                                        ->
                                        b.getValue()
                                                -
                                                a.getValue()
                        )

                        .limit(limit)

                        .map(
                                e
                                        ->
                                        songs.get(
                                                e.getKey()
                                        )
                        )

                        .toList();

        callback.onResults(
                diversify(
                        result
                )
        );

      } catch (
              Exception e
      ) {

        callback.onError(
                e
        );

      }

    });

  }

  private int score(
          YtTrack seed,
          YtTrack candidate
  ) {

    int score = 1;

    if (
            seed.artist.equalsIgnoreCase(
                    candidate.artist
            )
    )
      score += 4;

    long diff =
            Math.abs(
                    seed.durationSeconds
                            -
                            candidate.durationSeconds
            );

    if (diff < 30)
      score += 1;

    return score;
  }

  private List<YtTrack>
  diversify(
          List<YtTrack> tracks
  ) {

    Map<String, Integer>
            artistCount =
            new HashMap<>();

    List<YtTrack>
            out =
            new ArrayList<>();

    for (
            YtTrack t :
            tracks
    ) {

      int count =
              artistCount.getOrDefault(
                      t.artist,
                      0
              );

      if (count >= 2)
        continue;

      artistCount.put(
              t.artist,
              count + 1
      );

      out.add(
              t
      );
    }

    return out;
  }

}