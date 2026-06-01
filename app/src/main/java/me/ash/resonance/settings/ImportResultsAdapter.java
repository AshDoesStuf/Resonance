package me.ash.resonance.settings;

import android.annotation.SuppressLint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.bitmap.RoundedCorners;
import com.bumptech.glide.request.RequestOptions;

import java.util.ArrayList;
import java.util.List;

import me.ash.resonance.R;
import me.ash.resonance.yt.OuterTuneImporter;

/**
 * Shows each OuterTune-imported song with its resolved YouTube metadata.
 * Failed fetches show a warning icon + the raw filename instead.
 */
public class ImportResultsAdapter
        extends RecyclerView.Adapter<ImportResultsAdapter.ImportVH> {

  private final List<OuterTuneImporter.ImportedSong> items = new ArrayList<>();

  @SuppressLint("NotifyDataSetChanged")
  public void setItems(List<OuterTuneImporter.ImportedSong> songs) {
    items.clear();
    items.addAll(songs);
    notifyDataSetChanged();
  }

  @SuppressLint("NotifyDataSetChanged")
  public void clear() {
    items.clear();
    notifyDataSetChanged();
  }

  @NonNull
  @Override
  public ImportVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
    View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_import_result, parent, false);
    return new ImportVH(v);
  }

  @Override
  public void onBindViewHolder(@NonNull ImportVH h, int position) {
    OuterTuneImporter.ImportedSong song = items.get(position);

    if (song.ytTrack != null) {
      // ── Success ───────────────────────────────────────────────────────
      h.tvTitle.setText(song.ytTrack.title);
      h.tvSub.setText(song.ytTrack.artist != null ? song.ytTrack.artist : "");
      h.tvSub.setAlpha(0.75f);
      h.ivStatus.setImageResource(R.drawable.ic_check_circle);
      h.ivStatus.setColorFilter(
              h.itemView.getContext().getColor(R.color.accent_green));

      Glide.with(h.itemView)
              .load(song.ytTrack.thumbnailUrl)
              .apply(new RequestOptions()
                      .placeholder(R.drawable.ic_note_outlined)
                      .error(R.drawable.ic_note_outlined)
                      .transform(new RoundedCorners(12)))
              .into(h.ivArt);

    } else {
      // ── Failed metadata fetch ─────────────────────────────────────────
      h.tvTitle.setText(song.fileName);
      h.tvSub.setText("Metadata unavailable");
      h.tvSub.setAlpha(0.5f);
      h.ivStatus.setImageResource(R.drawable.ic_warning);
      h.ivStatus.setColorFilter(
              h.itemView.getContext().getColor(R.color.accent_orange));

      Glide.with(h.itemView)
              .load((Object) null)
              .apply(new RequestOptions()
                      .placeholder(R.drawable.ic_note_outlined)
                      .error(R.drawable.ic_note_outlined)
                      .transform(new RoundedCorners(12)))
              .into(h.ivArt);
    }
  }

  @Override
  public int getItemCount() {
    return items.size();
  }

  static class ImportVH extends RecyclerView.ViewHolder {
    ImageView ivArt, ivStatus;
    TextView tvTitle, tvSub;

    ImportVH(@NonNull View v) {
      super(v);
      ivArt = v.findViewById(R.id.ivImportArt);
      ivStatus = v.findViewById(R.id.ivImportStatus);
      tvTitle = v.findViewById(R.id.tvImportTitle);
      tvSub = v.findViewById(R.id.tvImportSub);
    }
  }
}