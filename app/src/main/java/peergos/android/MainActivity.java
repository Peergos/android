package peergos.android;

import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.util.Size;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
import android.webkit.JavascriptInterface;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AlertDialog;

import com.yubico.yubikit.android.YubiKitManager;
import com.yubico.yubikit.android.transport.nfc.NfcConfiguration;
import com.yubico.yubikit.android.transport.nfc.NfcNotAvailable;
import com.yubico.yubikit.android.transport.usb.UsbConfiguration;
import com.yubico.yubikit.android.transport.usb.DeviceFilter;
import com.yubico.yubikit.android.transport.usb.UsbYubiKeyDevice;
import com.yubico.yubikit.core.YubiKeyDevice;
import com.yubico.yubikit.core.application.CommandException;
import com.yubico.yubikit.core.fido.FidoConnection;
import com.yubico.yubikit.core.smartcard.SmartCardConnection;
import com.yubico.yubikit.fido.Cbor;
import com.yubico.yubikit.fido.ctap.Ctap2Session;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import android.widget.AbsoluteLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.webauthn4j.data.client.Origin;

import org.peergos.util.Futures;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import peergos.server.Builder;
import peergos.server.DirectOnlyStorage;
import peergos.server.JdbcPkiCache;
import peergos.server.Main;
import peergos.server.SyncProperties;
import peergos.server.UserService;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.login.JdbcAccount;
import peergos.server.mutable.JdbcPointerCache;
import peergos.server.net.SyncConfigHandler;
import peergos.server.sql.SqlSupplier;
import peergos.server.storage.FileBlockCache;
import peergos.server.storage.auth.JdbcBatCave;
import peergos.server.sync.SyncConfig;
import peergos.server.sync.SyncRunner;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OfflineCorenode;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.io.ipfs.api.JSONParser;
import peergos.shared.login.OfflineAccountStore;
import peergos.shared.mutable.HttpMutablePointers;
import peergos.shared.mutable.MutablePointersProxy;
import peergos.shared.mutable.OfflinePointerCache;
import peergos.shared.social.HttpSocialNetwork;
import peergos.shared.social.SocialNetworkProxy;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.storage.HttpSpaceUsage;
import peergos.shared.storage.SpaceUsageProxy;
import peergos.shared.storage.UnauthedCachingStorage;
import peergos.shared.storage.auth.BatCave;
import peergos.shared.storage.auth.HttpBatCave;
import peergos.shared.storage.auth.OfflineBatCache;
import peergos.shared.storage.controller.HttpInstanceAdmin;
import peergos.shared.user.Account;
import peergos.shared.user.EntryPoint;
import peergos.shared.user.HttpAccount;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.ServerMessager;
import peergos.shared.user.fs.AbsoluteCapability;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;
import peergos.shared.util.Constants;
import peergos.shared.util.Either;

public class MainActivity extends AppCompatActivity {

    public static final int PORT = 7777;
    public static final long MAX_BLOCK_CACHE_SIZE = 1 * 1024 * 1024 * 1024L;
    public static final String SYNC_CHANNEL_ID = "sync-updates";
    public static final int SYNC_NOTIFICATION_ID = 77;
    public static final int SYNC_NOTIFICATION_ERROR_ID = 78;
    WebView webView, cardDetails;
    YubiKitManager yubiKitManager;
    AlertDialog fido2Dialog;
    volatile int pendingCallbackId = -1;
    interface Fido2Op { void run(Ctap2Session ctap2) throws Exception; }
    volatile Fido2Op pendingOp = null;
    volatile String webauthnDefaultRpId = "localhost"; // updated when server starts
    Crypto crypto;
    HttpPoster poster;
    ContentAddressedStorage localDht;
    CoreNode core;
    ActivityResultLauncher requestPermissions;
    CompletableFuture<String> chosenHostDir;
    String currentUploadSession = null;
    CompletableFuture<Boolean> gotPermissions = new CompletableFuture<>();

    ServiceWorkerClient serviceWorker;
    ProgressDialog progressDialog;

    // for handling file upload, set a static value, any number you like
    // this value will be used by WebChromeClient during file upload
    private static final int file_chooser_activity_code = 1;
    private static final int dir_chooser_activity_code = 2;
    private static ValueCallback<Uri[]> mUploadMessageArr;

    private static final int REQUEST_ACTION_OPEN_DOCUMENT_TREE = 255;

