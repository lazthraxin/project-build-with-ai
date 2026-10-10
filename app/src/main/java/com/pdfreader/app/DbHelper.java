package com.pdfreader.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * SQLite metadata store for the managed PDF library.
 * Files live under <filesDir>/library with obfuscated names (content.pdf);
 * display names, folder structure and relationships are kept in this database.
 */
public class DbHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "pdf_library.db";
    private static final int DB_VERSION = 1;

    public static final String T_ITEMS = "items";
    public static final String C_ID = "_id";
    public static final String C_PARENT = "parent_id";
    public static final String C_TYPE = "type";        // 0 folder, 1 document
    public static final String C_NAME = "name";
    public static final String C_PATH = "path";
    public static final String C_DATE = "date_added";
    public static final String C_SIZE = "size";

    public DbHelper(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + T_ITEMS + " (" +
                C_ID + " TEXT PRIMARY KEY," +
                C_PARENT + " TEXT," +
                C_TYPE + " INTEGER NOT NULL," +
                C_NAME + " TEXT NOT NULL," +
                C_PATH + " TEXT," +
                C_DATE + " INTEGER NOT NULL," +
                C_SIZE + " INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE INDEX idx_parent ON " + T_ITEMS + "(" + C_PARENT + ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS " + T_ITEMS);
        onCreate(db);
    }

    // ------------------------------------------------------------------ crud

    public String insertItem(String parentId, LibraryItem.Type type, String name,
                             String path, long size) {
        String id = UUID.randomUUID().toString();
        ContentValues v = new ContentValues();
        v.put(C_ID, id);
        if (parentId != null) v.put(C_PARENT, parentId); else v.putNull(C_PARENT);
        v.put(C_TYPE, type == LibraryItem.Type.FOLDER ? 0 : 1);
        v.put(C_NAME, name);
        v.put(C_PATH, path);
        v.put(C_DATE, System.currentTimeMillis());
        v.put(C_SIZE, size);
        getWritableDatabase().insert(T_ITEMS, null, v);
        return id;
    }

    /** Reverse lookup: library item id whose stored path equals the given path. */
    public static String getIdForPath(Context ctx, String path) {
        DbHelper db = new DbHelper(ctx);
        Cursor c = db.getReadableDatabase().query(T_ITEMS, new String[]{C_ID},
                C_PATH + "=?", new String[]{path}, null, null, null);
        try {
            if (c.moveToFirst()) return c.getString(0);
            return null;
        } finally {
            c.close();
        }
    }

    public LibraryItem getItem(String id) {
        Cursor c = getReadableDatabase().query(T_ITEMS, null,
                C_ID + "=?", new String[]{id}, null, null, null);
        try {
            if (c.moveToFirst()) return fromCursor(c);
            return null;
        } finally {
            c.close();
        }
    }

    public List<LibraryItem> getChildren(String parentId) {
        List<LibraryItem> out = new ArrayList<>();
        Cursor c;
        if (parentId == null) {
            c = getReadableDatabase().query(T_ITEMS, null,
                    C_PARENT + " IS NULL", null, null, null,
                    C_TYPE + " ASC, " + C_NAME + " COLLATE NOCASE ASC");
        } else {
            c = getReadableDatabase().query(T_ITEMS, null,
                    C_PARENT + "=?", new String[]{parentId}, null, null,
                    C_TYPE + " ASC, " + C_NAME + " COLLATE NOCASE ASC");
        }
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<LibraryItem> getAllItems() {
        List<LibraryItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_ITEMS, null, null, null, null, null, null);
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Search by name across the whole library. */
    public List<LibraryItem> search(String query) {
        List<LibraryItem> out = new ArrayList<>();
        Cursor c = getReadableDatabase().query(T_ITEMS, null,
                C_NAME + " LIKE ?", new String[]{"%" + query + "%"}, null, null,
                C_TYPE + " ASC, " + C_NAME + " COLLATE NOCASE ASC");
        try {
            while (c.moveToNext()) out.add(fromCursor(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void renameItem(String id, String newName) {
        ContentValues v = new ContentValues();
        v.put(C_NAME, newName);
        getWritableDatabase().update(T_ITEMS, v, C_ID + "=?", new String[]{id});
    }

    public void updatePath(String id, String newPath) {
        ContentValues v = new ContentValues();
        v.put(C_PATH, newPath);
        getWritableDatabase().update(T_ITEMS, v, C_ID + "=?", new String[]{id});
    }

    public void moveItem(String id, String newParentId) {
        ContentValues v = new ContentValues();
        if (newParentId != null) v.put(C_PARENT, newParentId); else v.putNull(C_PARENT);
        getWritableDatabase().update(T_ITEMS, v, C_ID + "=?", new String[]{id});
    }

    public void deleteItemRow(String id) {
        getWritableDatabase().delete(T_ITEMS, C_ID + "=?", new String[]{id});
    }

    /** Collect ids of an item and all its descendants (for recursive delete). */
    public List<String> collectSubtreeIds(String rootId) {
        List<String> ids = new ArrayList<>();
        collect(rootId, ids);
        return ids;
    }

    private void collect(String id, List<String> acc) {
        acc.add(id);
        Cursor c = getReadableDatabase().query(T_ITEMS, new String[]{C_ID},
                C_PARENT + "=?", new String[]{id}, null, null, null);
        try {
            while (c.moveToNext()) collect(c.getString(0), acc);
        } finally {
            c.close();
        }
    }

    /** True if candidateId is inside the subtree of folderId (prevents cyclic moves). */
    public boolean isInSubtree(String folderId, String candidateId) {
        return collectSubtreeIds(folderId).contains(candidateId);
    }

    public boolean nameExists(String parentId, String name, LibraryItem.Type type,
                              String excludeId) {
        String sel = (parentId == null ? C_PARENT + " IS NULL" : C_PARENT + "=?")
                + " AND " + C_NAME + "=? AND " + C_TYPE + "=?";
        String[] args = parentId == null
                ? new String[]{name, type == LibraryItem.Type.FOLDER ? "0" : "1"}
                : new String[]{parentId, name, type == LibraryItem.Type.FOLDER ? "0" : "1"};
        Cursor c = getReadableDatabase().query(T_ITEMS, new String[]{C_ID},
                sel, args, null, null, null);
        try {
            while (c.moveToNext()) {
                if (!c.getString(0).equals(excludeId)) return true;
            }
            return false;
        } finally {
            c.close();
        }
    }

    private static LibraryItem fromCursor(Cursor c) {
        return new LibraryItem(
                c.getString(c.getColumnIndexOrThrow(C_ID)),
                c.isNull(c.getColumnIndexOrThrow(C_PARENT))
                        ? null : c.getString(c.getColumnIndexOrThrow(C_PARENT)),
                c.getInt(c.getColumnIndexOrThrow(C_TYPE)) == 0
                        ? LibraryItem.Type.FOLDER : LibraryItem.Type.DOCUMENT,
                c.getString(c.getColumnIndexOrThrow(C_NAME)),
                c.getString(c.getColumnIndexOrThrow(C_PATH)),
                c.getLong(c.getColumnIndexOrThrow(C_DATE)),
                c.getLong(c.getColumnIndexOrThrow(C_SIZE)));
    }

    // ------------------------------------------------------------------ storage layout

    /** Root directory holding all managed library content. */
    public static File libraryRoot(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "library");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Directory backing a new managed folder (unique, obfuscated name). */
    public static File newFolderDir(Context ctx) {
        File dir = new File(libraryRoot(ctx), "d_" + UUID.randomUUID());
        dir.mkdirs();
        return dir;
    }

    /** Destination file for an imported pdf: unique name inside the parent folder dir. */
    public static File newDocFile(Context ctx, String parentFolderPath) {
        File parent = (parentFolderPath == null)
                ? libraryRoot(ctx) : new File(parentFolderPath);
        if (!parent.exists()) parent.mkdirs();
        return new File(parent, "f_" + UUID.randomUUID() + ".pdf");
    }
}
