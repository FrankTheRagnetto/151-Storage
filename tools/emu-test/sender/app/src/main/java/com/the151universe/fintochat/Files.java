package com.the151universe.fintochat;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

/** Provider minimo in sola lettura: niente _data, niente delete (come quelli di WhatsApp/Gmail). */
public class Files extends ContentProvider {
    private File file(Uri u) { return new File(getContext().getCacheDir(), u.getLastPathSegment()); }
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) {
        File f = file(u);
        MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        c.addRow(new Object[]{f.getName(), f.length()});
        return c;
    }
    @Override public String getType(Uri u) { return "text/plain"; }
    @Override public ParcelFileDescriptor openFile(Uri u, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("sola lettura");
        return ParcelFileDescriptor.open(file(u), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException("niente delete"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
