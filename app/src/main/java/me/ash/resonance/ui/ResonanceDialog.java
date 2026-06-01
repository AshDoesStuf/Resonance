package me.ash.resonance.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import me.ash.resonance.R;

public class ResonanceDialog {

  public static class Builder {
    private final Context ctx;
    private String title;

    private String message;
    private View customView;
    private String[] items;
    private DialogInterface.OnClickListener itemsListener;
    private String positiveText;
    private DialogInterface.OnClickListener positiveListener;
    private String negativeText;
    private DialogInterface.OnClickListener negativeListener;

    private String[] singleChoiceItems;
    private int checkedItem = -1;
    private DialogInterface.OnClickListener singleChoiceListener;

    public Builder(Context ctx) {
      this.ctx = ctx;
    }

    public Builder setTitle(String title) {
      this.title = title;
      return this;
    }

    public Builder setMessage(String message) {
      this.message = message;
      return this;
    }

    public Builder setView(View view) {
      this.customView = view;
      return this;
    }

    public Builder setItems(String[] items, DialogInterface.OnClickListener listener) {
      this.items = items;
      this.itemsListener = listener;
      return this;
    }

    public Builder setPositiveButton(String text, DialogInterface.OnClickListener listener) {
      this.positiveText = text;
      this.positiveListener = listener;
      return this;
    }

    public Builder setNegativeButton(String text, DialogInterface.OnClickListener listener) {
      this.negativeText = text;
      this.negativeListener = listener;
      return this;
    }

    public Builder setSingleChoiceItems(String[] items, int checkedItem, DialogInterface.OnClickListener listener) {
      this.singleChoiceItems = items;
      this.checkedItem = checkedItem;
      this.singleChoiceListener = listener;
      return this;
    }

    public Dialog show() {
      Dialog dialog = new Dialog(ctx);
      View root = LayoutInflater.from(ctx)
              .inflate(R.layout.dialog_resonance, null);

      dialog.setContentView(root);
      if (dialog.getWindow() != null) {
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.getWindow().setLayout(
                (int) (ctx.getResources().getDisplayMetrics().widthPixels * 0.88),
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
      }

      // Title
      TextView tvTitle = root.findViewById(R.id.dialogTitle);
      if (title != null) {
        tvTitle.setText(title);
        tvTitle.setVisibility(View.VISIBLE);
      }

      TextView tvMessage = root.findViewById(R.id.dialogMessage);
      if (message != null) {
        tvMessage.setText(message);
        tvMessage.setVisibility(View.VISIBLE);
      }

      // Custom view
      FrameLayout frame = root.findViewById(R.id.dialogContentFrame);
      if (customView != null) {
        frame.addView(customView);
      }

      // List items
      ListView listView = root.findViewById(R.id.dialogListView);
      if (items != null) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                ctx,
                R.layout.item_dialog_list,
                R.id.dialogListItem,
                items
        );
        listView.setAdapter(adapter);
        listView.setVisibility(View.VISIBLE);
        listView.setOnItemClickListener((parent, view, position, id) -> {
          if (itemsListener != null)
            itemsListener.onClick(dialog, position);
          dialog.dismiss();
        });
      }

      RadioGroup radioGroup = root.findViewById(R.id.dialogRadioGroup);
      if (singleChoiceItems != null) {
        radioGroup.setVisibility(View.VISIBLE);
        for (int i = 0; i < singleChoiceItems.length; i++) {
          RadioButton rb = new RadioButton(ctx);
          rb.setButtonTintList(android.content.res.ColorStateList.valueOf(0xFFC7A1A9));
          rb.setText(singleChoiceItems[i]);
          rb.setTextColor(0xFFFAFAFF);   // text_primary
          rb.setTextSize(15);
          rb.setPadding(8, 18, 8, 18);
          rb.setId(i);
          if (i == checkedItem) rb.setChecked(true);
          radioGroup.addView(rb);
        }
        final int[] pending = {checkedItem};
        radioGroup.setOnCheckedChangeListener((group, id) -> {
          pending[0] = id;
          if (singleChoiceListener != null)
            singleChoiceListener.onClick(dialog, id);
        });
      }

      // Buttons
      LinearLayout buttonRow = root.findViewById(R.id.dialogButtonRow);
      if (negativeText != null || positiveText != null) {
        buttonRow.setVisibility(View.VISIBLE);

        if (negativeText != null) {
          Button btnNeg = makeButton(negativeText, 0xFF8D8F9C);
          btnNeg.setOnClickListener(v -> {
            if (negativeListener != null)
              negativeListener.onClick(dialog, DialogInterface.BUTTON_NEGATIVE);
            dialog.dismiss();
          });
          buttonRow.addView(btnNeg);
        }

        if (positiveText != null) {
          Button btnPos = makeButton(positiveText, 0xFFC7A1A9); // accent color
          btnPos.setOnClickListener(v -> {
            if (positiveListener != null)
              positiveListener.onClick(dialog, DialogInterface.BUTTON_POSITIVE);
            dialog.dismiss();
          });
          buttonRow.addView(btnPos);
        }
      }

      dialog.show();
      return dialog;
    }

    private Button makeButton(String text, int textColor) {
      Button btn = new Button(ctx);
      btn.setText(text);
      btn.setTextColor(textColor);
      btn.setBackground(new ColorDrawable(Color.TRANSPARENT));
      btn.setAllCaps(false);
      btn.setPadding(24, 0, 24, 0);
      return btn;
    }
  }
}