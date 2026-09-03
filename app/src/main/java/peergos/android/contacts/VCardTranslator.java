package peergos.android.contacts;

import android.content.ContentValues;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Event;
import android.provider.ContactsContract.CommonDataKinds.Nickname;
import android.provider.ContactsContract.CommonDataKinds.Note;
import android.provider.ContactsContract.CommonDataKinds.Organization;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.Photo;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal;
import android.provider.ContactsContract.CommonDataKinds.Website;
import android.provider.ContactsContract.Data;
import android.util.Base64;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import peergos.server.webdav.caldav.ICal;
import peergos.server.webdav.caldav.VCard;

/**
 * vCard to ContactsContract data rows. The parser is the one the CardDAV bridge uses,
 * compiled into the app from the server jar, so the two surfaces cannot drift in how they
 * read a property or decide which of a repeated one is the mobile number.
 *
 * The rows come back without a RAW_CONTACT_ID: the mirror owns the raw contact and fills
 * that in, either directly or as a back reference for a contact being inserted.
 */
public final class VCardTranslator {

    /** How large an inline photo may be before it is left out of the row. */
    static final int MAX_PHOTO_BYTES = 512 * 1024;

    private VCardTranslator() {}

    /**
     * The data rows for one card, or empty if the bytes are not a vCard at all.
     *
     * A card with nothing but a name still produces a row, because a contact with no name
     * and no number is not something the provider can show, whereas a name alone is.
     */
    public static Optional<List<ContentValues>> toDataRows(String vcf) {
        if (! VCard.isVCard(vcf))
            return Optional.empty();
        List<ContentValues> rows = new ArrayList<>();
        // FN and N are one row between them, as are ORG and TITLE, so both are built up
        // across the properties rather than emitted where they are met.
        ContentValues name = new ContentValues();
        ContentValues organization = new ContentValues();
        for (ICal.Property property : VCard.properties(vcf)) {
            switch (property.name) {
                case "FN":
                    name.put(StructuredName.DISPLAY_NAME, VCard.unescape(property.value));
                    break;
                case "N": {
                    List<String> parts = VCard.structured(property.value);
                    put(name, StructuredName.FAMILY_NAME, part(parts, 0));
                    put(name, StructuredName.GIVEN_NAME, part(parts, 1));
                    put(name, StructuredName.MIDDLE_NAME, part(parts, 2));
                    put(name, StructuredName.PREFIX, part(parts, 3));
                    put(name, StructuredName.SUFFIX, part(parts, 4));
                    break;
                }
                case "NICKNAME":
                    rows.add(row(Nickname.CONTENT_ITEM_TYPE, Nickname.NAME, VCard.unescape(property.value)));
                    break;
                case "TEL": {
                    ContentValues phone = row(Phone.CONTENT_ITEM_TYPE, Phone.NUMBER,
                            VCard.unescape(property.value));
                    phone.put(Phone.TYPE, phoneType(VCard.types(property)));
                    rows.add(phone);
                    break;
                }
                case "EMAIL": {
                    ContentValues email = row(Email.CONTENT_ITEM_TYPE, Email.ADDRESS,
                            VCard.unescape(property.value));
                    email.put(Email.TYPE, homeOrWork(VCard.types(property),
                            Email.TYPE_HOME, Email.TYPE_WORK, Email.TYPE_OTHER));
                    rows.add(email);
                    break;
                }
                case "ADR": {
                    List<String> parts = VCard.structured(property.value);
                    ContentValues postal = new ContentValues();
                    postal.put(Data.MIMETYPE, StructuredPostal.CONTENT_ITEM_TYPE);
                    postal.put(StructuredPostal.TYPE, homeOrWork(VCard.types(property),
                            StructuredPostal.TYPE_HOME, StructuredPostal.TYPE_WORK,
                            StructuredPostal.TYPE_OTHER));
                    put(postal, StructuredPostal.POBOX, part(parts, 0));
                    put(postal, StructuredPostal.NEIGHBORHOOD, part(parts, 1));
                    put(postal, StructuredPostal.STREET, part(parts, 2));
                    put(postal, StructuredPostal.CITY, part(parts, 3));
                    put(postal, StructuredPostal.REGION, part(parts, 4));
                    put(postal, StructuredPostal.POSTCODE, part(parts, 5));
                    put(postal, StructuredPostal.COUNTRY, part(parts, 6));
                    if (postal.size() > 2)
                        rows.add(postal);
                    break;
                }
                case "ORG": {
                    List<String> parts = VCard.structured(property.value);
                    put(organization, Organization.COMPANY, part(parts, 0));
                    put(organization, Organization.DEPARTMENT, part(parts, 1));
                    break;
                }
                case "TITLE":
                    put(organization, Organization.TITLE, VCard.unescape(property.value));
                    break;
                case "NOTE":
                    rows.add(row(Note.CONTENT_ITEM_TYPE, Note.NOTE, VCard.unescape(property.value)));
                    break;
                case "URL": {
                    ContentValues site = row(Website.CONTENT_ITEM_TYPE, Website.URL,
                            VCard.unescape(property.value));
                    site.put(Website.TYPE, Website.TYPE_OTHER);
                    rows.add(site);
                    break;
                }
                case "BDAY":
                    birthday(property.value).ifPresent(date -> {
                        ContentValues event = row(Event.CONTENT_ITEM_TYPE, Event.START_DATE, date);
                        event.put(Event.TYPE, Event.TYPE_BIRTHDAY);
                        rows.add(event);
                    });
                    break;
                case "PHOTO":
                    photo(property).ifPresent(bytes ->
                            rows.add(row(Photo.CONTENT_ITEM_TYPE, Photo.PHOTO, bytes)));
                    break;
                default:
                    // Everything else — VERSION, UID, REV, CATEGORIES, IMPP, X- properties —
                    // has no place in the contract, and survives because the upload half
                    // patches the stored card instead of rewriting it.
            }
        }
        if (name.size() > 0) {
            name.put(Data.MIMETYPE, StructuredName.CONTENT_ITEM_TYPE);
            rows.add(0, name);
        }
        if (organization.size() > 0) {
            organization.put(Data.MIMETYPE, Organization.CONTENT_ITEM_TYPE);
            organization.put(Organization.TYPE, Organization.TYPE_WORK);
            rows.add(organization);
        }
        return Optional.of(rows);
    }

