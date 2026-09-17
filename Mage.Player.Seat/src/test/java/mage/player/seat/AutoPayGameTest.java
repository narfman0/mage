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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The auto-payer through the real engine: a hand-built board, one spell in
 * hand, and what happens when the seat casts it — paid without a word from
 * the right sources, or the mana prompt reaching the seat because the choice
 * is the player's. The clean-source rule is on the cards themselves here
 * (Ancient Tomb's damage, Crystal Vein's sacrifice, a creature, conditional
 * mana), which {@link AutoPayTest} cannot see.
 */
public class AutoPayGameTest {

    private static final Logger LOG = Logger.getLogger(AutoPayGameTest.class);
    private static final String FILLER = "src/test/resources/decks/filler_opponent.dck";

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /** What one cast produced: the mana prompt count, and the board after. */
    private record Outcome(int manaPrompts, boolean cast, Set<String> tapped, Set<String> untapped, int life, List<String> log) {
    }

    /**
     * One scenario: {@code battlefield} under our control, {@code spell} in
     * hand, seven uncastable cards on top of the library so the opening hand
     * adds nothing playable. We choose to go first, keep, and at our first
     * main phase cast the spell; a mana prompt is answered with Cancel (what
     * the product's pilot does, and what a person's Cancel does).
     */
    private Outcome play(String spell, String... battlefield) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("autopay", "duel", 5L, null,
                List.of(new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("CPU", "cpu", FILLER, 6)), true));
        Game game = host.game();
        Player you = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            }
        }
        Assert.assertNotNull(you);
        List<PutToBattlefieldInfo> perms = new ArrayList<>();
        for (String name : battlefield) {
            perms.add(new PutToBattlefieldInfo(card(name), false));
        }
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        game.cheat(you.getId(), library, List.of(card(spell)), perms, List.of(), List.of(), List.of());
        int manaPrompts = 0;
        boolean cast = false;
        Set<String> tapped = new HashSet<>();
        Set<String> untapped = new HashSet<>();
        int life = -1;
        List<String> log = new ArrayList<>();
        host.start();
        try {
            for (int i = 0; i < 60; i++) {
                Map<String, Object> d = host.awaitDecision("You", 120_000);
                if (Boolean.TRUE.equals(d.get("game_over"))) {
                    break;
                }
                Assert.assertNull("render error", d.get("error"));
                String type = String.valueOf(d.get("action_type"));
                String message = String.valueOf(d.get("message"));
                log.add(d.get("context") + " " + type + " " + message);
                if (cast) {
                    // The decision after the cast: the mana prompt (the choice is the
                    // player's), or the next priority with the payment made.
                    if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                        manaPrompts++;
                        host.chooseAction("You", Map.of("choice", "no"));
                        continue;
                    }
                    Map<String, Object> board = GameHostTest.board(d);
                    for (Object o : (List<?>) board.get("battlefield")) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> perm = (Map<String, Object>) o;
                        (Boolean.TRUE.equals(perm.get("tapped")) ? tapped : untapped).add(String.valueOf(perm.get("name")));
                    }
                    life = ((Number) board.get("life")).intValue();
                    break;
                }
                Map<String, Object> args;
                if ("GAME_TARGET".equals(type) && message.contains("starting player")) {
                    args = Map.of("choice", indexOfYou(d));
                } else if ("GAME_PLAY_MANA".equals(type) || "GAME_PLAY_XMANA".equals(type)) {
                    manaPrompts++;
                    args = Map.of("choice", "no");
                } else if ("GAME_SELECT".equals(type) && "select".equals(d.get("response_type")) && d.get("combat_phase") == null && castIndex(d, spell) != null) {
                    cast = true;
                    args = Map.of("choice", castIndex(d, spell));
                } else {
                    args = Map.of("choice", "no");
                }
                Map<String, Object> answer = host.chooseAction("You", args);
                Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
            }
        } finally {
            host.end();
        }
        Outcome out = new Outcome(manaPrompts, cast, tapped, untapped, life, log);
        LOG.info(spell + " with " + List.of(battlefield) + " -> " + out);
        return out;
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

    private static String castIndex(Map<String, Object> d, String spell) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("cast".equals(c.get("action")) && spell.equals(c.get("name"))) {
                return String.valueOf(c.get("index"));
            }
        }
        return null;
    }

    private static Card card(String name) {
        CardInfo info = CardRepository.instance.findCard(name, true);
        Assert.assertNotNull("card: " + name, info);
        return info.createCard();
    }

    private static void paid(Outcome o, String... tapped) {
        Assert.assertTrue("cast offered and taken: " + o, o.cast());
        Assert.assertEquals("no mana prompt: " + o, 0, o.manaPrompts());
        Assert.assertEquals("tapped: " + o, Set.of(tapped), o.tapped());
    }

    private static void asked(Outcome o) {
        Assert.assertTrue("cast offered and taken: " + o, o.cast());
        Assert.assertTrue("the mana prompt reached the seat: " + o, o.manaPrompts() >= 1);
    }

    @Test(timeout = 240_000)
    public void whichForestDoesNotMatter() throws Exception {
        Outcome o = play("Llanowar Elves", "Forest", "Forest", "Forest");
        paid(o, "Forest");
        Assert.assertTrue("two Forests untapped: " + o, o.untapped().contains("Forest"));
    }

    @Test(timeout = 240_000)
    public void forestOrIslandForOneGenericIsAsked() throws Exception {
        asked(play("Sol Ring", "Forest", "Island"));
    }

    @Test(timeout = 240_000)
    public void tappingOutIsAutomatic() throws Exception {
        paid(play("Mind Stone", "Forest", "Island"), "Forest", "Island");
    }

    @Test(timeout = 240_000)
    public void ancientTombIsNeverTappedForYouWhenAForestCan() throws Exception {
        Outcome o = play("Sol Ring", "Ancient Tomb", "Forest");
        paid(o, "Forest");
        Assert.assertEquals("no damage taken: " + o, 20, o.life());
    }

    @Test(timeout = 240_000)
    public void ancientTombAloneIsAsked() throws Exception {
        asked(play("Sol Ring", "Ancient Tomb"));
    }

    @Test(timeout = 240_000)
    public void aRockPayingExactlyIsUsedAndABasicKept() throws Exception {
        paid(play("Mind Stone", "Sol Ring", "Forest"), "Sol Ring");
    }

    @Test(timeout = 240_000)
    public void aRockOrABasicForOneGenericIsAsked() throws Exception {
        asked(play("Sol Ring", "Sol Ring", "Forest"));
    }

    @Test(timeout = 240_000)
    public void aManaCreatureIsNeverTappedForYou() throws Exception {
        Outcome o = play("Llanowar Elves", "Llanowar Elves", "Forest");
        paid(o, "Forest");
    }

    @Test(timeout = 240_000)
    public void conditionalManaIsNotTheEnginesToSpend() throws Exception {
        // Ancient Ziggurat's mana is for creature spells only: not a clean source, so the Forest is the one plan.
        paid(play("Llanowar Elves", "Ancient Ziggurat", "Forest"), "Forest");
    }

    @Test(timeout = 240_000)
    public void crystalVeinPaysWithItsPlainAbilityAndStays() throws Exception {
        Outcome o = play("Mind Stone", "Crystal Vein", "Forest");
        paid(o, "Crystal Vein", "Forest");
        Assert.assertTrue("the Vein was not sacrificed: " + o, o.tapped().contains("Crystal Vein"));
    }

    @Test(timeout = 240_000)
    public void aDualIsKeptUpWhenABasicCanPay() throws Exception {
        paid(play("Llanowar Elves", "Breeding Pool", "Forest"), "Forest");
        paid(play("Grizzly Bears", "Breeding Pool", "Forest", "Island"), "Forest", "Island");
    }
}
