package peergos.android;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import androidx.documentfile.provider.DocumentFile;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import peergos.server.sync.SyncFilesystem;
import peergos.server.sync.SyncState;
import peergos.shared.user.fs.AsyncReader;
import peergos.shared.user.fs.Chunk;
import peergos.shared.user.fs.FileWrapper;
import peergos.shared.user.fs.HashTree;
import peergos.shared.user.fs.MimeTypes;
import peergos.shared.user.fs.Thumbnail;
import peergos.shared.crypto.hash.Hasher;
import peergos.shared.util.Triple;

public class AndroidSyncFileSystem implements SyncFilesystem {
    private final Uri rootUri;
    private final Context context;
    private final Hasher hasher;

    public AndroidSyncFileSystem(Uri rootUri, Context context, Hasher hasher) {
        this.rootUri = rootUri;
        this.context = context;
        this.hasher = hasher;
    }

    @Override
    public long totalSpace() throws IOException {
        return Long.MAX_VALUE / 1_000;
    }

    @Override
    public long freeSpace() throws IOException {
        return Long.MAX_VALUE / 2_000;
    }

    @Override
    public String getRoot() {
        return rootUri.toString();
    }

    @Override
    public Path resolve(String s) {
        return Paths.get(s);
    }

    private DocumentFile getByPath(Path p) {
        if (p == null)
            return DocumentFile.fromTreeUri(context, rootUri);
        List<String> path = new ArrayList<>(p.getNameCount());
        if (! p.toString().isBlank())
            for (int i=0; i < p.getNameCount(); i++)
                path.add(p.getName(i).toString());
        return getDescendant(DocumentFile.fromTreeUri(context, rootUri), path);
    }

    private DocumentFile getDescendant(DocumentFile d, List<String> path) {
        if (path.size() == 0)
            return d;
        if (path.size() == 1)
            return d.findFile(path.get(0));
        return getDescendant(d.findFile(path.get(0)), path.subList(1, path.size()));
    }

    @Override
    public boolean exists(Path p) {
        DocumentFile file = getByPath(p);
        return file != null && file.exists();
    }

    @Override
    public void mkdirs(Path p) {
        if (p.getNameCount() == 0 || p.toString().isEmpty()) // base dir
            return;
        Path parent = p.getParent();
        if (! exists(parent))
            mkdirs(parent);
        getByPath(parent).createDirectory(p.getFileName().toString());
    }

    @Override
    public void delete(Path p) {
        getByPath(p).delete();
    }

    @Override
    public void bulkDelete(Path p, Set<String> children) {
        for (String child : children) {
            getByPath(p.resolve(child)).delete();
        }
    }

    @Override
    public void moveTo(Path src, Path dest) {
        DocumentFile srcFile = getByPath(src);
        if (Objects.equals(src.getParent(), dest.getParent())) {
            srcFile.renameTo(dest.getFileName().toString());
        } else {
            try {
                AsyncReader reader = getBytes(src, 0);
                mkdirs(dest.getParent());
                setBytes(dest, 0, reader, srcFile.length(), Optional.empty(), Optional.empty(), Optional.empty());
                srcFile.delete();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Override
    public long getLastModified(Path p) {
        return getByPath(p).lastModified();
    }

    @Override
    public void setModificationTime(Path p, long time) {
    }

    @Override
    public void setHash(Path p, HashTree hashTree, long fileSize) {

    }

    @Override
    public void setHashes(List<Triple<String, FileWrapper, HashTree>> toUpdate) {

    }

    @Override
    public long size(Path p) {
        return getByPath(p).length();
    }

    @Override
    public void truncate(Path p, long size) throws IOException {
        DocumentFile f = getByPath(p);
        long current = f.length();
        if (current < size)
            return;
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(f.getUri(), "t");
             FileOutputStream fout = new FileOutputStream(pfd.getFileDescriptor())) {
            fout.getChannel().truncate(size);
        }
    }

    @Override
    public void setBytes(Path p,
                         long fileOffset,
                         AsyncReader reader,
                         long size,
                         Optional<HashTree> hash,
                         Optional<LocalDateTime> modified,
                         Optional<Thumbnail> thumb) throws IOException {
        if (! exists(p)) {
            DocumentFile parent = getByPath(p.getParent());
            byte[] start = new byte[(int)Math.min(1024L, size)];
            reader.readIntoArray(start, 0, start.length).join();
            String mimeType = MimeTypes.calculateMimeType(start, p.getFileName().toString());
            DocumentFile file = parent.createFile(mimeType, p.getFileName().toString());
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(file.getUri(), "w");
                 FileOutputStream fout = new FileOutputStream(pfd.getFileDescriptor())) {
                long prefix = 0;
                byte[] buf = new byte[1024*1024];
                while (prefix < fileOffset) {
                    int zeroes = Math.min(buf.length, (int) (fileOffset - prefix));
                    fout.write(buf, 0, zeroes);
                    prefix += zeroes;
                }
                long written = start.length;
                fout.write(start);
                while (written < size) {
                    int read = reader.readIntoArray(buf, 0, Math.min(buf.length, (int) (size - written))).join();
                    fout.write(buf, 0, read);
                    written += read;
                }
            }
        } else {
            DocumentFile existing = getByPath(p);
            try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(existing.getUri(), "rw");
                 FileOutputStream fout = new FileOutputStream(pfd.getFileDescriptor())) {
                fout.getChannel().position(fileOffset);
                byte[] buf = new byte[1024*1024];
                long written = 0;
                while (written < size) {
                    int read = reader.readIntoArray(buf, 0, Math.min(buf.length, (int) (size - written))).join();
                    fout.write(buf, 0, read);
                    written += read;
                }
            }
        }
    }

