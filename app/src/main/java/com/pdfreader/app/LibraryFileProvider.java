package com.pdfreader.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Read-only content provider that exposes managed library files to other apps
 * (e.g. "Open with" / share). URIs: content://com.pdfreader.app.fileprovider/doc/<id>
 */
public class LibraryFileProvider extends ContentProvider {

    public static final String AUTHORITY = "com.pdfreader.app.fileprovider";
    private static final int MATCH_DOC = 1;
    private UriMatcher matcher;

    @Override
    public boolean onCreate() {
        matcher = new UriMatcher(UriMatcher.NO_MATCH);
        matcher.addURI(AUTHORITY, "doc/*", MATCH_DOC);
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (matcher.match(uri) != MATCH_DOC) {
            throw new FileNotFoundException("Unsupported uri: " + uri);
        }
        String id = uri.getLastPathSegment();
        DbHelper db = new DbHelper(getContext());
        LibraryItem item = db.getItem(id);
        if (item == null || item.type != LibraryItem.Type.DOCUMENT) {
            throw new FileNotFoundException("Unknown document id: " + id);
        }
        File f = new File(item.filePath);
        if (!f.exists()) throw new FileNotFoundException("Missing file: " + item.filePath);
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return new MatrixCursor(new String[0]);
    }

    @Override
    public String getType(Uri uri) {
        return "application/pdf";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
