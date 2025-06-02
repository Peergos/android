package peergos.android;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleObserver;
import androidx.lifecycle.OnLifecycleEvent;

import java.util.concurrent.atomic.AtomicBoolean;

public class AppLifecycleObserver implements LifecycleObserver {

    public static final String TAG = AppLifecycleObserver.class.getName();
    public static final AtomicBoolean inForeground = new AtomicBoolean(false);

    @OnLifecycleEvent(Lifecycle.Event.ON_START)
    public void onEnterForeground() {
        inForeground.set(true);
    }

    @OnLifecycleEvent(Lifecycle.Event.ON_STOP)
    public void onEnterBackground() {
        inForeground.set(false);
    }
}