    private InputStream getInputStream(DocumentFile file) {
        try {
            return context.getContentResolver().openInputStream(file.getUri());
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public AsyncReader getBytes(Path p, long fileOffset) throws IOException {
        DocumentFile file = getByPath(p);
        InputStream fin = context.getContentResolver().openInputStream(file.getUri());
        fin.skip(fileOffset);
        return new AndroidAsyncReader(fin, () -> getInputStream(file));
    }

    @Override
    public void uploadSubtree(Stream<FileWrapper.FolderUploadProperties> directories) {
        byte[] buf = new byte[1024 * 1024];
        directories.forEach(forDir -> {
            mkdirs(forDir.path());
            DocumentFile dir = getByPath(forDir.path());
            for (FileWrapper.FileUploadProperties file : forDir.files) {
                byte[] start = new byte[(int)Math.min(1024L, file.length)];
                AsyncReader reader = file.fileData.get();
                reader.readIntoArray(start, 0, start.length).join();
                String mimeType = MimeTypes.calculateMimeType(start, file.filename);
                DocumentFile kid = dir.createFile(mimeType, file.filename);
                try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(kid.getUri(), "w");
                     FileOutputStream fout = new FileOutputStream(pfd.getFileDescriptor())) {
                    long written = start.length;
                    fout.write(start);
                    while (written < file.length) {
                        int read = reader.readIntoArray(buf, 0, Math.min(buf.length, (int) (file.length - written))).join();
                        fout.write(buf, 0, read);
                        written += read;
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    @Override
    public Optional<Thumbnail> getThumbnail(Path p) {
        return Optional.empty();
    }

    @Override
    public HashTree hashFile(Path p, Optional<FileWrapper> meta, String relPath, SyncState syncState) {
        DocumentFile f = getByPath(p);
        byte[] buf = new byte[4 * 1024];
        long size = f.length();
        int chunkOffset = 0;
        List<byte[]> chunkHashes = new ArrayList<>();

        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(f.getUri(), "r");
             FileInputStream fin = new FileInputStream(pfd.getFileDescriptor())) {
            MessageDigest chunkHash = MessageDigest.getInstance("SHA-256");
            for (long i = 0; i < size; ) {
                int read = fin.read(buf);
                chunkOffset += read;
                if (chunkOffset >= Chunk.MAX_SIZE) {
                    int thisChunk = read - chunkOffset + Chunk.MAX_SIZE;
                    chunkHash.update(buf, 0, thisChunk);
                    chunkHashes.add(chunkHash.digest());
                    chunkHash = MessageDigest.getInstance("SHA-256");
                    chunkOffset = 0;
                } else
                    chunkHash.update(buf, 0, read);
                i += read;
            }
            if (size == 0 || size % Chunk.MAX_SIZE != 0)
                chunkHashes.add(chunkHash.digest());

            return HashTree.build(chunkHashes, hasher).join();
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void applyToSubtree(Consumer<FileProps> onFile, Consumer<FileProps> onDir) throws IOException {
        DocumentFile root = getByPath(Paths.get(""));
        if (root == null)
            throw new IllegalStateException("Couldn't retrieve local directory!");
        applyToSubtree(Paths.get(""), root, onFile, onDir);
    }

    public void applyToSubtree(Path p, DocumentFile dir, Consumer<FileProps> onFile, Consumer<FileProps> onDir) throws IOException {
        DocumentFile[] kids = dir.listFiles();
        for (DocumentFile kid : kids) {
            FileProps props = new FileProps(p.resolve(kid.getName()).toString(), kid.lastModified() / 1000 * 1000, kid.length(), Optional.empty());
            if (kid.isFile()) {
                onFile.accept(props);
            } else {
                onDir.accept(props);
                applyToSubtree(p.resolve(kid.getName()), kid, onFile, onDir);
            }
        }
    }
}
