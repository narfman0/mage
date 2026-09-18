package mage.player.seat;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/** One human-facing seat of a hosted game: its player, the question it owes an answer to, and messages for it. */
final class Seat {

    enum StepKind { ATTACKER, DEFENDER, BLOCKER, BLOCK_TARGET, CONFIRM }

    /** One engine response of a batch combat declaration still to be sent. */
    record Step(StepKind kind, UUID id) {
    }

    final String name;
    final SeatPlayer player;
    final Object lock = new Object();
    /** The batch the seat asked for (attackers=..., blockers=...), consumed as the engine re-asks. */
    final Deque<Step> batch = new ArrayDeque<>();
    volatile boolean offerManaSources;
    private Decision pending;
    private final List<String> chat = new ArrayList<>();

    Seat(String name, SeatPlayer player, boolean offerManaSources) {
        this.name = name;
        this.player = player;
        this.offerManaSources = offerManaSources;
    }

    void deliver(Decision decision) {
        synchronized (lock) {
            pending = decision;
            lock.notifyAll();
        }
    }

    Decision pending() {
        synchronized (lock) {
            return pending;
        }
    }

    /**
     * Forgets {@code answered} once its answer has gone to the engine. Only that
     * decision: the answer wakes the game thread, which may already have asked
     * (and delivered) the next question by the time the caller gets here, and
     * an unconditional clear would drop it — the engine then waits forever for
     * an answer to a question nobody can see.
     */
    void clearPending(Decision answered) {
        synchronized (lock) {
            if (pending == answered) {
                pending = null;
            }
        }
    }

    void say(String text) {
        synchronized (lock) {
            chat.add(text);
        }
    }

    List<String> drainChat() {
        synchronized (lock) {
            List<String> out = new ArrayList<>(chat);
            chat.clear();
            return out;
        }
    }
}
