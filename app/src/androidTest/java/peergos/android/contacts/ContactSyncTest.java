package peergos.android.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.Data;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Optional;

import peergos.android.sync.PeergosAccount;

@RunWith(AndroidJUnit4.class)
public class ContactSyncTest {

    private static final String USER = "androidtest-contacts-user";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @After
    public void removeAccount() {
        AccountManager manager = AccountManager.get(context());
        for (Account account : manager.getAccountsByType(PeergosAccount.TYPE))
            manager.removeAccountExplicitly(account);
    }

    @Test
    public void oneAccountCarriesBothAuthorities() {
        Account account = PeergosAccount.ensure(context(), USER);
        PeergosAccount.startSyncing(account, ContactsContract.AUTHORITY);
        assertEquals(1, ContentResolver.getIsSyncable(account, ContactsContract.AUTHORITY));
        assertTrue("automatic sync should be on",
                ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY));

        // Turning contacts off must leave the calendar alone: they are two switches.
        PeergosAccount.startSyncing(account, android.provider.CalendarContract.AUTHORITY);
        PeergosAccount.stopSyncing(context(), ContactsContract.AUTHORITY);
        assertFalse(ContentResolver.getSyncAutomatically(account, ContactsContract.AUTHORITY));
        assertTrue("the calendar should still be syncing",
                ContentResolver.getSyncAutomatically(account, android.provider.CalendarContract.AUTHORITY));

