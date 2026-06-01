package me.ash.resonance.adapter;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import me.ash.resonance.R;
import me.ash.resonance.TabItem;

public class TabAdapter extends RecyclerView.Adapter<TabAdapter.VH> {

  private final List<TabItem> items;
  private final Listener listener;
  private int selectedIndex = 0;

  public TabAdapter(List<TabItem> items, Listener listener) {
    this.items = items;
    this.listener = listener;
  }

  public void setSelectedIndex(int index) {
    int prev = selectedIndex;
    selectedIndex = index;

    notifyItemChanged(prev);
    notifyItemChanged(selectedIndex);
  }

  @NonNull
  @Override
  public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    TextView tv = new TextView(parent.getContext());
    tv.setPadding(24, 20, 24, 20);
    tv.setTextSize(16f);
    tv.setGravity(Gravity.CENTER);

    int itemWidth = (int) (parent.getContext().getResources().getDisplayMetrics().widthPixels * 0.28f);

    ViewGroup.MarginLayoutParams params = new ViewGroup.MarginLayoutParams(
            itemWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT
    );
    params.setMargins(8, 0, 8, 0);
    tv.setLayoutParams(params);
    return new VH(tv);
  }

  @Override
  public int getItemViewType(int position) {
    return position;
  }

  @Override
  public void onBindViewHolder(@NonNull VH holder, int position) {
    TextView tv = (TextView) holder.itemView;

    int distance = Math.abs(position - selectedIndex);

    float scale;

    int textColor;

    if (distance == 0) {
      scale = 1.3f;
      textColor = ContextCompat.getColor(tv.getContext(), R.color.accent);
      // Optional: bold the selected tab for extra pop
      tv.setTypeface(null, android.graphics.Typeface.BOLD);
    } else if (distance == 1) {
      scale = 1.0f;
      textColor = ContextCompat.getColor(tv.getContext(), R.color.text_muted);
      tv.setTypeface(null, android.graphics.Typeface.NORMAL);
    } else {
      scale = 0.8f;
      textColor = ContextCompat.getColor(tv.getContext(), R.color.text_disabled);
      tv.setTypeface(null, android.graphics.Typeface.NORMAL);
    }

    tv.setText(items.get(position).title);
    tv.setTextColor(textColor);
    tv.setScaleX(scale);
    tv.setScaleY(scale);
    tv.setTranslationX(0f);
    tv.setOnClickListener(v -> listener.onTabClick(position));
  }


  @Override
  public int getItemCount() {
    return items.size();
  }

  public interface Listener {
    void onTabClick(int position);
  }

  static class VH extends RecyclerView.ViewHolder {
    VH(@NonNull View itemView) {
      super(itemView);
    }
  }
}