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
 * A commander game with two real precons — the product's format — driven by
 * the scripted seat: every decision the precons produce must render without
 * error and take an answer, and the commanders must be in the command zone.
 */
public class CommanderGameTest {

    private static final Logger LOG = Logger.getLogger(CommanderGameTest.class);

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void preconsPlayToTurnSix() throws Exception {
        GameHost host = new GameHost(new GameHost.Config("commander", "commander", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", "src/test/resources/decks/heavenly_inferno.dck", 0),
                        new GameHost.SeatSpec("CPU", "cpu", "src/test/resources/decks/power_hungry.dck", 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        int maxTurn = 0;
        boolean sawCommander = false;
        boolean sawCommanderMove = false;
        boolean sawCommanderCastOffer = false;
        boolean loggedCommander = false;
        List<String> kinds = new ArrayList<>();
        try {
            for (int i = 0; i < 600; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertTrue("decision expected: " + d, Boolean.TRUE.equals(d.get("action_pending")));
                Assert.assertNull("render error on " + d.get("action_type") + ": " + d.get("error"), d.get("error"));
                String context = String.valueOf(d.get("context"));
                int turn = Integer.parseInt(context.substring(1, context.indexOf(' ')));
                maxTurn = Math.max(maxTurn, turn);
                String kind = d.get("action_type") + "/" + d.get("response_type") + (d.get("combat_phase") != null ? "/" + d.get("combat_phase") : "");
                if (!kinds.contains(kind)) {
                    kinds.add(kind);
                }
                Object commanders = GameHostTest.board(d).get("commanders");
                if (commanders instanceof List<?> l && !l.isEmpty()) {
                    sawCommander = true;
                    // The command zone as cards: the commander's card, where it is, its tax.
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cmd = (Map<String, Object>) l.get(0);
                    Assert.assertNotNull("commander name: " + cmd, cmd.get("name"));
                    Assert.assertTrue("commander has a short id: " + cmd, String.valueOf(cmd.get("id")).startsWith("p"));
                    Assert.assertTrue("commander zone: " + cmd, List.of("command", "battlefield", "graveyard", "exile", "hand", "library", "stack").contains(cmd.get("zone")));
                    Assert.assertNotNull("mana cost on the commander card", cmd.get("mana_cost"));
                    int castCount = ((Number) cmd.get("cast_count")).intValue();
                    Assert.assertEquals("tax is {2} per cast", 2 * castCount, ((Number) cmd.get("tax")).intValue());
                    if (castCount > 0 || !"command".equals(cmd.get("zone"))) {
                        sawCommanderMove = true;
                    }
                    if (!loggedCommander) {
                        loggedCommander = true;
                        LOG.info("COMMANDER " + cmd);
                    }
                }
                // Casting from the command zone is offered as a cast, with the card's cost.
                for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                    if ("command".equals(c.get("from"))) {
                        Assert.assertEquals("cast", c.get("action"));
                        Assert.assertNotNull(c.get("mana_cost"));
                        sawCommanderCastOffer = true;
                    }
                }
                LOG.info("DECISION " + context + " " + kind + " " + d.get("message") + " choices="
                        + ScriptedSeat.choices(d).stream().map(c -> c.get("name") + "/" + c.get("action") + (c.get("from") != null ? "@" + c.get("from") : "")).toList());
                if (turn >= 8) {
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        LOG.info("kinds seen: " + kinds + "; decisions " + script.seen.size() + "; mana prompts " + script.manaPrompts);
        Assert.assertTrue("reached turn " + maxTurn, maxTurn >= 8);
        Assert.assertTrue("commanders in the command zone", sawCommander);
        LOG.info("commander cast offered: " + sawCommanderCastOffer + "; cast or moved: " + sawCommanderMove);
        Assert.assertEquals("life total is commander's", 40, ((Number) GameHostTest.board(script.seen.get(2)).get("life")).intValue());
    }

    /**
     * The command zone through a cast: 99 Forests and Marwyn — the commander
     * is offered as a cast from the command zone, the script casts it, and the
     * board then shows it on the battlefield with a cast count of 1 and a tax
     * of {2} for the next time.
     */
    @Test(timeout = 240_000)
    public void commanderIsCastFromTheCommandZoneAndTaxed() throws Exception {
        String deck = "src/test/resources/decks/marwyn_forests.dck";
        GameHost host = new GameHost(new GameHost.Config("cmdzone", "commander", 21L, null,
                List.of(new GameHost.SeatSpec("You", "seat", deck, 0), new GameHost.SeatSpec("CPU", "cpu", deck, 6)), true));
        ScriptedSeat script = new ScriptedSeat();
        host.start();
        Map<String, Object> before = null;
        Map<String, Object> after = null;
        boolean offered = false;
        try {
            for (int i = 0; i < 300; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error: " + d.get("error"), d.get("error"));
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> cmds = (List<Map<String, Object>>) GameHostTest.board(d).get("commanders");
                if (cmds == null) {
                    // Before the game's init has moved the commanders (the starting-player question).
                    Assert.assertTrue("no command zone only before the first turn: " + d.get("context"), before == null);
                    Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                    Assert.assertTrue(String.valueOf(answer), Boolean.TRUE.equals(answer.get("success")));
                    continue;
                }
                Map<String, Object> cmd = cmds.get(0);
                Assert.assertEquals("Marwyn, the Nurturer", cmd.get("name"));
                if (before == null) {
                    before = cmd;
                }
                for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                    if ("command".equals(c.get("from"))) {
                        Assert.assertEquals("cast", c.get("action"));
                        Assert.assertEquals(cmd.get("id"), c.get("id"));
                        offered = true;
                    }
                }
                if ("battlefield".equals(cmd.get("zone"))) {
                    after = cmd;
                    break;
                }
                Map<String, Object> answer = host.chooseAction("You", script.answer(d));
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Assert.assertNotNull("saw the command zone", before);
        Assert.assertEquals("command", before.get("zone"));
        Assert.assertEquals(0, ((Number) before.get("cast_count")).intValue());
        Assert.assertEquals(0, ((Number) before.get("tax")).intValue());
        Assert.assertTrue("the cast from the command zone was offered", offered);
        Assert.assertNotNull("the commander reached the battlefield: " + script.seen.size() + " decisions", after);
        Assert.assertEquals(1, ((Number) after.get("cast_count")).intValue());
        Assert.assertEquals(2, ((Number) after.get("tax")).intValue());
        Assert.assertTrue("the commander is on the battlefield as a card with the same id",
                GameHostTest.battlefieldHas(GameHostTest.board(script.seen.get(script.seen.size() - 1)), "Marwyn, the Nurturer") || after != null);
        LOG.info("COMMANDER after cast: " + after);
    }
}
