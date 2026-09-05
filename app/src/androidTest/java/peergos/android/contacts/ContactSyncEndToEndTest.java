package peergos.android.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentProviderClient;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.GrantPermissionRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Random;

import peergos.android.AndroidPoster;
import peergos.android.PeergosSession;
import peergos.android.ScryptAndroid;
import peergos.android.sync.PeergosAccount;
import peergos.server.Main;
import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.ContactStore;
import peergos.shared.Crypto;
import peergos.shared.NetworkAccess;
import peergos.shared.storage.ContentAddressedStorage;
import peergos.shared.user.App;
import peergos.shared.user.HttpPoster;
import peergos.shared.user.UserContext;
import peergos.shared.util.PathUtil;

/**
 * The contacts sync adapter against a real Peergos server, which is the only way to know
 * that the layout the mirror reads is the layout the CardDAV bridge writes.
 *
 * Host and port come from instrumentation args, exactly as in
 * {@link peergos.android.calendar.CalendarSyncEndToEndTest}.
 */
@RunWith(AndroidJUnit4.class)
public class ContactSyncEndToEndTest {

    @Rule
    public GrantPermissionRule permissions = GrantPermissionRule.grant(
            android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS);

    private Context context;
    private UserContext session;
    private Account account;
    private ContentProviderClient provider;
    private String username;

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build();
    }

    @Before
    public void signUp() throws Exception {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Crypto crypto = Main.initCrypto(new ScryptAndroid());
        android.os.Bundle args = InstrumentationRegistry.getArguments();
        String host = args.getString("peergosHost", "localhost");
        int port = Integer.parseInt(args.getString("peergosPort", "7777"));
        HttpPoster poster = new AndroidPoster(new URL("http://" + host + ":" + port), false,
                Optional.empty(), Optional.of("Peergos-android-contacts-test"));
        ContentAddressedStorage localDht = NetworkAccess.buildLocalDht(poster, true, crypto.hasher);
        NetworkAccess network = NetworkAccess.buildViaPeergosInstance(poster, poster, localDht,
                7_000, crypto.hasher, false).join();

        username = "androidcard" + Math.abs(new Random().nextInt() % 1_000_000);
        session = UserContext.signUp(username, "test-password-1", "", network, crypto).join();
        PeergosSession.publish(session, session.network, session.crypto);
        account = PeergosAccount.ensure(context, username);
        // The test drives ContactMirror directly, so stop the framework scheduling its own
        // pass alongside: two overlapping passes are what the lock in the mirror prevents,
        // and letting one run here would make the assertions racy.
        android.content.ContentResolver.setSyncAutomatically(account, ContactsContract.AUTHORITY, false);
        android.content.ContentResolver.removePeriodicSync(account, ContactsContract.AUTHORITY,
                android.os.Bundle.EMPTY);
        android.content.ContentResolver.cancelSync(account, ContactsContract.AUTHORITY);
        provider = context.getContentResolver()
                .acquireContentProviderClient(ContactsContract.AUTHORITY);
        assertNotNull(provider);
    }

    @After
    public void tearDown() {
        if (account != null) {
            try {
                context.getContentResolver().delete(asSyncAdapter(RawContacts.CONTENT_URI), null, null);
            } catch (RuntimeException ignored) {
                // best effort; the account removal below takes the contacts with it
            }
            AccountManager manager = AccountManager.get(context);
            for (Account existing : manager.getAccountsByType(PeergosAccount.TYPE))
                manager.removeAccountExplicitly(existing);
        }
        if (provider != null)
            provider.close();
        PeergosSession.clear();
    }

    private ContactStore store() {
        return new ContactStore(session);
    }

    private static String card(String uid, String name, String extra) {
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//Peergos//test//EN\r\n"
                + "UID:" + uid + "\r\nFN:" + name + "\r\nN:" + name + ";;;;\r\n" + extra
                + "END:VCARD\r\n";
    }

    @Test
    public void peergosContactsAppearOnTheDevice() throws Exception {
        App contacts = App.init(session, "contacts").join();
        contacts.writeInternal(PathUtil.get("App.config"),
                "{\"addressbooks\":[{\"name\":\"Work\",\"directory\":\"work\"}]}"
                        .getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/addressbook.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/alice.vcf"),
                card("alice", "Alice", "TEL;TYPE=CELL:+447777\r\n").getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/bob.vcf"),
                card("bob", "Bob", "").getBytes(StandardCharsets.UTF_8), null).join();

        int changes = new ContactMirror(provider, account, store()).sync();
        assertTrue("the mirror should have written something, got " + changes, changes >= 2);

        assertEquals("one raw contact each: " + describe(), 2, contactCount());
        assertEquals("Alice", displayNameOf("work/alice.vcf"));
        assertEquals("+447777", numberOf("work/alice.vcf"));

        // A second pass with nothing changed must be a no-op, which is what the ETag check
        // buys: otherwise every sync rewrites every contact.
        assertEquals("an unchanged address book should cost no writes",
                0, new ContactMirror(provider, account, store()).sync());
        assertEquals("after two passes: " + describe(), 2, contactCount());

        // A deletion in Peergos removes it from the device.
        AppDataStore.ObjectRef bob = store().getObject("work", "bob.vcf").orElseThrow();
        store().deleteObject("work", bob);
        new ContactMirror(provider, account, store()).sync();
        assertEquals("deleted in Peergos, so gone from the device: " + describe(),
                0, countOf("work/bob.vcf"));
        assertEquals("the other contact survives", 1, countOf("work/alice.vcf"));
    }

    @Test
    public void aDeviceEditReachesPeergos() throws Exception {
        App contacts = App.init(session, "contacts").join();
        // App.config as well as the directory: without it the store also synthesises the
        // default address book, and the contact would land in whichever came first.
        contacts.writeInternal(PathUtil.get("App.config"),
                "{\"addressbooks\":[{\"name\":\"Work\",\"directory\":\"work\"}]}"
                        .getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/addressbook.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();
        new ContactMirror(provider, account, store()).sync();

        // As the phone's contacts app would: a plain write, so the rows land dirty and the
        // raw contact names no address book, because the phone has no notion of one.
        ContentValues raw = new ContentValues();
        raw.put(RawContacts.ACCOUNT_NAME, account.name);
        raw.put(RawContacts.ACCOUNT_TYPE, account.type);
        Uri inserted = context.getContentResolver().insert(RawContacts.CONTENT_URI, raw);
        assertNotNull(inserted);
        long rawContactId = ContentUris.parseId(inserted);
        ContentValues name = new ContentValues();
        name.put(Data.RAW_CONTACT_ID, rawContactId);
        name.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        name.put(StructuredName.DISPLAY_NAME, "Created on the phone");
        name.put(StructuredName.GIVEN_NAME, "Created");
        name.put(StructuredName.FAMILY_NAME, "Phone");
        assertNotNull(context.getContentResolver().insert(Data.CONTENT_URI, name));
        ContentValues note = new ContentValues();
        note.put(Data.RAW_CONTACT_ID, rawContactId);
        note.put(Data.MIMETYPE, ContactsContract.CommonDataKinds.Note.CONTENT_ITEM_TYPE);
        note.put(ContactsContract.CommonDataKinds.Note.NOTE, "Notes; with, punctuation");
        assertNotNull(context.getContentResolver().insert(Data.CONTENT_URI, note));

        new ContactMirror(provider, account, store()).sync();

        var objects = store().listObjects("work");
        assertEquals("exactly one contact should have reached Peergos", 1, objects.size());
        String vcf = new String(store().read(objects.get(0)), StandardCharsets.UTF_8);
        assertTrue("name: " + vcf, vcf.contains("FN:Created on the phone"));
        assertTrue("structured name: " + vcf, vcf.contains("N:Phone;Created;;;"));
        String backslash = "\\";
        assertTrue("text should be escaped: " + vcf,
                vcf.contains("Notes" + backslash + "; with" + backslash + ", punctuation"));
        assertTrue("a card must carry a version: " + vcf, vcf.contains("VERSION:3.0"));

        // And the row now names the book it went to, so it is not uploaded again.
        assertEquals("a second pass should push nothing",
                0, new ContactMirror(provider, account, store()).sync());
        assertEquals("work/" + objects.get(0).name, sourceIdOf(rawContactId));
    }

    /**
     * The state an interrupted upload leaves behind: the contact has been given a name but
     * its card never reached Peergos. It must be written under that name, not a new one —
     * writing first and naming second is what filled an address book with duplicates.
     */
    @Test
    public void aNamedButUnwrittenContactIsNotDuplicated() throws Exception {
        App contacts = App.init(session, "contacts").join();
        contacts.writeInternal(PathUtil.get("App.config"),
                "{\"addressbooks\":[{\"name\":\"Work\",\"directory\":\"work\"}]}"
                        .getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/addressbook.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();

        ContentValues raw = new ContentValues();
        raw.put(RawContacts.ACCOUNT_NAME, account.name);
        raw.put(RawContacts.ACCOUNT_TYPE, account.type);
        Uri inserted = context.getContentResolver().insert(RawContacts.CONTENT_URI, raw);
        assertNotNull(inserted);
        long rawContactId = ContentUris.parseId(inserted);
        ContentValues name = new ContentValues();
        name.put(Data.RAW_CONTACT_ID, rawContactId);
        name.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
        name.put(StructuredName.DISPLAY_NAME, "Interrupted");
        assertNotNull(context.getContentResolver().insert(Data.CONTENT_URI, name));

        // exactly what the upload half writes before it stores the card
        ContentValues reserved = new ContentValues();
        reserved.put(RawContacts.SOURCE_ID, "work/reserved.vcf");
        reserved.put(RawContacts.SYNC2, "work");
        assertEquals(1, provider.update(asSyncAdapter(RawContacts.CONTENT_URI), reserved,
                RawContacts._ID + "=?", new String[]{Long.toString(rawContactId)}));

        new ContactMirror(provider, account, store()).sync();

        var objects = store().listObjects("work");
        assertEquals("one card, not a second under a fresh name: " + describe(), 1, objects.size());
        assertEquals("written under the name it already had", "reserved.vcf", objects.get(0).name);
        String vcf = new String(store().read(objects.get(0)), StandardCharsets.UTF_8);
        assertTrue("the uid follows the file name: " + vcf, vcf.contains("UID:reserved"));
        assertTrue("and it is the contact we had: " + vcf, vcf.contains("FN:Interrupted"));

        // the download half must not have read the missing card as a deletion
        assertEquals("the contact survives: " + describe(), 1, contactCount());
        assertEquals("and a second pass has nothing left to do",
                0, new ContactMirror(provider, account, store()).sync());
    }

    /** Peergos wins a conflict, and the local edit survives beside it rather than vanishing. */
    @Test
    public void aConflictKeepsBothVersions() throws Exception {
        App contacts = App.init(session, "contacts").join();
        contacts.writeInternal(PathUtil.get("App.config"),
                "{\"addressbooks\":[{\"name\":\"Work\",\"directory\":\"work\"}]}"
                        .getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/addressbook.inf"),
                "{\"name\":\"Work\"}".getBytes(StandardCharsets.UTF_8), null).join();
        contacts.writeInternal(PathUtil.get("work/alice.vcf"),
                card("alice", "Alice", "").getBytes(StandardCharsets.UTF_8), null).join();
        new ContactMirror(provider, account, store()).sync();
        long rawContactId = idOf("work/alice.vcf");

        // Edited on the phone...
        ContentValues local = new ContentValues();
        local.put(StructuredName.DISPLAY_NAME, "Alice on the phone");
        assertEquals(1, context.getContentResolver().update(Data.CONTENT_URI, local,
                Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[]{Long.toString(rawContactId), StructuredName.CONTENT_ITEM_TYPE}));
        // ...and in Peergos, since the phone last saw it.
        contacts.writeInternal(PathUtil.get("work/alice.vcf"),
                card("alice", "Alice in Peergos", "").getBytes(StandardCharsets.UTF_8), null).join();

        new ContactMirror(provider, account, store()).sync();

        assertEquals("Peergos wins the name it holds", "Alice in Peergos", displayNameOf("work/alice.vcf"));
        assertEquals("and the local edit is kept as a second contact: " + describe(), 2, contactCount());
        assertTrue("the copy says where it came from: " + describe(),
                describe().contains("edited on this device"));
    }

    private long idOf(String sourceId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts._ID}, RawContacts.SOURCE_ID + "=?",
                new String[]{sourceId}, null)) {
            assertNotNull(cursor);
            assertTrue("expected " + sourceId + " on the device", cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private String sourceIdOf(long rawContactId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts.SOURCE_ID}, RawContacts._ID + "=?",
                new String[]{Long.toString(rawContactId)}, null)) {
            assertNotNull(cursor);
            assertTrue(cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    private String displayNameOf(String sourceId) throws Exception {
        return valueOf(idOf(sourceId), StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME);
    }

    private String numberOf(String sourceId) throws Exception {
        return valueOf(idOf(sourceId), Phone.CONTENT_ITEM_TYPE, Phone.NUMBER);
    }

    private String valueOf(long rawContactId, String mimetype, String column) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(Data.CONTENT_URI),
                new String[]{column},
                Data.RAW_CONTACT_ID + "=? AND " + Data.MIMETYPE + "=?",
                new String[]{Long.toString(rawContactId), mimetype}, null)) {
            assertNotNull(cursor);
            assertTrue("expected a " + mimetype + " row", cursor.moveToFirst());
            return cursor.getString(0);
        }
    }

    private int countOf(String sourceId) throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts._ID}, RawContacts.SOURCE_ID + "=?",
                new String[]{sourceId}, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    private int contactCount() throws Exception {
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts._ID},
                RawContacts.ACCOUNT_NAME + "=? AND " + RawContacts.DELETED + "=0",
                new String[]{account.name}, null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    /** Every raw contact of ours with its display name, for an assertion message. */
    private String describe() throws Exception {
        StringBuilder out = new StringBuilder();
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI),
                new String[]{RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC2,
                        RawContacts.DIRTY, RawContacts.DELETED},
                RawContacts.ACCOUNT_NAME + "=?", new String[]{account.name}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                out.append("[id=").append(cursor.getLong(0))
                        .append(" src=").append(cursor.getString(1))
                        .append(" book=").append(cursor.getString(2))
                        .append(" dirty=").append(cursor.getInt(3))
                        .append(" del=").append(cursor.getInt(4));
                try {
                    out.append(" name=").append(valueOf(cursor.getLong(0),
                            StructuredName.CONTENT_ITEM_TYPE, StructuredName.DISPLAY_NAME));
                } catch (AssertionError | Exception unnamed) {
                    out.append(" name=?");
                }
                out.append("] ");
            }
        }
        return out.toString();
    }
}
