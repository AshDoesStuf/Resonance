package me.ash.resonance.ui;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import me.ash.resonance.song.Song;

public class AlphabetSidebarHelper {

  private static final String[] LETTERS = {
          "#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
          "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z"
  };
  private final LinearLayout sidebar;
  private final RecyclerView recyclerView;
  private final List<Song> songs;
  private Map<String, Integer> letterMap;

  public AlphabetSidebarHelper(LinearLayout sidebar, RecyclerView recyclerView, List<Song> songs) {
    this.sidebar = sidebar;
    this.recyclerView = recyclerView;
    this.songs = songs;
  }

  @SuppressLint("ClickableViewAccessibility")
  public void setup() {
    sidebar.removeAllViews();
    letterMap = new HashMap<>();
    for (int i = 0; i < songs.size(); i++) {
      String title = songs.get(i).title;
      if (title == null || title.isEmpty()) continue;
      String letter = Character.isLetter(title.charAt(0))
              ? title.substring(0, 1).toUpperCase() : "#";
      if (!letterMap.containsKey(letter)) letterMap.put(letter, i);
    }

    for (String letter : LETTERS) {
      TextView tv = new TextView(sidebar.getContext());
      tv.setLayoutParams(new LinearLayout.LayoutParams(
              LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
      tv.setText(letter);
      tv.setTextSize(9f);
      tv.setGravity(Gravity.CENTER);
      tv.setTextColor(Color.WHITE);
      tv.setTag(letter);
      sidebar.addView(tv);
    }

    sidebar.setOnTouchListener((v, event) -> {
      float y = event.getY();
      int sidebarHeight = v.getHeight();
      int letterCount = LETTERS.length;
      int letterHeight = sidebarHeight / letterCount;
      int index = (int) (y / letterHeight);

      if (index >= 0 && index < letterCount) {
        String letter = LETTERS[index];
        if (event.getAction() == MotionEvent.ACTION_DOWN ||
                event.getAction() == MotionEvent.ACTION_MOVE) {
          scrollToLetter(letter);
          updateHighlight(index);
        }
      }
      return true;
    });
  }

  public void updateFromScroll() {
    LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
    if (manager == null) return;

    int firstVisiblePos = manager.findFirstVisibleItemPosition();
    if (firstVisiblePos < 0 || firstVisiblePos >= songs.size()) return;

    String currentLetter = "#";
    String title = songs.get(firstVisiblePos).title;
    if (title != null && !title.isEmpty()) {
      currentLetter = Character.isLetter(title.charAt(0))
              ? title.substring(0, 1).toUpperCase() : "#";
    }

    for (int i = 0; i < LETTERS.length; i++) {
      if (LETTERS[i].equals(currentLetter)) {
        updateHighlight(i);
        break;
      }
    }
  }

  private void updateHighlight(int highlightIndex) {
    for (int i = 0; i < sidebar.getChildCount(); i++) {
      View child = sidebar.getChildAt(i);
      child.setAlpha(i == highlightIndex ? 1.0f : 0.6f);
    }
  }

  private void scrollToLetter(String letter) {
    Integer position = letterMap.get(letter);
    if (position != null) {
      LinearLayoutManager manager = (LinearLayoutManager) recyclerView.getLayoutManager();
      if (manager != null) {
        manager.scrollToPositionWithOffset(position, 0);
      }
    }
  }
}
