package com.pdfreader.app;

import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;

/** Full-screen PDF viewer with zoom, page navigation and share. */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_FILE_PATH = "file_path";
    public static final String EXTRA_DISPLAY_NAME = "display_name";
    public static final String EXTRA_START_PAGE = "start_page";

    private ZoomablePdfView pdfView;
    private TextView pageLabel;
    private View progressBar;
    private String filePath;
    private String displayName;
    private int startPage;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        filePath = getIntent().getStringExtra(EXTRA_FILE_PATH);
        displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
        startPage = getIntent().getIntExtra(EXTRA_START_PAGE, 0);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(displayName != null ? displayName : "");
        toolbar.setNavigationOnClickListener(v -> finish());

        pdfView = findViewById(R.id.pdf_view);
        pageLabel = findViewById(R.id.page_label);
        progressBar = findViewById(R.id.progress);

        ((ImageButton) findViewById(R.id.btn_first)).setOnClickListener(v -> pdfView.goToPage(0));
        ((ImageButton) findViewById(R.id.btn_last)).setOnClickListener(v ->
                pdfView.goToPage(pdfView.getPageCount() - 1));
        ((ImageButton) findViewById(R.id.btn_zoom_in)).setOnClickListener(v -> pdfView.zoomIn());
        ((ImageButton) findViewById(R.id.btn_zoom_out)).setOnClickListener(v -> pdfView.zoomOut());
        ((ImageButton) findViewById(R.id.btn_fit)).setOnClickListener(v -> pdfView.fitPage());
        ((ImageButton) findViewById(R.id.btn_share)).setOnClickListener(v -> shareDocument());

        pdfView.setListener((pageIndex, pageCount) -> updatePageLabel(pageIndex, pageCount));

        if (!PdfUtils.rendererSupported()) {
            Toast.makeText(this,
                    "PDF rendering requires Android 5.0 (API 21) or newer.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        openAsync();
    }

    private void openAsync() {
        AsyncTask.execute(() -> {
            ParcelFileDescriptor pfd = null;
            boolean ok = false;
            try {
                File f = new File(filePath);
                pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                // Validate the descriptor can be rendered before touching the UI thread.
                android.graphics.pdf.PdfRenderer r = new android.graphics.pdf.PdfRenderer(pfd);
                int pages = r.getPageCount();
                r.close();
                ok = pages > 0;
            } catch (Exception e) {
                if (pfd != null) try {
                    pfd.close();
                } catch (Exception ignored) {
                }
                pfd = null;
            }
            final ParcelFileDescriptor result = pfd;
            final boolean success = ok;
            runOnUiThread(() -> {
                progressBar.setVisibility(View.GONE);
                if (success && result != null) {
                    pdfView.open(result);
                    if (startPage > 0) {
                        pdfView.post(() -> pdfView.goToPage(startPage));
                    }
                    updatePageLabel(startPage, pdfView.getPageCount());
                } else {
                    Toast.makeText(this, R.string.err_open_pdf, Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        });
    }

    private void updatePageLabel(int pageIndex, int pageCount) {
        if (pageCount <= 0) {
            pageLabel.setText("");
        } else {
            pageLabel.setText(getString(R.string.page_of, pageIndex + 1, pageCount));
        }
    }

    private void shareDocument() {
        try {
            File f = new File(filePath);
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/pdf");
            Uri uri;
            if (filePath.startsWith(getFilesDir().getAbsolutePath())) {
                uri = Uri.parse("content://" + LibraryFileProvider.AUTHORITY
                        + "/doc/" + DbHelper.getIdForPath(this, filePath));
            } else {
                uri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", f);
            }
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.share)));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pdfView.closeDocument();
    }
}
