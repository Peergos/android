package peergos.android;


import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import androidx.exifinterface.media.ExifInterface;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import peergos.shared.user.fs.Thumbnail;
import peergos.shared.user.fs.ThumbnailGenerator;

public class AndroidImageThumbnailer implements ThumbnailGenerator.Generator {

    @Override
    public Optional<Thumbnail> generateThumbnail(byte[] bytes) {
        Bitmap orig = null, cropped = null, scaled = null, rotated = null;
        try {
            orig = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            int w = orig.getWidth();
            int h = orig.getHeight();
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
            if (w > h) {
                cropped = Bitmap.createBitmap(orig, w/2 - h/2, 0, h, h);
            } else {
                cropped = Bitmap.createBitmap(orig, 0, h/2 - w/2, w, w);
            }
            scaled = Bitmap.createScaledBitmap(cropped, 400, 400, true);
            rotated = rotatedImage(scaled, exif);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            rotated.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out);

            try {
                return Optional.of(new Thumbnail("image/webp", out.toByteArray()));
            } catch (IllegalStateException tooBig) {
                scaled = Bitmap.createScaledBitmap(cropped, 200, 200, true);
                rotated = rotatedImage(scaled, exif);
                out = new ByteArrayOutputStream();
                rotated.compress(Bitmap.CompressFormat.WEBP_LOSSY, 100, out);
                return Optional.of(new Thumbnail("image/webp", out.toByteArray()));
            }
        } catch (Exception e) {
            e.printStackTrace();
            return Optional.empty();
        } finally {
            if (orig != null)
                orig.recycle();
            if (cropped != null)
                cropped.recycle();
            if (scaled != null)
                scaled.recycle();
            if (rotated != null)
                rotated.recycle();
        }
    }

    private static Bitmap rotatedImage(Bitmap img, ExifInterface exif) {
        int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return rotateImage(img, 90);
            case ExifInterface.ORIENTATION_ROTATE_180:
                return rotateImage(img, 180);
            case ExifInterface.ORIENTATION_ROTATE_270:
                return rotateImage(img, 270);
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                return flipHorizontal(img);
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                return flipVertical(img);
            case ExifInterface.ORIENTATION_TRANSVERSE:
                return rotateImage(flipHorizontal(img), 90);
            case ExifInterface.ORIENTATION_TRANSPOSE:
                return rotateImage(flipHorizontal(img), 270);
            default:
                return img;
        }
    }

    private static Bitmap flipVertical(Bitmap img) {
        Matrix matrix = new Matrix();
        matrix.postScale(1.0f, -1.0f);
        Bitmap rotatedImg = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
    }

    private static Bitmap flipHorizontal(Bitmap img) {
        Matrix matrix = new Matrix();
        matrix.postScale(-1.0f, 1.0f);
        Bitmap rotatedImg = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
    }

    private static Bitmap rotateImage(Bitmap img, int degree) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        Bitmap rotatedImg = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
    }
}
