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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Blocking offers only the blocks the rules allow. A flyer attacks the seat,
 * which has a ground creature and a reach creature: the blockers window says
 * which attackers each blocker can block ({@code can_block}), a blocker that
 * can block nothing says why, and a batch that pairs the ground creature
 * with the flyer never strands the seat on XMage's "Select attacker to
 * block" with nothing in it (game c827ca01565e, 2026-09-23) — the pair is
 * dropped with a line saying why, the reach creature's block stands, and the
 * window comes back to the person. With a ground attacker beside the flyer,
 * the dropped pair must not become a block of the other attacker (XMage
 * picks the only legal attacker without asking).
 */
public class BlockLegalityTest {

    private static final Logger LOG = Logger.getLogger(BlockLegalityTest.class);

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    /** What the seat saw: the first blockers window, every question after it, the chat, and the game log. */
    private record Outcome(Map<String, Object> window, List<Map<String, Object>> after, List<String> chat, String log) {
    }

    /**
     * Atk goes first and attacks with everything it has on {@code attackers};
     * You hold Runeclaw Bear and Giant Spider and answer the first blockers
     * window with {@code blocks} (names, turned into ids), then confirm what
     * comes back.
     */
    private Outcome play(List<String> attackers, String blocks) throws Exception {
        GameHost host = new GameHost(new GameHost.Config("block-legality", "duel", 7L, null,
                List.of(new GameHost.SeatSpec("Atk", "seat", GameHostTest.BEARS, 0), new GameHost.SeatSpec("You", "seat", GameHostTest.BEARS, 0)), false));
        Game game = host.game();
        Player you = null;
        Player atk = null;
        for (Player p : game.getPlayers().values()) {
            if ("You".equals(p.getName())) {
                you = p;
            } else {
                atk = p;
            }
        }
        Assert.assertNotNull(you);
        Assert.assertNotNull(atk);
        List<PutToBattlefieldInfo> theirs = new ArrayList<>();
        for (String name : attackers) {
            theirs.add(new PutToBattlefieldInfo(card(name), false));
        }
        game.cheat(atk.getId(), library(), List.of(), theirs, List.of(), List.of(), List.of());
        game.cheat(you.getId(), library(), List.of(),
                List.of(new PutToBattlefieldInfo(card("Runeclaw Bear"), false), new PutToBattlefieldInfo(card("Giant Spider"), false)),
                List.of(), List.of(), List.of());
        Map<String, ScriptedSeat> scripts = Map.of("Atk", new ScriptedSeat(), "You", new ScriptedSeat());
        Map<String, Object> window = null;
        List<Map<String, Object>> after = new ArrayList<>();
        List<String> chat = new ArrayList<>();
        boolean done = false;
        host.start();
        try {
            for (int i = 0; i < 4000 && !done; i++) {
                for (String seat : List.of("Atk", "You")) {
                    Map<String, Object> d = host.awaitDecision(seat, 100);
                    if ("You".equals(seat) && d.get("recent_chat") instanceof List<?> l) {
                        for (Object o : l) {
                            chat.add(String.valueOf(o));
                        }
                    }
                    if (Boolean.TRUE.equals(d.get("game_over"))) {
                        done = true;
                        break;
                    }
                    if (!Boolean.TRUE.equals(d.get("action_pending"))) {
                        continue;
                    }
                    Assert.assertNull("render error", d.get("error"));
                    String context = String.valueOf(d.get("context"));
                    String message = String.valueOf(d.get("message"));
                    Map<String, Object> args;
                    if ("GAME_TARGET".equals(d.get("action_type")) && message.contains("starting player")) {
                        args = Map.of("choice", indexOfYou(d));
                    } else if ("You".equals(seat) && window != null) {
                        after.add(d);
                        if (context.contains("Combat Damage") || context.contains("End of Combat") || context.contains("Postcombat")
                                || !context.startsWith("T1")) {
                            done = true;
                            break;
                        }
                        args = "declare_blockers".equals(d.get("combat_phase")) ? Map.of("choice", "no") : scripts.get(seat).answer(d);
                    } else if ("You".equals(seat) && "declare_blockers".equals(d.get("combat_phase"))) {
                        window = d;
                        args = Map.of("blockers", pairs(d, blocks));
                    } else {
                        args = scripts.get(seat).answer(d);
                    }
                    Map<String, Object> answer = host.chooseAction(seat, args);
                    Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
                }
            }
        } finally {
            host.end();
        }
        String log = Fmt.stripHtml(String.join("\n", host.logLines()));
        Outcome out = new Outcome(window, after, chat, log);
        LOG.info("window " + window + "\nafter " + after.stream().map(d -> d.get("context") + " " + d.get("action_type") + " " + d.get("message") + " " + d.get("choices")).toList()
                + "\nchat " + chat + "\nlog:\n" + log);
        return out;
    }

