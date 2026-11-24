package peergos.android;


import android.graphics.Bitmap;
import android.graphics.ImageDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Optional;

import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;

public class AndroidImageThumbnailer implements ThumbnailGenerator.Generator {

    @Override
    public Optional<Thumbnail> generateThumbnail(byte[] bytes) {
        Bitmap orig, cropped = null, scaled = null;
        try {
            // ImageDecoder turns a JPEG or WebP the way its exif orientation says it is shown, and a HEIF as its container says
            orig = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), (decoder, info, source) -> {
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                // no more than the thumbnail needs, as a turned image is decoded whole and then turned
                decoder.setTargetSampleSize(Math.max(1, Math.min(info.getSize().getWidth(), info.getSize().getHeight()) / 445));
                // a file still being written gets what there is of it, as BitmapFactory gave it
                decoder.setOnPartialImageListener(e -> e.getError() == ImageDecoder.DecodeException.SOURCE_INCOMPLETE);
            });
        } catch (IOException | IllegalStateException | OutOfMemoryError undecodable) {
            // hwui won't make a bitmap over 2 GB, whatever memory there is, and says so with an
            // IllegalStateException, or with an OutOfMemoryError when an allocation fails
            return Optional.empty();
        }
        try {
            int w = orig.getWidth();
            int h = orig.getHeight();
            if (w > h) {
                cropped = Bitmap.createBitmap(orig, w/2 - h/2, 0, h, h);
            } else {
                cropped = Bitmap.createBitmap(orig, 0, h/2 - w/2, w, w);
            }
            scaled = Bitmap.createScaledBitmap(cropped, 400, 400, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out);

            try {
                return Optional.of(new Thumbnail("image/webp", out.toByteArray()));
            } catch (IllegalStateException tooBig) {
                scaled = Bitmap.createScaledBitmap(cropped, 200, 200, true);
                out = new ByteArrayOutputStream();
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out);
                return Optional.of(new Thumbnail("image/webp", out.toByteArray()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        } finally {
            orig.recycle();
            if (cropped != null)
                cropped.recycle();
            if (scaled != null)
                scaled.recycle();
        }
    }
}
