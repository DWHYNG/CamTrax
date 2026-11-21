package com.wing.camtrax;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.MediaStore;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FallbackStrategy;
import androidx.camera.video.MediaStoreOutputOptions;
import androidx.camera.video.PendingRecording;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recorder;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleService;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Keeps the dashcam recording when the screen is off or another app is in front.
 *
 * Life cycle:
 *  - "armed": started (as a foreground service) when the page starts recording, while the app is visible.
 *    Starting it while visible is what lets it use the camera, mic and GPS later from the background.
 *  - "active": the page has handed over (screen off / app hidden) → CameraX loop-records video + audio
 *    into Movies/CamTrax with the same loop_ naming and quota as the page, plus a .gpx per clip.
 *  - back in the app → stop, finalize the clip, release the camera, then the page takes over again.
 */
public class BgRecordService extends LifecycleService {
    static final String CHANNEL = "camtrax_rec";
    static final int NOTIF_ID = 7;
    static BgRecordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private long segMs = 180_000, quota = 4_000_000_000L;
    private boolean audio = true, gps = true, front = false, hd = false;

    private ProcessCameraProvider provider;
    private Recorder recorder;
    private Recording recording;
    private boolean active, stopping;
    private int bindTries;
    private String currentName;
    private int clipsThisRun;
    private Runnable onStopped;
    private PowerManager.WakeLock wake;

    private LocationManager lm;
    private final List<double[]> track = new ArrayList<>();   // lat, lon, alt, time, speed
    private final LocationListener locListener = new LocationListener() {
        @Override public void onLocationChanged(@NonNull Location l) {
            synchronized (track) {
                track.add(new double[]{ l.getLatitude(), l.getLongitude(), l.hasAltitude() ? l.getAltitude() : Double.NaN,
                        l.getTime(), l.hasSpeed() ? l.getSpeed() : Double.NaN });
            }
        }
        @Override public void onStatusChanged(String p, int s, Bundle b) { }
        @Override public void onProviderEnabled(@NonNull String p) { }
        @Override public void onProviderDisabled(@NonNull String p) { }
    };

    private final Runnable rotate = () -> { if (active && !stopping && recording != null) recording.stop(); };

    // ---------------- control from the app ----------------

    /** Start (or update) the armed service. Must be called while the app is visible. */
    static void arm(Context ctx, String json) {
        Intent i = new Intent(ctx, BgRecordService.class).putExtra("cfg", json);
        ContextCompat.startForegroundService(ctx, i);
    }

    static void disarm(Context ctx) {
        BgRecordService s = instance;
        if (s != null) s.end(() -> s.stopSelf());
        else ctx.stopService(new Intent(ctx, BgRecordService.class));
    }

