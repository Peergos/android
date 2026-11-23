package peergos.android;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;

import androidx.exifinterface.media.ExifInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.CRC32;

import peergos.server.Main;
import peergos.shared.Crypto;
import peergos.shared.user.fs.ResumeUploadProps;
import peergos.shared.user.fs.Thumbnail;

/**
 * A camera stores a portrait photo as landscape pixels and an EXIF orientation saying how to
 * turn them. A gallery shows the photo turned, so its thumbnail has to be turned the same way.
 */
@RunWith(AndroidJUnit4.class)
public class ThumbnailOrientationTest {

    private static final int W = 300, H = 400;

    private Context ctx;
    private Crypto crypto;
    private AndroidSyncFileSystem fs;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        crypto = Main.initCrypto(new ScryptAndroid(), new BcMldsa());

        Uri authorityUri = Uri.parse("content://" + TestDocumentsProvider.AUTHORITY);
        Bundle empty = new Bundle();
        empty.putInt("count", 0);
        ctx.getContentResolver().call(authorityUri, TestDocumentsProvider.METHOD_RESET_AND_SEED, null, empty);

        Uri treeUri = DocumentsContract.buildTreeDocumentUri(
                TestDocumentsProvider.AUTHORITY, TestDocumentsProvider.ROOT_ID);
        fs = new AndroidSyncFileSystem(treeUri, ctx, crypto);
    }

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

    private byte[] photo(int orientation) throws Exception {
        File file = new File(ctx.getCacheDir(), "orient-" + orientation + ".jpg");
        try (FileOutputStream out = new FileOutputStream(file)) {
            stored(orientation).compress(Bitmap.CompressFormat.JPEG, 95, out);
        }
        ExifInterface exif = new ExifInterface(file.getAbsolutePath());
        exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation));
        exif.saveAttributes();
        byte[] bytes = Files.readAllBytes(file.toPath());
        Assert.assertTrue(file.delete());
        return bytes;
    }

    /** The thumbnail a sync makes of the file, which is then removed again. */
    private Optional<Thumbnail> syncedThumbnail(String name, byte[] bytes) throws Exception {
        Path p = fs.resolve(name);
        fs.setBytes(p, 0,
                new AndroidAsyncReader(new ByteArrayInputStream(bytes), () -> new ByteArrayInputStream(bytes)),
                bytes.length, Optional.empty(), Optional.empty(), Optional.empty(),
                ResumeUploadProps.random(crypto), () -> false, s -> {});
        Optional<Thumbnail> thumbnail = fs.getThumbnail(p);
        fs.delete(p);
        return thumbnail;
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
    public void syncedPhotosGetUprightThumbnails() throws Exception {
        for (int orientation = 1; orientation <= 8; orientation++)
            assertUpright("synced orientation " + orientation, syncedThumbnail("orient-" + orientation + ".jpg", photo(orientation)));
    }

    /** The whole short side is in the thumbnail: a 1733x1300 photo sampled to a short side within 10% of 400
     *  would be cropped to 400 rather than scaled, and lose the bands along its long edges. */
    @Test
    public void theThumbnailKeepsTheWholeShortSide() throws Exception {
        Bitmap image = Bitmap.createBitmap(1733, 1300, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(image);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint();
        paint.setColor(Color.RED);
        canvas.drawRect(0, 0, 1733, 26, paint);
        paint.setColor(Color.BLUE);
        canvas.drawRect(0, 1274, 1733, 1300, paint);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        image.compress(Bitmap.CompressFormat.JPEG, 95, jpeg);
        Optional<Thumbnail> thumbnail = syncedThumbnail("framed.jpg", jpeg.toByteArray());
        Assert.assertTrue(thumbnail.isPresent());
        Bitmap thumb = BitmapFactory.decodeByteArray(thumbnail.get().data, 0, thumbnail.get().data.length);
        int top = thumb.getPixel(200, 2), bottom = thumb.getPixel(200, thumb.getHeight() - 3);
        Assert.assertTrue("the top band, not " + Integer.toHexString(top), Color.red(top) > 200 && Color.green(top) < 80);
        Assert.assertTrue("the bottom band, not " + Integer.toHexString(bottom), Color.blue(bottom) > 200 && Color.green(bottom) < 80);
    }

    /** A file still being written gets what there is of it, as BitmapFactory gave it. */
    @Test
    public void aPhotoStillBeingWrittenGetsAThumbnail() throws Exception {
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        stored(1).compress(Bitmap.CompressFormat.JPEG, 95, jpeg);
        byte[] unfinished = Arrays.copyOf(jpeg.toByteArray(), jpeg.size() * 95 / 100);
        assertUpright("a photo still being written", syncedThumbnail("unfinished.jpg", unfinished));
    }

    /** A small png whose header claims more than one bitmap can hold, even sampled down to the thumbnail's
     *  short side: no thumbnail, and nothing thrown. */
    @Test
    public void anImageTooBigToDecodeGetsNoThumbnail() throws Exception {
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        stored(1).compress(Bitmap.CompressFormat.PNG, 100, png);
        ByteBuffer bytes = ByteBuffer.wrap(png.toByteArray());
        // IHDR's width, height and bit depth: 399 by a million at 16 bits a channel is 3.2 GB
        bytes.putInt(16, 399).putInt(20, 1_000_000).put(24, (byte) 16);
        CRC32 crc = new CRC32();
        crc.update(bytes.array(), 12, 4 + 13);
        bytes.putInt(29, (int) crc.getValue());
        Assert.assertEquals(Optional.empty(), syncedThumbnail("too-big.png", bytes.array()));
    }
}
