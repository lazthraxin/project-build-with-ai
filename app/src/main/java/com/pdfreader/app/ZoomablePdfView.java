package com.pdfreader.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

/**
 * A scrollable / pinch-zoomable PDF page viewer built on top of the platform
 * PdfRenderer (API 21+). Pages are rendered off the main thread into a small
 * LRU bitmap cache and drawn in vertical reading order.
 */
public class ZoomablePdfView extends View {

    /** Callback interface for page tracking. */
    public interface Listener {
        void onPageChanged(int pageIndex, int pageCount);
    }

    private static final int MAX_CACHED_PAGES = 6;

    private ParcelFileDescriptor pfd;
    private PdfRenderer renderer;
    private Listener listener;

    // Rendering happens on this worker thread.
    private Thread renderThread;
    private final Object lock = new Object();
    private final Queue<Integer> renderQueue = new ArrayDeque<>();
    private volatile boolean quitRequested;

    private final List<String> cacheKeys = new ArrayList<>();
    private final java.util.LinkedHashMap<String, Bitmap> pageCache =
            new java.util.LinkedHashMap<>();
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint bgPaint = new Paint();

    private float scale = 1f;          // extra zoom on top of fit-width
    private float baseScale = 1f;      // fit-width scale, computed per document
    private float contentWidthPt;      // widest page width in points
    private float totalHeightPx;
    private float scrollY;             // current vertical scroll offset
    private float lastContentHeight = -1;

    private int currentPage = 0;
    private boolean ready;

    private final ScaleGestureDetector scaleDetector;
    private final GestureDetector gestureDetector;
    private float downX, downY, startScrollY;
    private boolean dragging;
    private static final float TOUCH_SLOP = 8f;

    public ZoomablePdfView(Context context) {
        this(context, null);
    }

