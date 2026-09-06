package peergos.android.calendar;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Publishes {@link CalendarSyncAdapter} to the sync framework. */
public class CalendarSyncService extends Service {

    // written under the lock in onCreate, read from the binder thread in onBind
    private static volatile CalendarSyncAdapter adapter;
    private static final Object lock = new Object();

    @Override
    public void onCreate() {
        synchronized (lock) {
            if (adapter == null)
                adapter = new CalendarSyncAdapter(getApplicationContext());
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return adapter.getSyncAdapterBinder();
    }
}
