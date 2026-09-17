package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The harness: a whole game hosted in this JVM, driven through the same API
 * the product will use over the socket, by a scripted seat against
 * ComputerPlayer7. Asserts on the decisions as the product sees them.
 */
public class GameHostTest {

    private static final Logger LOG = Logger.getLogger(GameHostTest.class);
    static final String BEARS = "src/test/resources/decks/bears_and_forests.dck";

    @BeforeClass
    public static void loadCards() {
        long t0 = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
        LOG.info("cards scanned in " + (System.currentTimeMillis() - t0) + " ms");
    }

    @Test(timeout = 240_000)
    public void scriptedSeatPlaysBearsAgainstTheCpu() throws Exception {
        Path logDir = Files.createTempDirectory("seat-game");
        GameHost host = new GameHost(new GameHost.Config("harness", "duel", 7L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        ScriptedSeat script = new ScriptedSeat();
        long t0 = System.currentTimeMillis();
        host.start();
        long firstDecisionAt = 0;
        int maxTurn = 0;
        boolean sawOurCreature = false;
        boolean sawOurAttack = false;
        try {
            for (int i = 0; i < 400; i++) {
                Map<String, Object> d = host.awaitDecision("You", 60_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    LOG.info("game over: " + d.get("winner"));
                    break;
                }
                Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
                if (firstDecisionAt == 0) {
                    firstDecisionAt = System.currentTimeMillis();
                }
                String context = String.valueOf(d.get("context"));
                Assert.assertTrue("context: " + context, context.matches("^T\\d+ .*\\(.*\\).*"));
                Assert.assertNotNull("action_type", d.get("action_type"));
                Assert.assertNotNull("response_type", d.get("response_type"));
                Assert.assertNull("render error", d.get("error"));
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                maxTurn = Math.max(maxTurn, turn);
                Map<String, Object> board = board(d);
                Assert.assertTrue("our seat first on the board", Boolean.TRUE.equals(board.get("is_you")));
                if (battlefieldHas(board, "Grizzly Bears")) {
                    sawOurCreature = true;
                }
                if (d.get("already_attacking") != null || (d.get("combat") != null && "declare_blockers".equals(d.get("combat_phase")) == false && String.valueOf(d.get("combat")).contains("Grizzly Bears"))) {
                    sawOurAttack = true;
                }
                LOG.info("DECISION " + context + " " + d.get("action_type") + "/" + d.get("response_type") + " " + d.get("message")
                        + (d.get("combat_phase") != null ? " " + d.get("combat_phase") : "") + " choices=" + ScriptedSeat.choices(d).size());
                if (turn >= 8) {
                    host.end();
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        long elapsed = System.currentTimeMillis() - t0;
        LOG.info(String.format("first decision after %d ms; %d decisions in %d ms; reached turn %d; lands %d casts %d attacks %d blocks %d mana prompts %d",
                firstDecisionAt - t0, script.seen.size(), elapsed, maxTurn, script.lands, script.casts, script.attacks, script.blocks, script.manaPrompts));
        Assert.assertTrue("reached turn " + maxTurn, maxTurn >= 5);
        Assert.assertTrue("played lands", script.lands >= 2);
        Assert.assertTrue("cast a creature", script.casts >= 1);
        Assert.assertTrue("our creature reached the battlefield (mana was auto-paid)", sawOurCreature);
        Assert.assertEquals("no mana prompt reached the seat", 0, script.manaPrompts);
        Assert.assertTrue("declared an attack", script.attacks >= 1);
        Assert.assertTrue("our attack reached combat", sawOurAttack);
        Path record = logDir.resolve("server_game_events.jsonl");
        Assert.assertTrue("event record written", Files.exists(record));
        long decisions = Files.lines(record).filter(l -> l.contains("\"type\":\"decision\"") || l.contains("\"type\": \"decision\"")).count();
        Assert.assertTrue("decisions recorded: " + decisions, decisions >= 5);
    }

    @Test(timeout = 120_000)
    public void answersAreValidatedAgainstThePendingQuestion() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("validate", "duel", 3L, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)), false));
        try {
            Map<String, Object> none = host.chooseAction("You", Map.of("choice", "no"));
            Assert.assertEquals("no_pending_action", none.get("error_code"));
            host.start();
            Map<String, Object> d = host.awaitDecision("You", 60_000);
            Assert.assertEquals("GAME_TARGET", d.get("action_type")); // "Select a starting player"
            Map<String, Object> bad = host.chooseAction("You", Map.of("choice", "42"));
            Assert.assertEquals("index_out_of_range", bad.get("error_code"));
            Assert.assertEquals(true, bad.get("retryable"));
            Map<String, Object> wrongKind = host.chooseAction("You", Map.of("attackers", "all"));
            Assert.assertEquals("invalid_choice", wrongKind.get("error_code"));
            Map<String, Object> ok = host.chooseAction("You", Map.of("choice", "0"));
            Assert.assertEquals(true, ok.get("success"));
            Map<String, Object> again = host.awaitDecision("You", 60_000);
            Assert.assertEquals("GAME_ASK", again.get("action_type")); // mulligan
            Assert.assertTrue(String.valueOf(again.get("message")).toLowerCase().contains("mulligan"));
            Assert.assertNotNull("hand shown on the mulligan question", again.get("your_hand"));
            Map<String, Object> state = host.state("You");
            Assert.assertNotNull(state.get("board"));
        } finally {
            host.end();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> board(Map<String, Object> d) {
        return ((List<Map<String, Object>>) d.get("board")).get(0);
    }

    @SuppressWarnings("unchecked")
    static boolean battlefieldHas(Map<String, Object> player, String name) {
        Object bf = player.get("battlefield");
        if (!(bf instanceof List<?> l)) {
            return false;
        }
        for (Object o : l) {
            if (name.equals(((Map<String, Object>) o).get("name"))) {
                return true;
            }
        }
        return false;
    }
}