    private CompletableFuture<String> chooseDirToAccess() {
        CompletableFuture<String> res = new CompletableFuture<>();
        chosenHostDir = res;
        StorageManager sm = (StorageManager) getSystemService(Context.STORAGE_SERVICE);
        Intent intent = sm.getPrimaryStorageVolume().createOpenDocumentTreeIntent();
//            String startDir = "DCIM%2FCamera";
//            Uri uri = intent.getParcelableExtra("android.provider.extra.INITIAL_URI");
//            String scheme = uri.toString();
//            scheme = scheme.replace("/root/", "/document/");
//            scheme += "%3A" + startDir;
//            uri = Uri.parse(scheme);
//            intent.putExtra("android.provider.extra.INITIAL_URI", uri);
        startActivityForResult(intent, REQUEST_ACTION_OPEN_DOCUMENT_TREE);
        return res;
    }

    private boolean wantsDirectory = false; // flag set by JS

    @JavascriptInterface
    public void notifyDirectoryRequest() {
        wantsDirectory = true;
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.out.println("Peergos v1");
        AppLifecycleObserver appLifecycleObserver = new AppLifecycleObserver();
        ProcessLifecycleOwner.get().getLifecycle().addObserver(appLifecycleObserver);
        createNotificationChannel();
        if (ActivityCompat.checkSelfPermission(getApplicationContext(),
                android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
            }
        }

        requestPermissions = registerForActivityResult(new RequestMultiplePermissions(), m -> {
            System.out.println("PERMISSIONS");
            System.out.println(m);
            gotPermissions.complete(true);
        });

        crypto = Main.initCrypto(new ScryptAndroid());
        ThumbnailGenerator.setInstance(new AndroidImageThumbnailer());
        try {
            poster = new AndroidPoster(new URL("http://localhost:" + PORT), false, Optional.empty(), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-android"));
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
        localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        core = NetworkAccess.buildDirectCorenode(poster);

        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        progressDialog = new ProgressDialog(MainActivity.this);
        progressDialog.setCancelable(true);
        progressDialog.setMessage("Loading Peergos...");
        progressDialog.show();

        webView = findViewById(R.id.webView);

        // for handling Android Device [Back] key press
        webView.canGoBackOrForward(99);

        // enable text selection (in theory)
        webView.setLongClickable(true);

        // handling web page browsing mechanism
        webView.setWebViewClient(new NavigationHandler());

        // handling file upload mechanism
        webView.setWebChromeClient(new UploadHandler());

        webView.addJavascriptInterface(this, "Android");

        ServiceWorkerController swController = ServiceWorkerController.getInstance();
        serviceWorker = new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
//                System.out.println("in service worker. isMainFrame:" + request.isForMainFrame() + ": " + request.getUrl());

                return super.shouldInterceptRequest(request);
            }
        };
        swController.setServiceWorkerClient(serviceWorker);

        // some other settings
        WebSettings settings = webView.getSettings();
        settings.setUserAgentString("Peergos-1.0.0-android");
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowContentAccess(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);

        yubiKitManager = new YubiKitManager(this);
        yubiKitManager.startUsbDiscovery(new UsbConfiguration().setDeviceFilter(new DeviceFilter()), this::onYubiKeyDevice);

        webView.setDownloadListener(downloadListener);
        new Thread(() -> {
            SyncRunner syncer = startServer(PORT);
            MainActivity.this.runOnUiThread(() -> {
                webView.loadUrl("http://localhost:" + PORT);
                progressDialog.hide();
            });
            ForkJoinPool.commonPool().submit(syncer::runNow);
        }).start();
    }

    class NavigationHandler extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            System.out.println("WebViewClient url loaded " + request.getUrl().toString());
            String encodedPath = request.getUrl().getEncodedPath();
            boolean isDownload = encodedPath.startsWith("/" + Constants.ANDROID_FILE_REFLECTOR);
            if (! isDownload)
                return false;
            downloadFile(request.getUrl());
            return true;
        }

