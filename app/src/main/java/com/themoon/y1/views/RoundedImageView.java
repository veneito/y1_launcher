package com.themoon.y1.views;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;

// Fork: album art with rounded corners (issue #39). Software layer because clipPath
// isn't supported on hardware canvases before API 18 (the Y1 runs 4.2 / API 17).
public class RoundedImageView extends ImageView {
    private float radius = 0;
    private final Path path = new Path();
    private final RectF rect = new RectF();

    public RoundedImageView(Context c) { super(c); }
    public RoundedImageView(Context c, AttributeSet a) { super(c, a); }
    public RoundedImageView(Context c, AttributeSet a, int s) { super(c, a, s); }

    public void setCornerRadius(float px) {
        radius = px;
        setLayerType(px > 0 ? View.LAYER_TYPE_SOFTWARE : View.LAYER_TYPE_NONE, null);
        invalidate();
    }

    @Override
    public void draw(Canvas canvas) {
        if (radius <= 0) { super.draw(canvas); return; }
        rect.set(0, 0, getWidth(), getHeight());
        path.reset();
        path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        int save = canvas.save();
        canvas.clipPath(path);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }
}
