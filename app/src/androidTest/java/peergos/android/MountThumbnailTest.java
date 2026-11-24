package peergos.android;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;

import peergos.shared.user.fs.Thumbnail;

/**
 * The thumbnailer the documents provider uses: a photo turned the way its EXIF orientation says it is
 * shown, and a thumbnail made in reasonable time whatever its EXIF holds.
 */
@RunWith(AndroidJUnit4.class)
public class MountThumbnailTest {

    private static final int W = 300, H = 400;

    /** Upright: red at the top left and green at the top right of the centre square a thumbnail keeps. */
    private static Bitmap upright() {
        Bitmap image = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(10, 60, 60, 110, paint);
        paint.setColor(Color.GREEN);
        canvas.drawRect(240, 60, 290, 110, paint);
        return image;
    }

    /** The upright picture stored the way TIFF 6.0 says an orientation describes: which visual side
     *  the stored 0th row and 0th column are. */
    private static Bitmap stored(int orientation) {
        Bitmap up = upright();
        boolean turned = orientation >= 5;
        Bitmap out = Bitmap.createBitmap(turned ? H : W, turned ? W : H, Bitmap.Config.ARGB_8888);
        for (int r = 0; r < out.getHeight(); r++)
            for (int c = 0; c < out.getWidth(); c++) {
                int x, y;
                switch (orientation) {
                    case 1: case 4: x = c; break;          // 0th column is the visual left
                    case 2: case 3: x = W - 1 - c; break;  // the visual right
                    case 5: case 8: x = r; break;          // 0th row is the visual left
                    default: x = W - 1 - r;                // 6, 7: the visual right
                }
                switch (orientation) {
                    case 1: case 2: y = r; break;          // 0th row is the visual top
                    case 3: case 4: y = H - 1 - r; break;  // the visual bottom
                    case 5: case 6: y = c; break;          // 0th column is the visual top
                    default: y = H - 1 - c;                // 7, 8: the visual bottom
                }
                out.setPixel(c, r, up.getPixel(x, y));
            }
        return out;
    }

    private static byte[] encoded(Bitmap.CompressFormat format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        upright().compress(format, 95, out);
        return out.toByteArray();
    }

    private static void assertUpright(String what, Optional<Thumbnail> thumbnail) {
        Assert.assertTrue(what + " has a thumbnail", thumbnail.isPresent());
        Bitmap image = BitmapFactory.decodeByteArray(thumbnail.get().data, 0, thumbnail.get().data.length);
        int size = image.getWidth();
        int topLeft = image.getPixel(size / 8, size / 8), topRight = image.getPixel(size * 7 / 8, size / 8);
        Assert.assertTrue(what + ": red at the top left, not " + Integer.toHexString(topLeft),
                Color.red(topLeft) > 200 && Color.green(topLeft) < 80);
        Assert.assertTrue(what + ": green at the top right, not " + Integer.toHexString(topRight),
                Color.green(topRight) > 200 && Color.red(topRight) < 80);
    }

    @Test
    public void everyOrientationIsTurnedUpright() throws Exception {
        File file = new File(InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir(), "orient.jpg");
        for (int orientation = 1; orientation <= 8; orientation++) {
            try (FileOutputStream out = new FileOutputStream(file)) {
                stored(orientation).compress(Bitmap.CompressFormat.JPEG, 95, out);
            }
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
            exif.saveAttributes();
            byte[] photo = Files.readAllBytes(file.toPath());
            Assert.assertTrue(file.delete());
            assertUpright("orientation " + orientation, new AndroidImageThumbnailer().generateThumbnail(photo));
        }
    }

