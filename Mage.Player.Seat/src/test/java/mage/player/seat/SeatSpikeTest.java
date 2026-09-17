package mage.player.seat;

import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.cards.repository.CardScanner;
import mage.constants.MultiplayerAttackOption;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.events.PlayerQueryEvent;
import mage.game.events.TableEvent;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayer7;
import mage.player.human.HumanPlayer;
import mage.players.net.UserData;
import mage.view.GameView;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The rearch spike (fullpod docs/rearch.md §7 step 1): one JVM, no
 * mage.server, no client. A HumanPlayer seat is answered by an in-process
 * PlayerQueryEvent listener -- the role GameController plays over the
 * network -- against ComputerPlayer7, for three turns. What it must prove:
 * the engine runs without the server's remoting; our listener sees every
 * question with the objects behind it; the game thread blocks in
 * HumanPlayer and resumes on setResponse*; a GameView can be rendered from
 * the live state at any decision.
 */
public class SeatSpikeTest {

    private static final Logger LOG = Logger.getLogger(SeatSpikeTest.class);
    private static final int STOP_AT_TURN = 3;

    @BeforeClass
    public static void loadCards() {
        long t0 = System.currentTimeMillis();
        List<String> errors = new ArrayList<>();
        CardScanner.scan(errors);
        Assert.assertTrue(String.join("\n", errors), errors.isEmpty());
        LOG.info("cards scanned in " + (System.currentTimeMillis() - t0) + " ms");
    }

    @Test(timeout = 180_000)
    public void twoSeatGameToTurnThree() throws Exception {
        Game game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                MulliganType.GAME_DEFAULT.getMulligan(0), 60, 20, 7);
        game.setGameOptions(new GameOptions());
        // The AI simulates from the players' MatchPlayer (deck access); the server gets it from the table's match.
        Match match = new TwoPlayerMatch(new MatchOptions("spike", "Two Player Duel", false));

        HumanPlayer seat = new HumanPlayer("Seat", RangeOfInfluence.ONE, 0);
        seat.setUserData(UserData.getDefaultUserDataView());
        ComputerPlayer7 cpu = new ComputerPlayer7("CPU", RangeOfInfluence.ONE, 6);
        addPlayer(game, match, seat, "src/test/resources/decks/grizzly_bears.dck");
        addPlayer(game, match, cpu, "src/test/resources/decks/filler_opponent.dck");

