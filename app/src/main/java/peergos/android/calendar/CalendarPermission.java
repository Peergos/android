package peergos.android.calendar;

/** Where the calendar permission request is held until calendar syncing is turned on.
 *
 *  The permission is only worth asking for once the sync adapter is registered and starting to
 *  write to the calendar provider, which is not the same thing as mounting the drive: either can
 *  be on without the other. That happens on a background thread in the application process with no
 *  activity of its own, so whichever activity is on screen registers here to do the asking, and a
 *  start that happens with nothing on screen is picked up by the next activity to register.
 */
public final class CalendarPermission {

    private static volatile Runnable asker = null;
    private static volatile boolean started = false;

    private CalendarPermission() {}

    /** Calendar syncing has been turned on, from whatever thread turned it on. */
    public static void onCalendarStarted() {
        started = true;
        Runnable ask = asker;
        if (ask != null)
            ask.run();
    }

    public static void onCalendarStopped() {
        started = false;
    }

    /** Register an activity that can put the dialog up, asking now if the calendar is running. */
    public static void setAsker(Runnable ask) {
        asker = ask;
        if (started)
            ask.run();
    }

    public static void clearAsker(Runnable ask) {
        if (asker == ask)
            asker = null;
    }
}
