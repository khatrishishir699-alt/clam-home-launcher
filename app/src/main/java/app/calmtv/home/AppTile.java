package app.calmtv.home;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A focusable rounded app icon with a label. Grows and gets a white ring when focused. */
final class AppTile extends LinearLayout {

    private final GradientDrawable ringDrawable = new GradientDrawable();
    private final FrameLayout ring;
    private final TextView label;
    private final boolean labelAlways;
    private final float density;

    AppTile(Context c, Drawable icon, CharSequence text, int iconPx, int widthPx, boolean labelAlways) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        this.labelAlways = labelAlways;

        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);
        setFocusable(true);
        setClickable(true);
        setClipChildren(false);
        setClipToPadding(false);
        setPadding(0, dp(12), 0, dp(12));

        final int ringPad = dp(4);
        final float radius = iconPx * 0.22f;

        ring = new FrameLayout(c);
        ringDrawable.setShape(GradientDrawable.RECTANGLE);
        ringDrawable.setCornerRadius(radius + ringPad);
        ringDrawable.setColor(Color.TRANSPARENT);
        ringDrawable.setStroke(dp(3), Color.TRANSPARENT);
        ring.setBackground(ringDrawable);

        FrameLayout frame = new FrameLayout(c);
        frame.setBackgroundColor(0x59000000);
        frame.setClipToOutline(true);
        frame.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });

        ImageView iv = new ImageView(c);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setImageDrawable(fresh(c, icon));
        frame.addView(iv, new FrameLayout.LayoutParams(iconPx, iconPx));

        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(iconPx, iconPx);
        flp.setMargins(ringPad, ringPad, ringPad, ringPad);
        ring.addView(frame, flp);
        addView(ring, new LinearLayout.LayoutParams(iconPx + 2 * ringPad, iconPx + 2 * ringPad));

        label = new TextView(c);
        label.setText(text);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setGravity(Gravity.CENTER);
        label.setShadowLayer(dp(4), 0, dp(1), 0x99000000);
        label.setAlpha(labelAlways ? 0.75f : 0f);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                widthPx, ViewGroup.LayoutParams.WRAP_CONTENT);
        llp.topMargin = dp(8);
        addView(label, llp);
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** Each ImageView needs its own Drawable instance, because Drawables hold their bounds. */
    private static Drawable fresh(Context c, Drawable d) {
        if (d == null) {
            return null;
        }
        Drawable.ConstantState cs = d.getConstantState();
        return cs != null ? cs.newDrawable(c.getResources()).mutate() : d;
    }

    @Override
    protected void onFocusChanged(boolean gained, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gained, direction, previouslyFocusedRect);
        ringDrawable.setStroke(dp(3), gained ? Color.WHITE : Color.TRANSPARENT);
        float s = gained ? 1.16f : 1f;
        ring.animate().scaleX(s).scaleY(s).setDuration(140);
        float a = gained ? 1f : (labelAlways ? 0.75f : 0f);
        label.animate().alpha(a).setDuration(120);
    }
}