        List<String> decisions = new ArrayList<>();
        List<String> log = new ArrayList<>();
        AtomicInteger maxTurn = new AtomicInteger();
        AtomicLong firstDecisionAt = new AtomicLong();
        AtomicLong started = new AtomicLong();
        ExecutorService answers = Executors.newSingleThreadExecutor();

        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.INFO && event.getMessage() != null) {
                log.add(event.getMessage());
            }
        });

        // This is GameController's listener, minus the network. It runs on the
        // game thread, which is about to park in HumanPlayer.waitForResponse;
        // the answer therefore has to come from another thread.
        game.addPlayerQueryEventListener((PlayerQueryEvent event) -> {
            if (!event.getPlayerId().equals(seat.getId())) {
                return;
            }
            firstDecisionAt.compareAndSet(0, System.currentTimeMillis());
            maxTurn.accumulateAndGet(game.getTurnNum(), Math::max);
            String rendered = render(game, seat.getId(), event);
            decisions.add(rendered);
            LOG.info("DECISION " + rendered);
            boolean stop = game.getTurnNum() >= STOP_AT_TURN;
            answers.submit(() -> {
                try {
                    if (stop) {
                        game.end();
                    }
                    answer(seat, event);
                } catch (Exception e) {
                    LOG.error("answer failed", e);
                }
            });
        });

        started.set(System.currentTimeMillis());
        Thread gameThread = new Thread(() -> game.start(seat.getId()), "GAME spike");
        gameThread.start();
        gameThread.join(170_000);
        answers.shutdown();
        answers.awaitTermination(5, TimeUnit.SECONDS);

        long ended = System.currentTimeMillis();
        LOG.info(String.format("first decision after %d ms; game over after %d ms; %d decisions; turn %d; %d log lines",
                firstDecisionAt.get() - started.get(), ended - started.get(), decisions.size(), maxTurn.get(), log.size()));
        for (String line : log) {
            LOG.info("LOG " + line);
        }
        Runtime rt = Runtime.getRuntime();
        LOG.info(String.format("heap used %d MB; process RSS %s", (rt.totalMemory() - rt.freeMemory()) >> 20, rss()));
        Assert.assertFalse("game thread still running", gameThread.isAlive());
        Assert.assertTrue("game did not end", game.hasEnded());
        Assert.assertTrue("no decisions reached the seat", decisions.size() > 3);
        Assert.assertTrue("stopped before turn " + STOP_AT_TURN + " (turn " + maxTurn.get() + ")", maxTurn.get() >= STOP_AT_TURN);
    }

    private static String rss() {
        try {
            for (String line : java.nio.file.Files.readAllLines(java.nio.file.Paths.get("/proc/self/status"))) {
                if (line.startsWith("VmRSS:")) {
                    return line.substring(6).trim();
                }
            }
        } catch (java.io.IOException ignored) {
        }
        return "?";
    }

    private static void addPlayer(Game game, Match match, mage.players.Player player, String deckFile) throws Exception {
        DeckCardLists list = DeckImporter.importDeckFromFile(deckFile, true);
        Deck deck = Deck.load(list, false, false, null);
        Assert.assertTrue("deck " + deckFile + " loaded " + deck.getMaindeckCards().size(), deck.getMaindeckCards().size() >= 40);
        game.loadCards(deck.getCards(), player.getId());
        game.loadCards(deck.getSideboard(), player.getId());
        game.addPlayer(player, deck);
        match.addPlayer(player, deck);
    }

    /** One line per question, from the event's own objects -- the information the bridge had to re-derive from option lists. */
    private static String render(Game game, UUID seatId, PlayerQueryEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("turn ").append(game.getTurnNum()).append(' ').append(game.getStep() == null ? "-" : game.getStep().getType())
                .append(' ').append(e.getQueryType()).append(" | ").append(e.getMessage());
        if (e.getOptions() != null && !e.getOptions().isEmpty()) {
            sb.append(" | options=").append(e.getOptions().keySet());
        }
        if (e.getTargets() != null) {
            sb.append(" | targets=").append(e.getTargets().size()).append(e.isRequired() ? " required" : "");
        }
        if (e.getAbilities() != null) {
            sb.append(" | abilities=").append(e.getAbilities().size());
        }
        if (e.getChoice() != null) {
            sb.append(" | choice=").append(e.getChoice().getChoices());
        }
        if (e.getQueryType() == PlayerQueryEvent.QueryType.SELECT) {
            GameView view = new GameView(game.getState(), game, seatId, null);
            sb.append(" | view: hand=").append(view.getMyHand().size()).append(" battlefield=")
                    .append(view.getPlayers().stream().mapToInt(p -> p.getBattlefield().size()).sum())
                    .append(" life=").append(view.getPlayers().get(0).getLife());
        }
        return sb.toString();
    }

    /** The hard-coded policy: keep, pass, decline what can be declined, first option where something is required. */
    private static void answer(HumanPlayer seat, PlayerQueryEvent e) {
        switch (e.getQueryType()) {
            case ASK:
            case SELECT:
            case PLAY_MANA:
            case PLAY_X_MANA:
                seat.setResponseBoolean(false);
                break;
            case PICK_TARGET:
                if (e.isRequired() && e.getTargets() != null && !e.getTargets().isEmpty()) {
                    seat.setResponseUUID(e.getTargets().iterator().next());
                } else {
                    seat.setResponseBoolean(false);
                }
                break;
            case PICK_ABILITY:
            case CHOOSE_ABILITY:
                if (e.getAbilities() != null && !e.getAbilities().isEmpty()) {
                    seat.setResponseUUID(e.getAbilities().get(0).getId());
                } else {
                    seat.setResponseBoolean(false);
                }
                break;
            case CHOOSE_MODE:
                seat.setResponseUUID(e.getModes().keySet().iterator().next());
                break;
            case CHOOSE_CHOICE:
                seat.setResponseString(e.getChoice().getChoices().iterator().next());
                break;
            case AMOUNT:
                seat.setResponseInteger(e.getMin());
                break;
            case CHOOSE_PILE:
                seat.setResponseBoolean(true);
                break;
            default:
                seat.setResponseBoolean(false);
        }
    }
}
