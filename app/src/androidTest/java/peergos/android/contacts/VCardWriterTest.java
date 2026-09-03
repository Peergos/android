package peergos.android.contacts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.ContentValues;
import android.provider.ContactsContract.CommonDataKinds.Email;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.CommonDataKinds.StructuredName;
import android.provider.ContactsContract.Data;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

@RunWith(AndroidJUnit4.class)
public class VCardWriterTest {

    private static final List<String> MANAGED =
            Arrays.asList("FN", "N", "TEL", "EMAIL", "ADR", "ORG", "TITLE", "NOTE", "URL", "BDAY", "REV");

    private static final String STORED =
            "BEGIN:VCARD\r\n"
            + "VERSION:3.0\r\n"
            + "UID:existing-contact\r\n"
            + "FN:Alice Smith\r\n"
            + "N:Smith;Alice;;;\r\n"
            + "TEL;TYPE=HOME:+441111\r\n"
            + "TEL;TYPE=WORK:+442222\r\n"
            + "EMAIL:alice@example.com\r\n"
            + "PHOTO;ENCODING=b;TYPE=JPEG:/9j/keepme\r\n"
            + "CATEGORIES:Friends\r\n"
            + "X-PEERGOS-CUSTOM:keep me\r\n"
            + "END:VCARD\r\n";

    /** The whole point of patching rather than re-serialising. */
    @Test
    public void patchKeepsWhatItDoesNotUnderstand() {
        String patched = VCardWriter.patch(STORED,
                List.of(VCardWriter.text("FN", "Alice Jones"),
                        VCardWriter.typed("TEL", "CELL", "+443333")),
                MANAGED);

        assertTrue("name replaced", patched.contains("FN:Alice Jones"));
        assertFalse("old name gone", patched.contains("FN:Alice Smith"));
        assertTrue("new number written", patched.contains("TEL;TYPE=CELL:+443333"));

        assertTrue("photo kept", patched.contains("PHOTO;ENCODING=b;TYPE=JPEG:/9j/keepme"));
        assertTrue("categories kept", patched.contains("CATEGORIES:Friends"));
        assertTrue("custom property kept", patched.contains("X-PEERGOS-CUSTOM:keep me"));
        assertTrue("uid kept", patched.contains("UID:existing-contact"));
        assertTrue("version kept", patched.contains("VERSION:3.0"));
    }

    /**
     * A contact has several numbers, all called TEL, so a patch that replaced one line each
     * would leave the numbers the user deleted on the phone behind.
     */
    @Test
    public void patchReplacesEveryRepeatOfAManagedProperty() {
        String patched = VCardWriter.patch(STORED,
                List.of(VCardWriter.text("FN", "Alice Smith"),
                        VCardWriter.typed("TEL", "HOME", "+441111")),
                MANAGED);
        assertEquals("one number survives", 1, countOf(patched, "TEL"));
        assertFalse("the deleted number is gone", patched.contains("+442222"));
        assertFalse("so is the email", patched.contains("alice@example.com"));
    }

    @Test
    public void patchAddsAPropertyThatWasAbsent() {
        String patched = VCardWriter.patch(STORED,
                List.of(VCardWriter.text("NOTE", "Met at a conference")), MANAGED);
        assertTrue(patched.contains("NOTE:Met at a conference"));
        // added inside the card, not after it
        assertTrue(patched.indexOf("NOTE:Met at a conference") < patched.indexOf("END:VCARD"));
    }

    /** "item1.EMAIL" is the EMAIL property, and a missed prefix leaves the old value behind. */
    @Test
    public void patchReplacesAGroupedProperty() {
        String grouped = "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Alice\r\n"
                + "item1.EMAIL:old@example.com\r\nEND:VCARD\r\n";
        String patched = VCardWriter.patch(grouped,
                List.of(VCardWriter.text("EMAIL", "new@example.com")), MANAGED);
        assertFalse("old address gone", patched.contains("old@example.com"));
        assertTrue("new address written", patched.contains("EMAIL:new@example.com"));
    }