//        @Nullable
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view,
                                                          WebResourceRequest request) {
//            System.out.println("in webview client. isMainFrame:"+request.isForMainFrame() +": " + request.getUrl());
            return serviceWorker.shouldInterceptRequest(request);
//            return null;
//            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
//            super.onPageStarted(view, url, favicon);
            //showing the progress bar once the page has started loading
            progressDialog.show();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
//            super.onPageFinished(view, url);
            // hide the progress bar once the page has loaded
            progressDialog.dismiss();
            if (url.startsWith("http://localhost:")) {
                injectWebAuthnPolyfill();
            }
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            webView.loadUrl("file:///android_asset/no_internet.html");
            progressDialog.dismiss();
            Toast.makeText(getApplicationContext(),"Internet issue", Toast.LENGTH_SHORT).show();
        }
    }

    private void downloadFile(Uri uri) {
        new Thread(() -> {
            System.out.println("onDownloadStart");
            String path = uri.getEncodedPath();
            String withAction = path.substring(path.indexOf(Constants.ANDROID_FILE_REFLECTOR) + Constants.ANDROID_FILE_REFLECTOR.length());
            String action = withAction.split("/")[0];
            String rest = withAction.substring(action.length() + 1);
            if (action.equals("file")) {
                AbsoluteCapability cap = AbsoluteCapability.fromLink(rest);
                NetworkAccess network = buildLocalhostNetwork();
                FileWrapper file = network.getFile(cap, "").join().get();
                String filename = file.getName();
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(filename);
                request.setDescription("Downloading file...");
                String mimeType = file.getFileProperties().mimeType;
                request.setMimeType(mimeType);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                request.setDestinationUri(Uri.fromFile(downloads.toPath().resolve(filename).toFile()));
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);

                MainActivity.this.runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Downloading...", Toast.LENGTH_SHORT).show());
                ContextCompat.registerReceiver(MainActivity.this, onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED);
            } else if (action.equals("zip")) {
                List<AbsoluteCapability> caps = Arrays.stream(rest.split("\\$"))
                        .map(AbsoluteCapability::fromLink)
                        .collect(Collectors.toList());
                NetworkAccess network = buildLocalhostNetwork();
                Set<FileWrapper> files = network.retrieveAll(caps.stream().map(cap -> new EntryPoint(cap, "")).collect(Collectors.toList())).join();
                String filename = files.size() == 1 ? files.stream().findFirst().get().getName() + ".zip" : "archive-" + LocalDateTime.now() + ".zip";
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setTitle(filename);
                request.setDescription("Downloading file...");
                String mimeType = "application/zip";
                request.setMimeType(mimeType);
                System.out.println("Download manager downloading zip..");
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                request.setDestinationUri(Uri.fromFile(downloads.toPath().resolve(filename).toFile()));
                DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                dm.enqueue(request);

                MainActivity.this.runOnUiThread(() -> Toast.makeText(getApplicationContext(), "Downloading...", Toast.LENGTH_SHORT).show());
                ContextCompat.registerReceiver(MainActivity.this, onComplete, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_NOT_EXPORTED);
            }
        }).start();
    }

    public class UploadHandler extends WebChromeClient {
        @SuppressLint("NewApi")
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback, FileChooserParams fileChooserParams) {
            // Save the callback for handling the selected file
            mUploadMessageArr = valueCallback;

            if (wantsDirectory) {
                wantsDirectory = false;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                ((Activity) webView.getContext()).startActivityForResult(intent, dir_chooser_activity_code);
                return true;
            }

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            // set single file type, e.g. "image/*" for images
            intent.setType("*/*");

            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            Intent chooserIntent = Intent.createChooser(intent, "Choose file(s)");
            ((Activity) webView.getContext()).startActivityForResult(chooserIntent, file_chooser_activity_code);
            return true;
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) {
            if (! userGesture)
                return false;
            cardDetails = new WebView(MainActivity.this);
            view.addView(cardDetails);
            AbsoluteLayout.LayoutParams params = new AbsoluteLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 0);
            cardDetails.setLayoutParams(params);
            view.scrollTo(0, 0);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(cardDetails);
            resultMsg.sendToTarget();

            WebSettings settings = cardDetails.getSettings();
            settings.setUserAgentString("Peergos-1.0.0-android-payment");
            settings.setJavaScriptEnabled(true);

            cardDetails.setWebViewClient(new WebViewClient() {
                @Override
                public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    System.out.println("WebViewClient payment page url loaded " + request.getUrl().toString());
                    if (request.getUrl().getHost().endsWith("peergos.net"))
                        return false;
                    return true;
                }
            });

            cardDetails.setWebChromeClient(new WebChromeClient() {
                @Override
                public void onCloseWindow(WebView window) {
                    super.onCloseWindow(window);
                    view.removeView(cardDetails);
                    MainActivity.this.cardDetails = null;
                    view.requestFocus();
                }
            });

            return true;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // check if the chrome activity is a file choosing session
        if (requestCode == file_chooser_activity_code) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri[] results = null;

                // Check if response is a multiple choice selection containing the results
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    // Response is a single choice selection
                    results = new Uri[]{data.getData()};
                }

                mUploadMessageArr.onReceiveValue(results);
                mUploadMessageArr = null;
            } else {
                mUploadMessageArr.onReceiveValue(null);
                mUploadMessageArr = null;
                Toast.makeText(MainActivity.this, "Error getting file", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == dir_chooser_activity_code) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Uri treeUri = data.getData();

                // Recursively collect every file inside folder
                List<Uri> fileUris = getAllFilesInDirectory(treeUri);

                if (mUploadMessageArr != null) {
                    mUploadMessageArr.onReceiveValue(fileUris.toArray(new Uri[0]));
                    mUploadMessageArr = null;
                }

            } else {
                if (mUploadMessageArr != null) {
                    mUploadMessageArr.onReceiveValue(null);
                    mUploadMessageArr = null;
                }
                Toast.makeText(this, "Folder selection canceled", Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_ACTION_OPEN_DOCUMENT_TREE) {
            System.out.println("Got FOLDER ACCESS");
            if (data != null) {
                Uri uri = uri = data.getData();
                // eg. content://com.android.externalstorage.documents/tree/primary%3ADocuments
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                // Perform operations on the document using its URI.
                chosenHostDir.complete(uri.toString());
            }
        }
    }

    private List<Uri> getAllFilesInDirectory(Uri treeUri) {
        List<Uri> result = new ArrayList<>();
        if (currentUploadSession != null)
            UploadFileProvider.clearSession(currentUploadSession);
        currentUploadSession = UploadFileProvider.startSession();

        DocumentFile root = DocumentFile.fromTreeUri(this, treeUri);
        if (root == null || !root.isDirectory()) {
            return result;
        }

        traverseDirectory(root, root.getName(), result);
        return result;
    }

    private void traverseDirectory(DocumentFile dir, String relativePath, List<Uri> result) {
        for (DocumentFile file : dir.listFiles()) {
            if (file.isDirectory()) {
                traverseDirectory(file, relativePath + "/" + file.getName(), result);
            } else if (file.isFile() && file.canRead()) {
                String relPath = relativePath + "/" + file.getName();
                Uri virtualUri = UploadFileProvider.addFile(currentUploadSession, file.getUri(), relPath);
                result.add(virtualUri);
            }
        }
    }

    BroadcastReceiver onComplete = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            Toast.makeText(getApplicationContext(),"Downloading Complete",Toast.LENGTH_SHORT).show();
        }
    };

    DownloadListener downloadListener = (url, userAgent, contentDisposition, wrongMimetype, contentLength) -> downloadFile(Uri.parse(url));

    @Override
    public void onBackPressed() {
        if (cardDetails != null) {
            webView.removeView(cardDetails);
            cardDetails = null;
            return;
        }
        if(webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingOp != null) {
            try {
                yubiKitManager.startNfcDiscovery(new NfcConfiguration(), this, this::onYubiKeyDevice);
            } catch (NfcNotAvailable e) { /* NFC unavailable, USB only */ }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        yubiKitManager.stopNfcDiscovery(this);
    }

    // ---- WebAuthn / YubiKey bridge ----

    private void injectWebAuthnPolyfill() {
        try {
            java.io.InputStream is = getAssets().open("webauthn_polyfill.js");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            webView.evaluateJavascript(baos.toString("UTF-8"), null);
        } catch (IOException e) {
            System.err.println("Failed to inject WebAuthn polyfill: " + e);
        }
    }

    private String rpIdToOrigin(String rpId) {
        if (rpId.equals("localhost")) return "http://localhost:" + PORT;
        return "https://" + rpId;
    }

    @JavascriptInterface
    public void webauthnCreate(int callbackId, String optionsJson) {
        try {
            JSONObject pk = new JSONObject(optionsJson).getJSONObject("publicKey");

            // Build clientDataJSON with the correct origin for server-side validation
            JSONObject rpJson = pk.getJSONObject("rp");
            String rpId = rpJson.optString("id", webauthnDefaultRpId);
            String origin = rpIdToOrigin(rpId);

            byte[] challengeBytes = extractBytes(pk.get("challenge"));
            String clientDataJson = "{\"type\":\"webauthn.create\",\"challenge\":\""
                    + base64url(challengeBytes) + "\",\"origin\":\"" + origin
                    + "\",\"crossOrigin\":false}";
            byte[] clientDataHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clientDataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // Build CTAP2 rp / user / pubKeyCredParams maps
            Map<String, Object> rp = new java.util.LinkedHashMap<>();
            rp.put("id", rpId);
            rp.put("name", rpJson.getString("name"));

            JSONObject userJson = pk.getJSONObject("user");
            Map<String, Object> user = new java.util.LinkedHashMap<>();
            user.put("id", extractBytes(userJson.get("id")));
            user.put("name", userJson.getString("name"));
            user.put("displayName", userJson.getString("displayName"));

            List<Map<String, ?>> credParams = new ArrayList<>();
            JSONArray algs = pk.getJSONArray("pubKeyCredParams");
            for (int i = 0; i < algs.length(); i++) {
                JSONObject p = algs.getJSONObject(i);
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("type", p.getString("type"));
                m.put("alg", p.getInt("alg"));
                credParams.add(m);
            }

            System.out.println("FIDO2: webauthnCreate rpId=" + rpId + " origin=" + origin + " clientDataJson=" + clientDataJson);
            pendingCallbackId = callbackId;
            pendingOp = ctap2 -> {
                Ctap2Session.CredentialData cred = ctap2.makeCredential(
                        clientDataHash, rp, user, credParams,
                        null, null, null, null, null, null, null);

                byte[] authData = cred.getAuthenticatorData();
                byte[] credId = extractCredentialId(authData);

                // Server only supports NoneAttestationStatement — strip attestation,
                // keep authData (which contains the public key).
                System.out.println("FIDO2: fmt=" + cred.getFormat() + " stripping to none attestation");
                Map<String, Object> attObj = new java.util.LinkedHashMap<>();
                attObj.put("fmt", "none");
                attObj.put("authData", authData);
                attObj.put("attStmt", new java.util.LinkedHashMap<String, Object>());
                byte[] attestationObject = Cbor.encode(attObj);

                JSONObject resp = new JSONObject();
                String credIdB64 = base64url(credId);
                resp.put("id", credIdB64);
                resp.put("rawId", credIdB64);
                resp.put("type", "public-key");
                JSONObject r = new JSONObject();
                r.put("attestationObject", base64url(attestationObject));
                r.put("clientDataJSON", base64url(
                        clientDataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                resp.put("response", r);
                webAuthnCallback(callbackId, resp.toString());
            };
            runOnUiThread(() -> showFido2Dialog(callbackId, "Tap or connect your security key to register"));
        } catch (Exception e) {
            webAuthnError(callbackId, e.getMessage());
        }
    }

    @JavascriptInterface
    public void webauthnGet(int callbackId, String optionsJson) {
        try {
            JSONObject pk = new JSONObject(optionsJson).getJSONObject("publicKey");

            String rpId = pk.optString("rpId", webauthnDefaultRpId);
            String origin = rpIdToOrigin(rpId);

            byte[] challengeBytes = extractBytes(pk.get("challenge"));
            String clientDataJson = "{\"type\":\"webauthn.get\",\"challenge\":\""
                    + base64url(challengeBytes) + "\",\"origin\":\"" + origin
                    + "\",\"crossOrigin\":false}";
            byte[] clientDataHash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(clientDataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            List<Map<String, ?>> allowList = new ArrayList<>();
            if (pk.has("allowCredentials")) {
                JSONArray allow = pk.getJSONArray("allowCredentials");
                for (int i = 0; i < allow.length(); i++) {
                    JSONObject c = allow.getJSONObject(i);
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("type", c.getString("type"));
                    m.put("id", extractBytes(c.get("id")));
                    allowList.add(m);
                }
            }

            pendingCallbackId = callbackId;
            pendingOp = ctap2 -> {
                List<Ctap2Session.AssertionData> assertions = ctap2.getAssertions(
                        rpId, clientDataHash,
                        allowList.isEmpty() ? null : allowList,
                        null, null, null, null, null);

                Ctap2Session.AssertionData assertion = assertions.get(0);
                byte[] authData = assertion.getAuthenticatorData();
                byte[] signature = assertion.getSignature();

                // Credential ID: from response if present, else from allowList
                byte[] credId = null;
                Map<String, ?> credDesc = assertion.getCredential();
                if (credDesc != null) credId = (byte[]) credDesc.get("id");
                if (credId == null && !allowList.isEmpty())
                    credId = (byte[]) allowList.get(0).get("id");

                byte[] userHandle = null;
                Map<String, ?> userMap = assertion.getUser();
                if (userMap != null) userHandle = (byte[]) userMap.get("id");

                JSONObject resp = new JSONObject();
                String credIdB64 = base64url(credId);
                resp.put("id", credIdB64);
                resp.put("rawId", credIdB64);
                resp.put("type", "public-key");
                JSONObject r = new JSONObject();
                r.put("clientDataJSON", base64url(
                        clientDataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                r.put("authenticatorData", base64url(authData));
                r.put("signature", base64url(signature));
                if (userHandle != null) r.put("userHandle", base64url(userHandle));
                resp.put("response", r);
                webAuthnCallback(callbackId, resp.toString());
            };
            runOnUiThread(() -> showFido2Dialog(callbackId, "Tap or connect your security key to authenticate"));
        } catch (Exception e) {
            webAuthnError(callbackId, e.getMessage());
        }
    }

    private void showFido2Dialog(int callbackId, String message) {
        fido2Dialog = new AlertDialog.Builder(this)
                .setTitle("Security Key")
                .setMessage(message)
                .setNegativeButton("Cancel", (d, w) -> {
                    pendingOp = null;
                    yubiKitManager.stopNfcDiscovery(this);
                    yubiKitManager.stopUsbDiscovery();
                    webAuthnError(callbackId, "User cancelled");
                })
                .setCancelable(false)
                .show();
        System.out.println("FIDO2: showing dialog, starting NFC/USB discovery");
        try {
            yubiKitManager.startNfcDiscovery(new NfcConfiguration(), this, this::onYubiKeyDevice);
        } catch (NfcNotAvailable e) {
            System.out.println("FIDO2: NFC not available: " + e);
        }
        // Restart USB discovery for newly-connected devices
        yubiKitManager.stopUsbDiscovery();
        yubiKitManager.startUsbDiscovery(new UsbConfiguration().setDeviceFilter(new DeviceFilter()), this::onYubiKeyDevice);
        System.out.println("FIDO2: USB discovery (re)started");

        // YubiKit won't re-fire for an already-connected device after a discovery restart,
        // so enumerate USB devices directly and trigger the callback ourselves.
        android.hardware.usb.UsbManager usbMgr =
                (android.hardware.usb.UsbManager) getSystemService(Context.USB_SERVICE);
        for (android.hardware.usb.UsbDevice usbDev : usbMgr.getDeviceList().values()) {
            System.out.println("FIDO2: found USB device vid=0x" + Integer.toHexString(usbDev.getVendorId()) + " pid=0x" + Integer.toHexString(usbDev.getProductId()));
            UsbYubiKeyDevice yubiDev = new UsbYubiKeyDevice(usbMgr, usbDev);
            if (yubiDev.supportsConnection(FidoConnection.class) || yubiDev.supportsConnection(SmartCardConnection.class)) {
                System.out.println("FIDO2: device supports FIDO/SmartCard connection");
                if (yubiDev.hasPermission()) {
                    System.out.println("FIDO2: device has permission, triggering directly");
                    onYubiKeyDevice(yubiDev);
                } else {
                    System.out.println("FIDO2: device found but no permission yet, USB discovery will request it");
                }
            }
        }
    }

    private void onYubiKeyDevice(YubiKeyDevice device) {
        System.out.println("FIDO2: onYubiKeyDevice called, device=" + device + ", pendingOp=" + pendingOp);
        Fido2Op op = pendingOp;
        if (op == null) {
            System.out.println("FIDO2: no pending op, ignoring device");
            return;
        }
        pendingOp = null;
        runOnUiThread(() -> {
            if (fido2Dialog != null) fido2Dialog.setMessage("Processing...");
        });
        boolean isUsb = device instanceof UsbYubiKeyDevice;
        System.out.println("FIDO2: device is USB=" + isUsb + ", requesting connection");
        if (isUsb) {
            device.requestConnection(FidoConnection.class, result -> {
                System.out.println("FIDO2: FidoConnection result, success=" + result.isSuccess());
                try {
                    FidoConnection conn = result.getValue();
                    System.out.println("FIDO2: got FidoConnection, opening Ctap2Session");
                    try (Ctap2Session ctap2 = new Ctap2Session(conn)) {
                        op.run(ctap2);
                    }
                    System.out.println("FIDO2: op completed successfully");
                } catch (Exception e) {
                    System.out.println("FIDO2: op failed: " + e);
                    e.printStackTrace();
                    webAuthnError(pendingCallbackId, e.getMessage() != null ? e.getMessage() : e.toString());
                } finally {
                    runOnUiThread(() -> {
                        if (fido2Dialog != null) { fido2Dialog.dismiss(); fido2Dialog = null; }
                    });
                    yubiKitManager.stopNfcDiscovery(this);
                    yubiKitManager.stopUsbDiscovery();
                }
            });
        } else {
            device.requestConnection(SmartCardConnection.class, result -> {
                System.out.println("FIDO2: SmartCardConnection result, success=" + result.isSuccess());
                try {
                    SmartCardConnection conn = result.getValue();
                    System.out.println("FIDO2: got SmartCardConnection, opening Ctap2Session");
                    try (Ctap2Session ctap2 = new Ctap2Session(conn)) {
                        op.run(ctap2);
                    }
                    System.out.println("FIDO2: op completed successfully");
                } catch (Exception e) {
                    System.out.println("FIDO2: op failed: " + e);
                    e.printStackTrace();
                    webAuthnError(pendingCallbackId, e.getMessage() != null ? e.getMessage() : e.toString());
                } finally {
                    runOnUiThread(() -> {
                        if (fido2Dialog != null) { fido2Dialog.dismiss(); fido2Dialog = null; }
                    });
                    yubiKitManager.stopNfcDiscovery(this);
                    yubiKitManager.stopUsbDiscovery();
                }
            });
        }
    }

    private void webAuthnCallback(int callbackId, String responseJson) {
        String encoded = android.util.Base64.encodeToString(
                responseJson.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                android.util.Base64.NO_WRAP);
        webView.post(() -> webView.evaluateJavascript(
                "window._webauthnSuccess(" + callbackId + ",'" + encoded + "')", null));
    }

    private void webAuthnError(int callbackId, String message) {
        String safe = (message != null ? message : "Unknown error").replace("'", "\\'");
        webView.post(() -> webView.evaluateJavascript(
                "window._webauthnError(" + callbackId + ",'" + safe + "')", null));
    }

    private static byte[] extractCredentialId(byte[] authData) {
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(authData);
        buf.position(32); // rpIdHash
        byte flags = buf.get();
        buf.position(37); // skip signCount
        if ((flags & 0x40) == 0) return null; // AT flag not set
        buf.position(53); // skip aaguid
        int len = ((buf.get() & 0xFF) << 8) | (buf.get() & 0xFF);
        byte[] credId = new byte[len];
        buf.get(credId);
        return credId;
    }

    private static byte[] extractBytes(Object val) throws JSONException {
        if (val instanceof JSONArray) {
            JSONArray arr = (JSONArray) val;
            byte[] bytes = new byte[arr.length()];
            for (int i = 0; i < arr.length(); i++) bytes[i] = (byte) arr.getInt(i);
            return bytes;
        }
        if (val instanceof JSONObject) {
            JSONObject obj = (JSONObject) val;
            if ("ArrayBuffer".equals(obj.optString("_type"))) {
                JSONArray data = obj.getJSONArray("data");
                byte[] bytes = new byte[data.length()];
                for (int i = 0; i < data.length(); i++) bytes[i] = (byte) data.getInt(i);
                return bytes;
            }
        }
        throw new JSONException("Cannot extract bytes from: " + val);
    }

    private static String base64url(byte[] bytes) {
        return android.util.Base64.encodeToString(bytes,
                android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING);
    }

    // ---- end WebAuthn bridge ----

    public NetworkAccess buildLocalhostNetwork() {
        return NetworkAccess.buildToPeergosServer(Collections.emptyList(), core, localDht, poster, poster, 7_000, crypto.hasher, Collections.emptyList(), false);
    }

    public static Optional<Thumbnail> generateVideoThumbnail(File f) {
        try {
            Bitmap thumb = ThumbnailUtils.createVideoThumbnail(f, new Size(400, 400), null);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out);

            return Optional.of(new Thumbnail("image/webp", out.toByteArray()));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private void createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is not in the Support Library.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int importance = NotificationManager.IMPORTANCE_DEFAULT;
            NotificationChannel channel = new NotificationChannel(SYNC_CHANNEL_ID, "Sync", importance);
            channel.setDescription("Sync updates");
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this.
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    public SyncRunner startServer(int port) {
        File privateStorage = this.getFilesDir();
        Path peergosDir = Paths.get(privateStorage.getAbsolutePath());
        System.out.println("Peergos using private storage dir: " + peergosDir);
        // make sure sqlite loads correct shared library on Android
        System.out.println("Initial runtime name: " + System.getProperty("java.runtime.name", ""));

        Path config = peergosDir.resolve("config");
        Args a = Args.parse(new String[]{
                "PEERGOS_PATH", peergosDir.toString(),
//                "-peergos-url", "https://test.peergos.net",
                "-mutable-pointers-cache", "pointer-cache.sql",
                "-account-cache-sql-file", "account-cache.sql",
                "-pki-cache-sql-file", "pki-cache.sql",
                "-bat-cache-sql-file", "bat-cache.sql",
                "pki-cache-sql-file", "pki-cache.sqlite",
                "port", port + ""
        }, config.toFile().exists() ? Optional.of(config) : Optional.empty(), false);
        a.saveToFile();
        try {
            // check if the local server is already running first
            URI api = new URI("http://localhost:" + port);
            AndroidPoster localPoster = new AndroidPoster(api.toURL(), false, Optional.empty(), Optional.empty());
            Hasher hasher = new ScryptAndroid();
            ContentAddressedStorage localhostDht = NetworkAccess.buildLocalDht(localPoster, true, hasher);
            boolean alreadyRunning = false;
            try {
                localhostDht.ids().join();
                alreadyRunning = true;
            } catch (Exception e){}
            if (alreadyRunning)
                return null;

            // now start the server
            String serverUrl = a.getArg("server-url", "https://peergos.net");
            URL target = new URL(serverUrl);
            webauthnDefaultRpId = target.getHost();
            HttpPoster poster = new AndroidPoster(target, true, Optional.empty(), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-android"));
            ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, hasher);
            CoreNode core = NetworkAccess.buildDirectCorenode(poster);
            ContentAddressedStorage s3 = NetworkAccess.buildDirectS3Blockstore(localDht, core, poster, true, hasher).join();
            MutablePointersProxy httpMutable = new HttpMutablePointers(poster, poster);
            Account account = new HttpAccount(poster, poster);

            SocialNetworkProxy httpSocial = new HttpSocialNetwork(poster, poster);
            SpaceUsageProxy httpUsage = new HttpSpaceUsage(poster, poster);
            ServerMessager serverMessager = new ServerMessager.HTTP(poster);
            BatCave batCave = new HttpBatCave(poster, poster);
            HttpInstanceAdmin admin = new HttpInstanceAdmin(poster);

            FileBlockCache blockCache = new FileBlockCache(a.getPeergosDir().resolve(Paths.get("blocks", "cache")),
                    MAX_BLOCK_CACHE_SIZE);
            ContentAddressedStorage locallyCachedStorage = new UnauthedCachingStorage(s3, blockCache, crypto.hasher);
            DirectOnlyStorage withoutS3 = new DirectOnlyStorage(locallyCachedStorage);

            Supplier<Connection> dbConnector = Builder.getDBConnector(a, "mutable-pointers-cache");
            JdbcIpnsAndSocial rawPointers = Builder.buildRawPointers(a, dbConnector);
            OnlineState online = new OnlineState(() -> Futures.of(true));
            OfflinePointerCache pointerCache = new OfflinePointerCache(httpMutable, new JdbcPointerCache(rawPointers, locallyCachedStorage), online);

            SqlSupplier commands = Builder.getSqlCommands(a);
            OfflineCorenode offlineCorenode = new OfflineCorenode(core, new JdbcPkiCache(Builder.getDBConnector(a, "pki-cache-sql-file", dbConnector), commands), online);

            Origin origin = new Origin("http://localhost:" + port);
            JdbcAccount localAccount = new JdbcAccount(Builder.getDBConnector(a, "account-cache-sql-file", dbConnector), commands, origin, "localhost");
            OfflineAccountStore offlineAccounts = new OfflineAccountStore(account, localAccount, online);

            OfflineBatCache offlineBats = new OfflineBatCache(batCave, new JdbcBatCave(Builder.getDBConnector(a, "bat-cache-sql-file", dbConnector), commands));

            ThumbnailGenerator.setVideoInstance(f -> generateVideoThumbnail(f));

            Data syncArgs = new Data.Builder()
                    .putString("PEERGOS_PATH", peergosDir.toString())
                    .build();
            Constraints periodic = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build();

            Constraints once = new Constraints.Builder()
                .build();

//            WorkManager.initialize(
//                    this,
//                    new Configuration.Builder()
//                            .setExecutor(Executors.newFixedThreadPool(1))
//                            .build());
            WorkManager backgroundWork = WorkManager.getInstance(this);
            SyncRunner syncer = new SyncRunner() {
                private static final String periodicUuid = "fe64ee2f-a2a2-4dab-96d8-0aec9475541f";

                @Override
                public void start() {
                    runNow();
                    backgroundWork.enqueue(new PeriodicWorkRequest.Builder(SyncWorker.class, 15, TimeUnit.MINUTES)
                            .setConstraints(periodic)
                            .setId(UUID.fromString(periodicUuid))
                            .setInputData(syncArgs).setInitialDelay(Duration.of(1, ChronoUnit.MINUTES))
                            .build());
                }

                @Override
                public void runNow() {
                    backgroundWork.enqueue(new OneTimeWorkRequest.Builder(SyncWorker.class)
                            .setConstraints(once)
                            .setId(UUID.randomUUID())
                            .setInputData(syncArgs)
                            .build());
                }

                @Override
                public StatusHolder getStatusHolder() {
                    return SyncWorker.status;
                }
            };

            Path oldSyncConfigFile = peergosDir.resolve(SyncConfigHandler.OLD_SYNC_CONFIG_FILENAME);
            Path jsonSyncConfig = peergosDir.resolve(SyncConfigHandler.SYNC_CONFIG_FILENAME);

            boolean jsonExists = jsonSyncConfig.toFile().exists();
            SyncConfig syncConfig = jsonExists ?
                    SyncConfig.fromJson((Map<String, Object>) JSONParser.parse(new String(Files.readAllBytes(jsonSyncConfig)))) :
                    SyncConfig.fromArgs(Args.parse(new String[]{"-run-once", "true"}, Optional.of(oldSyncConfigFile), false));

            Optional<UserService.LocalAppProperties> localAppProps = Optional.of(new UserService.LocalAppProperties(peergosDir, serverUrl));
            UserService server = new UserService(withoutS3, offlineBats, crypto, offlineCorenode, offlineAccounts,
                    httpSocial, pointerCache, admin, httpUsage, serverMessager, null,
                    Optional.of(new SyncProperties(syncConfig, a.getPeergosDir(), syncer, Either.b(this::chooseDirToAccess))), localAppProps);

            InetSocketAddress localAPIAddress = new InetSocketAddress("localhost", port);
            List<String> appSubdomains = Arrays.asList("markup-viewer,calendar,code-editor,pdf".split(","));
            int connectionBacklog = 50;
            int handlerPoolSize = 4;
            server.initAndStart(localAPIAddress, Arrays.asList(), Optional.empty(), Optional.empty(),
                    Collections.emptyList(), Collections.emptyList(), appSubdomains, true,
                    Optional.empty(), Optional.empty(), Optional.empty(), true, false,
                    connectionBacklog, handlerPoolSize);

            return syncer;
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}