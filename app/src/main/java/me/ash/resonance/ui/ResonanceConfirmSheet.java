package me.ash.resonance.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import me.ash.resonance.R;

/**
 * A glass-themed confirmation bottom sheet (e.g. for delete actions).
 * Usage:
 * ResonanceConfirmSheet.newInstance("Delete playlist?", "This won't delete the songs.", () -> { ... })
 * .show(fragmentManager, ResonanceConfirmSheet.TAG);
 */
public class ResonanceConfirmSheet extends BottomSheetDialogFragment {

  public static final String TAG = "ResonanceConfirmSheet";
  private static final String ARG_TITLE = "title";
  private static final String ARG_MESSAGE = "message";
  private OnConfirm callback;

  public static ResonanceConfirmSheet newInstance(
          String title, String message, OnConfirm callback) {
    ResonanceConfirmSheet sheet = new ResonanceConfirmSheet();
    sheet.callback = callback;
    Bundle b = new Bundle();
    b.putString(ARG_TITLE, title);
    b.putString(ARG_MESSAGE, message);
    sheet.setArguments(b);
    return sheet;
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.dialog_confirm, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    if (getDialog() != null && getDialog().getWindow() != null)
      getDialog().getWindow().setBackgroundDrawableResource(android.R.color.transparent);

    Bundle args = getArguments();
    if (args == null) {
      dismiss();
      return;
    }

    ((TextView) view.findViewById(R.id.tvConfirmTitle)).setText(args.getString(ARG_TITLE));
    ((TextView) view.findViewById(R.id.tvConfirmMessage)).setText(args.getString(ARG_MESSAGE));

    view.findViewById(R.id.btnConfirmCancel).setOnClickListener(v -> dismiss());
    view.findViewById(R.id.btnConfirmDelete).setOnClickListener(v -> {
      if (callback != null) callback.onConfirm();
      dismiss();
    });
  }

  public interface OnConfirm {
    void onConfirm();
  }
}