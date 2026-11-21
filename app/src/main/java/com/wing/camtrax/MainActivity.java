package com.wing.camtrax;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

public class MainActivity extends AppCompatActivity {
    private static final int CAMERA_REQ = 1;
    private static final int LOCATION_REQ = 2;
    private GeolocationPermissions.Callback pendingGeo;
    private String pendingGeoOrigin;
    // Bundled copy, used when offline or before GitHub Pages is set up
    private static final String LOCAL_URL = "https://appassets.androidplatform.net/assets/index.html";

    private WebView webView;
    private PermissionRequest pendingRequest;
    private boolean usingFallback = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView = new WebView(this);
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setGeolocationEnabled(true);
        final MediaBridge bridge = new MediaBridge(this);
        webView.addJavascriptInterface(bridge, "AndroidBridge");

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                WebResourceResponse media = bridge.serve(request);   // saved photos/videos for the in-app gallery
                if (media != null) return media;
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
                                        @NonNull WebResourceErrorCompat error) {
                if (request.isForMainFrame()) loadFallback();
            }

            @Override
            public void onReceivedHttpError(@NonNull WebView view, @NonNull WebResourceRequest request,
                                            @NonNull WebResourceResponse response) {
                if (request.isForMainFrame() && response.getStatusCode() >= 400) loadFallback();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (hasLocation()) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeo = callback; pendingGeoOrigin = origin;
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{
                            Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, LOCATION_REQ);
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (allowed(request).length == request.getResources().length) {
                        request.grant(request.getResources());
                    } else {
                        if (pendingRequest != null) pendingRequest.deny();
                        pendingRequest = request;
                        ActivityCompat.requestPermissions(MainActivity.this,
                                new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}, CAMERA_REQ);
                    }
                });
            }
        });

        setContentView(webView);
        if (!hasCamera() || !hasLocation() || !hasMic()) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, CAMERA_REQ);
        }

        String remote = BuildConfig.PAGES_URL;
        if (remote == null || remote.isEmpty()) {
            loadFallback();
        } else {
            // Timestamp makes sure the latest page is fetched, not a cached copy
            webView.loadUrl(remote + "?v=" + System.currentTimeMillis());
        }
    }

    private void loadFallback() {
        if (usingFallback) return;
        usingFallback = true;
        webView.post(() -> webView.loadUrl(LOCAL_URL));
    }

    private boolean hasLocation() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** The subset of a page's camera/mic request that the app currently has permission for. */
    private String[] allowed(PermissionRequest req) {
        java.util.List<String> ok = new java.util.ArrayList<>();
        for (String r : req.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r) && hasCamera()) ok.add(r);
            else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r) && hasMic()) ok.add(r);
        }
        return ok.toArray(new String[0]);
    }

    private boolean hasCamera() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (pendingGeo != null && hasLocation()) {
            pendingGeo.invoke(pendingGeoOrigin, true, false); pendingGeo = null;
        } else if (code == LOCATION_REQ && pendingGeo != null) {
            pendingGeo.invoke(pendingGeoOrigin, false, false); pendingGeo = null;
        }
        if (code == CAMERA_REQ && pendingRequest != null) {
            String[] ok = allowed(pendingRequest);
            if (ok.length > 0) {
                pendingRequest.grant(ok);
            } else {
                pendingRequest.deny();
            }
            pendingRequest = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) webView.goBack(); else super.onBackPressed();
    }
}
