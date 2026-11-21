package com.wing.camtrax;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.StatFs;
import android.provider.MediaStore;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Storage helpers shared by the page bridge and the background recorder. */
final class ClipStore {
    static final String FOLDER = "CamTrax";
    static final long MIN_FREE = 300L * 1024 * 1024;

    private ClipStore() { }

    static Uri videos() { return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }
    static Uri downloads() { return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }

    static long freeBytes() {
        try { return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes(); }
        catch (Exception e) { return Long.MAX_VALUE; }
    }

    /** Delete oldest loop_ clips until (used + reserve) fits the quota and the phone keeps MIN_FREE. */
    static void enforceQuota(ContentResolver r, long quota, long reserve) {
        List<long[]> loops = new ArrayList<>();   // {id, size}
        List<String> names = new ArrayList<>();
        long used = 0;
        String[] cols = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = { Environment.DIRECTORY_MOVIES + "/" + FOLDER + "%" };
        try (Cursor c = r.query(videos(), cols, sel, args, MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
            if (c == null) return;
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n == null || !n.startsWith("loop_")) continue;
                loops.add(new long[]{ c.getLong(0), Math.max(0, c.getLong(2)) });
                names.add(n);
                used += Math.max(0, c.getLong(2));
            }
        } catch (Exception e) { return; }
        long free = freeBytes();
        for (int i = 0; i < loops.size() && (used + reserve > quota || free - reserve < MIN_FREE); i++) {
            try {
                if (r.delete(ContentUris.withAppendedId(videos(), loops.get(i)[0]), null, null) > 0) {
                    used -= loops.get(i)[1];
                    free += loops.get(i)[1];
                    deleteCompanions(r, names.get(i));
                }
            } catch (Exception ignored) { }
        }
    }

    static void deleteCompanions(ContentResolver r, String clipName) {
        String base = clipName.replaceFirst("\\.[^.]+$", "");
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = { Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%", base + ".%" };
        try { r.delete(downloads(), sel, args); } catch (Exception ignored) { }
    }

    static boolean saveText(ContentResolver r, String name, String mime, String content) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = r.insert(downloads(), v);
            if (uri == null) return false;
            try (OutputStream os = r.openOutputStream(uri)) {
                if (os == null) return false;
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
