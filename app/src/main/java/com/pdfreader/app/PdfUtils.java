package com.pdfreader.app;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Utility helpers for opening PDFs (device storage or external content uris). */
public final class PdfUtils {

    private PdfUtils() {}

    /** True when the platform PdfRenderer (API 21+) can be used. */
    public static boolean rendererSupported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    /** Copy a stream to a file. Returns true on success. */
    public static boolean copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
        return true;
    }

    /** Best-effort display name for an external uri. */
    public static String displayName(Context ctx, Uri uri) {
        if (uri.getScheme() != null && uri.getScheme().equals("file")) {
            String p = uri.getPath();
            if (p != null) return new File(p).getName();
        }
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        String last = uri.getLastPathSegment();
        if (last != null && !last.isEmpty()) return last;
        return "document.pdf";
    }

    /** Strip a trailing ".pdf" (case-insensitive). */
    public static String stripPdfExt(String name) {
        if (name.toLowerCase().endsWith(".pdf")) {
            return name.substring(0, name.length() - 4);
        }
        return name;
    }

    /** Make a name unique under parent by appending " (2)", " (3)"… if needed. */
    public static String uniqueName(DbHelper db, String parentId, String baseName,
                                    LibraryItem.Type type) {
        if (!db.nameExists(parentId, baseName, type, null)) return baseName;
        for (int i = 2; i < 1000; i++) {
            String candidate = baseName + " (" + i + ")";
            if (!db.nameExists(parentId, candidate, type, null)) return candidate;
        }
        return baseName + " " + System.currentTimeMillis();
    }

    /** Quick sanity check that a file starts with the %PDF- header. */
    public static boolean looksLikePdf(File f) {
        if (f == null || !f.exists() || f.length() < 8) return false;
        byte[] head = new byte[5];
        try (InputStream in = new java.io.FileInputStream(f)) {
            if (in.read(head) < 5) return false;
        } catch (IOException e) {
            return false;
        }
        return head[0] == '%' && head[1] == 'P' && head[2] == 'D'
                && head[3] == 'F' && head[4] == '-';
    }

    /**
     * Render the first page of a pdf into a bitmap thumbnail.
     * Requires API 21+; returns null on any failure.
     */
    public static Bitmap renderThumbnail(ParcelFileDescriptor pfd, int targetWidthPx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return null;
        PdfRenderer renderer = null;
        PdfRenderer.Page page = null;
        try {
            renderer = new PdfRenderer(pfd);
            if (renderer.getPageCount() == 0) return null;
            page = renderer.openPage(0);
            float aspect = (float) page.getHeight() / page.getWidth();
            int w = Math.max(1, targetWidthPx);
            int h = Math.max(1, Math.round(w * aspect));
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            bmp.eraseColor(Color.WHITE);
            Canvas canvas = new Canvas(bmp);
            // Render the page at its native size and let the ImageView scale it down.
            page.render(canvas, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bmp;
        } catch (Exception e) {
            return null;
        } finally {
            if (page != null) page.close();
            if (renderer != null) renderer.close();
        }
    }
}
