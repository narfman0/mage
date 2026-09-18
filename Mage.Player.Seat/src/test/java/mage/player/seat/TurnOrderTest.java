package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The board lists the seats in turn order from the seat it is for — the
 * order turns actually go round, read from the game, never sorted by
 * name. Proved against the record: the turns the game plays are the order
 * the boards claimed. (XMage's player list is a CircularList that inserts
 * at the front, so the cycle runs opposite to seating order: seated Amy,
 * Zed, Bob, Cat, the turns go Amy, Cat, Bob, Zed. A quirk, but the
 * engine's, and the board must say what the engine does.)
 */
public class TurnOrderTest {

    static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 180_000)
    public void boardFollowsTheTurnCycleFromEachSeat() throws Exception {
        Path logDir = Files.createTempDirectory("turn-order");
        List<String> seats = List.of("Amy", "Zed", "Bob");
        GameHost host = new GameHost(new GameHost.Config("turnorder", "commander", 5L, logDir.toString(),
                List.of(new GameHost.SeatSpec("Amy", "seat", BEARS, 0), new GameHost.SeatSpec("Zed", "seat", BEARS, 0),
                        new GameHost.SeatSpec("Bob", "seat", BEARS, 0), new GameHost.SeatSpec("Cat", "cpu", BEARS, 6)),
                false, null, 0, 0, "host"));
        try {
            host.start();
            // Amy (the host) picks the starter: the first question is hers. From
            // here every seat answers "no" (keep, pass, no attacks) until turn 6.
            Map<String, Object> d = host.awaitDecision("Amy", 60_000);
            Assert.assertTrue("a question for Amy: " + d, Boolean.TRUE.equals(d.get("action_pending")));
            Assert.assertEquals(List.of("Amy", "Cat", "Bob", "Zed"), names(host.state("Amy")));
            Assert.assertEquals(List.of("Zed", "Amy", "Cat", "Bob"), names(host.state("Zed")));
            Assert.assertEquals(List.of("Bob", "Zed", "Amy", "Cat"), names(host.state("Bob")));
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> board = (List<Map<String, Object>>) host.state("Bob").get("board");
            for (int i = 0; i < board.size(); i++) {
                Assert.assertEquals(i, board.get(i).get("turn_order"));
            }
            Assert.assertTrue(Boolean.TRUE.equals(board.get(0).get("is_you")));
            List<String> turns = new ArrayList<>();
            for (int i = 0; i < 2000 && turns.size() < 6; i++) {
                for (String seat : seats) {
                    Map<String, Object> q = host.awaitDecision(seat, 200);
                    if (Boolean.TRUE.equals(q.get("action_pending"))) {
                        // Amy picks herself as the starter (the seat itself is choice 0); everything else is "no".
                        // Any other required pick (the cleanup discard) takes its first option too.
                        boolean starter = String.valueOf(q.get("message")).contains("starting player");
                        boolean required = Boolean.TRUE.equals(q.get("required"));
                        host.chooseAction(seat, Map.of("choice", starter || required ? "0" : "no"));
                    }
                }
                turns = turns(logDir);
            }
            Assert.assertEquals("the turns the game played: " + turns, List.of("Amy", "Cat", "Bob", "Zed", "Amy", "Cat"), turns);
        } finally {
            host.end();
        }
    }

    /**
     * The active player of each turn in the record, in order — from the
     * phase changes (turn 1's turn_change is written before the starter is
     * known, with no active player).
     */
    private static List<String> turns(Path logDir) throws IOException {
        List<String> out = new ArrayList<>();
        Path record = logDir.resolve("server_game_events.jsonl");
        if (!Files.exists(record)) {
            return out;
        }
        java.util.regex.Pattern turn = java.util.regex.Pattern.compile("\"turn\":\\s*(\\d+)");
        java.util.regex.Pattern active = java.util.regex.Pattern.compile("\"active_player\":\\s*\"([^\"]+)\"");
        int lastTurn = -1;
        for (String line : Files.readAllLines(record)) {
            if (!line.contains("\"phase_change\"")) {
                continue;
            }
            java.util.regex.Matcher t = turn.matcher(line);
            java.util.regex.Matcher a = active.matcher(line);
            if (t.find() && a.find() && Integer.parseInt(t.group(1)) != lastTurn) {
                lastTurn = Integer.parseInt(t.group(1));
                out.add(a.group(1));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> names(Map<String, Object> result) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> p : (List<Map<String, Object>>) result.get("board")) {
            out.add(String.valueOf(p.get("name")));
        }
        return out;
    }
}
