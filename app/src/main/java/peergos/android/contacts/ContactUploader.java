package peergos.android.contacts;

import android.accounts.Account;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import peergos.server.webdav.caldav.AppDataStore;
import peergos.server.webdav.caldav.ContactStore;

/**
 * Pushes local contact edits back into Peergos.
 *
 * Peergos is the source of truth. Where both sides changed a contact since the last sync —
 * which the stored ETag makes exact — the Peergos version is kept and the local edit is
 * written out as a *new* contact rather than discarded. The user then sees both and
 * decides, which is the one outcome that cannot silently lose work.
 *
 * A photo taken on the phone does not travel: the stored card is patched rather than
 * rewritten, and PHOTO is deliberately not one of the properties patched, so a photo a
 * CardDAV client put there survives a name being edited here. The phone is not the source
 * of truth for one.
 */
public class ContactUploader {

    private static final String TAG = "PeergosContacts";

    /** How many new contacts share one commit. */
    private static final int CHUNK = 100;

    /** The properties rebuilt from the data rows. PHOTO is not among them, on purpose. */
    private static final List<String> MANAGED = Arrays.asList(
            "FN", "N", "NICKNAME", "TEL", "EMAIL", "ADR", "ORG", "TITLE", "NOTE", "URL", "BDAY", "REV");

    private static final String[] CONTACT_COLUMNS = {
            RawContacts._ID,
            RawContacts.SOURCE_ID,
            RawContacts.SYNC1,
            RawContacts.DELETED,
    };

    /**
     * Every CommonDataKinds column is an alias of one of the generic data columns, so one
     * projection serves every mimetype and a value can still be read out by the named
     * constant that describes it.
     */
    private static final String[] DATA_COLUMNS = {
            Data.MIMETYPE,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5,
            Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9, Data.DATA10,
    };

    private final ContentProviderClient provider;
    private final Account account;
    private final ContactStore store;

    public ContactUploader(ContentProviderClient provider, Account account, ContactStore store) {
        this.provider = provider;
        this.account = account;
        this.store = store;
    }

    /** Local changes to the contacts of one address book. @return how many were pushed. */
    public int upload(String directory) throws Exception {
        return push(dirtyRows(RawContacts.SYNC2 + "=?", directory), directory);
    }

    /**
     * Contacts created in the phone's own app, which named no address book because the
     * phone has no notion of one, filed into the book the mirror chose for them.
     */
    public int uploadNewContacts(String directory) throws Exception {
        return push(dirtyRows(RawContacts.SYNC2 + " IS NULL", null), directory);
    }

    private int push(List<Row> rows, String directory) {
        int pushed = 0;
        List<Row> fresh = new ArrayList<>();
        for (Row row : rows) {
            // Contacts Peergos has no confirmed copy of go up together: a phone handing over
            // its whole address book is hundreds of these, and one commit each is hours.
            if (isNew(row, directory)) {
                fresh.add(row);
                continue;
            }
            try {
                pushed += apply(row, directory) ? 1 : 0;
            } catch (Exception e) {
                // One bad contact should not stop the rest; it stays dirty and is retried.
                Log.w(TAG, "Could not upload contact " + row.id, e);
            }
        }
        return pushed + createAll(fresh, directory);
    }

    /**
     * A contact Peergos holds no confirmed copy of: one that has never been named, or one
     * named by a pass that did not get as far as writing it. Both are written by the same
     * path, which is what makes an interrupted upload cost a repeat rather than a duplicate.
     */
    private static boolean isNew(Row row, String directory) {
        if (row.deleted)
            return false;
        if (row.sourceId == null)
            return true;
        return row.etag.isEmpty() && ContactMirror.nameIn(directory, row.sourceId) != null;
    }

    /**
     * The new contacts, in chunks. A chunk is one commit and two provider transactions, and
     * the chunking is what makes a sync the framework cancels half way still leave progress
     * behind: whole chunks are done, and what is left is still dirty and retried.
     */
    private int createAll(List<Row> rows, String directory) {
        int pushed = 0;
        for (int start = 0; start < rows.size(); start += CHUNK) {
            List<Row> chunk = rows.subList(start, Math.min(rows.size(), start + CHUNK));
            try {
                pushed += createChunk(chunk, directory);
            } catch (Exception e) {
                Log.w(TAG, "Could not upload " + chunk.size() + " new contacts", e);
            }
        }
        return pushed;
    }