        assertEquals("stopping one authority must not remove the account",
                1, AccountManager.get(context()).getAccountsByType(PeergosAccount.TYPE).length);
    }

    @Test
    public void mapsTheFieldsAContactIsMadeOf() {
        List<ContentValues> rows = rows("BEGIN:VCARD\r\nVERSION:3.0\r\nUID:alice\r\n"
                + "FN:Alice Smith\r\nN:Smith;Alice;Jane;Dr;PhD\r\n"
                + "TEL;TYPE=CELL:+447777\r\nTEL;TYPE=WORK;TYPE=VOICE:+442222\r\n"
                + "EMAIL;TYPE=WORK:alice@work.com\r\n"
                + "ADR;TYPE=HOME:;;1 High St;London;;N1 1AA;UK\r\n"
                + "ORG:Peergos;Engineering\r\nTITLE:Developer\r\n"
                + "NOTE:Met at a conference\r\nURL:https://peergos.org\r\n"
                + "BDAY:19800229\r\nEND:VCARD\r\n");

        ContentValues name = rowOf(rows, StructuredName.CONTENT_ITEM_TYPE);
        assertEquals("Alice Smith", name.getAsString(StructuredName.DISPLAY_NAME));
        assertEquals("Smith", name.getAsString(StructuredName.FAMILY_NAME));
        assertEquals("Jane", name.getAsString(StructuredName.MIDDLE_NAME));
        assertEquals("Dr", name.getAsString(StructuredName.PREFIX));
        assertEquals("PhD", name.getAsString(StructuredName.SUFFIX));

        List<ContentValues> phones = rowsOf(rows, Phone.CONTENT_ITEM_TYPE);
        assertEquals(2, phones.size());
        assertEquals(Integer.valueOf(Phone.TYPE_MOBILE), phones.get(0).getAsInteger(Phone.TYPE));
        assertEquals(Integer.valueOf(Phone.TYPE_WORK), phones.get(1).getAsInteger(Phone.TYPE));

        ContentValues address = rowOf(rows, StructuredPostal.CONTENT_ITEM_TYPE);
        assertEquals("1 High St", address.getAsString(StructuredPostal.STREET));
        assertEquals("London", address.getAsString(StructuredPostal.CITY));
        assertEquals("N1 1AA", address.getAsString(StructuredPostal.POSTCODE));
        assertEquals(Integer.valueOf(StructuredPostal.TYPE_HOME),
                address.getAsInteger(StructuredPostal.TYPE));

        // ORG and TITLE are one row, which is how the phone shows a job.
        ContentValues org = rowOf(rows, Organization.CONTENT_ITEM_TYPE);
        assertEquals("Peergos", org.getAsString(Organization.COMPANY));
        assertEquals("Engineering", org.getAsString(Organization.DEPARTMENT));
        assertEquals("Developer", org.getAsString(Organization.TITLE));

        assertEquals("Met at a conference", rowOf(rows, Note.CONTENT_ITEM_TYPE).getAsString(Note.NOTE));
        assertEquals("1980-02-29", rowOf(rows, Event.CONTENT_ITEM_TYPE).getAsString(Event.START_DATE));
        assertEquals(Integer.valueOf(Email.TYPE_WORK),
                rowOf(rows, Email.CONTENT_ITEM_TYPE).getAsInteger(Email.TYPE));
    }

    /** A folded line is one property, and an escaped separator is part of the value. */
    @Test
    public void readsFoldedAndEscapedValues() {
        List<ContentValues> rows = rows("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\n"
                + "NOTE:a long note\r\n  that was folded\r\n"
                + "ADR:;;1 High St\\; Flat 2;London;;N1 1AA;UK\r\nEND:VCARD\r\n");
        assertEquals("a long note that was folded",
                rowOf(rows, Note.CONTENT_ITEM_TYPE).getAsString(Note.NOTE));
        assertEquals("1 High St; Flat 2",
                rowOf(rows, StructuredPostal.CONTENT_ITEM_TYPE).getAsString(StructuredPostal.STREET));
    }

    @Test
    public void readsAnInlinePhotoAndSkipsOneItWouldHaveToFetch() {
        // "hello" as base64, which is a photo as far as the provider is concerned
        List<ContentValues> inline = rows("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\n"
                + "PHOTO;ENCODING=b;TYPE=JPEG:aGVsbG8=\r\nEND:VCARD\r\n");
        assertEquals("hello", new String(rowOf(inline, Photo.CONTENT_ITEM_TYPE)
                .getAsByteArray(Photo.PHOTO)));

        List<ContentValues> linked = rows("BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Bob\r\n"
                + "PHOTO;VALUE=URI:https://example.com/bob.jpg\r\nEND:VCARD\r\n");
        assertTrue("a photo we would have to go and fetch is left out",
                rowsOf(linked, Photo.CONTENT_ITEM_TYPE).isEmpty());
    }

    /** The provider wants --MM-dd for a birthday with no year; a vCard writes it without the dash. */
    @Test
    public void mapsBirthdaysBothWays() {
        assertEquals(Optional.of("--02-29"), VCardTranslator.birthday("--0229"));
        assertEquals(Optional.of("1980-02-29"), VCardTranslator.birthday("1980-02-29"));
        assertEquals(Optional.of("1980-02-29"), VCardTranslator.birthday("19800229T000000Z"));
        assertEquals(Optional.empty(), VCardTranslator.birthday("sometime in spring"));

        assertEquals(Optional.of("--0229"), ContactUploader.birthday("--02-29"));
        assertEquals(Optional.of("1980-02-29"), ContactUploader.birthday("1980-02-29"));
        assertEquals(Optional.empty(), ContactUploader.birthday(""));
    }

    @Test
    public void skipsWhatIsNotAContact() {
        assertTrue(VCardTranslator.toDataRows("not a vcard at all").isEmpty());
        assertTrue("a calendar object is not a contact",
                VCardTranslator.toDataRows("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n").isEmpty());
    }

    /** The source id is the path the CardDAV bridge serves the contact at. */
    @Test
    public void addressBooksAreCarriedOnTheSourceId() {
        assertEquals("work/alice.vcf", ContactMirror.sourceId("work", "alice.vcf"));
        assertEquals("alice.vcf", ContactMirror.nameIn("work", "work/alice.vcf"));
        assertEquals(null, ContactMirror.nameIn("home", "work/alice.vcf"));
    }

    private static List<ContentValues> rows(String vcf) {
        Optional<List<ContentValues>> rows = VCardTranslator.toDataRows(vcf);
        assertTrue("should have produced rows", rows.isPresent());
        return rows.get();
    }

    private static ContentValues rowOf(List<ContentValues> rows, String mimetype) {
        List<ContentValues> found = rowsOf(rows, mimetype);
        assertFalse("expected a " + mimetype + " row", found.isEmpty());
        return found.get(0);
    }

    private static List<ContentValues> rowsOf(List<ContentValues> rows, String mimetype) {
        return rows.stream().filter(r -> mimetype.equals(r.getAsString(Data.MIMETYPE)))
                .collect(java.util.stream.Collectors.toList());
    }
}
