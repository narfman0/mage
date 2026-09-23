package mage.player.seat;

import mage.cards.Card;
import mage.cards.repository.CardInfo;
import mage.cards.repository.CardRepository;
import mage.cards.repository.CardScanner;
import mage.game.Game;
import mage.game.PutToBattlefieldInfo;
import mage.players.Player;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An attack tax paid by a person's seat: the opponent controls Propaganda,
 * the seat attacks, says yes to "Pay {2} to attack?", and the mana prompt
 * lists what it can pay with. XMage leaves the view's playable list empty
 * while attackers are declared, and the prompt used to offer only Cancel
 * (report 8c560a8728). Three different lands make {2} ambiguous, so auto-pay
 * hands the choice to the prompt, as it did in the report.
 */
public class AttackTaxTest {

    private static final Logger LOG = Logger.getLogger(AttackTaxTest.class);
    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /** What the attack produced: each mana prompt's offered sources, and the opponent's life after combat. */
    private record Outcome(List<List<String>> prompts, int opponentLife, List<String> log) {
    }

    /**
     * We go first, keep, pass the main phase, attack with everything, pay the
     * tax from the prompt (the first offered source each time) and read the
     * opponent's life at the next question after combat damage.
     */
    private Outcome attack(List<String> hand, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("attack-tax", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        Player cpu = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            } else {
                cpu = p;
            }
        }
        Assert.assertNotNull(you);
        Assert.assertNotNull(cpu);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> inHand = new ArrayList<>();
        for (String name : hand) {
            inHand.add(card(name));
        }
        game.cheat(you.getId(), library(), inHand, perms, List.of(), List.of(), List.of());
        game.cheat(cpu.getId(), library(), List.of(), List.of(new PutToBattlefieldInfo(card("Propaganda"), false)), List.of(), List.of(), List.of());
        List<List<String>> prompts = new ArrayList<>();
        List<String> log = new ArrayList<>();
        int opponentLife = -1;
        boolean attacked = false;
        host.start();
        try {
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                String context = String.valueOf(d.get("context"));
                log.add(context + " " + type + " " + message);
                if (attacked && !context.contains("Declare Attackers")) {
                    // past the declaration: wait for the damage, then read the opponent's life
                    if (context.contains("Combat Damage") || context.contains("End of Combat") || context.contains("Postcombat")) {
                        opponentLife = life(d, false);
                        break;
                    }
                }
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("declare_attackers".equals(d.get("combat_phase")) && !attacked) {
                    // by name, the way the report's attack was made (atk:p39,…): XMage's
                    // "attack with everything" declares nothing that has a cost to attack
                    attacked = true;
                    args = Map.of("attackers", attackerId(d, "Grizzly Bears"));
                } else if (message.contains("to attack?")) {
                    args = Map.of("choice", "yes");
                } else if ("GAME_PLAY_MANA".equals(type)) {
                    List<String> offered = new ArrayList<>();
                    for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                        offered.add(String.valueOf(c.get("name")));
                    }
                    prompts.add(offered);
                    List<Map<String, Object>> choices = ScriptedSeat.choices(d);
                    args = Map.of("choice", choices.isEmpty() ? "no" : String.valueOf(choices.get(0).get("index")));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Outcome out = new Outcome(prompts, opponentLife, log);
        LOG.info(List.of(battlefield) + " hand " + hand + " -> " + out);
        return out;
    }

    private static List<Card> library() {
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        return library;
    }

    @SuppressWarnings("unchecked")
    private static int life(Map<String, Object> d, boolean you) {
        for (Object o : (List<?>) d.get("board")) {
            Map<String, Object> p = (Map<String, Object>) o;
            if (Boolean.TRUE.equals(p.get("is_you")) == you) {
                return ((Number) p.get("life")).intValue();
            }
        }
        throw new AssertionError("no board entry");
    }

    private static String attackerId(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("attacker".equals(c.get("choice_type")) && name.equals(c.get("name"))) {
                return String.valueOf(c.get("id"));
            }
        }
        throw new AssertionError(name + " is not offered as an attacker: " + ScriptedSeat.choices(d));
    }

    private static String indexOfYou(Map<String, Object> d) {
        List<Map<String, Object>> choices = ScriptedSeat.choices(d);
        for (int i = 0; i < choices.size(); i++) {
            if (Boolean.TRUE.equals(choices.get(i).get("is_you"))) {
                return String.valueOf(i);
            }
        }
        return "0";
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name, true);
        Assert.assertNotNull("card: " + name, info);
        return info.createCard();
    }

    @Test(timeout = 240_000)
    public void propagandaPromptListsTheLands() throws Exception {
        Outcome o = attack(List.of(), "Grizzly Bears", "Plains", "Forest", "Island");
        Assert.assertFalse("a mana prompt reached the seat: " + o, o.prompts().isEmpty());
        Assert.assertTrue("the first prompt lists the untapped lands: " + o,
                o.prompts().get(0).containsAll(List.of("Plains", "Forest", "Island")));
        Assert.assertEquals("the tax was paid and the Bears connected: " + o, 20 - 2, o.opponentLife());
    }

    @Test(timeout = 240_000)
    public void propagandaPromptListsAManaSourceInHand() throws Exception {
        Outcome o = attack(List.of("Simian Spirit Guide"), "Grizzly Bears", "Island");
        Assert.assertFalse("a mana prompt reached the seat: " + o, o.prompts().isEmpty());
        Assert.assertTrue("the prompt offers the card in hand as well as the land: " + o,
                o.prompts().get(0).containsAll(List.of("Simian Spirit Guide", "Island")));
        Assert.assertEquals("the tax was paid and the Bears connected: " + o, 20 - 2, o.opponentLife());
    }
}