    /** The display name a card carries, for a raw contact that needs naming before its rows. */
    public static Optional<String> displayName(String vcf) {
        return VCard.values(vcf, "FN").stream().findFirst().map(VCard::unescape)
                .filter(n -> ! n.isEmpty());
    }

    private static ContentValues row(String mimetype, String column, String value) {
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, mimetype);
        values.put(column, value);
        return values;
    }

    private static ContentValues row(String mimetype, String column, byte[] value) {
        ContentValues values = new ContentValues();
        values.put(Data.MIMETYPE, mimetype);
        values.put(column, value);
        return values;
    }

    private static void put(ContentValues values, String column, String value) {
        if (! value.isEmpty())
            values.put(column, value);
    }

    private static String part(List<String> parts, int index) {
        return index < parts.size() ? parts.get(index).trim() : "";
    }

    /**
     * Which kind of number this is. CELL wins over WORK and HOME because a work mobile is
     * still the number that rings in a pocket, which is what the user is looking for.
     */
    private static int phoneType(List<String> types) {
        boolean work = types.contains("WORK");
        boolean home = types.contains("HOME");
        if (types.contains("CELL"))
            return work ? Phone.TYPE_WORK_MOBILE : Phone.TYPE_MOBILE;
        if (types.contains("FAX"))
            return work ? Phone.TYPE_FAX_WORK : home ? Phone.TYPE_FAX_HOME : Phone.TYPE_OTHER_FAX;
        if (types.contains("PAGER"))
            return Phone.TYPE_PAGER;
        return work ? Phone.TYPE_WORK : home ? Phone.TYPE_HOME : Phone.TYPE_OTHER;
    }

    private static int homeOrWork(List<String> types, int home, int work, int other) {
        if (types.contains("WORK"))
            return work;
        if (types.contains("HOME"))
            return home;
        return other;
    }

    /**
     * A birthday as the contract wants it: yyyy-MM-dd, or --MM-dd where the card gives no
     * year, which is the form the provider itself uses for a birthday without one.
     */
    static Optional<String> birthday(String value) {
        String date = VCard.unescape(value).trim();
        // a vCard 4.0 timestamp carries the time as well, which a birthday has no use for
        int t = date.indexOf('T');
        if (t > 0)
            date = date.substring(0, t);
        String digits = date.replace("-", "");
        if (date.startsWith("--") && digits.length() == 4)
            return Optional.of("--" + digits.substring(0, 2) + "-" + digits.substring(2));
        if (digits.length() != 8 || ! digits.chars().allMatch(Character::isDigit))
            return Optional.empty();
        return Optional.of(digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6));
    }

    /**
     * An inline photo, decoded. A PHOTO naming a URL is left out rather than fetched: the
     * sync adapter is holding a provider client and has no business making its own requests
     * to whatever host a card happens to name.
     */
    private static Optional<byte[]> photo(ICal.Property property) {
        String value = property.value.trim();
        boolean inline = property.param("ENCODING")
                .map(e -> e.equalsIgnoreCase("b") || e.equalsIgnoreCase("BASE64")).orElse(false);
        if (value.toLowerCase(Locale.ROOT).startsWith("data:")) {
            int comma = value.indexOf(',');
            if (comma < 0)
                return Optional.empty();
            value = value.substring(comma + 1);
            inline = true;
        }
        if (! inline)
            return Optional.empty();
        try {
            byte[] decoded = Base64.decode(value, Base64.DEFAULT);
            // The rows travel to the provider in one binder transaction, which a photo of a
            // few megabytes would overrun, taking the whole contact with it.
            return decoded.length == 0 || decoded.length > MAX_PHOTO_BYTES
                    ? Optional.empty() : Optional.of(decoded);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
    }
}
