package peergos.android.calendar;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.CalendarStore;

/**
 * Mirrors the Peergos calendars into CalendarContract.
 *
 * One direction only, for now: Peergos is the source and the platform side is a cache, so
 * the calendars are published read-only. That is a deliberate stopping point rather than
 * an oversight — a two-way adapter has to decide what happens when both sides changed, and
 * publishing writable calendars before that logic exists would invite edits it would then
 * have to throw away.
 *
 * Events are keyed by _SYNC_ID, which holds the Peergos member name (the .ics file name).
 * That is the same key the CalDAV bridge uses as an href, and it survives an event moving
 * between month shards, which its path does not.
 */
public class CalendarMirror {

    private static final String TAG = "PeergosCalendar";

    private final ContentProviderClient provider;
    private final Account account;
    private final CalendarStore store;

    public CalendarMirror(ContentProviderClient provider, Account account, CalendarStore store) {
        this.provider = provider;
        this.account = account;
        this.store = store;
    }

    /** @return the number of events added, updated or removed. */
    public int sync() throws Exception {
        Map<String, Long> existing = existingCalendars();
        Set<String> wanted = new HashSet<>();
        int changes = 0;
        for (AppDataStore.CollectionInfo info : store.listCollections()) {
            wanted.add(info.directory);
            long calendarId = existing.containsKey(info.directory)
                    ? updateCalendar(existing.get(info.directory), info)
                    : insertCalendar(info);
            changes += syncEvents(calendarId, info.directory);
        }
        for (Map.Entry<String, Long> gone : existing.entrySet()) {
            if (! wanted.contains(gone.getKey())) {
                provider.delete(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                        CalendarContract.Calendars._ID + "=?",
                        new String[]{Long.toString(gone.getValue())});
                changes++;
            }
        }
        return changes;
    }

    private Map<String, Long> existingCalendars() throws Exception {
        Map<String, Long> byDirectory = new HashMap<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                new String[]{CalendarContract.Calendars._ID, CalendarContract.Calendars._SYNC_ID},
                CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                        + CalendarContract.Calendars.ACCOUNT_TYPE + "=?",
                new String[]{account.name, account.type}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (! cursor.isNull(1))
                    byDirectory.put(cursor.getString(1), cursor.getLong(0));
            }
        }
        return byDirectory;
    }

    private long insertCalendar(AppDataStore.CollectionInfo info) throws Exception {
        Uri inserted = provider.insert(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI),
                calendarValues(info));
        return ContentUris.parseId(inserted);
    }

    private long updateCalendar(long id, AppDataStore.CollectionInfo info) throws Exception {
        provider.update(asSyncAdapter(CalendarContract.Calendars.CONTENT_URI), calendarValues(info),
                CalendarContract.Calendars._ID + "=?", new String[]{Long.toString(id)});
        return id;
    }

    private ContentValues calendarValues(AppDataStore.CollectionInfo info) {
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars._SYNC_ID, info.directory);
        values.put(CalendarContract.Calendars.ACCOUNT_NAME, account.name);
        values.put(CalendarContract.Calendars.ACCOUNT_TYPE, account.type);
        values.put(CalendarContract.Calendars.OWNER_ACCOUNT, account.name);
        values.put(CalendarContract.Calendars.NAME, info.directory);
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, info.name);
        values.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
                CalendarContract.Calendars.CAL_ACCESS_READ);
        values.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        values.put(CalendarContract.Calendars.VISIBLE, 1);
        values.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, "UTC");
        parseColour(info.colour).ifPresent(c -> values.put(CalendarContract.Calendars.CALENDAR_COLOR, c));
        return values;
    }

    /**
     * Events are compared by ETag, which is the Peergos content hash: unchanged events cost
     * a listing and nothing more, and in particular are never re-downloaded.
     */
    private int syncEvents(long calendarId, String directory) throws Exception {
        Map<String, String> onDevice = existingEvents(calendarId);
        Set<String> seen = new HashSet<>();
        int changes = 0;
        for (AppDataStore.ObjectRef object : store.listObjects(directory)) {
            seen.add(object.name);
            String etag = object.etag();
            if (etag.equals(onDevice.get(object.name)))
                continue;
            String ics = new String(store.read(object), StandardCharsets.UTF_8);
            var values = EventTranslator.toEvent(ics, calendarId);
            if (values.isEmpty()) {
                Log.w(TAG, "Skipping " + directory + "/" + object.name + ": no usable start date");
                continue;
            }
            values.get().put(CalendarContract.Events._SYNC_ID, object.name);
            values.get().put(CalendarContract.Events.SYNC_DATA1, etag);
            if (onDevice.containsKey(object.name))
                provider.update(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values.get(),
                        CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?",
                        new String[]{Long.toString(calendarId), object.name});
            else
                provider.insert(asSyncAdapter(CalendarContract.Events.CONTENT_URI), values.get());
            changes++;
        }
        List<String> removed = new ArrayList<>();
        for (String name : onDevice.keySet()) {
            if (! seen.contains(name))
                removed.add(name);
        }
        for (String name : removed) {
            provider.delete(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                    CalendarContract.Events.CALENDAR_ID + "=? AND " + CalendarContract.Events._SYNC_ID + "=?",
                    new String[]{Long.toString(calendarId), name});
            changes++;
        }
        return changes;
    }

    /** Member name to the ETag we stored when we last wrote it. */
    private Map<String, String> existingEvents(long calendarId) throws Exception {
        Map<String, String> byName = new HashMap<>();
        try (Cursor cursor = provider.query(asSyncAdapter(CalendarContract.Events.CONTENT_URI),
                new String[]{CalendarContract.Events._SYNC_ID, CalendarContract.Events.SYNC_DATA1},
                CalendarContract.Events.CALENDAR_ID + "=?",
                new String[]{Long.toString(calendarId)}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (! cursor.isNull(0))
                    byName.put(cursor.getString(0), cursor.isNull(1) ? "" : cursor.getString(1));
            }
        }
        return byName;
    }

    private static java.util.Optional<Integer> parseColour(String colour) {
        if (colour == null || ! colour.startsWith("#") || colour.length() != 7)
            return java.util.Optional.empty();
        try {
            return java.util.Optional.of(0xff000000 | Integer.parseInt(colour.substring(1), 16));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Writes only count as sync-adapter writes with these parameters, and only those may
     * set _SYNC_ID or clear the dirty flag without marking the row dirty again.
     */
    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account.name)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, account.type)
                .build();
    }
}
