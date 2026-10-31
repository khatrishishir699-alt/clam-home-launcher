package app.calmtv.home;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** The "All apps" icon: a 3 x 3 grid of rounded squares. */
final class GridIcon extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    GridIcon() {
        paint.setColor(0xF2FFFFFF);
    }

    @Override
    public void draw(Canvas canvas) {
        Rect b = getBounds();
        float w = b.width();
        float pad = w * 0.24f;
        float gap = w * 0.06f;
        float cell = (w - 2 * pad - 2 * gap) / 3f;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 3; c++) {
                float x = b.left + pad + c * (cell + gap);
                float y = b.top + pad + r * (cell + gap);
                canvas.drawRoundRect(new RectF(x, y, x + cell, y + cell), cell * 0.25f, cell * 0.25f, paint);
            }
        }
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return 256;
    }

    @Override
    public int getIntrinsicHeight() {
        return 256;
    }
}
