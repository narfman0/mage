package mage.player.seat;

import mage.game.Game;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        Path dir = path.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        Path tmp = dir.resolve(path.getFileName() + ".tmp");
        try (ObjectOutputStream out = new ObjectOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp))))) {
            out.writeInt(VERSION);
            out.writeObject(game);
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return Files.size(path);
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
