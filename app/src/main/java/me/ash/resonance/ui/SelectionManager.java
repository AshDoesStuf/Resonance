package me.ash.resonance.ui;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Generic, reusable multi-selection tracker.
 * T should implement equals/hashCode (String, Long, or a model with an ID field).
 * <p>
 * Usage:
 * SelectionManager<String> sel = new SelectionManager<>(this::onSelectionChanged);
 * sel.toggle(item.videoId);
 * if (sel.isSelected(item.videoId)) { ... }
 */
public class SelectionManager<T> {

  private final Set<T> selected = new LinkedHashSet<>();
  private final Listener listener;

  public SelectionManager(Listener listener) {
    this.listener = listener;
  }

  public void toggle(T id) {
    if (!selected.remove(id)) selected.add(id);
    listener.onSelectionChanged(selected.size());
  }

  public boolean isSelected(T id) {
    return selected.contains(id);
  }

  public boolean isActive() {
    return !selected.isEmpty();
  }

  public int count() {
    return selected.size();
  }

  public Set<T> getSelected() {
    return Collections.unmodifiableSet(selected);
  }

  public void clear() {
    selected.clear();
    listener.onSelectionChanged(0);
  }

  public interface Listener {
    /**
     * Called whenever the selection set changes.
     */
    void onSelectionChanged(int count);
  }
}