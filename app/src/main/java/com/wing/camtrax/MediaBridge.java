package com.wing.camtrax;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Bitmap;
import android.util.Size;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.StatFs;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Called from the page as window.AndroidBridge.
 * Clips go to Movies/CamTrax, photos to Pictures/CamTrax (visible in Gallery).
 * Loop clips are named loop_*; locked clips keep_* and are never auto-deleted.
 */
public class MediaBridge {
    private static final String FOLDER = "CamTrax";
    private static final long MIN_FREE = 300L * 1024 * 1024; // always leave 300 MB free on the phone

    private final Activity activity;
    private final ContentResolver resolver;
    private final Map<String, Rec> open = new HashMap<>();

    private static class Rec { Uri uri; OutputStream os; String name; }

    private WebView webView;
    void setWebView(WebView w) { webView = w; }

    /**
     * Reads a licence plate with an online service on a background thread (never blocks recording),
     * then calls window.__plateResult(id, body, httpCode) in the page.
     */
    @JavascriptInterface
    public void cloudPlate(String id, String provider, String key, String b64) {
        new Thread(() -> {
            int code = 0;
            String body;
            try {
                byte[] img = Base64.decode(b64, Base64.DEFAULT);
                boolean pr = "pr".equals(provider);
                URL url = new URL(pr ? "https://api.platerecognizer.com/v1/plate-reader/"
                                     : "https://api.iapp.co.th/license-plate-recognition/file");
                String boundary = "----CamTrax" + System.nanoTime();
                HttpURLConnection c = (HttpURLConnection) url.openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(10000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                if (pr) c.setRequestProperty("Authorization", "Token " + key);
                else c.setRequestProperty("apikey", key);
                try (OutputStream os = c.getOutputStream()) {
                    StringBuilder sb = new StringBuilder();
                    if (pr) sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"regions\"\r\n\r\nth\r\n");
                    sb.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                      .append(pr ? "upload" : "file").append("\"; filename=\"plate.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n");
                    os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    os.write(img);
                    os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                }
                code = c.getResponseCode();
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                body = in == null ? "" : readAll(in);
                c.disconnect();
            } catch (Exception e) {
                body = "{\"error\":" + JSONObject.quote(String.valueOf(e)) + "}";
                code = 0;
            }
            final String js = "window.__plateResult&&window.__plateResult(" + JSONObject.quote(id) + ","
                    + JSONObject.quote(body) + "," + code + ")";
            final WebView w = webView;
            if (w != null) w.post(() -> w.evaluateJavascript(js, null));
        }).start();
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    MediaBridge(Activity a) {
        activity = a;
        resolver = a.getContentResolver();
    }

    private Uri downloadCollection() { return MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }

    /** Delete the .gpx / .plates.csv files that belong to a deleted clip. */
    private void deleteCompanions(String clipName) {
        String base = clipName.replaceFirst("\\.[^.]+$", "");
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = { Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%", base + ".%" };
        try { resolver.delete(downloadCollection(), sel, args); } catch (Exception ignored) { }
    }

    private Uri videoCollection() { return MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }

    @JavascriptInterface
    public synchronized String beginFile(String name, String mime, double quotaBytes, double reserveBytes) {
        try {
            enforceQuota((long) quotaBytes, (long) reserveBytes);
            if (freeBytes() < MIN_FREE + (long) reserveBytes / 2) return "";
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(videoCollection(), v);
            if (uri == null) return "";
            Rec r = new Rec();
            r.uri = uri; r.name = name; r.os = resolver.openOutputStream(uri);
            open.put(name, r);
            return name;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean appendChunk(String id, String b64) {
        Rec r;
        synchronized (this) { r = open.get(id); }
        if (r == null) return false;
        try {
            byte[] data = Base64.decode(b64, Base64.DEFAULT);
            synchronized (r) { r.os.write(data); }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public synchronized String finishFile(String id, boolean keep) {
        Rec r = open.remove(id);
        if (r == null) return "";
        try {
            synchronized (r) { r.os.flush(); r.os.close(); }
            ContentValues v = new ContentValues();
            String finalName = keep ? r.name.replaceFirst("^loop_", "keep_") : r.name;
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, finalName);
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(r.uri, v, null, null);
            return finalName;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean savePhoto(String name, String b64) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + FOLDER);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
            if (uri == null) return false;
            try (OutputStream os = resolver.openOutputStream(uri)) {
                os.write(Base64.decode(b64, Base64.DEFAULT));
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** GPX tracks and plate CSVs go to Download/CamTrax. */
    @JavascriptInterface
    public boolean saveText(String name, String mime, String content) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER);
            Uri uri = resolver.insert(downloadCollection(), v);
            if (uri == null) return false;
            try (OutputStream os = resolver.openOutputStream(uri)) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void keepScreenOn(boolean on) {
        activity.runOnUiThread(() -> {
            if (on) activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else activity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        });
    }

    @JavascriptInterface
    public synchronized String getUsage() {
        try {
            long loopBytes = 0, keepBytes = 0;
            int loopCount = 0, keepCount = 0;
            for (Item it : listClips()) {
                if (it.name.startsWith("loop_")) { loopBytes += it.size; loopCount++; }
                else if (it.name.startsWith("keep_")) { keepBytes += it.size; keepCount++; }
            }
            JSONObject o = new JSONObject();
            o.put("loopBytes", loopBytes); o.put("loopCount", loopCount);
            o.put("keepBytes", keepBytes); o.put("keepCount", keepCount);
            o.put("freeBytes", freeBytes());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }


    // ===================== In-app gallery =====================
    private Uri imageCollection() { return MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY); }
    private Uri itemUri(String t, long id) { return ContentUris.withAppendedId("v".equals(t) ? videoCollection() : imageCollection(), id); }

    /** All CamTrax photos and videos, newest first. */
    @JavascriptInterface
    public synchronized String listMedia() {
        JSONArray out = new JSONArray();
        try {
            addItems(out, "v", videoCollection(), Environment.DIRECTORY_MOVIES);
            addItems(out, "i", imageCollection(), Environment.DIRECTORY_PICTURES);
            // sort by date desc
            List<JSONObject> list = new ArrayList<>();
            for (int i = 0; i < out.length(); i++) list.add(out.getJSONObject(i));
            list.sort((a, b) -> Long.compare(b.optLong("date"), a.optLong("date")));
            return new JSONArray(list).toString();
        } catch (Exception e) {
            return out.toString();
        }
    }

    private void addItems(JSONArray out, String t, Uri coll, String dir) throws Exception {
        boolean video = "v".equals(t);
        String[] cols = video
                ? new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.Video.VideoColumns.DURATION }
                : new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = { dir + "/" + FOLDER + "%" };
        try (Cursor c = resolver.query(coll, cols, sel, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                JSONObject o = new JSONObject();
                o.put("t", t); o.put("id", c.getLong(0)); o.put("name", c.getString(1));
                o.put("size", c.getLong(2)); o.put("date", c.getLong(3));
                if (video) o.put("dur", c.getLong(4));
                out.put(o);
            }
        }
    }

    @JavascriptInterface
    public synchronized boolean deleteMedia(String t, double idD) {
        long id = (long) idD;
        try {
            String name = nameOf(itemUri(t, id));
            int n = resolver.delete(itemUri(t, id), null, null);
            if (n > 0 && "v".equals(t) && name != null) deleteCompanions(name);
            return n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Lock (keep_) or unlock (loop_) a clip; companion files are renamed too. Returns the new name. */
    @JavascriptInterface
    public synchronized String setKeep(double idD, boolean keep) {
        long id = (long) idD;
        try {
            Uri uri = itemUri("v", id);
            String old = nameOf(uri);
            if (old == null) return "";
            String neu = keep ? old.replaceFirst("^loop_", "keep_") : old.replaceFirst("^keep_", "loop_");
            if (neu.equals(old)) return old;
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, neu);
            if (resolver.update(uri, v, null, null) <= 0) return "";
            renameCompanions(old, neu);
            return neu;
        } catch (Exception e) {
            return "";
        }
    }

    @JavascriptInterface
    public boolean openExternal(String t, double idD) {
        try {
            Uri uri = itemUri(t, (long) idD);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, resolver.getType(uri));
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            if (i.resolveActivity(activity.getPackageManager()) == null) return false;
            activity.runOnUiThread(() -> activity.startActivity(i));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public void shareMedia(String t, double idD) {
        try {
            Uri uri = itemUri(t, (long) idD);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType(resolver.getType(uri));
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent chooser = Intent.createChooser(i, "แชร์ไฟล์");
            activity.runOnUiThread(() -> activity.startActivity(chooser));
        } catch (Exception ignored) { }
    }

    private String nameOf(Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{ MediaStore.MediaColumns.DISPLAY_NAME }, null, null, null)) {
            return c != null && c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private void renameCompanions(String oldClip, String newClip) {
        String oldBase = oldClip.replaceFirst("\\.[^.]+$", ""), newBase = newClip.replaceFirst("\\.[^.]+$", "");
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND " + MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ?";
        String[] args = { Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "%", oldBase + ".%" };
        try (Cursor c = resolver.query(downloadCollection(), new String[]{ MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME }, sel, args, null)) {
            if (c == null) return;
            while (c.moveToNext()) {
                String n = c.getString(1);
                if (n == null || !n.startsWith(oldBase + ".")) continue;
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, newBase + n.substring(oldBase.length()));
                resolver.update(ContentUris.withAppendedId(downloadCollection(), c.getLong(0)), v, null, null);
            }
        } catch (Exception ignored) { }
    }

    // ---- serve files to the WebView: https://appassets.androidplatform.net/camtrax/{media|thumb}/{v|i}/{id}
    private static final Pattern RANGE = Pattern.compile("bytes=(\\d+)-(\\d*)");

    /** True when the APK carries the AI models (built with app/bundle-models.sh). */
    @JavascriptInterface
    public boolean hasModels() {
        try (InputStream in = activity.getAssets().open("models/coco/model.json")) { return true; }
        catch (Exception e) { return false; }
    }

    private static String mimeFor(String name) {
        if (name.endsWith(".js")) return "text/javascript";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".wasm")) return "application/wasm";
        if (name.endsWith(".gz")) return "application/gzip";
        return "application/octet-stream";
    }

    WebResourceResponse serve(WebResourceRequest req) {
        Uri u = req.getUrl();
        if (!"appassets.androidplatform.net".equals(u.getHost())) return null;
        List<String> seg = u.getPathSegments();
        if (seg.size() < 3 || !"camtrax".equals(seg.get(0))) return null;
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        // bundled AI models: /camtrax/models/<path>
        if ("models".equals(seg.get(1))) {
            String rel = String.join("/", seg.subList(2, seg.size()));
            if (rel.contains("..")) return null;
            try {
                InputStream in = activity.getAssets().open("models/" + rel);
                h.put("Cache-Control", "max-age=31536000");
                return new WebResourceResponse(mimeFor(rel), null, 200, "OK", h, in);
            } catch (Exception e) {
                return new WebResourceResponse("text/plain", null, 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
            }
        }
        if (seg.size() != 4) return null;
        h.put("Cache-Control", "no-store");
        try {
            Uri item = itemUri(seg.get(2), Long.parseLong(seg.get(3)));
            if ("thumb".equals(seg.get(1))) {
                Bitmap b = resolver.loadThumbnail(item, new Size(400, 225), null);
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                b.compress(Bitmap.CompressFormat.JPEG, 80, bo);
                return new WebResourceResponse("image/jpeg", null, 200, "OK", h, new ByteArrayInputStream(bo.toByteArray()));
            }
            String mime = resolver.getType(item);
            ParcelFileDescriptor pfd = resolver.openFileDescriptor(item, "r");
            long size = pfd.getStatSize();
            FileInputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd);
            h.put("Accept-Ranges", "bytes");
            String range = null;
            for (Map.Entry<String, String> e : req.getRequestHeaders().entrySet())
                if ("range".equalsIgnoreCase(e.getKey())) range = e.getValue();
            Matcher m = range != null ? RANGE.matcher(range) : null;
            if (m != null && m.find() && size > 0) {
                long start = Long.parseLong(m.group(1));
                long end = m.group(2).isEmpty() ? size - 1 : Math.min(Long.parseLong(m.group(2)), size - 1);
                if (start > end) start = 0;
                in.getChannel().position(start);
                h.put("Content-Range", "bytes " + start + "-" + end + "/" + size);
                h.put("Content-Length", String.valueOf(end - start + 1));
                return new WebResourceResponse(mime, null, 206, "Partial Content", h, new Limited(in, end - start + 1));
            }
            h.put("Content-Length", String.valueOf(size));
            return new WebResourceResponse(mime, null, 200, "OK", h, in);
        } catch (Exception e) {
            return new WebResourceResponse("text/plain", null, 404, "Not Found", h, new ByteArrayInputStream(new byte[0]));
        }
    }

    private static class Limited extends FilterInputStream {
        private long left;
        Limited(InputStream in, long n) { super(in); left = n; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int b = super.read(); if (b >= 0) left--; return b; }
        @Override public int read(byte[] buf, int off, int len) throws IOException {
            if (left <= 0) return -1;
            int r = super.read(buf, off, (int) Math.min(len, left));
            if (r > 0) left -= r;
            return r;
        }
    }

    // ---- helpers ----
    private static class Item { long id; String name; long size; }

    private List<Item> listClips() {
        List<Item> out = new ArrayList<>();
        String[] cols = { MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE };
        String sel = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = { Environment.DIRECTORY_MOVIES + "/" + FOLDER + "%" };
        try (Cursor c = resolver.query(videoCollection(), cols, sel, args, MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Item it = new Item();
                it.id = c.getLong(0); it.name = c.getString(1); it.size = c.getLong(2);
                if (it.name == null) continue;
                if (it.size <= 0) it.size = sizeOf(ContentUris.withAppendedId(videoCollection(), it.id));
                out.add(it);
            }
        }
        return out;
    }

    private long sizeOf(Uri uri) {
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            return fd != null ? fd.getStatSize() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private long freeBytes() {
        try {
            return new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
        } catch (Exception e) {
            return Long.MAX_VALUE;
        }
    }

    /** Delete oldest loop_ clips until (used + reserve) fits the quota and the phone keeps MIN_FREE. */
    private void enforceQuota(long quota, long reserve) {
        List<Item> loops = new ArrayList<>();
        long used = 0;
        for (Item it : listClips()) if (it.name.startsWith("loop_")) { loops.add(it); used += it.size; }
        long free = freeBytes();
        int i = 0;
        while (i < loops.size() && (used + reserve > quota || free - reserve < MIN_FREE)) {
            Item it = loops.get(i++);
            try {
                int n = resolver.delete(ContentUris.withAppendedId(videoCollection(), it.id), null, null);
                if (n > 0) { used -= it.size; free += it.size; deleteCompanions(it.name); }
            } catch (Exception ignored) { }
        }
    }
}
