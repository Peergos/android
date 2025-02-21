package peergos.android;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.DownloadListener;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.webauthn4j.data.client.Origin;

import org.peergos.util.Futures;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

import peergos.server.Builder;
import peergos.server.DirectOnlyStorage;
import peergos.server.JavaCrypto;
import peergos.server.JdbcPkiCache;
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
import peergos.shared.user.HttpAccount;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.ServerMessager;

public class MainActivity extends AppCompatActivity {
    WebView webView;
    ProgressDialog progressDialog;

    // for handling file upload, set a static value, any number you like
    // this value will be used by WebChromeClient during file upload
    private static final int file_chooser_activity_code = 1;
    private static ValueCallback<Uri[]> mUploadMessageArr;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        System.out.println("Peergos v1");

        new Thread(() -> {
            startServer(7777);
            System.out.println("Loading Peergos UI");
            MainActivity.this.runOnUiThread(() -> {
                webView.loadUrl("http://localhost:7777");
                progressDialog.hide();
            });
        }).start();

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

        // handling web page browsing mechanism
//        webView.setWebViewClient(new myWebViewClient());

        // handling file upload mechanism
        webView.setWebChromeClient(new myWebChromeClient());

        // some other settings
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
//        settings.setAllowFileAccessFromFileURLs(true);
        settings.setUserAgentString("Peergos-1.0.0-android");

        webView.setDownloadListener(downloadListener);
    }

    public static class myWebChromeClient extends WebChromeClient {
        @SuppressLint("NewApi")
        @Override
        public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> valueCallback, FileChooserParams fileChooserParams) {

            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);

            // set single file type, e.g. "image/*" for images
            intent.setType("*/*");

            // set multiple file types
            String[] mimeTypes = {"image/*", "application/pdf"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

            Intent chooserIntent = Intent.createChooser(intent, "Choose file");
            ((Activity) webView.getContext()).startActivityForResult(chooserIntent, file_chooser_activity_code);

            // Save the callback for handling the selected file
            mUploadMessageArr = valueCallback;

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

    DownloadListener downloadListener = new DownloadListener() {
        @Override
        public void onDownloadStart(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {

            progressDialog.dismiss();
            Intent i = new Intent(Intent.ACTION_VIEW);

            // example of URL = https://www.example.com/invoice.pdf
            i.setData(Uri.parse(url));
            startActivity(i);
        }
    };
    
    public boolean startServer(int port) {
        System.out.println("SQLITE library present: " + (null != peergos.server.Main.class.getResourceAsStream("/org/sqlite/native/Linux-Android/aarch64/libsqlitejdbc.so")));
        File privateStorage = this.getFilesDir();
        Path peergosDir = Paths.get(privateStorage.getAbsolutePath());
        System.out.println("Peergos using private storage dir: " + peergosDir);
        // make sure sqlite loads correct shared library on Android
        System.out.println("Initial runtime name: " + System.getProperty("java.runtime.name", ""));

        Args a = Args.parse(new String[]{
                "PEERGOS_PATH", peergosDir.toString(),
                "-mutable-pointers-cache", "pointer-cache.sql",
                "-account-cache-sql-file", "account-cache.sql",
                "-pki-cache-sql-file", "pki-cache.sql",
                "-bat-cache-sql-file", "bat-cache.sql",
                "port", port + ""
        });
        try {
            Crypto crypto = JavaCrypto.init();
            URL target = new URL(a.getArg("peergos-url", "https://peergos.net"));
            HttpPoster poster = new AndroidPoster(target, true, Optional.empty(), Optional.of("Peergos-" + UserService.CURRENT_VERSION + "-android"));
            ScryptJava hasher = new ScryptJava();
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }
}