    /** An ImageDescription claiming 2 GB, at an offset where two bytes really are. */
    @Test
    public void aLyingExifCountStillGetsAThumbnail() {
        ByteBuffer tiff = ByteBuffer.allocate(8 + 2 + 12 + 4 + 2);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8).putShort((short) 1);
        tiff.putShort((short) 0x010E).putShort((short) 2).putInt(0x7ffffff0).putInt(26);
        tiff.putInt(0).put((byte) 'x').put((byte) 0);
        byte[] plain = encoded(Bitmap.CompressFormat.JPEG);
        ByteBuffer jpeg = ByteBuffer.allocate(plain.length + 4 + 6 + tiff.capacity());
        jpeg.put(plain, 0, 2) // SOI
                .putShort((short) 0xFFE1).putShort((short) (2 + 6 + tiff.capacity())).put("Exif\0\0".getBytes()).put(tiff.array())
                .put(plain, 2, plain.length - 2);
        assertUpright("a lying exif count", new AndroidImageThumbnailer().generateThumbnail(jpeg.array()));
    }

    /** A png whose eXIf chunk chains 100,000 IFDs, each a SubIFDs entry pointing at the next. */
    @Test
    public void aDeepExifChainStillGetsAThumbnail() {
        int ifds = 100_000;
        ByteBuffer tiff = ByteBuffer.allocate(8 + 18 * ifds + 6);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        for (int i = 0; i < ifds; i++) // one entry, SubIFDs (330), a LONG, then no next IFD: 18 bytes
            tiff.putShort((short) 1).putShort((short) 330).putShort((short) 4).putInt(1).putInt(8 + 18 * (i + 1)).putInt(0);
        tiff.putShort((short) 0).putInt(0);
        byte[] plain = encoded(Bitmap.CompressFormat.PNG), type = "eXIf".getBytes();
        CRC32 crc = new CRC32();
        crc.update(type);
        crc.update(tiff.array());
        int afterIhdr = 8 + 4 + 4 + 13 + 4; // signature, then IHDR's length, type, data and crc
        ByteBuffer png = ByteBuffer.allocate(plain.length + 4 + 4 + tiff.capacity() + 4);
        png.put(plain, 0, afterIhdr).putInt(tiff.capacity()).put(type).put(tiff.array()).putInt((int) crc.getValue())
                .put(plain, afterIhdr, plain.length - afterIhdr);
        assertUpright("a deep exif chain", new AndroidImageThumbnailer().generateThumbnail(png.array()));
    }

    /** A webp whose EXIF chunk has three IFDs of 30,000 entries, each covering the whole 2 MB chunk. */
    @Test(timeout = 20_000)
    public void anExifOfOverlappingEntriesStillGetsAThumbnailQuickly() {
        int entries = 30_000, ifd = 2 + 12 * entries + 4, length = 8 + 6 * ifd; // the IFDs, then as much again of padding
        ByteBuffer tiff = ByteBuffer.allocate(length);
        tiff.put((byte) 'M').put((byte) 'M').putShort((short) 42).putInt(8);
        for (int k = 0; k < 3; k++) { // IFD0, the SubIFDs it points to, and IFD1 after it
            tiff.putShort((short) entries);
            int i = 0;
            if (k == 0) {
                tiff.putShort((short) 330).putShort((short) 4).putInt(1).putInt(8 + ifd);
                i = 1;
            }
            for (; i < entries; i++) // an UNDEFINED ImageDescription of everything after the header
                tiff.putShort((short) 0x010E).putShort((short) 7).putInt(length - 8).putInt(8);
            tiff.putInt(k == 0 ? 8 + 2 * ifd : 0);
        }
        // the webp, made extended if it isn't already, with an EXIF chunk after the image data
        byte[] plain = encoded(Bitmap.CompressFormat.WEBP_LOSSY);
        boolean extended = new String(plain, 12, 4).equals("VP8X");
        int chunks = plain.length - 12, vp8x = extended ? 0 : 18;
        ByteBuffer webp = ByteBuffer.allocate(12 + vp8x + chunks + 8 + length).order(ByteOrder.LITTLE_ENDIAN);
        webp.put("RIFF".getBytes()).putInt(4 + vp8x + chunks + 8 + length).put("WEBP".getBytes());
        if (! extended)
            webp.put("VP8X".getBytes()).putInt(10).putInt(0)
                    .put((byte) (W - 1)).put((byte) ((W - 1) >> 8)).put((byte) 0)
                    .put((byte) (H - 1)).put((byte) ((H - 1) >> 8)).put((byte) 0);
        webp.put(plain, 12, chunks);
        webp.put(20, (byte) (webp.get(20) | 0x08)); // the EXIF flag
        webp.put("EXIF".getBytes()).putInt(length).put(tiff.array());
        assertUpright("an exif of overlapping entries", new AndroidImageThumbnailer().generateThumbnail(webp.array()));
    }

    /** A file still being written gets what there is of it. */
    @Test
    public void aPhotoStillBeingWrittenGetsAThumbnail() {
        byte[] jpeg = encoded(Bitmap.CompressFormat.JPEG);
        byte[] unfinished = Arrays.copyOf(jpeg, jpeg.length * 95 / 100);
        assertUpright("a photo still being written", new AndroidImageThumbnailer().generateThumbnail(unfinished));
    }

    /** A small png whose header claims more than one bitmap can hold, even sampled down to the thumbnail's
     *  short side: no thumbnail, and nothing thrown. */
    @Test
    public void anImageTooBigToDecodeGetsNoThumbnail() {
        ByteBuffer png = ByteBuffer.wrap(encoded(Bitmap.CompressFormat.PNG));
        // IHDR's width, height and bit depth: 399 by a million at 16 bits a channel is 3.2 GB
        png.putInt(16, 399).putInt(20, 1_000_000).put(24, (byte) 16);
        CRC32 crc = new CRC32();
        crc.update(png.array(), 12, 4 + 13);
        png.putInt(29, (int) crc.getValue());
        Assert.assertEquals(Optional.empty(), new AndroidImageThumbnailer().generateThumbnail(png.array()));
    }
}