    public ZoomablePdfView(Context context, AttributeSet attrs) {
        super(context, attrs);
        bgPaint.setColor(Color.parseColor("#424242"));
        bgPaint.setStyle(Paint.Style.FILL);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector detector) {
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float prev = scale;
                scale = clampScale(scale * detector.getScaleFactor());
                adjustScrollForZoom(prev, detector.getFocusX(), detector.getFocusY());
                requestLayout();
                invalidate();
                return true;
            }
        });

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onDoubleTapEvent(MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_UP) {
                    float target = scale < 1.9f ? 2.2f : 1f;
                    float prev = scale;
                    scale = clampScale(target);
                    adjustScrollForZoom(prev, e.getX(), e.getY());
                    requestLayout();
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                applyScroll(scrollY + dy);
                return true;
            }
        });
    }

    public void setListener(Listener l) {
        this.listener = l;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public float getScale() {
        return scale;
    }

    /** Open a pdf from an already-open file descriptor. Takes ownership. */
    public void open(ParcelFileDescriptor descriptor) {
        closeDocument();
        this.pfd = descriptor;
        try {
            renderer = new PdfRenderer(pfd);
        } catch (IOException e) {
            closeDocument();
            return;
        }
        ready = true;
        computeBaseMetrics();
        startRenderThreadIfNeeded();
        requestPage(currentPage);
        requestLayout();
        invalidate();
    }

    public boolean isReady() {
        return ready;
    }

    public int getPageCount() {
        synchronized (lock) {
            return (ready && renderer != null) ? renderer.getPageCount() : 0;
        }
    }

    /** Jump so that the given page is visible at the top. */
    public void goToPage(int page) {
        if (!ready) return;
        int count = getPageCount();
        page = Math.max(0, Math.min(count - 1, page));
        float y = 0;
        synchronized (lock) {
            for (int i = 0; i < page; i++) {
                PdfRenderer.Page p = renderer.openPage(i);
                y += effectiveHeight(p) * displayScale();
                p.close();
            }
        }
        applyScroll(y);
        requestPage(page);
    }

    public void zoomIn() {
        scale = clampScale(scale * 1.25f);
        requestLayout();
        invalidate();
    }

    public void zoomOut() {
        scale = clampScale(scale / 1.25f);
        requestLayout();
        invalidate();
    }

    public void fitPage() {
        scale = 1f;
        requestLayout();
        invalidate();
    }

    private float clampScale(float s) {
        return Math.max(0.5f, Math.min(8f, s));
    }

    private float displayScale() {
        return baseScale * scale;
    }

    // ------------------------------------------------------------------ metrics

    private void computeBaseMetrics() {
        synchronized (lock) {
            if (renderer == null) return;
            float maxW = 0;
            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page p = renderer.openPage(i);
                maxW = Math.max(maxW, p.getWidth());
                p.close();
            }
            contentWidthPt = Math.max(1f, maxW);
        }
        float vw = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        baseScale = vw / contentWidthPt;
        recomputeTotalHeight();
    }

    private void recomputeTotalHeight() {
        float ds = displayScale();
        float total = 0;
        synchronized (lock) {
            if (renderer == null) {
                totalHeightPx = 0;
                return;
            }
            for (int i = 0; i < renderer.getPageCount(); i++) {
                PdfRenderer.Page p = renderer.openPage(i);
                total += effectiveHeight(p) * ds;
                p.close();
            }
        }
        totalHeightPx = total;
    }

    /** Page height scaled to the fit-width base scale (points), i.e. aspect-correct. */
    private float effectiveHeight(PdfRenderer.Page page) {
        float ratio = (float) page.getHeight() / page.getWidth();
        return contentWidthPt * ratio;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        if (ready && w > 0) {
            float newBase = w / contentWidthPt;
            if (Math.abs(newBase - baseScale) > 0.0001f) {
                baseScale = newBase;
                recomputeTotalHeight();
            }
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        if (ready && w > 0) {
            baseScale = w / contentWidthPt;
            recomputeTotalHeight();
            applyScroll(scrollY);
        }
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.parseColor("#424242"));
        if (!ready || renderer == null) return;

        float ds = displayScale();
        int vH = getHeight();
        float y = -scrollY;
        List<Integer> visiblePages = new ArrayList<>();

        synchronized (lock) {
            int count = renderer.getPageCount();
            for (int i = 0; i < count; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                float pH = effectiveHeight(page) * ds;
                page.close();
                if (y + pH >= 0 && y <= vH) {
                    visiblePages.add(i);
                    drawPage(canvas, i, y, pH, ds);
                }
                y += pH;
            }
        }

        // Track current page: first page whose top edge is above viewport center.
        int newCurrent = findCurrentPage();
        if (newCurrent != currentPage) {
            currentPage = newCurrent;
            if (listener != null) listener.onPageChanged(currentPage, getPageCount());
        }

        // Request renders for any visible page not cached yet.
        for (int i : visiblePages) {
            String key = cacheKey(i, ds);
            if (!pageCache.containsKey(key)) requestPage(i);
        }
    }

    private void drawPage(Canvas canvas, int index, float top, float height, float ds) {
        String key = cacheKey(index, ds);
        Bitmap bmp = pageCache.get(key);
        float width = contentWidthPt * ds;
        Rect dst = new Rect(Math.round((getWidth() - width) / 2f), Math.round(top),
                Math.round((getWidth() + width) / 2f), Math.round(top + height));
        // subtle margin around pages
        dst.inset(dp(2), dp(2));
        if (bmp != null) {
            canvas.drawBitmap(bmp, null, dst, bmpPaint);
        } else {
            Paint placeholder = new Paint();
            placeholder.setColor(Color.parseColor("#616161"));
            canvas.drawRect(dst, placeholder);
            requestPage(index);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int findCurrentPage() {
        if (!ready) return 0;
        float ds = displayScale();
        float mid = scrollY + getHeight() / 2f;
        float acc = 0;
        synchronized (lock) {
            if (renderer == null) return 0;
            int count = renderer.getPageCount();
            for (int i = 0; i < count; i++) {
                PdfRenderer.Page p = renderer.openPage(i);
                float h = effectiveHeight(p) * ds;
                p.close();
                if (acc + h > mid) return i;
                acc += h;
            }
            return count - 1;
        }
    }

    // ------------------------------------------------------------------ scrolling

    private void applyScroll(float newY) {
        float maxScroll = Math.max(0, totalHeightPx - getHeight());
        scrollY = Math.max(0, Math.min(maxScroll, newY));
        invalidate();
    }

    private void adjustScrollForZoom(float prevScale, float focusX, float focusY) {
        // Keep the content point under the focus stationary while zooming.
        float factor = displayScale() / (baseScale * prevScale);
        float contentY = scrollY + focusY;
        float newContentY = contentY * factor;
        applyScroll(newContentY - focusY);
    }

    @Override
    public void computeScroll() {
        // no fling animation; kept simple
    }

    // ------------------------------------------------------------------ touch

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
        gestureDetector.onTouchEvent(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                startScrollY = scrollY;
                dragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!scaleDetector.isInProgress()) {
                    float dy = event.getY() - downY;
                    if (!dragging && Math.abs(dy) > TOUCH_SLOP) dragging = true;
                    if (dragging) applyScroll(startScrollY - dy);
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (!dragging && !scaleDetector.isInProgress()) {
                    // single tap toggles controls via listener elsewhere
                }
                dragging = false;
                break;
        }
        return true;
    }

    // ------------------------------------------------------------------ render thread

    private String cacheKey(int page, float ds) {
        return page + "@" + Math.round(ds * 1000);
    }

    private void requestPage(int index) {
        synchronized (lock) {
            if (renderer == null) return;
            String key = cacheKey(index, displayScale());
            if (pageCache.containsKey(key)) return;
            if (!renderQueue.contains(index)) renderQueue.offer(index);
            lock.notifyAll();
        }
    }

    private void startRenderThreadIfNeeded() {
        if (renderThread != null && renderThread.isAlive()) return;
        quitRequested = false;
        renderThread = new Thread(this::renderLoop, "pdf-render");
        renderThread.setDaemon(true);
        renderThread.start();
    }

    private void renderLoop() {
        while (!quitRequested) {
            int index;
            float ds;
            synchronized (lock) {
                while (renderQueue.isEmpty() && !quitRequested) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        return;
                    }
                }
                if (quitRequested) return;
                index = renderQueue.poll();
                ds = displayScale();
            }
            final float renderDs = ds;
            Bitmap bmp = renderPageBitmap(index, renderDs);
            if (bmp == null) continue;
            final String key = cacheKey(index, renderDs);
            post(() -> {
                synchronized (lock) {
                    pageCache.put(key, bmp);
                    cacheKeys.add(key);
                    while (cacheKeys.size() > MAX_CACHED_PAGES) {
                        String oldest = cacheKeys.remove(0);
                        Bitmap ob = pageCache.remove(oldest);
                        if (ob != null) ob.recycle();
                    }
                }
                invalidate();
            });
        }
    }

    private Bitmap renderPageBitmap(int index, float ds) {
        synchronized (lock) {
            if (renderer == null || index < 0 || index >= renderer.getPageCount()) return null;
            PdfRenderer.Page page = null;
            try {
                page = renderer.openPage(index);
                int w = Math.max(1, Math.round(contentWidthPt * ds));
                int h = Math.max(1, Math.round(effectiveHeight(page) * ds));
                // cap bitmap size to avoid OOM on huge zoom levels
                long maxPixels = 12_000_000L;
                while ((long) w * h > maxPixels) {
                    w = (int) (w * 0.85f);
                    h = (int) (h * 0.85f);
                }
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                Canvas c = new Canvas(bmp);
                Rect dst = new Rect(0, 0, w, h);
                page.render(c, dst, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (Exception e) {
                return null;
            } finally {
                if (page != null) page.close();
            }
        }
    }

    // ------------------------------------------------------------------ lifecycle

    public void closeDocument() {
        quitRequested = true;
        synchronized (lock) {
            lock.notifyAll();
            clearCacheLocked();
            if (renderer != null) {
                renderer.close();
                renderer = null;
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            pfd = null;
        }
        ready = false;
        renderThread = null;
    }

    private void clearCacheLocked() {
        for (Bitmap b : pageCache.values()) b.recycle();
        pageCache.clear();
        cacheKeys.clear();
        renderQueue.clear();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        closeDocument();
    }
}