    private int createChunk(List<Row> rows, String directory) throws Exception {
        Map<Long, String> names = new LinkedHashMap<>();
        ArrayList<ContentProviderOperation> reserve = new ArrayList<>();
        for (Row row : rows) {
            if (row.sourceId != null) {
                // named by an earlier pass that never got the card written
                names.put(row.id, ContactMirror.nameIn(directory, row.sourceId));
                continue;
            }
            String name = UUID.randomUUID().toString() + ContactStore.VCF_SUFFIX;
            names.put(row.id, name);
            reserve.add(ContentProviderOperation.newUpdate(asSyncAdapter(RawContacts.CONTENT_URI))
                    .withSelection(RawContacts._ID + "=?", new String[]{Long.toString(row.id)})
                    .withValue(RawContacts.SOURCE_ID, ContactMirror.sourceId(directory, name))
                    .withValue(RawContacts.SYNC2, directory)
                    .build());
        }
        // The name goes on the row before the card is written, so a pass that dies in
        // between leaves a contact that is still dirty and still knows what it is called.
        // Writing first and naming after is what turns an interrupted upload into a second
        // copy of every contact in the chunk. The row stays dirty either way — only the
        // confirmation below clears it — so nothing is treated as synced early.
        if (! reserve.isEmpty())
            provider.applyBatch(reserve);

        List<AppDataStore.NewObject> cards = new ArrayList<>();
        for (Row row : rows) {
            String name = names.get(row.id);
            try {
                cards.add(new AppDataStore.NewObject(name, VCardWriter
                        .create(uidFor(name), properties(row, dataRows(row.id)))
                        .getBytes(StandardCharsets.UTF_8)));
            } catch (Exception e) {
                // as in the single case: one unreadable contact must not cost the chunk
                Log.w(TAG, "Could not read contact " + row.id, e);
            }
        }
        Map<String, AppDataStore.ObjectRef> written = store.putObjects(directory, cards);

        ArrayList<ContentProviderOperation> confirm = new ArrayList<>();
        for (Map.Entry<Long, String> uploaded : names.entrySet()) {
            AppDataStore.ObjectRef stored = written.get(uploaded.getValue());
            // Nothing confirmed against a card that did not land, so it stays dirty and the
            // next pass writes it again under the name it already has.
            if (stored == null)
                continue;
            confirm.add(ContentProviderOperation.newUpdate(asSyncAdapter(RawContacts.CONTENT_URI))
                    .withSelection(RawContacts._ID + "=?", new String[]{Long.toString(uploaded.getKey())})
                    .withValue(RawContacts.SYNC1, stored.etag())
                    .withValue(RawContacts.DIRTY, 0)
                    .build());
        }
        if (confirm.isEmpty())
            return 0;
        provider.applyBatch(confirm);
        return confirm.size();
    }

    /** A row that is not a plain creation: a deletion, or an edit to a contact Peergos has. */
    private boolean apply(Row row, String directory) throws Exception {
        if (row.deleted)
            return delete(row, directory);
        String name = ContactMirror.nameIn(directory, row.sourceId);
        if (name == null) {
            // Its source id names another book, so this pass is not the one that owns it.
            Log.w(TAG, "Contact " + row.id + " is not in " + directory + ": " + row.sourceId);
            return false;
        }
        return update(row, directory, name);
    }

