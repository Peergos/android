package peergos.android.contacts;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.ContactStore;

/**
 * Keeps the Peergos address books and ContactsContract in step.
 *
 * Each pass uploads local edits first and then reconciles against Peergos, so the download
 * half sees whatever the upload half just wrote — and restores anything the upload half
 * decided it had lost, which is how a conflicted contact gets its remote version back while
 * the local edit survives alongside it as a new contact.
 *
 * ContactsContract has no address book: a raw contact belongs to an account and nothing
 * finer. So the book is carried on the raw contact itself — SYNC2 names it, and SOURCE_ID
 * holds {@code <book>/<file>}, which is both unique across books and the same path the
 * CardDAV bridge serves the contact at. SYNC1 holds the ETag we last wrote, so an unchanged
 * contact costs a listing and is never re-downloaded.
 */
public class ContactMirror {

    private static final String TAG = "PeergosContacts";

    private final ContentProviderClient provider;
    private final Account account;
    private final ContactStore store;

    public ContactMirror(ContentProviderClient provider, Account account, ContactStore store) {
        this.provider = provider;
        this.account = account;
        this.store = store;
    }

    /**
     * Only one pass at a time, for the same reason the calendar mirror has this: the
     * framework will not run two syncs for one account and authority at once, but a manual
     * pass can overlap one it scheduled, and two passes interleaved insert every contact
     * twice — each reads the device state before the other has written its half.
     */
    private static final Object passLock = new Object();

    /** @return the number of contacts added, updated or removed. */
    public int sync() throws Exception {
        synchronized (passLock) {
            return runPass();
        }
    }

    private int runPass() throws Exception {
        List<AppDataStore.CollectionInfo> books = store.listCollections();
        if (books.isEmpty())
            return 0;
        ContactUploader uploader = new ContactUploader(provider, account, store);
        // A contact added in the phone's own app names no address book, because the phone
        // has no idea there is more than one. It goes to the first, which is the default
        // book unless the user has made others.
        int changes = uploader.uploadNewContacts(books.get(0).directory);
        Set<String> wanted = new HashSet<>();
        for (AppDataStore.CollectionInfo book : books) {
            wanted.add(book.directory);
            changes += uploader.upload(book.directory);
            changes += download(book.directory);
        }
        return changes + removeVanishedBooks(wanted);
    }

    /** What we hold on the device for one contact. */
    private static final class Existing {
        final long id;
        final String etag;
        /** Local changes not yet in Peergos: a card of ours it would be wrong to expect there. */
        final boolean pending;

        Existing(long id, String etag, boolean pending) {
            this.id = id;
            this.etag = etag;
            this.pending = pending;
        }
    }

    private int download(String directory) throws Exception {
        Map<String, Existing> onDevice = existingContacts(directory);
        Set<String> seen = new HashSet<>();
        int changes = 0;
        for (AppDataStore.ObjectRef object : store.listObjects(directory)) {
            seen.add(object.name);
            String etag = object.etag();
            Existing existing = onDevice.get(object.name);
            if (existing != null && etag.equals(existing.etag))
                continue;
            String vcf = new String(store.read(object), StandardCharsets.UTF_8);
            Optional<List<ContentValues>> rows = VCardTranslator.toDataRows(vcf);
            if (rows.isEmpty() || rows.get().isEmpty()) {
                Log.w(TAG, "Skipping " + directory + "/" + object.name + ": nothing a contact can hold");
                continue;
            }
            if (existing == null)
                insert(directory, object.name, etag, rows.get());
            else
                update(existing.id, etag, rows.get());
            changes++;
        }
        List<Existing> removed = new ArrayList<>();
        for (Map.Entry<String, Existing> held : onDevice.entrySet()) {
            // A contact the upload half named but has not managed to write is missing from
            // Peergos because it has never been there, not because it was deleted there.
            if (! seen.contains(held.getKey()) && ! held.getValue().pending)
                removed.add(held.getValue());
        }
        for (Existing gone : removed) {
            provider.delete(asSyncAdapter(RawContacts.CONTENT_URI),
                    RawContacts._ID + "=?", new String[]{Long.toString(gone.id)});
            changes++;
        }
        return changes;
    }

