package mage.player.seat;

import mage.game.Game;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.BooleanSupplier;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * A game's state on disk, to be played on from in another JVM: the whole
 * {@link Game} through Java serialization (gzip), the way the XMage server's
 * own saveGame wrote it. What makes it resumable is in the engine, not here:
 * GameImpl.readObject rebuilds the event sources, bookmarks and rollback
 * copies; HumanPlayer and ComputerPlayer rebuild their transient scratch; the
 * game seq and short-id registry are serialized with the game so seqs and ids
 * continue; and {@code GameImpl.resume()} re-enters the turn loop at the
 * state's turn, phase and step. A snapshot is only ever written while the game
 * thread is parked on a top-level question (GameHost's policy), so the step
 * it resumes into asks that question again.
 *
 * A file is written whole then moved into place, so a reader never sees a
 * partial one. The version is this engine's format; a snapshot is also only
 * as portable as the classes it names, so the product refuses one from
 * another engine pin (fullpod docs/save-resume.md).
 */
final class Snapshot {

    static final String FILE = "snapshot.bin";
    private static final int VERSION = 1;

    private Snapshot() {
    }

    /** Writes the game to {@code path}; the file's size in bytes. */
    static long write(Game game, Path path) throws IOException {
        return write(game, path, () -> false);
    }

    /** A write given up because {@code abort} said so; the previous file is untouched. */
    static final class Aborted extends IOException {
        Aborted() {
            super("snapshot write aborted");
        }
    }

    /**
     * Writes the game to {@code path}, giving up (and throwing {@link Aborted})
     * as soon as {@code abort} says so: it is asked at every buffer the
     * serializer hands down, so a write stops within milliseconds, the temp file is
     * deleted and whatever was at {@code path} stays.
     */
    static long write(Game game, Path path, BooleanSupplier abort) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(path.getFileName() + ".tmp");
        try {
            // The check sits right under the serializer, which hands its bytes
            // down about every kilobyte: a volatile read each time.
            try (ObjectOutputStream out = new ObjectOutputStream(new Abortable(new BufferedOutputStream(
                    new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))), 64 * 1024), abort))) {
                out.writeInt(VERSION);
                out.writeObject(game);
            }
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(tmp);
            throw ex;
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Files.size(path);
    }

    /** Passes bytes through until {@code abort} says stop. */
    private static final class Abortable extends FilterOutputStream {
        private final BooleanSupplier abort;

        Abortable(OutputStream out, BooleanSupplier abort) {
            super(out);
            this.abort = abort;
        }

        @Override
        public void write(int b) throws IOException {
            check();
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            check();
            out.write(b, off, len);
        }

        private void check() throws Aborted {
            if (abort.getAsBoolean()) {
                throw new Aborted();
            }
        }
    }

    static Game read(Path path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new GZIPInputStream(new BufferedInputStream(Files.newInputStream(path))))) {
            int version = in.readInt();
            if (version != VERSION) {
                throw new IOException("snapshot format " + version + "; this engine reads " + VERSION);
            }
            return (Game) in.readObject();
        }
    }
}
