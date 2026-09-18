package mage.player.seat;

import mage.cards.repository.CardScanner;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Attacking at a pod: with two or more opponents the engine asks which
 * player each attacker attacks (HumanPlayer.selectDefender). The attackers
 * window names the defenders, and an answer names one per attacker
 * ({@code p1>P3}, {@code all>P3}) — the batch answers the engine's
 * questions itself, so a declaration is one answer, never an interrupted
 * batch and a stray target prompt. An answer that names no defender still
 * gets the engine's question, and the batch resumes after it.
 */
public class PodAttackTest {

    private static final Logger LOG = Logger.getLogger(PodAttackTest.class);
    static final String BEARS = GameHostTest.BEARS;

    @BeforeClass
    public static void loadCards() {
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
    }

    @Test(timeout = 300_000)
    public void attackersNameTheirDefender() throws Exception {
        Path logDir = Files.createTempDirectory("pod-attack");
        List<String> seats = List.of("You", "P2", "P3");
        GameHost host = new GameHost(new GameHost.Config("podattack", "commander", 21L, logDir.toString(),
                List.of(new GameHost.SeatSpec("You", "seat", BEARS, 0), new GameHost.SeatSpec("P2", "seat", BEARS, 0),
                        new GameHost.SeatSpec("P3", "seat", BEARS, 0), new GameHost.SeatSpec("Cat", "cpu", BEARS, 6)),
                false, null, 0, 0, "random"));
        Map<String, ScriptedSeat> scripts = new HashMap<>();
        for (String s : seats) {
            scripts.put(s, new ScriptedSeat());
        }
        List<String> chat = new ArrayList<>();
        int ourAttacks = 0;
        boolean sawDefenderQuestion = false;
        try {
            host.start();
            for (int i = 0; i < 6000 && ourAttacks < 2; i++) {
                for (String seat : seats) {
                    Map<String, Object> d = host.awaitDecision(seat, 100);
                    if (d.get("recent_chat") instanceof List<?> l) {
                        for (Object o : l) {
                            chat.add(seat + ": " + o);
                        }
                    }
                    if (Boolean.TRUE.equals(d.get("game_over"))) {
                        i = 6000;
                        break;
                    }
                    if (!Boolean.TRUE.equals(d.get("action_pending"))) {
                        continue;
                    }
                    Map<String, Object> args;
                    boolean attackers = "declare_attackers".equals(d.get("combat_phase"))
                            && ScriptedSeat.choices(d).stream().anyMatch(c -> "attacker".equals(c.get("choice_type")));
                    if ("You".equals(seat) && attackers) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> defenders = (List<Map<String, Object>>) d.get("defenders");
                        Assert.assertNotNull("the attackers window names the defenders: " + d, defenders);
                        List<String> names = new ArrayList<>();
                        for (Map<String, Object> def : defenders) {
                            names.add(String.valueOf(def.get("name")));
                            Assert.assertEquals("player", def.get("kind"));
                            Assert.assertNotNull(def.get("id"));
                        }
                        Assert.assertEquals(List.of("Cat", "P2", "P3"), names.stream().sorted().toList());
                        args = new HashMap<>();
                        if (ourAttacks == 0) {
                            args.put("attackers", "all>P3");
                        } else {
                            // Each attacker at P2, by the defender's short id.
                            String p2 = defenders.stream().filter(def -> "P2".equals(def.get("name"))).map(def -> String.valueOf(def.get("id"))).findFirst().orElseThrow();
                            List<String> entries = new ArrayList<>();
                            for (Map<String, Object> c : ScriptedSeat.choices(d)) {
                                if ("attacker".equals(c.get("choice_type"))) {
                                    entries.add(c.get("id") + ">" + p2);
                                }
                            }
                            args.put("attackers", String.join(",", entries));
                        }
                        ourAttacks++;
                    } else {
                        if ("GAME_TARGET".equals(d.get("action_type")) && String.valueOf(d.get("message")).contains("to attack")) {
                            // Another seat's bare "all": the engine's own defender question, theirs to answer.
                            sawDefenderQuestion = true;
                        }
                        args = scripts.get(seat).answer(d);
                    }
                    Map<String, Object> answer = host.chooseAction(seat, args);
                    Assert.assertTrue("answer rejected: " + answer + " for " + d, Boolean.TRUE.equals(answer.get("success")));
                }
            }
            // Let the second declaration reach the record.
            for (int i = 0; i < 20; i++) {
                for (String seat : seats) {
                    Map<String, Object> d = host.awaitDecision(seat, 100);
                    if (Boolean.TRUE.equals(d.get("action_pending"))) {
                        host.chooseAction(seat, "You".equals(seat) && "declare_attackers".equals(d.get("combat_phase")) ? Map.of("choice", "no") : scripts.get(seat).answer(d));
                    }
                }
            }
        } finally {
            host.end();
        }
        String log = Fmt.stripHtml(String.join("\n", host.logLines()));
        LOG.info("log:\n" + log + "\nchat: " + chat);
        Assert.assertEquals("two declarations by us", 2, ourAttacks);
        Assert.assertTrue("all>P3 attacked P3: " + log, log.contains("You attacks P3 with"));
        Assert.assertTrue("p>P2 attacked P2: " + log, log.contains("You attacks P2 with"));
        Assert.assertTrue("no declaration was interrupted: " + chat, chat.stream().noneMatch(c -> c.contains("interrupted")));
        Assert.assertTrue("a bare \"all\" from another seat got the engine's defender question", sawDefenderQuestion);
        for (String s : List.of("P2", "P3")) {
            Assert.assertTrue(s + " attacked someone: " + log, log.contains(s + " attacks "));
        }
    }
}
