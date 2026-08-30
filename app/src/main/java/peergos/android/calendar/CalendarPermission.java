package peergos.android.calendar;

/** Where the calendar permission request is held until the drive is mounted.
 *
 *  The permission is only worth asking for once the drive is mounted, because that is the point
 *  the sync adapter is registered and starts writing to the calendar provider. The mount runs on a
 *  background thread in the application process and has no activity of its own, so whichever
 *  activity is on screen registers here to do the asking, and a mount that happens with nothing on
 *  screen is picked up by the next activity to register.
 */
public final class CalendarPermission {

    private static volatile Runnable asker = null;
    private static volatile boolean mounted = false;

    private CalendarPermission() {}

    /** The drive has been mounted, from whatever thread mounted it. */
    public static void onMounted() {
        mounted = true;
        Runnable ask = asker;
        if (ask != null)
            ask.run();
    }

    public static void onUnmounted() {
        mounted = false;
    }

    /** Register an activity that can put the dialog up, asking now if we are already mounted. */
    public static void setAsker(Runnable ask) {
        asker = ask;
        if (mounted)
            ask.run();
    }

    public static void clearAsker(Runnable ask) {
        if (asker == ask)
            asker = null;
    }
}
