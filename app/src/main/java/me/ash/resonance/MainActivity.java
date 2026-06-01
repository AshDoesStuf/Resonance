package me.ash.resonance;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentContainerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import me.ash.resonance.adapter.MusicPagerAdapter;
import me.ash.resonance.adapter.TabAdapter;

public class MainActivity extends AppCompatActivity {

  private static final int PERMISSION_REQUEST_CODE = 101;

  private MiniPlayerManager miniPlayerManager;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    setSupportActionBar(findViewById(R.id.toolbar));

    FragmentContainerView container = findViewById(R.id.fragmentContainer);
    container.setVisibility(View.GONE);
    requestPermissions();
  }

  private void setupViewPager() {
    RecyclerView tabRecycler = findViewById(R.id.tabRecycler);
    ViewPager2 viewPager = findViewById(R.id.viewPager);

    List<TabItem> tabs = Arrays.asList(
            new TabItem("Songs"),
            new TabItem("Albums"),
            new TabItem("Artists"),
            new TabItem("Playlists")
    );

    // IMPORTANT
    viewPager.setAdapter(new MusicPagerAdapter(this));

    LinearLayoutManager layoutManager =
            new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);

    tabRecycler.setLayoutManager(layoutManager);

    tabRecycler.setClipToPadding(false);
    tabRecycler.setOverScrollMode(View.OVER_SCROLL_NEVER);

    tabRecycler.addItemDecoration(new RecyclerView.ItemDecoration() {
      @Override
      public void getItemOffsets(@NonNull Rect outRect, @NonNull View view,
                                 @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        int count = parent.getAdapter().getItemCount();
        int index = parent.getChildAdapterPosition(view);
        int halfRv = parent.getWidth() / 2;
        int halfItem = view.getWidth() / 2;
        int edge = halfRv - halfItem;

        if (index == 0) outRect.left = edge;
        else if (index == count - 1) outRect.right = edge;
      }
    });

    TabAdapter adapter = new TabAdapter(tabs, position -> {
      viewPager.setCurrentItem(position, true);
    });

    tabRecycler.setAdapter(adapter);

    tabRecycler.post(() -> scrollTabToCenter(tabRecycler, 0));

    viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
      @Override
      public void onPageSelected(int position) {
        adapter.setSelectedIndex(position);
        scrollTabToCenter(tabRecycler, position);
      }
    });

    miniPlayerManager = new MiniPlayerManager(this);
    ((ResonanceApp) getApplication()).getSharedController(controller ->
            miniPlayerManager.init(controller)
    );

    findViewById(R.id.btnSearch).setOnClickListener(v ->
            startActivity(new Intent(this, me.ash.resonance.search.SearchActivity.class)));

    findViewById(R.id.btnSettings).setOnClickListener(v ->
            startActivity(new Intent(this, me.ash.resonance.settings.SettingsActivity.class)));

    findViewById(R.id.btnStats).setOnClickListener(v -> startActivity(new Intent(this, StatsActivity.class)));
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    if (miniPlayerManager != null) miniPlayerManager.detach();
  }

  // ── Permissions ──────────────────────────────────────────────────────────

  private void requestPermissions() {
    List<String> needed = new ArrayList<>();

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
            != PackageManager.PERMISSION_GRANTED)
      needed.add(Manifest.permission.READ_MEDIA_AUDIO);

    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED)
      needed.add(Manifest.permission.POST_NOTIFICATIONS);


    if (needed.isEmpty()) {
      setupViewPager();
    } else {
      ActivityCompat.requestPermissions(
              this, needed.toArray(new String[0]), PERMISSION_REQUEST_CODE);
    }
  }

  private void scrollTabToCenter(RecyclerView tabRecycler, int position) {
    tabRecycler.post(() -> {
      LinearLayoutManager lm = (LinearLayoutManager) tabRecycler.getLayoutManager();
      if (lm == null) return;

      View child = lm.findViewByPosition(position);
      if (child == null) {
        lm.scrollToPositionWithOffset(position, 0);
        tabRecycler.post(() -> scrollTabToCenter(tabRecycler, position));
        return;
      }

      int childCenter = child.getLeft() + child.getWidth() / 2;
      int rvCenter = tabRecycler.getWidth() / 2;
      tabRecycler.smoothScrollBy(childCenter - rvCenter, 0);
    });
  }

  @Override
  public void onRequestPermissionsResult(int requestCode,
                                         @NonNull String[] permissions,
                                         @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == PERMISSION_REQUEST_CODE) setupViewPager();
  }
}