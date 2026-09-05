package peergos.android.contacts;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Publishes {@link ContactsSyncAdapter} to the sync framework. */
public class ContactsSyncService extends Service {

    private static ContactsSyncAdapter adapter;
    private static final Object lock = new Object();

    @Override
    public void onCreate() {
        synchronized (lock) {
            if (adapter == null)
                adapter = new ContactsSyncAdapter(getApplicationContext());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }
}
