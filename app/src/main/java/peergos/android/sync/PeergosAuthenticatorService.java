package peergos.android.sync;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Publishes {@link PeergosAuthenticator} to the account manager. */
public class PeergosAuthenticatorService extends Service {

    private PeergosAuthenticator authenticator;

    @Override
    public void onCreate() {
        authenticator = new PeergosAuthenticator(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return authenticator.getIBinder();
    }
}
