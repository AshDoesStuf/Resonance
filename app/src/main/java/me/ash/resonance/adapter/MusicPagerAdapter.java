package me.ash.resonance.adapter;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.adapter.FragmentStateAdapter;

import me.ash.resonance.fragment.AlbumsFragment;
import me.ash.resonance.fragment.ArtistsFragment;
import me.ash.resonance.fragment.PlaylistsFragment;
import me.ash.resonance.fragment.SongsFragment;

public class MusicPagerAdapter extends FragmentStateAdapter {

  public MusicPagerAdapter(@NonNull FragmentActivity fragmentActivity) {
    super(fragmentActivity);
  }

  @Override
  public int getItemCount() {
    return 4;
  }


  @NonNull
  @Override
  public Fragment createFragment(int position) {
    switch (position) {
      case 1:
        return new AlbumsFragment();
      case 2:
        return new ArtistsFragment();
      case 3:
        return new PlaylistsFragment();
      default:
        return new SongsFragment();
    }
  }


}