    static boolean isArmed() { return instance != null; }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        instance = this;
        if (intent != null && intent.getStringExtra("cfg") != null) {
            try {
                JSONObject c = new JSONObject(intent.getStringExtra("cfg"));
                segMs = Math.max(30_000, (long) (c.optDouble("segSec", 180) * 1000));
                quota = (long) c.optDouble("quota", 4e9);
                audio = c.optBoolean("audio", true);
                gps = c.optBoolean("gps", true);
                front = "user".equals(c.optString("facing"));
                hd = "1080".equals(c.optString("res"));
            } catch (Exception ignored) { }
        }
        startInForeground("พร้อมอัดต่อเมื่อปิดจอ");
        return START_NOT_STICKY;
    }

    @Nullable @Override
    public IBinder onBind(@NonNull Intent intent) { super.onBind(intent); return null; }

    @Override
    public void onDestroy() {
        stopGps();
        if (provider != null) try { provider.unbindAll(); } catch (Exception ignored) { }
        releaseWake();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    private boolean granted(String p) { return ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED; }

    private Notification buildNotif(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "CamTrax recording", NotificationManager.IMPORTANCE_LOW));
        }
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("CamTrax")
                .setContentText(text)
                .setOngoing(true)
                .setContentIntent(pi)
                .build();
    }

    /** Change the notification text without re-entering the foreground (allowed from the background). */
    private void updateNotif(String text) {
        try { ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIF_ID, buildNotif(text)); } catch (Exception ignored) { }
    }

    private void startInForeground(String text) {
        Notification n = buildNotif(text);
        int type = 0;
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION))
            type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
        if (Build.VERSION.SDK_INT >= 30) {
            if (granted(Manifest.permission.CAMERA)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            if (granted(Manifest.permission.RECORD_AUDIO)) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            if (type != 0) startForeground(NOTIF_ID, n, type); else startForeground(NOTIF_ID, n);
        } catch (Exception e) {
            startForeground(NOTIF_ID, n);
        }
    }

    // ---------------- background recording ----------------

    /** The page has let go of the camera: start recording here. */
    void begin() {
        if (active) return;
        active = true; stopping = false;
        if (bindTries == 0) clipsThisRun = 0;
        acquireWake();
        updateNotif("● กำลังอัดเบื้องหลัง (แตะเพื่อกลับเข้าแอป)");
        if (gps) startGps();
        ProcessCameraProvider.getInstance(this).addListener(() -> {
            try {
                provider = ProcessCameraProvider.getInstance(this).get();
                QualitySelector qs = QualitySelector.from(hd ? Quality.FHD : Quality.HD,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD));
                recorder = new Recorder.Builder().setQualitySelector(qs).build();
                VideoCapture<Recorder> vc = VideoCapture.withOutput(recorder);
                provider.unbindAll();
                provider.bindToLifecycle(this, front ? CameraSelector.DEFAULT_FRONT_CAMERA : CameraSelector.DEFAULT_BACK_CAMERA, vc);
                bindTries = 0;
                startSegment();
            } catch (Exception e) {
                // camera still busy (page not released yet): try again shortly
                active = false;
                if (++bindTries <= 10 && !stopping) main.postDelayed(this::begin, 1000);
                else { bindTries = 0; releaseWake(); stopGps(); updateNotif("พร้อมอัดต่อเมื่อปิดจอ"); }
            }
        }, ContextCompat.getMainExecutor(this));
    }

    @SuppressLint("MissingPermission")
    private void startSegment() {
        if (!active || stopping || recorder == null) return;
        long reserve = (long) ((hd ? 6e6 : 3e6) / 8 * (segMs / 1000.0) * 1.15);
        ClipStore.enforceQuota(getContentResolver(), quota, reserve);
        final String name = "loop_" + stamp() + ".mp4";
        currentName = name;
        synchronized (track) { track.clear(); }
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        cv.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/" + ClipStore.FOLDER);
        MediaStoreOutputOptions out = new MediaStoreOutputOptions.Builder(getContentResolver(), ClipStore.videos())
                .setContentValues(cv).build();
        PendingRecording pr = recorder.prepareRecording(this, out);
        if (audio && granted(Manifest.permission.RECORD_AUDIO)) pr = pr.withAudioEnabled();
        recording = pr.start(ContextCompat.getMainExecutor(this), ev -> {
            if (ev instanceof VideoRecordEvent.Finalize) onFinalized(name);
        });
        main.postDelayed(rotate, segMs);
    }

    private void onFinalized(String name) {
        recording = null;
        main.removeCallbacks(rotate);
        clipsThisRun++;
        writeGpx(name);
        if (active && !stopping) { startSegment(); return; }
        // stopping: hand the camera back to the page
        if (provider != null) try { provider.unbindAll(); } catch (Exception ignored) { }
        recorder = null;
        active = false; stopping = false;
        stopGps();
        releaseWake();
        updateNotif("พร้อมอัดต่อเมื่อปิดจอ");
        Runnable r = onStopped; onStopped = null;
        if (r != null) r.run();
    }

    /** Stop background recording (if any) and run done when the camera is free again. */
    void end(Runnable done) {
        if (!active) { if (done != null) done.run(); return; }
        stopping = true; onStopped = done;
        main.removeCallbacks(rotate);
        if (recording != null) recording.stop();
        else { // still opening the camera
            main.postDelayed(() -> { if (stopping && recording == null) { active = false; stopping = false; if (provider != null) provider.unbindAll(); stopGps(); releaseWake(); Runnable r = onStopped; onStopped = null; if (r != null) r.run(); } }, 1500);
        }
    }

    int clipsThisRun() { return clipsThisRun; }
    boolean isActive() { return active; }

    // ---------------- GPS ----------------

    @SuppressLint("MissingPermission")
    private void startGps() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return;
        try {
            lm = (LocationManager) getSystemService(LOCATION_SERVICE);
            String p = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER;
            lm.requestLocationUpdates(p, 1000, 0, locListener, Looper.getMainLooper());
        } catch (Exception ignored) { }
    }

    private void stopGps() {
        try { if (lm != null) lm.removeUpdates(locListener); } catch (Exception ignored) { }
        lm = null;
    }

    private void writeGpx(String clip) {
        List<double[]> pts;
        synchronized (track) { pts = new ArrayList<>(track); track.clear(); }
        if (pts.size() < 2) return;
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        String base = clip.replaceFirst("\\.[^.]+$", "");
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<gpx version=\"1.1\" creator=\"CamTrax\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n<trk><name>")
                .append(base).append("</name><trkseg>\n");
        for (double[] p : pts) {
            sb.append("<trkpt lat=\"").append(p[0]).append("\" lon=\"").append(p[1]).append("\">");
            if (!Double.isNaN(p[2])) sb.append("<ele>").append(String.format(Locale.US, "%.1f", p[2])).append("</ele>");
            sb.append("<time>").append(iso.format(new Date((long) p[3]))).append("</time>");
            if (!Double.isNaN(p[4])) sb.append("<extensions><speed>").append(String.format(Locale.US, "%.2f", p[4])).append("</speed></extensions>");
            sb.append("</trkpt>\n");
        }
        sb.append("</trkseg></trk>\n</gpx>\n");
        ClipStore.saveText(getContentResolver(), base + ".gpx", "application/gpx+xml", sb.toString());
    }

    // ---------------- helpers ----------------

    private static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }

    private void acquireWake() {
        if (wake != null) return;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CamTrax:rec");
        wake.acquire(12 * 60 * 60 * 1000L);
    }

    private void releaseWake() {
        try { if (wake != null && wake.isHeld()) wake.release(); } catch (Exception ignored) { }
        wake = null;
    }
}
