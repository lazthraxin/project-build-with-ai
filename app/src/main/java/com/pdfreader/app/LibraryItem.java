package com.pdfreader.app;

import java.io.File;

/**
 * An item shown in the library: either a managed folder or an imported PDF document.
 */
public class LibraryItem {

    public static final String PREFIX_FOLDER = "folder:";
    public static final String PREFIX_DOC = "doc:";

    public enum Type { FOLDER, DOCUMENT }

    /** Stable id used for parent/child relationships (never changes on rename/move). */
    public final String id;
    /** Folder id of the containing folder, or null when at library root. */
    public final String parentId;
    public final Type type;
    /** Display name (without .pdf extension for documents). */
    public String displayName;
    /** Absolute file path: directory for folders, .pdf file for documents. */
    public String filePath;
    public long dateAdded;
    public long sizeBytes;

    public LibraryItem(String id, String parentId, Type type, String displayName,
                       String filePath, long dateAdded, long sizeBytes) {
        this.id = id;
        this.parentId = parentId;
        this.type = type;
        this.displayName = displayName;
        this.filePath = filePath;
        this.dateAdded = dateAdded;
        this.sizeBytes = sizeBytes;
    }

    /** Uri stored in the database ("folder:<id>" / "doc:<id>") used to build content uris. */
    public String toItemUri() {
        return (type == Type.FOLDER ? PREFIX_FOLDER : PREFIX_DOC) + id;
    }

    public static boolean isFolderUri(String uri) {
        return uri != null && uri.startsWith(PREFIX_FOLDER);
    }

    public static String idFromUri(String uri) {
        if (uri == null) return null;
        if (uri.startsWith(PREFIX_FOLDER)) return uri.substring(PREFIX_FOLDER.length());
        if (uri.startsWith(PREFIX_DOC)) return uri.substring(PREFIX_DOC.length());
        return uri;
    }
}