    private boolean delete(Row row, String directory) throws Exception {
        String name = row.sourceId == null ? null : ContactMirror.nameIn(directory, row.sourceId);
        if (name == null) {
            purge(row.id);
            return false;
        }
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, name);
        if (remote.isPresent() && ! remote.get().etag().equals(row.etag)) {
            // Changed in Peergos since we last saw it, so the delete loses: dropping the
            // row lets the download pass restore the newer version.
            Log.i(TAG, "Deletion of " + name + " conflicts with a remote change; keeping the remote copy");
            purge(row.id);
            return false;
        }
        remote.ifPresent(object -> store.deleteObject(directory, object));
        purge(row.id);
        return true;
    }

    private boolean update(Row row, String directory, String name) throws Exception {
        Optional<AppDataStore.ObjectRef> remote = store.getObject(directory, name);
        if (remote.isEmpty()) {
            // Removed in Peergos while we were editing. Writing it back under the same name
            // resurrects the user's version rather than dropping their edit.
            write(directory, name, VCardWriter.create(uidFor(name),
                    properties(row, dataRows(row.id))), row.id);
            return true;
        }
        String existing = new String(store.read(remote.get()), StandardCharsets.UTF_8);
        if (! remote.get().etag().equals(row.etag)) {
            duplicate(row, directory);
            purge(row.id);
            return true;
        }
        write(directory, name,
                VCardWriter.patch(existing, properties(row, dataRows(row.id)), MANAGED), row.id);
        return true;
    }

    /** Writes the local version as a new contact, leaving the remote one alone. */
    private void duplicate(Row row, String directory) throws Exception {
        String uid = UUID.randomUUID().toString();
        List<DataRow> data = dataRows(row.id);
        List<VCardWriter.Line> properties = properties(row, data);
        // FN comes first out of properties(), and a card must not carry two of them
        properties.set(0, VCardWriter.text("FN",
                displayName(row, data) + " (edited on this device)"));
        store.putObject(directory, uid + ContactStore.VCF_SUFFIX,
                VCardWriter.create(uid, properties).getBytes(StandardCharsets.UTF_8), Optional.empty());
        Log.i(TAG, "Kept a conflicting local edit as " + uid);
    }

    /**
     * An address book is flat, so a card can never move between shards and the write needs
     * no listing to find where the old copy was — and the ref it returns is where the new
     * ETag comes from. Both matter: a listing walks every contact in the book, and doing
     * that twice per contact is quadratic in the size of the address book.
     */
    private void write(String directory, String name, String vcf, long rawContactId) throws Exception {
        AppDataStore.ObjectRef stored = store.putObject(directory, name,
                vcf.getBytes(StandardCharsets.UTF_8), Optional.empty());
        ContentValues values = new ContentValues();
        values.put(RawContacts.SOURCE_ID, ContactMirror.sourceId(directory, name));
        values.put(RawContacts.SYNC1, stored.etag());
        values.put(RawContacts.SYNC2, directory);
        values.put(RawContacts.DIRTY, 0);
        provider.update(asSyncAdapter(RawContacts.CONTENT_URI), values,
                RawContacts._ID + "=?", new String[]{Long.toString(rawContactId)});
    }

    /** The UID already in the file name, so an update keeps the contact's identity. */
    private static String uidFor(String name) {
        return name.endsWith(ContactStore.VCF_SUFFIX)
                ? name.substring(0, name.length() - ContactStore.VCF_SUFFIX.length())
                : UUID.randomUUID().toString();
    }

    // ------------------------------------------------------------------ the card itself

    /** The properties a vCard can be given from what the contacts provider holds. */
    private List<VCardWriter.Line> properties(Row row, List<DataRow> data) {
        List<VCardWriter.Line> lines = new ArrayList<>();
        lines.add(VCardWriter.text("FN", displayName(row, data)));
        structuredName(data).ifPresent(lines::add);
        for (DataRow item : data) {
            switch (item.mimetype) {
                case Nickname.CONTENT_ITEM_TYPE:
                    add(lines, "NICKNAME", item.get(Nickname.NAME));
                    break;
                case Phone.CONTENT_ITEM_TYPE:
                    if (! item.get(Phone.NUMBER).isEmpty())
                        lines.add(VCardWriter.typed("TEL", phoneType(item.type(Phone.TYPE)),
                                item.get(Phone.NUMBER)));
                    break;
                case Email.CONTENT_ITEM_TYPE:
                    if (! item.get(Email.ADDRESS).isEmpty())
                        lines.add(VCardWriter.typed("EMAIL", homeOrWork(item.type(Email.TYPE),
                                Email.TYPE_HOME, Email.TYPE_WORK), item.get(Email.ADDRESS)));
                    break;
                case StructuredPostal.CONTENT_ITEM_TYPE: {
                    List<String> parts = Arrays.asList(
                            item.get(StructuredPostal.POBOX),
                            item.get(StructuredPostal.NEIGHBORHOOD),
                            item.get(StructuredPostal.STREET),
                            item.get(StructuredPostal.CITY),
                            item.get(StructuredPostal.REGION),
                            item.get(StructuredPostal.POSTCODE),
                            item.get(StructuredPostal.COUNTRY));
                    if (parts.stream().anyMatch(p -> ! p.isEmpty()))
                        lines.add(VCardWriter.typedStructured("ADR",
                                homeOrWork(item.type(StructuredPostal.TYPE),
                                        StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK),
                                parts));
                    break;
                }
                case Organization.CONTENT_ITEM_TYPE: {
                    String company = item.get(Organization.COMPANY);
                    String department = item.get(Organization.DEPARTMENT);
                    if (! company.isEmpty() || ! department.isEmpty())
                        lines.add(VCardWriter.structured("ORG", department.isEmpty()
                                ? Arrays.asList(company) : Arrays.asList(company, department)));
                    add(lines, "TITLE", item.get(Organization.TITLE));
                    break;
                }
                case Note.CONTENT_ITEM_TYPE:
                    add(lines, "NOTE", item.get(Note.NOTE));
                    break;
                case Website.CONTENT_ITEM_TYPE:
                    add(lines, "URL", item.get(Website.URL));
                    break;
                case Event.CONTENT_ITEM_TYPE:
                    if (item.type(Event.TYPE) == Event.TYPE_BIRTHDAY)
                        birthday(item.get(Event.START_DATE)).ifPresent(
                                date -> lines.add(VCardWriter.raw("BDAY", date)));
                    break;
                default:
                    // A photo, a group membership, an IM address: either not ours to write
                    // or nothing a vCard property maps onto.
            }
        }
        lines.add(VCardWriter.timestamp("REV", System.currentTimeMillis()));
        return lines;
    }

    private static Optional<VCardWriter.Line> structuredName(List<DataRow> data) {
        for (DataRow item : data) {
            if (! item.mimetype.equals(StructuredName.CONTENT_ITEM_TYPE))
                continue;
            List<String> parts = Arrays.asList(
                    item.get(StructuredName.FAMILY_NAME),
                    item.get(StructuredName.GIVEN_NAME),
                    item.get(StructuredName.MIDDLE_NAME),
                    item.get(StructuredName.PREFIX),
                    item.get(StructuredName.SUFFIX));
            if (parts.stream().anyMatch(p -> ! p.isEmpty()))
                return Optional.of(VCardWriter.structured("N", parts));
        }
        return Optional.empty();
    }

    /**
     * FN, which every vCard must carry. The provider's own display name where it has one,
     * and otherwise whatever identifies the contact at all: a card whose FN is empty is
     * one a CardDAV client may refuse to show.
     */
    private static String displayName(Row row, List<DataRow> data) {
        for (DataRow item : data) {
            if (item.mimetype.equals(StructuredName.CONTENT_ITEM_TYPE)) {
                String display = item.get(StructuredName.DISPLAY_NAME);
                if (! display.isEmpty())
                    return display;
                String given = item.get(StructuredName.GIVEN_NAME);
                String family = item.get(StructuredName.FAMILY_NAME);
                if (! given.isEmpty() || ! family.isEmpty())
                    return (given + " " + family).trim();
            }
        }
        for (DataRow item : data) {
            if (item.mimetype.equals(Phone.CONTENT_ITEM_TYPE) && ! item.get(Phone.NUMBER).isEmpty())
                return item.get(Phone.NUMBER);
            if (item.mimetype.equals(Email.CONTENT_ITEM_TYPE) && ! item.get(Email.ADDRESS).isEmpty())
                return item.get(Email.ADDRESS);
        }
        return "Contact " + row.id;
    }

    private static void add(List<VCardWriter.Line> lines, String name, String value) {
        if (! value.isEmpty())
            lines.add(VCardWriter.text(name, value));
    }

    /** vCard 3.0 takes a comma separated type list, which 4.0 also accepts unquoted. */
    private static String phoneType(int type) {
        switch (type) {
            case Phone.TYPE_MOBILE: return "CELL";
            case Phone.TYPE_WORK_MOBILE: return "CELL,WORK";
            case Phone.TYPE_HOME: return "HOME";
            case Phone.TYPE_WORK: return "WORK";
            case Phone.TYPE_FAX_HOME: return "FAX,HOME";
            case Phone.TYPE_FAX_WORK: return "FAX,WORK";
            case Phone.TYPE_OTHER_FAX: return "FAX";
            case Phone.TYPE_PAGER: case Phone.TYPE_WORK_PAGER: return "PAGER";
            default: return "";
        }
    }

    private static String homeOrWork(int type, int home, int work) {
        if (type == work)
            return "WORK";
        if (type == home)
            return "HOME";
        return "";
    }

    /**
     * The provider stores a birthday as yyyy-MM-dd, or --MM-dd where the user gave no year.
     * A vCard writes the year-less form without the separator.
     */
    static Optional<String> birthday(String stored) {
        String digits = stored.trim().replace("-", "");
        if (! digits.chars().allMatch(Character::isDigit))
            return Optional.empty();
        if (stored.trim().startsWith("--") && digits.length() == 4)
            return Optional.of("--" + digits);
        if (digits.length() != 8)
            return Optional.empty();
        return Optional.of(digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6));
    }

    // ------------------------------------------------------------------ the provider

    private void purge(long rawContactId) throws Exception {
        provider.delete(asSyncAdapter(RawContacts.CONTENT_URI),
                RawContacts._ID + "=?", new String[]{Long.toString(rawContactId)});
    }

    private List<Row> dirtyRows(String bookClause, String directory) throws Exception {
        List<String> args = new ArrayList<>();
        args.add(account.name);
        args.add(account.type);
        if (directory != null)
            args.add(directory);
        List<Row> rows = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(RawContacts.CONTENT_URI), CONTACT_COLUMNS,
                RawContacts.ACCOUNT_NAME + "=? AND " + RawContacts.ACCOUNT_TYPE + "=? AND "
                        + bookClause + " AND (" + RawContacts.DIRTY + "=1 OR " + RawContacts.DELETED + "=1)",
                args.toArray(new String[0]), null)) {
            while (cursor != null && cursor.moveToNext())
                rows.add(Row.from(cursor));
        }
        return rows;
    }

    private List<DataRow> dataRows(long rawContactId) throws Exception {
        List<DataRow> rows = new ArrayList<>();
        try (Cursor cursor = provider.query(asSyncAdapter(Data.CONTENT_URI), DATA_COLUMNS,
                Data.RAW_CONTACT_ID + "=?", new String[]{Long.toString(rawContactId)}, null)) {
            while (cursor != null && cursor.moveToNext()) {
                Map<String, String> values = new HashMap<>();
                for (int i = 1; i < DATA_COLUMNS.length; i++)
                    values.put(DATA_COLUMNS[i], cursor.isNull(i) ? "" : cursor.getString(i));
                rows.add(new DataRow(cursor.isNull(0) ? "" : cursor.getString(0), values));
            }
        }
        return rows;
    }

    private Uri asSyncAdapter(Uri uri) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build();
    }

    private static final class Row {
        long id;
        String sourceId;
        String etag;
        boolean deleted;

        static Row from(Cursor c) {
            Row row = new Row();
            row.id = c.getLong(0);
            row.sourceId = c.isNull(1) ? null : c.getString(1);
            row.etag = c.isNull(2) ? "" : c.getString(2);
            row.deleted = c.getInt(3) == 1;
            return row;
        }
    }

    /** One data row, addressed by the named column each generic one stands for. */
    private static final class DataRow {
        final String mimetype;
        private final Map<String, String> values;

        DataRow(String mimetype, Map<String, String> values) {
            this.mimetype = mimetype;
            this.values = values;
        }

        String get(String column) {
            String value = values.get(column);
            return value == null ? "" : value.trim();
        }

        int type(String column) {
            try {
                return Integer.parseInt(get(column));
            } catch (NumberFormatException none) {
                return -1;
            }
        }
    }
}
