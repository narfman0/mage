package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The table's rules through the host config: a d20 roll-off for who goes
 * first (no "starting player" prompt, the rolls in the log) and the London
 * mulligan with one free mulligan (the first is free, the second bottoms a
 * card — and the bottom question reaches the seat and is answerable).
 */
public class TableRulesTest {

    private static final Logger LOG = Logger.getLogger(TableRulesTest.class);
    static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    private static GameHost host(String id, long seed, int freeMulligans, String startingPlayer) throws Exception {
        return new GameHost(new GameHost.Config(id, "duel", seed, null,
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", BEARS, 6)),
                false, null, 0, freeMulligans, startingPlayer));
    }

    @Test(timeout = 120_000)
    public void rollOffDecidesTheStarterWithoutAPrompt() throws Exception {
        GameHost host = host("rolloff", 11L, 0, "roll");
        try {
            host.start();
            Map<String, Object> d = host.awaitDecision("You", 60_000);
            Assert.assertEquals("the first question is the mulligan, not the starting player: " + d, "GAME_ASK", d.get("action_type"));
            Assert.assertTrue(String.valueOf(d.get("message")).toLowerCase().contains("mulligan"));
            String log = Fmt.stripHtml(String.join("\n", host.logLines()));
            LOG.info("log so far:\n" + log);
            Assert.assertTrue("the rolls are in the log: " + log, log.matches("(?s).*\\w+ rolls \\d+, \\w+ rolls \\d+.*"));
            Assert.assertTrue("someone goes first: " + log, log.contains(" goes first"));
            Assert.assertFalse("no toss when rolling", log.contains("won the toss"));
        } finally {
            host.end();
        }
    }

    @Test(timeout = 120_000)
    public void randomStarterNeedsNoPromptEither() throws Exception {
        GameHost host = host("randomstart", 12L, 0, "random");
        try {
            host.start();
            Map<String, Object> d = host.awaitDecision("You", 60_000);
            Assert.assertEquals("GAME_ASK", d.get("action_type"));
            String log = Fmt.stripHtml(String.join("\n", host.logLines()));
            Assert.assertTrue(log, log.contains(" goes first (random)"));
        } finally {
            host.end();
        }
    }

    @Test(timeout = 120_000)
    public void oneFreeMulliganThenLondon() throws Exception {
        GameHost host = host("mulligan", 13L, 1, "random");
        try {
            host.start();
            List<String> seen = new ArrayList<>();
            int yesses = 0;
            boolean bottomed = false;
            for (int i = 0; i < 40; i++) {
                Map<String, Object> d = host.awaitDecision("You", 60_000);
                String type = String.valueOf(d.get("action_type"));
                String msg = String.valueOf(d.get("message"));
                seen.add(type + " " + msg);
                LOG.info("DECISION " + type + "/" + d.get("response_type") + " " + msg + " choices=" + ScriptedSeat.choices(d).size());
                if ("GAME_ASK".equals(type) && msg.toLowerCase().contains("mulligan")) {
                    // Two mulligans: the free one, then a real London one.
                    yesses++;
                    Map<String, Object> a = host.chooseAction("You", Map.of("choice", yesses <= 2 ? "yes" : "no"));
                    Assert.assertEquals(String.valueOf(a), true, a.get("success"));
                    continue;
                }
                if (msg.toLowerCase().contains("bottom")) {
                    bottomed = true;
                    Assert.assertFalse("the bottom question offers the hand: " + d, ScriptedSeat.choices(d).isEmpty());
                    Map<String, Object> a = host.chooseAction("You", Map.of("choice", "0"));
                    Assert.assertEquals(String.valueOf(a), true, a.get("success"));
                    continue;
                }
                if ("GAME_SELECT".equals(type)) {
                    break; // priority: the opening hand is settled
                }
                Map<String, Object> a = host.chooseAction("You", Map.of("choice", "no"));
                Assert.assertEquals(String.valueOf(a), true, a.get("success"));
            }
            String log = Fmt.stripHtml(String.join("\n", host.logLines()));
            LOG.info("seen: " + seen + "\nlog:\n" + log + "\nrecord: " + host.game().getState().getTurnNum());
            Assert.assertEquals("asked three times (free, London, keep)", 3, yesses);
            Assert.assertTrue("the free one: " + log, log.contains("You mulligans for free"));
            Assert.assertTrue("the London one: " + log, log.contains("You mulligans down to 6 cards"));
            Assert.assertTrue("the bottom question reached the seat: " + seen, bottomed);
            Map<String, Object> state = host.state("You");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> board = (List<Map<String, Object>>) state.get("board");
            Object hand = board.get(0).get("hand");
            Assert.assertEquals("six cards kept: " + hand, 6, ((List<?>) hand).size());
        } finally {
            host.end();
        }
    }
}
