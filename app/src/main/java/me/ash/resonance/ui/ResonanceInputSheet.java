package me.ash.resonance.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import me.ash.resonance.R;

/**
 * A glass-themed bottom sheet with a single text input.
 * Usage:
 * ResonanceInputSheet.show(fragmentManager, "New Playlist", "Playlist name", "", "Create",
 * input -> { ... });
 */
public class ResonanceInputSheet extends BottomSheetDialogFragment {

  private static final String ARG_TITLE = "title";
  private static final String ARG_HINT = "hint";
  private static final String ARG_PREFILL = "prefill";
  private static final String ARG_BTN = "btn_label";
  private OnConfirm callback;

  public static ResonanceInputSheet newInstance(
          String title, String hint, String prefill, String confirmLabel, OnConfirm callback) {
    ResonanceInputSheet sheet = new ResonanceInputSheet();
    sheet.callback = callback;
    Bundle b = new Bundle();
    b.putString(ARG_TITLE, title);
    b.putString(ARG_HINT, hint);
    b.putString(ARG_PREFILL, prefill);
    b.putString(ARG_BTN, confirmLabel);
    sheet.setArguments(b);
    return sheet;
  }

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.dialog_input, container, false);
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

    ((TextView) view.findViewById(R.id.tvInputTitle)).setText(args.getString(ARG_TITLE));

    EditText et = view.findViewById(R.id.etInput);
    et.setHint(args.getString(ARG_HINT));
    String prefill = args.getString(ARG_PREFILL, "");
    if (!prefill.isEmpty()) {
      et.setText(prefill);
      et.setSelection(prefill.length());
    }

    ((TextView) view.findViewById(R.id.btnInputConfirm))
            .setText(args.getString(ARG_BTN, "Confirm"));

    // Show keyboard automatically
    et.requestFocus();
    if (getDialog() != null && getDialog().getWindow() != null)
      getDialog().getWindow().setSoftInputMode(
              WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

    view.findViewById(R.id.btnInputCancel).setOnClickListener(v -> dismiss());

    view.findViewById(R.id.btnInputConfirm).setOnClickListener(v -> {
      String value = et.getText().toString().trim();
      if (value.isEmpty()) {
        et.setError("Can't be empty");
        return;
      }
      if (callback != null) callback.onConfirm(value);
      dismiss();
    });
  }

  public interface OnConfirm {
    void onConfirm(String value);
  }
}