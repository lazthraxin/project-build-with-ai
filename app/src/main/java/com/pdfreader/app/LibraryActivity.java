package com.pdfreader.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Main screen: a locally managed PDF library with custom folders.
 * Users can import PDFs from device storage, create/rename/move/delete folders,
 * organise documents into those folders and open any document in the viewer.
 */
public class LibraryActivity extends AppCompatActivity
        implements LibraryAdapter.Callbacks {

    private static final int REQ_PICK_PDF = 10;
    private static final int REQ_STORAGE_PERM = 11;

    private DbHelper db;
    private LibraryAdapter adapter;
    private RecyclerView recycler;
    private TextView emptyView;
    private MaterialToolbar toolbar;

    /** Current folder id, or null for library root. */
    @Nullable
    private String currentFolderId;
    private boolean searching = false;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_library);

        db = new DbHelper(this);

        toolbar = findViewById(R.id.toolbar);
        recycler = findViewById(R.id.recycler);
        emptyView = findViewById(R.id.empty_view);
        EditText searchInput = findViewById(R.id.search_input);

        adapter = new LibraryAdapter(this, this);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.addItemDecoration(
                new DividerItemDecoration(this, DividerItemDecoration.VERTICAL));
        recycler.setAdapter(adapter);

        toolbar.setNavigationOnClickListener(v -> navigateUp());
        updateToolbar();

        findViewById(R.id.fab_add_pdf).setOnClickListener(v -> launchPdfPicker());
        findViewById(R.id.fab_new_folder).setOnClickListener(v -> promptNewFolder());

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
                searching = s.length() > 0;
                refresh();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        maybeRequestLegacyPermission();

        // Handle VIEW / SEND intents coming from other apps.
        handleIncomingIntent(getIntent());
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    // ------------------------------------------------------------------ navigation

    private void navigateUp() {
        if (currentFolderId == null) return;
        LibraryItem cur = db.getItem(currentFolderId);
        currentFolderId = cur == null ? null : cur.parentId;
        updateToolbar();
        refresh();
    }

    private void updateToolbar() {
        if (currentFolderId == null) {
            toolbar.setTitle(R.string.library_title);
            toolbar.setNavigationIcon(null);
        } else {
            LibraryItem cur = db.getItem(currentFolderId);
            toolbar.setTitle(cur != null ? cur.displayName : getString(R.string.library_title));
            toolbar.setNavigationIcon(R.drawable.ic_arrow_up);
        }
    }

    private void refresh() {
        List<LibraryItem> items;
        if (searching) {
            EditText input = findViewById(R.id.search_input);
            items = db.search(input.getText().toString().trim());
        } else {
            items = db.getChildren(currentFolderId);
        }
        adapter.submit(items);
        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            emptyView.setText(searching
                    ? "No results."
                    : (currentFolderId == null
                    ? getString(R.string.empty_library)
                    : getString(R.string.empty_folder)));
        }
    }

    // ------------------------------------------------------------------ picking pdfs

    private void launchPdfPicker() {
        Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        open.addCategory(Intent.CATEGORY_OPENABLE);
        open.setType("application/pdf");
        open.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(open, REQ_PICK_PDF);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_PDF || resultCode != RESULT_OK || data == null) return;

        List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        importUris(uris);
    }

    private void importUris(List<Uri> uris) {
        if (uris.isEmpty()) return;
        AsyncTask.execute(() -> {
            int imported = 0;
            for (Uri uri : uris) {
                try {
                    persistIfNeeded(uri);
                    if (importOne(uri)) imported++;
                } catch (Exception e) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Import failed: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show());
                }
            }
            final int count = imported;
            runOnUiThread(() -> {
                if (count > 0) {
                    Toast.makeText(this, R.string.toast_added, Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
        });
    }

    private boolean importOne(Uri uri) throws Exception {
        String name = PdfUtils.displayName(this, uri);
        String base = PdfUtils.stripPdfExt(name);
        String unique = PdfUtils.uniqueName(db, currentFolderId, base,
                LibraryItem.Type.DOCUMENT);

        File dest = DbHelper.newDocFile(this, currentFolderPath());
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            PdfUtils.copyStream(in, out);
        }
        if (!PdfUtils.looksLikePdf(dest)) {
            dest.delete();
            runOnUiThread(() -> Toast.makeText(this, R.string.err_not_pdf,
                    Toast.LENGTH_SHORT).show());
            return false;
        }
        db.insertItem(currentFolderId, LibraryItem.Type.DOCUMENT, unique,
                dest.getAbsolutePath(), dest.length());
        return true;
    }

    private void persistIfNeeded(Uri uri) {
        try {
            int flags = getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
            // not all providers allow persistence; the copy below still works
        }
    }

    /** Absolute path of the current folder, or null at root. */
    @Nullable
    private String currentFolderPath() {
        if (currentFolderId == null) return null;
        LibraryItem item = db.getItem(currentFolderId);
        return item == null ? null : item.filePath;
    }

    // ------------------------------------------------------------------ incoming intents

    private void handleIncomingIntent(Intent intent) {
        if (intent == null || intent.getData() == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_VIEW.equals(action) && !Intent.ACTION_SEND.equals(action)) return;
        Uri uri = Intent.ACTION_SEND.equals(action)
                ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData();
        if (uri == null) return;
        List<Uri> single = new ArrayList<>();
        single.add(uri);
        importUris(single);
    }

    // ------------------------------------------------------------------ folder ops

    private void promptNewFolder() {
        EditText input = new EditText(this);
        input.setHint(R.string.folder_name);
        input.setSingleLine();

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_folder)
                .setView(input)
                .setPositiveButton(R.string.create, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) return;
                    String unique = PdfUtils.uniqueName(db, currentFolderId, name,
                            LibraryItem.Type.FOLDER);
                    File dir = DbHelper.newFolderDir(this);
                    db.insertItem(currentFolderId, LibraryItem.Type.FOLDER, unique,
                            dir.getAbsolutePath(), 0);
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ adapter callbacks

    @Override
    public void onOpen(LibraryItem item) {
        if (item.type == LibraryItem.Type.FOLDER) {
            currentFolderId = item.id;
            updateToolbar();
            refresh();
        } else {
            openDocument(item);
        }
    }

    private void openDocument(LibraryItem item) {
        File f = new File(item.filePath);
        if (!f.exists()) {
            Toast.makeText(this, R.string.err_open_pdf, Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, PdfViewerActivity.class);
        i.putExtra(PdfViewerActivity.EXTRA_FILE_PATH, f.getAbsolutePath());
        i.putExtra(PdfViewerActivity.EXTRA_DISPLAY_NAME, item.displayName);
        startActivity(i);
    }

    @Override
    public void onRename(LibraryItem item) {
        EditText input = new EditText(this);
        input.setText(item.displayName);
        input.setSelectAllOnFocus(true);
        input.setSingleLine();

        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty() || name.equals(item.displayName)) return;
                    db.renameItem(item.id, name);
                    Toast.makeText(this, R.string.msg_renamed, Toast.LENGTH_SHORT).show();
                    refresh();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onDelete(LibraryItem item) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete_title)
                .setMessage(getString(R.string.confirm_delete_msg, item.displayName))
                .setPositiveButton(R.string.delete, (d, w) -> deleteRecursive(item))
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void deleteRecursive(LibraryItem item) {
        for (String id : db.collectSubtreeIds(item.id)) {
            LibraryItem child = db.getItem(id);
            if (child == null) continue;
            if (child.type == LibraryItem.Type.DOCUMENT) {
                new File(child.filePath).delete();
            }
            db.deleteItemRow(id);
        }
        if (item.type == LibraryItem.Type.FOLDER) {
            deleteDirRecursive(new File(item.filePath));
        }
        Toast.makeText(this, R.string.msg_deleted, Toast.LENGTH_SHORT).show();
        refresh();
    }

    private static void deleteDirRecursive(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirRecursive(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    @Override
    public void onMove(LibraryItem item) {
        showFolderChooser(item);
    }

    private void showFolderChooser(LibraryItem moving) {
        List<String> labels = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        labels.add(getString(R.string.root_folder));
        ids.add(null);
        collectFolderOptions(null, "", labels, ids, moving);

        String[] arr = labels.toArray(new String[0]);
        new AlertDialog.Builder(this)
                .setTitle(R.string.select_destination)
                .setItems(arr, (d, which) -> {
                    String targetId = ids.get(which);
                    if (targetId != null && db.isInSubtree(moving.id, targetId)) {
                        Toast.makeText(this, "Cannot move a folder into itself.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performMove(moving, targetId);
                })
                .show();
    }

    private void collectFolderOptions(String parentId, String prefix,
                                      List<String> labels, List<String> ids,
                                      LibraryItem moving) {
        for (LibraryItem child : db.getChildren(parentId)) {
            if (child.type != LibraryItem.Type.FOLDER) continue;
            if (child.id.equals(moving.id)) continue;           // cannot move into self
            if (moving.type == LibraryItem.Type.FOLDER
                    && db.isInSubtree(moving.id, child.id)) continue;
            labels.add(prefix + child.displayName);
            ids.add(child.id);
            collectFolderOptions(child.id, prefix + child.displayName + " / ",
                    labels, ids, moving);
        }
    }

    private void performMove(LibraryItem item, String targetFolderId) {
        if (targetFolderId != null && targetFolderId.equals(item.parentId)) {
            return; // already there
        }
        String newPath = relocate(item, targetFolderId);
        if (newPath == null) return;              // move failed, keep state untouched
        db.updatePath(item.id, newPath);
        db.moveItem(item.id, targetFolderId);
        if (item.type == LibraryItem.Type.FOLDER) {
            fixChildPaths(new File(newPath), item.id);
        }
        Toast.makeText(this, R.string.msg_moved, Toast.LENGTH_SHORT).show();
        refresh();
    }

    /** After a folder moved on disk, rewrite stored paths of all descendants. */
    private void fixChildPaths(File folderDir, String folderId) {
        for (LibraryItem child : db.getChildren(folderId)) {
            File actual = new File(folderDir, new File(child.filePath).getName());
            if (!actual.getAbsolutePath().equals(child.filePath)) {
                db.updatePath(child.id, actual.getAbsolutePath());
            }
            if (child.type == LibraryItem.Type.FOLDER) {
                fixChildPaths(actual, child.id);
            }
        }
    }

    /** Physically move an item's file/dir under the new parent folder; returns new path. */
    @Nullable
    private String relocate(LibraryItem item, String targetFolderId) {
        String targetPath = (targetFolderId == null)
                ? DbHelper.libraryRoot(this).getAbsolutePath()
                : db.getItem(targetFolderId).filePath;
        File src = new File(item.filePath);
        // Keep the obfuscated on-disk name (f_<uuid>.pdf / d_<uuid>) — display names
        // live only in the database, so no collisions are possible on disk.
        File dstFile = new File(targetPath, src.getName());
        if (!dstFile.getParentFile().exists()) dstFile.getParentFile().mkdirs();
        if (src.renameTo(dstFile)) {
            return dstFile.getAbsolutePath();
        }
        // Fallback: copy + delete (rename across devices can fail)
        try {
            if (item.type == LibraryItem.Type.DOCUMENT) {
                copyFile(src, dstFile);
                src.delete();
            } else {
                copyDir(src, dstFile);
                deleteDirRecursive(src);
            }
            return dstFile.getAbsolutePath();
        } catch (Exception e) {
            Toast.makeText(this, "Move failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    @Override
    public void onOpenWith(LibraryItem item) {
        try {
            Uri uri = Uri.parse("content://" + LibraryFileProvider.AUTHORITY
                    + "/doc/" + item.id);
            Intent view = new Intent(Intent.ACTION_VIEW);
            view.setDataAndType(uri, "application/pdf");
            view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(view, getString(R.string.open_with)));
        } catch (Exception e) {
            Toast.makeText(this, "No app found that can open PDFs.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ permissions (API <=32)

    private void maybeRequestLegacyPermission() {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return; // API 33+: no perm needed
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED) return;
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_STORAGE_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE_PERM && grantResults.length > 0
                && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.storage_permission_rationale,
                    Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ file helpers

    private static void copyFile(File src, File dst) throws Exception {
        try (InputStream in = new java.io.FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            PdfUtils.copyStream(in, out);
        }
    }

    private static void copyDir(File src, File dst) throws Exception {
        if (!dst.exists()) dst.mkdirs();
        File[] files = src.listFiles();
        if (files == null) return;
        for (File f : files) {
            File target = new File(dst, f.getName());
            if (f.isDirectory()) copyDir(f, target);
            else copyFile(f, target);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }
}
