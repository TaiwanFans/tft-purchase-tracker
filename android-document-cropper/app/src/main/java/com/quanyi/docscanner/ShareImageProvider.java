package com.quanyi.docscanner;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

public class ShareImageProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    private File resolve(Uri uri) throws FileNotFoundException {
        if (getContext() == null) throw new FileNotFoundException();
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File dir = new File(getContext().getCacheDir(), "shared");
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new FileNotFoundException();
        } catch (Exception e) { throw new FileNotFoundException(); }
        if (!f.exists()) throw new FileNotFoundException();
        return f;
    }

    @Override public String getType(Uri uri) { return "image/jpeg"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        try {
            File f = resolve(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (Exception e) { return null; }
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
}