    @Test
    public void createEmitsACardAClientWillAccept() {
        String vcf = VCardWriter.create("new-uid",
                List.of(VCardWriter.text("FN", "Bob Brown"),
                        VCardWriter.structured("N", List.of("Brown", "Bob", "", "", "")),
                        VCardWriter.typed("TEL", "CELL", "+447777")));
        assertTrue(vcf.startsWith("BEGIN:VCARD"));
        assertTrue(vcf.contains("VERSION:3.0"));
        assertTrue(vcf.contains("UID:new-uid"));
        assertTrue(vcf.contains("FN:Bob Brown"));
        assertTrue(vcf.contains("N:Brown;Bob;;;"));
        assertTrue("a card should record when it was written", vcf.contains("REV:"));
        assertTrue(vcf.trim().endsWith("END:VCARD"));
    }

    @Test
    public void textValuesAreEscaped() {
        String vcf = VCardWriter.create("escapes",
                List.of(VCardWriter.text("NOTE", "Tea, cake; and\nbiscuits")));
        String backslash = "\\";
        assertTrue(vcf.contains("NOTE:Tea" + backslash + ", cake" + backslash
                + "; and" + backslash + "nbiscuits"));
    }

    /** A separator in a component has to survive as part of it, not split it in two. */
    @Test
    public void structuredComponentsEscapeTheirSeparator() {
        String vcf = VCardWriter.create("address",
                List.of(VCardWriter.structured("ADR",
                        List.of("", "", "1 High St; Flat 2", "London", "", "N1 1AA", "UK"))));
        String backslash = "\\";
        assertTrue(vcf, vcf.contains("ADR:;;1 High St" + backslash + "; Flat 2;London;;N1 1AA;UK"));
    }

    /** What we write must be readable by the parser the bridge and the mirror use. */
    @Test
    public void whatWeWriteWeCanReadBack() {
        String vcf = VCardWriter.create("round-trip",
                List.of(VCardWriter.text("FN", "Carol Jones"),
                        VCardWriter.structured("N", List.of("Jones", "Carol", "", "Dr", "")),
                        VCardWriter.typed("TEL", "CELL", "+447777"),
                        VCardWriter.typed("EMAIL", "WORK", "carol@example.com"),
                        VCardWriter.structured("ADR",
                                List.of("", "", "1 High St; Flat 2", "London", "", "N1 1AA", "UK"))));
        List<ContentValues> rows = VCardTranslator.toDataRows(vcf).orElseThrow();

        ContentValues name = rowOf(rows, StructuredName.CONTENT_ITEM_TYPE);
        assertEquals("Carol Jones", name.getAsString(StructuredName.DISPLAY_NAME));
        assertEquals("Jones", name.getAsString(StructuredName.FAMILY_NAME));
        assertEquals("Carol", name.getAsString(StructuredName.GIVEN_NAME));
        assertEquals("Dr", name.getAsString(StructuredName.PREFIX));

        ContentValues phone = rowOf(rows, Phone.CONTENT_ITEM_TYPE);
        assertEquals("+447777", phone.getAsString(Phone.NUMBER));
        assertEquals(Integer.valueOf(Phone.TYPE_MOBILE), phone.getAsInteger(Phone.TYPE));

        ContentValues email = rowOf(rows, Email.CONTENT_ITEM_TYPE);
        assertEquals("carol@example.com", email.getAsString(Email.ADDRESS));
        assertEquals(Integer.valueOf(Email.TYPE_WORK), email.getAsInteger(Email.TYPE));

        assertEquals(Optional.of("Carol Jones"), VCardTranslator.displayName(vcf));
    }

    private static ContentValues rowOf(List<ContentValues> rows, String mimetype) {
        return rows.stream().filter(r -> mimetype.equals(r.getAsString(Data.MIMETYPE)))
                .findFirst().orElseThrow();
    }

    private static int countOf(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1))
            count++;
        return count;
    }
}
