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
 * hands the choice to the prompt, as it did in the report. Attacking with
 * everything ("all") asks the same question per creature — XMage's own "All
 * attack" skips a creature with an attack cost — and neither the question
 * nor its prompt interrupts the declaration.
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
    private record Outcome(List<List<String>> prompts, int opponentLife, int asked, List<String> chat, List<String> log) {
        boolean interrupted() {
            return chat.stream().anyMatch(c -> c.contains("interrupted"));
        }
    }

    /**
     * We go first, keep, pass the main phase, attack with everything, pay the
     * tax from the prompt (the first offered source each time) and read the
     * opponent's life at the next question after combat damage. {@code all}
     * answers the attackers window with "all" (the board's "Everything")
     * instead of the Bears' id; {@code pay} is how many "to attack?"
     * questions get a yes — the rest a no.
     */
    private Outcome attack(boolean all, int pay, List<String> hand, String... battlefield) throws Exception {
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
        List<String> chat = new ArrayList<>();
        int asked = 0;
        int opponentLife = -1;
        boolean attacked = false;
        host.start();
        try {
            for (int i = 0; i < 80; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (d.get("recent_chat") instanceof List<?> l) {
                    for (Object o : l) {
                        chat.add(String.valueOf(o));
                    }
                }
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
                    // by id, the way the report's attack was made (atk:p39,…), or "all"
                    attacked = true;
                    args = Map.of("attackers", all ? "all" : attackerId(d, "Grizzly Bears"));
                } else if (message.contains("to attack?")) {
                    asked++;
                    args = Map.of("choice", asked <= pay ? "yes" : "no");
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
        Outcome out = new Outcome(prompts, opponentLife, asked, chat, log);
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
        Outcome o = attack(false, 1, List.of(), "Grizzly Bears", "Plains", "Forest", "Island");
        Assert.assertFalse("a mana prompt reached the seat: " + o, o.prompts().isEmpty());
        Assert.assertTrue("the first prompt lists the untapped lands: " + o,
                o.prompts().get(0).containsAll(List.of("Plains", "Forest", "Island")));
        Assert.assertEquals("the tax was paid and the Bears connected: " + o, 20 - 2, o.opponentLife());
        Assert.assertFalse("the tax question didn't interrupt the declaration: " + o, o.interrupted());
    }

    @Test(timeout = 240_000)
    public void propagandaPromptListsAManaSourceInHand() throws Exception {
        Outcome o = attack(false, 1, List.of("Simian Spirit Guide"), "Grizzly Bears", "Island");
        Assert.assertFalse("a mana prompt reached the seat: " + o, o.prompts().isEmpty());
        Assert.assertTrue("the prompt offers the card in hand as well as the land: " + o,
                o.prompts().get(0).containsAll(List.of("Simian Spirit Guide", "Island")));
        Assert.assertEquals("the tax was paid and the Bears connected: " + o, 20 - 2, o.opponentLife());
        Assert.assertFalse("the tax question didn't interrupt the declaration: " + o, o.interrupted());
    }

    /** "Attack with everything": XMage's own "All attack" skips a creature with an attack cost; the seat declares each one so the tax is asked. */
    @Test(timeout = 240_000)
    public void propagandaAttackWithEverythingAsksToPay() throws Exception {
        Outcome o = attack(true, 1, List.of(), "Grizzly Bears", "Plains", "Forest", "Island");
        Assert.assertEquals("asked to pay once: " + o, 1, o.asked());
        Assert.assertFalse("a mana prompt reached the seat: " + o, o.prompts().isEmpty());
        Assert.assertTrue("the prompt lists the untapped lands: " + o,
                o.prompts().get(0).containsAll(List.of("Plains", "Forest", "Island")));
        Assert.assertEquals("the tax was paid and the Bears connected: " + o, 20 - 2, o.opponentLife());
        Assert.assertFalse("the tax question didn't interrupt the declaration: " + o, o.interrupted());
    }

    /** Two Bears, the first tax paid and the second declined: the declined one is undone and the declaration goes on. */
    @Test(timeout = 240_000)
    public void attackWithEverythingDeclinedTaxDropsOnlyThatCreature() throws Exception {
        Outcome o = attack(true, 1, List.of(), "Grizzly Bears", "Grizzly Bears", "Plains", "Forest", "Island", "Mountain");
        Assert.assertEquals("asked for each Bears: " + o, 2, o.asked());
        Assert.assertEquals("one Bears paid and connected: " + o, 20 - 2, o.opponentLife());
        Assert.assertFalse("the tax questions didn't interrupt the declaration: " + o, o.interrupted());
    }

    /**
     * Two Bears, mana for exactly one tax: the engine still asks for the
     * second (its canPay doesn't count the tapped lands), the yes can't be
     * paid, and that Bears is dropped while the first attacks.
     */
    @Test(timeout = 240_000)
    public void attackWithEverythingManaForOneTax() throws Exception {
        Outcome o = attack(true, 2, List.of(), "Grizzly Bears", "Grizzly Bears", "Plains", "Forest");
        Assert.assertEquals("asked for each Bears: " + o, 2, o.asked());
        Assert.assertEquals("one Bears paid and connected: " + o, 20 - 2, o.opponentLife());
        Assert.assertFalse("the tax question didn't interrupt the declaration: " + o, o.interrupted());
    }
}
