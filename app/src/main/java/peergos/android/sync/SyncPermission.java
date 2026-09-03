package peergos.android.sync;

/** Where a runtime permission request is held until the sync that needs it is turned on.
 *
 *  The permission is only worth asking for once a sync adapter is registered and starting to
 *  write to its provider, which is not the same thing as mounting the drive: any of them can
 *  be on without the others. That happens on a background thread in the application process
 *  with no activity of its own, so whichever activity is on screen registers here to do the
 *  asking, and a start that happens with nothing on screen is picked up by the next activity
 *  to register.
 *
 *  One instance per permission the user is asked for separately, so turning the calendar on
 *  does not put up a contacts dialog they never asked for.
 */
public final class SyncPermission {

    public static final SyncPermission CALENDAR = new SyncPermission();
    public static final SyncPermission CONTACTS = new SyncPermission();

    private volatile Runnable asker = null;
    private volatile boolean started = false;

    private SyncPermission() {}

    /** This sync has been turned on, from whatever thread turned it on. */
    public void onStarted() {
        started = true;
        Runnable ask = asker;
        if (ask != null)
            ask.run();
    }

    public void onStopped() {
        started = false;
    }

    /** Register an activity that can put the dialog up, asking now if the sync is running. */
    public void setAsker(Runnable ask) {
        asker = ask;
        if (started)
            ask.run();
    }

    public void clearAsker(Runnable ask) {
        if (asker == ask)
            asker = null;
    }
}
