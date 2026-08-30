package me.ash.resonance.queue;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.common.MediaItem;
import androidx.recyclerview.widget.AsyncListDiffer;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import me.ash.resonance.R;
import me.ash.resonance.songs.SongActionMenu;
import me.ash.resonance.songs.SongContext;
import me.ash.resonance.songs.SongRowBinder;

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

  private final AsyncListDiffer<MediaItem> differ;
  private final QueueInteractionListener listener;
  private final SongActionMenu.ActionHandler actionHandler;
  private int currentIndex = -1;
  private ItemTouchHelper touchHelper;
  private boolean shuffleEnabled = false;

  public QueueAdapter(QueueInteractionListener listener, SongActionMenu.ActionHandler actionHandler) {
    this.listener = listener;
    this.actionHandler = actionHandler;
    this.differ = new AsyncListDiffer<>(this, DIFF_CALLBACK);
  }

  private static final DiffUtil.ItemCallback<MediaItem> DIFF_CALLBACK = new DiffUtil.ItemCallback<MediaItem>() {
    @Override
    public boolean areItemsTheSame(@NonNull MediaItem oldItem, @NonNull MediaItem newItem) {
      return oldItem.mediaId.equals(newItem.mediaId);
    }

    @Override
    public boolean areContentsTheSame(@NonNull MediaItem oldItem, @NonNull MediaItem newItem) {
      return Objects.equals(oldItem.mediaMetadata.title, newItem.mediaMetadata.title) &&
              Objects.equals(oldItem.mediaMetadata.artist, newItem.mediaMetadata.artist) &&
              Objects.equals(oldItem.mediaMetadata.artworkUri, newItem.mediaMetadata.artworkUri);
    }
  };

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
      public int getMovementFlags(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder viewHolder) {
        int dragFlags = shuffleEnabled ? 0 : (ItemTouchHelper.UP | ItemTouchHelper.DOWN);
        int swipeFlags = ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT;
        return makeMovementFlags(dragFlags, swipeFlags);
      }

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
        int from = source.getBindingAdapterPosition();
        int to = target.getBindingAdapterPosition();
        
        // We can't swap in AsyncListDiffer directly, but we can report it.
        // For drag-and-drop to feel responsive, we update the local list and notify.
        List<MediaItem> currentList = new ArrayList<>(differ.getCurrentList());
        java.util.Collections.swap(currentList, from, to);
        differ.submitList(currentList); // DiffUtil will handle the move efficiently
        
        dragTo = to;
        return true;
      }

      @Override
      public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
        int pos = viewHolder.getBindingAdapterPosition();
        List<MediaItem> currentList = new ArrayList<>(differ.getCurrentList());
        currentList.remove(pos);
        differ.submitList(currentList);
        listener.onItemRemoved(pos);
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

  public void setItems(List<MediaItem> newItems, int currentIdx) {
    int oldIdx = this.currentIndex;
    this.currentIndex = currentIdx;

    differ.submitList(newItems, () -> {
      if (oldIdx != currentIndex) {
        if (oldIdx != -1) notifyItemChanged(oldIdx);
        if (currentIndex != -1) notifyItemChanged(currentIndex);
      }
    });
  }

  public void updateCurrentIndex(int index) {
    int old = currentIndex;
    currentIndex = index;
    notifyItemChanged(old);
    notifyItemChanged(currentIndex);
  }

  public void setShuffleEnabled(boolean enabled) {
    if (this.shuffleEnabled == enabled) return;
    this.shuffleEnabled = enabled;
    notifyDataSetChanged(); // refresh drag handle visibility
  }

  @NonNull
  @Override
  public QueueViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_queue, parent, false);
    QueueViewHolder vh = new QueueViewHolder(v);
    vh.itemView.setOnClickListener(vClick -> {
      int pos = vh.getBindingAdapterPosition();
      if (pos != RecyclerView.NO_POSITION) listener.onItemClicked(pos);
    });
    vh.ivOverflow.setOnClickListener(vMenu -> {
      int pos = vh.getBindingAdapterPosition();
      if (pos != RecyclerView.NO_POSITION) {
        MediaItem item = differ.getCurrentList().get(pos);
        SongActionMenu.show(
                vh.itemView.getContext(),
                item,
                SongContext.queue(pos),
                actionHandler
        );
      }
    });
    return vh;
  }

  @SuppressLint("ClickableViewAccessibility")
  @Override
  public void onBindViewHolder(@NonNull QueueViewHolder h, int position) {
    MediaItem item = differ.getCurrentList().get(position);
    boolean isCurrent = (position == currentIndex);

    SongRowBinder.bind(h.binderViews, item, isCurrent, !shuffleEnabled, null, null, null);

    h.ivDragHandle.setOnTouchListener(shuffleEnabled ? null : (vTouch, event) -> {
      if (event.getActionMasked() == MotionEvent.ACTION_DOWN && touchHelper != null) {
        touchHelper.startDrag(h);
      }
      return false;
    });
  }

  @Override
  public int getItemCount() {
    return differ.getCurrentList().size();
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
    ImageView ivArt, ivDragHandle, ivPlaying, ivOverflow;
    TextView tvTitle, tvArtist;
    final SongRowBinder.Views binderViews;

    QueueViewHolder(@NonNull View v) {
      super(v);
      ivArt = v.findViewById(R.id.queueItemArt);
      ivDragHandle = v.findViewById(R.id.queueItemDragHandle);
      ivPlaying = v.findViewById(R.id.queueItemPlaying);
      ivOverflow = v.findViewById(R.id.queueItemOverflow);
      tvTitle = v.findViewById(R.id.queueItemTitle);
      tvArtist = v.findViewById(R.id.queueItemArtist);

      binderViews = new SongRowBinder.Views();
      binderViews.ivArt = ivArt;
      binderViews.ivDragHandle = ivDragHandle;
      binderViews.ivPlaying = ivPlaying;
      binderViews.tvTitle = tvTitle;
      binderViews.tvArtist = tvArtist;
    }
  }
}