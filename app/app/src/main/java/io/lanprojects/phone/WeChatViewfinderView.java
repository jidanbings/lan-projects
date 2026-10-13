package io.lanprojects.phone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

/**
 * WeChat-style scanner viewfinder: a dark mask over the whole screen, a centered
 * square scan area outlined by four green corner brackets, and a green scan line
 * that sweeps up and down. Replaces zxing's default bare laser beam.
 */
public class WeChatViewfinderView extends View {

    private final Paint maskPaint = new Paint();
    private final Paint cornerPaint = new Paint();
    private final Paint laserPaint = new Paint();
    private final Rect frame = new Rect();
    private float scanLineY;
    private int cornerSize = 32;

    public WeChatViewfinderView(Context context) {
        super(context);
        init();
    }

    public WeChatViewfinderView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        maskPaint.setColor(0x99000000);          // dark translucent mask
        cornerPaint.setColor(0xFF00E676);        // WeChat green
        cornerPaint.setStyle(Paint.Style.STROKE);
        cornerPaint.setStrokeWidth(8);
        cornerPaint.setStrokeCap(Paint.Cap.ROUND);
        laserPaint.setColor(0xCC00E676);         // translucent green line
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int size = (int) (Math.min(w, h) * 0.68f); // centered square
        int left = (w - size) / 2;
        int top = (h - size) / 2;
        frame.set(left, top, left + size, top + size);
        cornerSize = size / 10;
        scanLineY = frame.top;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // Mask everything outside the frame.
        canvas.drawRect(0, 0, getWidth(), frame.top, maskPaint);
        canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), maskPaint);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, maskPaint);
        canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, maskPaint);

        // Four green corner brackets.
        int c = cornerSize;
        canvas.drawLine(frame.left, frame.top, frame.left + c, frame.top, cornerPaint);
        canvas.drawLine(frame.left, frame.top, frame.left, frame.top + c, cornerPaint);

        canvas.drawLine(frame.right, frame.top, frame.right - c, frame.top, cornerPaint);
        canvas.drawLine(frame.right, frame.top, frame.right, frame.top + c, cornerPaint);

        canvas.drawLine(frame.left, frame.bottom, frame.left + c, frame.bottom, cornerPaint);
        canvas.drawLine(frame.left, frame.bottom, frame.left, frame.bottom - c, cornerPaint);

        canvas.drawLine(frame.right, frame.bottom, frame.right - c, frame.bottom, cornerPaint);
        canvas.drawLine(frame.right, frame.bottom, frame.right, frame.bottom - c, cornerPaint);

        // Moving green scan line.
        canvas.drawRect(frame.left + c / 2, scanLineY, frame.right - c / 2, scanLineY + 4, laserPaint);

        // Animate the sweep.
        scanLineY += 4;
        if (scanLineY > frame.bottom) scanLineY = frame.top;
        postInvalidateDelayed(16);
    }
}
