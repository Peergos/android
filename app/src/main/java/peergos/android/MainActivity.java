package peergos.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.ViewGroup;
import android.webkit.DownloadListener;
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
import android.widget.AbsoluteLayout;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.webauthn4j.data.client.Origin;

import org.peergos.util.Futures;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import peergos.server.Builder;
import peergos.server.DirectOnlyStorage;
import peergos.server.JdbcPkiCache;
import peergos.server.Main;
import peergos.server.UserService;
import peergos.server.corenode.JdbcIpnsAndSocial;
import peergos.server.crypto.hash.ScryptJava;
import peergos.server.login.JdbcAccount;
import peergos.server.mutable.JdbcPointerCache;
import peergos.server.sql.SqlSupplier;
import peergos.server.storage.FileBlockCache;
import peergos.server.storage.auth.JdbcBatCave;
import peergos.server.util.Args;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.OnlineState;
import peergos.shared.corenode.CoreNode;
import peergos.shared.corenode.OfflineCorenode;
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
import peergos.shared.util.Constants;

public class MainActivity extends AppCompatActivity {

    public static final int PORT = 7777;
    WebView webView, cardDetails;
    Crypto crypto;
    HttpPoster poster;
    ContentAddressedStorage localDht;
    CoreNode core;

    ServiceWorkerClient serviceWorker;
    ProgressDialog progressDialog;

    // for handling file upload, set a static value, any number you like
    // this value will be used by WebChromeClient during file upload
    private static final int file_chooser_activity_code = 1;
    private static ValueCallback<Uri[]> mUploadMessageArr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.out.println("Peergos v1");
        crypto = Main.initCrypto();
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

        ServiceWorkerController swController = ServiceWorkerController.getInstance();
        serviceWorker = new ServiceWorkerClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                System.out.println("in service worker. isMainFrame:" + request.isForMainFrame() + ": " + request.getUrl());

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

        webView.setDownloadListener(downloadListener);
        new Thread(() -> {
            startServer(PORT);
            MainActivity.this.runOnUiThread(() -> {
                webView.loadUrl("http://localhost:" + PORT);
                progressDialog.hide();
            });
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
            System.out.println("in webview client. isMainFrame:"+request.isForMainFrame() +": " + request.getUrl());
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

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            // set single file type, e.g. "image/*" for images
            intent.setType("*/*");

            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

            Intent chooserIntent = Intent.createChooser(intent, "Choose file(s)");
            ((Activity) webView.getContext()).startActivityForResult(chooserIntent, file_chooser_activity_code);

            // Save the callback for handling the selected file
            mUploadMessageArr = valueCallback;
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

    public NetworkAccess buildLocalhostNetwork() {
        return NetworkAccess.buildToPeergosServer(Collections.emptyList(), core, localDht, poster, poster, 7_000, crypto.hasher, Collections.emptyList(), false);
    }
    
    public boolean startServer(int port) {
        File privateStorage = this.getFilesDir();
        Path peergosDir = Paths.get(privateStorage.getAbsolutePath());
        System.out.println("Peergos using private storage dir: " + peergosDir);
        // make sure sqlite loads correct shared library on Android
        System.out.println("Initial runtime name: " + System.getProperty("java.runtime.name", ""));

        Args a = Args.parse(new String[]{
                "PEERGOS_PATH", peergosDir.toString(),
//                "-peergos-url", "https://test.peergos.net",
                "-mutable-pointers-cache", "pointer-cache.sql",
                "-account-cache-sql-file", "account-cache.sql",
                "-pki-cache-sql-file", "pki-cache.sql",
                "-bat-cache-sql-file", "bat-cache.sql",
                "port", port + ""
        });
        try {
            // check if the local server is already running first
            URI api = new URI("http://localhost:" + port);
            AndroidPoster localPoster = new AndroidPoster(api.toURL(), false, Optional.empty(), Optional.empty());
            ScryptJava hasher = new ScryptJava();
            ContentAddressedStorage localhostDht = NetworkAccess.buildLocalDht(localPoster, true, hasher);
            boolean alreadyRunning = false;
            try {
                localhostDht.ids().join();
                alreadyRunning = true;
            } catch (Exception e){}
            if (alreadyRunning)
                return true;

            // now start the server
            URL target = new URL(a.getArg("peergos-url", "https://peergos.net"));
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
                    10 * 1024 * 1024 * 1024L);
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

            UserService server = new UserService(withoutS3, offlineBats, crypto, offlineCorenode, offlineAccounts,
                    httpSocial, pointerCache, admin, httpUsage, serverMessager, null);

            InetSocketAddress localAPIAddress = new InetSocketAddress("localhost", port);
            List<String> appSubdomains = Arrays.asList("markup-viewer,calendar,code-editor,pdf".split(","));
            int connectionBacklog = 50;
            int handlerPoolSize = 4;
            server.initAndStart(localAPIAddress, Arrays.asList(), Optional.empty(), Optional.empty(),
                    Collections.emptyList(), Collections.emptyList(), appSubdomains, true,
                    Optional.empty(), Optional.empty(), Optional.empty(), true, false,
                    connectionBacklog, handlerPoolSize);
        } catch (IOException | URISyntaxException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}