    /** File name to what we hold for it, for one address book. */
    private Map<String, Existing> existingContacts(String directory) throws Exception {
        Map<String, Existing> byName = new HashMap<>();
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.DIRTY},
                ours(RawContacts.SYNC2 + "=?"), args(directory), null)) {
            while (cursor != null && cursor.moveToNext()) {
                if (cursor.isNull(1))
                    continue;
                String name = nameIn(directory, cursor.getString(1));
                if (name == null)
                    continue;
                String etag = cursor.isNull(2) ? "" : cursor.getString(2);
                byName.put(name, new Existing(cursor.getLong(0), etag,
                        etag.isEmpty() || cursor.getInt(3) == 1));
            }
        }
        return byName;
    }

    private void insert(String directory, String name, String etag, List<ContentValues> rows)
            throws Exception {
        ContentValues contact = new ContentValues();
        contact.put(RawContacts.ACCOUNT_NAME, account.name);
        contact.put(RawContacts.ACCOUNT_TYPE, account.type);
        contact.put(RawContacts.SOURCE_ID, sourceId(directory, name));
        contact.put(RawContacts.SYNC1, etag);
        contact.put(RawContacts.SYNC2, directory);
        ArrayList<ContentProviderOperation> batch = new ArrayList<>();
        batch.add(ContentProviderOperation.newInsert(asSyncAdapter(RawContacts.CONTENT_URI))
                .withValues(contact).build());
        for (ContentValues row : rows) {
            // The raw contact does not exist until the batch runs, so its id is a reference
            // back to the first operation rather than a value.
            batch.add(ContentProviderOperation.newInsert(asSyncAdapter(Data.CONTENT_URI))
                    .withValues(row)
                    .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                    .build());
        }
        provider.applyBatch(batch);
    }

    private void update(long rawContactId, String etag, List<ContentValues> rows) throws Exception {
        boolean hasPhoto = rows.stream()
                .anyMatch(r -> Photo.CONTENT_ITEM_TYPE.equals(r.getAsString(Data.MIMETYPE)));
        ContentValues contact = new ContentValues();
        contact.put(RawContacts.SYNC1, etag);
        // The local rows are about to be replaced by the remote ones, so whatever made this
        // row dirty has either been uploaded or lost the conflict; either way it is settled.
        contact.put(RawContacts.DIRTY, 0);
        ArrayList<ContentProviderOperation> batch = new ArrayList<>();
        batch.add(ContentProviderOperation.newUpdate(asSyncAdapter(RawContacts.CONTENT_URI))
                .withSelection(RawContacts._ID + "=?", new String[]{Long.toString(rawContactId)})
                .withValues(contact).build());
        // Rebuilt rather than diffed: a vCard property has no id to match a data row by, so
        // there is nothing to line an old row up with a new one. A photo is the exception —
        // the card may carry none because a CardDAV client never sent one, and dropping the
        // one on the phone would be losing data the card never claimed to hold.
        batch.add(ContentProviderOperation.newDelete(asSyncAdapter(Data.CONTENT_URI))
                .withSelection(hasPhoto ? Data.RAW_CONTACT_ID + "=?"
                                : Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "<>?",
                        hasPhoto ? new String[]{Long.toString(rawContactId)}
                                : new String[]{Long.toString(rawContactId), Photo.CONTENT_ITEM_TYPE})
                .build());
        for (ContentValues row : rows) {
            batch.add(ContentProviderOperation.newInsert(asSyncAdapter(Data.CONTENT_URI))
                    .withValues(row)
                    .withValue(Data.RAW_CONTACT_ID, rawContactId)
                    .build());
        }
        provider.applyBatch(batch);
    }

    /**
     * Drops the contacts of an address book that is no longer there. Deleting them as the
     * sync adapter removes them outright rather than queueing a deletion to push back,
     * which is what we want: the book is already gone in Peergos.
     */
    private int removeVanishedBooks(Set<String> wanted) throws Exception {
        Set<String> gone = new HashSet<>();
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts.SYNC2}, ours(RawContacts.SYNC2 + " IS NOT NULL"),
                args(), null)) {
            while (cursor != null && cursor.moveToNext()) {
                String book = cursor.getString(0);
                if (! wanted.contains(book))
                    gone.add(book);
            }
        }
        int changes = 0;
        for (String book : gone) {
            changes += provider.delete(asSyncAdapter(RawContacts.CONTENT_URI),
                    ours(RawContacts.SYNC2 + "=?"), args(book));
            Log.i(TAG, "Removed the contacts of address book " + book + ", which is gone in Peergos");
        }
        return changes;
    }

    static String sourceId(String directory, String name) {
        return directory + "/" + name;
    }

    /** The file name in a source id, or null if it belongs to another book. */
    static String nameIn(String directory, String sourceId) {
        String prefix = directory + "/";
        return sourceId.startsWith(prefix) ? sourceId.substring(prefix.length()) : null;
    }

    /**
     * The account clause. The URI parameters already scope a query to this account, but a
     * selection that says so as well cannot be defeated by a provider that ignores them,
     * and these are other people's contacts.
     */
    private String ours(String selection) {
        return RawContacts.ACCOUNT_NAME + "=? AND " + RawContacts.ACCOUNT_TYPE + "=? AND (" + selection + ")";
    }

    private String[] args(String... rest) {
        String[] args = new String[rest.length + 2];
        args[0] = account.name;
        args[1] = account.type;
        System.arraycopy(rest, 0, args, 2, rest.length);
        return args;
    }

    /**
     * Writes only count as sync-adapter writes with these parameters, and only those may
     * set SOURCE_ID or clear the dirty flag without marking the row dirty again.
     */
    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build();
    }
}
