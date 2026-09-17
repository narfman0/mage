package mage.player.seat;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Map;

public class SeatTest {

    /**
     * The answer to one question wakes the game thread, which can ask the next
     * question before the answering thread has cleared the first; clearing must
     * therefore name what it answered (seen as CI stalls in chooseMulligan with
     * nothing pending, 2026-09-17).
     */
    @Test
    public void clearingTheAnsweredQuestionKeepsTheOneAskedMeanwhile() {
        Seat seat = new Seat("You", null, false);
        Decision first = new Decision(1, null, Map.of(), List.of());
        Decision next = new Decision(2, null, Map.of(), List.of());
        seat.deliver(first);
        seat.clearPending(first);
        Assert.assertNull(seat.pending());

        seat.deliver(first);
        seat.deliver(next); // the engine, on the game thread, before the caller's clear
        seat.clearPending(first);
        Assert.assertSame(next, seat.pending());
    }
}
