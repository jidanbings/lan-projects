package io.lanprojects.phone;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;

/**
 * Minimal pull-to-refresh container. The standard SwipeRefreshLayout cannot be
 * added as a dependency (the build machine is offline), so this replicates its
 * essential behaviour natively:
 *
 *  - pulling down while the inner ScrollView is at the top drags the whole page
 *    content down with the finger (with resistance),
 *  - a small circular spinner fades in above the page,
 *  - releasing past the threshold fires onRefresh() (the spinner stays visible
 *    for a beat, then the page springs back),
 *  - releasing short of the threshold just springs back.
 *
 * Only the ScrollView child is translated; overlay siblings (e.g. the settings
 * button) stay put.
 */
public class PullRefreshLayout extends FrameLayout {

    private ProgressBar indicator;
    private ScrollView content;
    private Runnable onRefresh;

    private boolean pulling = false;
    private boolean refreshing = false;
    private float downY = 0;
    private float translation = 0;

    /** The page travels this fraction of the finger's downward distance. */
    private static final float RESISTANCE = 0.45f;
    private static final int REFRESH_TRIGGER_DP = 96;
    private static final int MAX_PULL_DP = 180;
    /** Keep the spinner on screen this long so a fast refresh is still visible. */
    private static final long MIN_REFRESH_SHOW_MS = 500;

    private final int triggerPx;
    private final int maxPullPx;

    public PullRefreshLayout(Context context) {
        this(context, null);
    }

    public PullRefreshLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = context.getResources().getDisplayMetrics().density;
        triggerPx = Math.round(REFRESH_TRIGGER_DP * density);
        maxPullPx = Math.round(MAX_PULL_DP * density);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View v = getChildAt(i);
            if (v instanceof ProgressBar) {
                indicator = (ProgressBar) v;
            } else if (v instanceof ScrollView) {
                content = (ScrollView) v;
            }
        }
        if (indicator != null) {
            indicator.setVisibility(View.GONE);
            indicator.setAlpha(0f);
        }
    }

    /** Listener fired when the user releases past the pull threshold. */
    public void setOnRefresh(Runnable r) {
        onRefresh = r;
    }

    /** True while the inner scroll view cannot scroll up any further. */
    private boolean atTop() {
        return content == null || !content.canScrollVertically(-1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (refreshing) return false; // don't start a new pull mid-refresh
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                break;
            case MotionEvent.ACTION_MOVE:
                if (atTop() && ev.getY() - downY > 0 && translation == 0) {
                    pulling = true;
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                break;
            default:
                break;
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!pulling) return super.onTouchEvent(ev);
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_MOVE: {
                float dy = ev.getY() - downY;
                if (dy < 0) dy = 0;
                setTranslation(Math.min(dy * RESISTANCE, maxPullPx));
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                pulling = false;
                if (translation >= triggerPx) {
                    startRefresh();
                } else {
                    animateBack();
                }
                break;
            default:
                break;
        }
        return true;
    }

    private void setTranslation(float t) {
        translation = t;
        if (content != null) content.setTranslationY(t);
        if (indicator != null) {
            if (t > 1) indicator.setVisibility(View.VISIBLE);
            indicator.setAlpha(Math.min(1f, t / triggerPx));
        }
    }

    private void startRefresh() {
        refreshing = true;
        // Stay pulled down with the spinner spinning while the refresh runs;
        // snap back once the minimum display time has passed.
        if (onRefresh != null) onRefresh.run();
        postDelayed(this::finishRefresh, MIN_REFRESH_SHOW_MS);
    }

    private void finishRefresh() {
        refreshing = false;
        animateBack();
    }

    private void animateBack() {
        if (content != null) {
            content.animate().translationY(0).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        if (indicator != null) {
            indicator.animate().alpha(0f).setDuration(200)
                    .withEndAction(() -> indicator.setVisibility(View.GONE)).start();
        }
        translation = 0;
    }
}