    /** "Runeclaw Bear:Wind Drake,…" by name → the window's short ids. */
    private static String pairs(Map<String, Object> d, String blocks) {
        Map<String, String> ids = new HashMap<>();
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            ids.put(String.valueOf(c.get("name")), String.valueOf(c.get("id")));
        }
        for (Object o : (List<?>) d.get("incoming_attackers")) {
            Map<?, ?> a = (Map<?, ?>) o;
            ids.put(String.valueOf(a.get("name")), String.valueOf(a.get("id")));
        }
        List<String> out = new ArrayList<>();
        for (String pair : blocks.split(",")) {
            String[] names = pair.split(":");
            out.add(ids.get(names[0]) + ":" + ids.get(names[1]));
        }
        return String.join(",", out);
    }

    private static Map<String, Object> blocker(Map<String, Object> d, String name) {
        for (Map<String, Object> c : ScriptedSeat.choices(d)) {
            if ("blocker".equals(c.get("choice_type")) && name.equals(c.get("name"))) {
                return c;
            }
        }
        throw new AssertionError(name + " is not offered as a blocker: " + ScriptedSeat.choices(d));
    }

    private static String attackerId(Map<String, Object> d, String name) {
        for (Object o : (List<?>) d.get("incoming_attackers")) {
            Map<?, ?> a = (Map<?, ?>) o;
            if (name.equals(a.get("name"))) {
                return String.valueOf(a.get("id"));
            }
        }
        throw new AssertionError(name + " is not attacking: " + d.get("incoming_attackers"));
    }

    /** No question with nothing to pick reached the seat after the batch: XMage's empty "Select attacker to block". */
    private static void noEmptyTargetQuestion(Outcome o) {
        for (Map<String, Object> d : o.after()) {
            if ("GAME_TARGET".equals(d.get("action_type"))) {
                Assert.assertFalse("an empty target question reached the seat: " + d, ScriptedSeat.choices(d).isEmpty());
                Assert.assertFalse("the engine's attacker question reached the seat: " + d,
                        String.valueOf(d.get("message")).contains("attacker to block"));
            }
        }
    }

    @Test(timeout = 240_000)
    public void flyerIsBlockableOnlyByReach() throws Exception {
        Outcome o = play(List.of("Wind Drake"), "Runeclaw Bear:Wind Drake,Giant Spider:Wind Drake");
        Assert.assertNotNull("a blockers window reached the seat", o.window());
        String drake = attackerId(o.window(), "Wind Drake");
        Assert.assertEquals("the reach creature can block the flyer", List.of(drake), blocker(o.window(), "Giant Spider").get("can_block"));
        Map<String, Object> bear = blocker(o.window(), "Runeclaw Bear");
        Assert.assertEquals("the ground creature can block nothing", List.of(), bear.get("can_block"));
        Assert.assertEquals("and says why", "can't block Wind Drake (flying)", bear.get("block_reason"));
        noEmptyTargetQuestion(o);
        Assert.assertTrue("the dropped pair is said: " + o.chat(),
                o.chat().stream().anyMatch(c -> c.contains("Runeclaw Bear can't block Wind Drake (flying)")));
        Assert.assertTrue("the blockers window came back to the person: " + o.after(),
                o.after().stream().anyMatch(d -> "declare_blockers".equals(d.get("combat_phase"))));
        Assert.assertTrue("the reach block stuck: " + o.log(), o.log().contains("blocked by Giant Spider"));
        Assert.assertFalse("the ground creature didn't block: " + o.log(), o.log().contains("Runeclaw Bear ("));
    }

    @Test(timeout = 240_000)
    public void droppedPairDoesNotBlockTheOtherAttacker() throws Exception {
        Outcome o = play(List.of("Wind Drake", "Grizzly Bears"), "Runeclaw Bear:Wind Drake,Giant Spider:Wind Drake");
        Assert.assertNotNull("a blockers window reached the seat", o.window());
        String drake = attackerId(o.window(), "Wind Drake");
        String bears = attackerId(o.window(), "Grizzly Bears");
        Assert.assertEquals(List.of(bears), blocker(o.window(), "Runeclaw Bear").get("can_block"));
        Assert.assertEquals(List.of(drake, bears).stream().sorted().toList(),
                ((List<?>) blocker(o.window(), "Giant Spider").get("can_block")).stream().map(String::valueOf).sorted().toList());
        Assert.assertNull("a blocker with something to block has no reason", blocker(o.window(), "Runeclaw Bear").get("block_reason"));
        noEmptyTargetQuestion(o);
        Assert.assertTrue("the reach block stuck: " + o.log(), o.log().contains("blocked by Giant Spider"));
        Assert.assertFalse("the ground creature wasn't moved onto the Bears: " + o.log(), o.log().contains("Runeclaw Bear ("));
    }

    /**
     * Menace is a check of the whole declaration, not of a pair: one blocker
     * passes {@code can_block}, the window carries {@code min_blockers}, and
     * at confirm the engine discards the lone blocker, says why in the game
     * log and — the seat having two creatures that could block it — asks
     * the blockers window again rather than ending on a dead end.
     */
    @Test(timeout = 240_000)
    public void menaceLoneBlockerIsDiscardedAndReasked() throws Exception {
        Outcome o = play(List.of("Boggart Brute"), "Runeclaw Bear:Boggart Brute");
        Assert.assertNotNull("a blockers window reached the seat", o.window());
        String brute = attackerId(o.window(), "Boggart Brute");
        Assert.assertEquals(List.of(brute), blocker(o.window(), "Runeclaw Bear").get("can_block"));
        Map<?, ?> incoming = (Map<?, ?>) ((List<?>) o.window().get("incoming_attackers")).get(0);
        Assert.assertEquals("the window says menace needs two", 2, incoming.get("min_blockers"));
        noEmptyTargetQuestion(o);
        Assert.assertTrue("the engine says why: " + o.log(), o.log().contains("can't be blocked except by 2 or more creatures"));
        Assert.assertTrue("the blockers window came back: " + o.after(),
                o.after().stream().anyMatch(d -> "declare_blockers".equals(d.get("combat_phase"))));
        Assert.assertTrue("unblocked after the empty re-declaration: " + o.log(), o.log().contains("Boggart Brute (3/2) unblocked"));
    }

    private static List<Card> library() {
        List<Card> library = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            library.add(card("Colossal Dreadmaw"));
        }
        return library;
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
}
