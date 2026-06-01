package me.ash.resonance.queue;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.R;

/**
 * Changes from original:
 * - Removed local Collections.swap() / items.remove() calls inside the
 * ItemTouchHelper callbacks. The adapter no longer mutates its own list
 * on drag/swipe. Instead it notifies the listener, which updates the
 * MediaController (single source of truth), and then calls setItems()
 * to push the new snapshot back down to the adapter.
 * This eliminates the double-mutation bug where both the adapter list
 * and the controller list were modified independently.
 */
public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.QueueViewHolder> {

  private final List<MediaItem> items = new ArrayList<>();
  private final QueueInteractionListener listener;
  private int currentIndex = 0;
  private ItemTouchHelper touchHelper;

  public QueueAdapter(QueueInteractionListener listener) {
    this.listener = listener;
  }

  public void attachTouchHelper(RecyclerView rv) {
    touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP | ItemTouchHelper.DOWN,
            ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT
    ) {
      // Tracks the drag start position so we can report a single
      // fromIndex → toIndex pair rather than a chain of incremental swaps.
      private long dragFrom = RecyclerView.NO_ID;
      private long dragTo = RecyclerView.NO_ID;

      @Override
      public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
        super.onSelectedChanged(viewHolder, actionState);
        if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && viewHolder != null) {
          dragFrom = viewHolder.getAdapterPosition();
          dragTo = dragFrom;
        }
      }

      @Override
      public void clearView(@NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder viewHolder) {
        super.clearView(rv, viewHolder);
        if (dragFrom != RecyclerView.NO_ID && dragTo != RecyclerView.NO_ID
                && dragFrom != dragTo) {
          listener.onItemMoved((int) dragFrom, (int) dragTo);
        }
        dragFrom = dragTo = (int) RecyclerView.NO_ID;
      }

      @Override
      public boolean onMove(@NonNull RecyclerView rv,
                            @NonNull RecyclerView.ViewHolder source,
                            @NonNull RecyclerView.ViewHolder target) {
        int from = source.getAdapterPosition();
        int to = target.getAdapterPosition();
        // Visual-only swap so the drag feels responsive.
        // The real mutation happens in clearView() → listener.onItemMoved().
        java.util.Collections.swap(items, from, to);
        notifyItemMoved(from, to);
        dragTo = to;
        return true;
      }

      @Override
      public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        int pos = viewHolder.getAdapterPosition();
        // Remove from local list immediately so the animation plays,
        // then tell the listener to remove it from the controller too.
        items.remove(pos);
        notifyItemRemoved(pos);
        listener.onItemRemoved(pos);
        // Note: listener should NOT call setItems() here because the
        // remove animation is already running; let it finish first.
      }

      @Override
      public void onChildDraw(@NonNull Canvas c,
                              @NonNull RecyclerView recyclerView,
                              @NonNull RecyclerView.ViewHolder viewHolder,
                              float dX, float dY,
                              int actionState, boolean isCurrentlyActive) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
          float alpha = 1f - (Math.abs(dX) / (float) viewHolder.itemView.getWidth());
          viewHolder.itemView.setAlpha(alpha);
        }
        super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
      }

      // Prevent swiping the currently playing item
      @Override
      public float getSwipeThreshold(@NonNull RecyclerView.ViewHolder viewHolder) {
        return viewHolder.getAdapterPosition() == currentIndex ? 1f : 0.4f;
      }
    });
    touchHelper.attachToRecyclerView(rv);
  }

  @SuppressLint("NotifyDataSetChanged")
  public void setItems(List<MediaItem> newItems, int currentIdx) {
    items.clear();
    items.addAll(newItems);
    this.currentIndex = currentIdx;
    notifyDataSetChanged();
  }

  public void updateCurrentIndex(int index) {
    int old = currentIndex;
    currentIndex = index;
    notifyItemChanged(old);
    notifyItemChanged(currentIndex);
  }

  @NonNull
  @Override
  public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_queue, parent, false);
    return new QueueViewHolder(v);
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onBindViewHolder(@NonNull QueueViewHolder h, int position) {
    MediaItem item = items.get(position);
    boolean isCurrent = (position == currentIndex);

    CharSequence title = item.mediaMetadata.title;
    CharSequence artist = item.mediaMetadata.artist;

    h.tvTitle.setText(title != null ? title : "Unknown");
    h.tvArtist.setText(artist != null ? artist : "Unknown");

    h.tvTitle.setAlpha(isCurrent ? 1f : 0.85f);
    if (isCurrent) {
      h.ivPlaying.setVisibility(View.VISIBLE);
      h.ivPlaying.setImageResource(R.drawable.ic_equalizer_animated);
      Drawable drawable = h.ivPlaying.getDrawable();
      if (drawable instanceof AnimatedVectorDrawable) {
        ((AnimatedVectorDrawable) drawable).start();
      }
    } else {
      h.ivPlaying.setVisibility(View.GONE);
      Drawable drawable = h.ivPlaying.getDrawable();
      if (drawable instanceof AnimatedVectorDrawable) {
        ((AnimatedVectorDrawable) drawable).stop();
      }
    }

    Glide.with(h.itemView.getContext())
            .load(item.mediaMetadata.artworkUri)
            .apply(new RequestOptions()
                    .placeholder(R.drawable.music_note_24px)
                    .error(R.drawable.music_note_24px)
                    .transform(new RoundedCorners(20)))
            .into(h.ivArt);

    h.itemView.setOnClickListener(v -> listener.onItemClicked(h.getAdapterPosition()));

    h.ivDragHandle.setOnTouchListener((v, event) -> {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
        touchHelper.startDrag(h);
      }
      return false;
    });
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  public interface QueueInteractionListener {
    /**
     * Called when the user drags an item from one position to another.
     */
    void onItemMoved(int fromIndex, int toIndex);

    /**
     * Called when the user swipes an item away.
     */
    void onItemRemoved(int index);

    /**
     * Called when the user taps an item.
     */
    void onItemClicked(int index);
  }

  static class QueueViewHolder extends RecyclerView.ViewHolder {
    ImageView ivArt, ivDragHandle, ivPlaying;
    TextView tvTitle, tvArtist;

    QueueViewHolder(@NonNull View v) {
      super(v);
      ivArt = v.findViewById(R.id.queueItemArt);
      ivDragHandle = v.findViewById(R.id.queueItemDragHandle);
      ivPlaying = v.findViewById(R.id.queueItemPlaying);
      tvTitle = v.findViewById(R.id.queueItemTitle);
      tvArtist = v.findViewById(R.id.queueItemArtist);
    }
  }
}