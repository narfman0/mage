package mage.player.seat;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.collectors.DataCollectorServices;
import mage.collectors.services.ServerGameEventLogCollector;
import mage.constants.ManaType;
import mage.constants.MultiplayerAttackOption;
import mage.constants.PlayerAction;
import mage.constants.RangeOfInfluence;
import mage.game.CommanderFreeForAll;
import mage.game.CommanderFreeForAllMatch;
import mage.game.Game;
import mage.game.GameOptions;
import mage.game.TwoPlayerDuel;
import mage.game.TwoPlayerMatch;
import mage.game.events.PlayerQueryEvent;
import mage.game.events.TableEvent;
import mage.game.match.Match;
import mage.game.match.MatchOptions;
import mage.game.mulligan.MulliganType;
import mage.player.ai.ComputerPlayer;
import mage.players.PlayerImpl;
import mage.players.Player;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Hosts one game in this JVM: creates it the way the engine's own tests do
 * (no table, no room, no remoting), seats human-facing SeatPlayers and CPU
 * players, listens for the engine's questions to the seats and renders them
 * as decisions, and feeds answers back through the players' response
 * objects — GameController's job, minus the network.
 *
 * Threads: the game runs on its own thread and parks inside HumanPlayer
 * while a decision is open. Questions arrive on that thread (the listener);
 * answers must come from any other thread, which is why batch combat steps
 * are answered from an executor.
 */
public final class GameHost {

    private static final Logger LOG = Logger.getLogger(GameHost.class);

    /**
     * One seat: a person or pilot ({@code seat}) or the XMage CPU ({@code cpu}).
     * A CPU's {@code skill} sets its search depth (at least 4) and, unless
     * {@code maxThinkSecs} is given, its think cap (3 s a point, upstream's rule).
     */
    public record SeatSpec(String name, String kind, String deck, int skill, int maxThinkSecs) {
        public SeatSpec(String name, String kind, String deck, int skill) {
            this(name, kind, deck, skill, 0);
        }
    }

    /**
     * {@code replayFrom} names a recorded engine log whose decisions the
     * replay feeder answers first (resume, fullpod docs/save-resume.md); seats
     * see no question with a seq at or below {@code holdThroughSeq}.
     * {@code freeMulligans} is the London rule's free count (0 as written, 1
     * the common multiplayer table rule). {@code startingPlayer} is who goes
     * first: "host" (the first seat chooses, the default), "toss" (the engine's
     * coin toss picks who chooses), "roll" (a d20 roll-off, highest first) or
     * "random" (no prompt).
     */
    public record Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats,
                         boolean offerManaSources, String replayFrom, int holdThroughSeq,
                         int freeMulligans, String startingPlayer,
                         // Snapshot resume (Snapshot): load this file instead of building a
                         // game — every seat keeps its state, a `seat` spec over a CPU
                         // player takes it over — and, with `snapshot`, keep
                         // <gameLogDir>/snapshot.bin current at every top-level question.
                         String snapshotFrom, boolean snapshot) {
        public Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats, boolean offerManaSources) {
            this(gameId, format, seed, gameLogDir, seats, offerManaSources, null, 0);
        }

        public Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats,
                      boolean offerManaSources, String replayFrom, int holdThroughSeq) {
            this(gameId, format, seed, gameLogDir, seats, offerManaSources, replayFrom, holdThroughSeq, 0, "host");
        }

        public Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats,
                      boolean offerManaSources, String replayFrom, int holdThroughSeq,
                      int freeMulligans, String startingPlayer) {
            this(gameId, format, seed, gameLogDir, seats, offerManaSources, replayFrom, holdThroughSeq, freeMulligans, startingPlayer, null, false);
        }
    }

    private enum Kind { SELECT_ATTACKERS, SELECT_BLOCKERS, PICK_DEFENDER, PICK_TARGET, OTHER }

    private final Config config;
    private final Game game;
    private final Views views;
    private final DecisionRenderer renderer;
    private final Map<UUID, Seat> seatsById = new LinkedHashMap<>();
    private final Map<String, Seat> seatsByName = new LinkedHashMap<>();
    private final List<Player> players = new ArrayList<>();
    private final List<String> logLines = Collections.synchronizedList(new ArrayList<>());
    private final ExecutorService auto = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seat-auto-answer");
        t.setDaemon(true);
        return t;
    });
    private Thread gameThread;
    private volatile Throwable gameError;
    // Snapshot resume (Snapshot). The game thread is parked inside HumanPlayer
    // while a question is open; a snapshot is written then, under a lock every
    // answer (and rollback, take-back, concede, end) takes too, so nothing
    // wakes the game thread while the state is being written.
    private final Object gameLock = new Object();
    private final java.nio.file.Path snapshotPath;
    private final boolean resumed;
    private final List<String> swapped = new ArrayList<>();
    private final ExecutorService snapshots = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "seat-snapshot");
        t.setDaemon(true);
        return t;
    });
    private volatile int snapshotSeq = -1;
    private volatile long snapshotBytes;
    private volatile long snapshotMs;
    private volatile String snapshotError;
    // The snapshot write (Snapshot): only at a question still open
    // `snapshotDebounceMs` after it was asked, so a stop answered at once (an
    // auto-pass, a quick answer) never waits on a write; and any answer aborts
    // a write in progress (`answers` moves, the stream throws, the previous
    // file stays). `snapshotWritingSince` is when the current write started.
    private volatile long snapshotDebounceMs = 1_500;
    private final java.util.concurrent.atomic.AtomicLong answers = new java.util.concurrent.atomic.AtomicLong();
    private volatile long snapshotWritingSince;

    // ---- perf (fullpod docs/engine.md "Perf records") ----
    // What made the table wait, drained onto the next reply that has a seat:
    // each CPU think and window, each snapshot write, each answer that waited
    // on the lock. Bounded: a game nobody polls keeps its newest 256.
    private static final int PERF_RING = 256;
    private final java.util.ArrayDeque<Map<String, Object>> perf = new java.util.ArrayDeque<>();
    // The XMage CPUs by engine name (SeatCpu, or an older snapshot's ComputerPlayer7).
    private final Map<String, mage.player.ai.ComputerPlayer6> cpus = new LinkedHashMap<>();
    // The per-window ceiling on CPU thinking (SeatCpu.Hooks.capFor): a window
    // is the stretch between two questions to a seat; 0 = none.
    private volatile long cpuWindowMs = 8_000;
    private final java.util.concurrent.atomic.AtomicLong windowSpentMs = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger windowThinks = new java.util.concurrent.atomic.AtomicInteger();
    private volatile int windowTurn = -1;

    /** Once per JVM: the data collectors, so decisions land in server_game_events.jsonl when a game has a log dir. */
    public static synchronized void initCollectors() {
        DataCollectorServices.init(false, false);
    }

    public GameHost(Config config) throws Exception {
        this.config = config;
        initCollectors();
        boolean commander = "commander".equals(config.format());
        RangeOfInfluence range = commander ? RangeOfInfluence.ALL : RangeOfInfluence.ONE;
        resumed = config.snapshotFrom() != null;
        game = resumed ? adopt(Snapshot.read(java.nio.file.Path.of(config.snapshotFrom()))) : build(commander, range);
        views = new Views(game.getShortIdRegistry());
        renderer = new DecisionRenderer(views);
        snapshotPath = config.snapshot() && config.gameLogDir() != null
                ? java.nio.file.Path.of(config.gameLogDir(), Snapshot.FILE) : null;
        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.INFO && event.getMessage() != null) {
                logLines.add(event.getMessage());
            }
        });
        game.addPlayerQueryEventListener(this::onQuery);
    }

    /** A new game the way XMage's own tests make one: players and decks added, a match for the AI's simulations. */
    private Game build(boolean commander, RangeOfInfluence range) throws Exception {
        Game g;
        Match match;
        // London mulligan (XMage's GAME_DEFAULT) with the table's free count.
        int free = Math.max(0, config.freeMulligans());
        if (commander) {
            g = new CommanderFreeForAll(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL,
                    MulliganType.GAME_DEFAULT.getMulligan(free), 40, 7);
            match = new CommanderFreeForAllMatch(new MatchOptions(config.gameId(), "Commander Free For All", true));
        } else {
            g = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
                    MulliganType.GAME_DEFAULT.getMulligan(free), 60, 20, 7);
            match = new TwoPlayerMatch(new MatchOptions(config.gameId(), "Two Player Duel", false));
        }
        GameOptions options = new GameOptions();
        options.gameLogDir = config.gameLogDir();
        options.gameSeed = config.seed();
        options.replayFrom = config.replayFrom();
        options.startingPlayer = switch (String.valueOf(config.startingPlayer())) {
            case "roll" -> GameOptions.StartingPlayer.ROLL;
            case "random" -> GameOptions.StartingPlayer.RANDOM;
            default -> GameOptions.StartingPlayer.CHOOSE;
        };
        g.setGameOptions(options);
        for (SeatSpec spec : config.seats()) {
            Player player;
            if ("cpu".equals(spec.kind())) {
                SeatCpu cpu = new SeatCpu(spec.name(), range, spec.skill() > 0 ? spec.skill() : 6);
                if (spec.maxThinkSecs() > 0) {
                    cpu.setMaxThinkSecs(spec.maxThinkSecs());
                }
                cpu.setHooks(cpuHooks);
                cpus.put(spec.name(), cpu);
                player = cpu;
            } else {
                SeatPlayer seat = new SeatPlayer(spec.name(), range);
                seat.setAskWhenAmbiguous(config.offerManaSources());
                player = seat;
                Seat s = new Seat(spec.name(), seat, config.offerManaSources());
                seatsById.put(seat.getId(), s);
                seatsByName.put(spec.name(), s);
            }
            DeckCardLists list = DeckImporter.importDeckFromFile(spec.deck(), true);
            Deck deck = Deck.load(list, false, false, null);
            g.loadCards(deck.getCards(), player.getId());
            g.loadCards(deck.getSideboard(), player.getId());
            g.addPlayer(player, deck);
            match.addPlayer(player, deck);
            players.add(player);
            // Short ids in a fixed order (seat order, then the decklist's order) so
            // a card is the same "p12" in the game and in its resumed replay,
            // whatever is rendered first.
            for (Card card : deck.getCards()) {
                g.getShortIdRegistry().getOrAssign(card.getId());
            }
            for (Card card : deck.getSideboard()) {
                g.getShortIdRegistry().getOrAssign(card.getId());
            }
        }
        return g;
    }

    /**
     * A game read back from a snapshot: its players are the seats. Every
     * player in the snapshot needs a spec of the same name; a `seat` spec
     * over a CPU player takes it over (SeatPlayer(PlayerImpl) — same id,
     * same state), and a `cpu` spec over a seat is refused (a person's
     * questions can't be handed to the CPU mid-game).
     */
    private Game adopt(Game g) {
        g.getOptions().gameLogDir = config.gameLogDir();
        g.getOptions().replayFrom = null;
        Map<String, Player> byName = new LinkedHashMap<>();
        for (Player p : g.getPlayers().values()) {
            byName.put(p.getName(), p);
        }
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (SeatSpec spec : config.seats()) {
            Player p = byName.get(spec.name());
            if (p == null) {
                throw new IllegalArgumentException("the snapshot has no player named " + spec.name() + " (it has " + byName.keySet() + ")");
            }
            seen.add(spec.name());
            if ("cpu".equals(spec.kind())) {
                if (!(p instanceof ComputerPlayer)) {
                    throw new IllegalArgumentException(spec.name() + " was a seat in the snapshot; it can't become the CPU");
                }
                // The file's cap is the one it was written with; the spec's wins.
                if (p instanceof SeatCpu cpu) {
                    cpu.setHooks(cpuHooks);
                    if (spec.maxThinkSecs() > 0) {
                        cpu.setMaxThinkSecs(spec.maxThinkSecs());
                    }
                    cpus.put(spec.name(), cpu);
                } else if (p instanceof mage.player.ai.ComputerPlayer6 cp6) {
                    if (spec.maxThinkSecs() > 0) {
                        cp6.setMaxThinkTimeSecs(spec.maxThinkSecs());
                    }
                    cpus.put(spec.name(), cp6);
                }
                continue;
            }
            SeatPlayer seat;
            if (p instanceof SeatPlayer sp) {
                seat = sp;
            } else {
                seat = new SeatPlayer((PlayerImpl) p);
                g.getState().getPlayers().put(seat.getId(), seat);
                swapped.add(spec.name());
            }
            seat.setAskWhenAmbiguous(config.offerManaSources());
            Seat s = new Seat(spec.name(), seat, config.offerManaSources());
            seatsById.put(seat.getId(), s);
            seatsByName.put(spec.name(), s);
        }
        if (!seen.containsAll(byName.keySet())) {
            throw new IllegalArgumentException("every player in the snapshot needs a seat: " + byName.keySet() + ", given " + seen);
        }
        players.addAll(g.getPlayers().values());
        return g;
    }

    /** Whether this game was read back from a snapshot (start() resumes it). */
    public boolean resumed() {
        return resumed;
    }

    /** Seats that were the CPU's in the snapshot and are a seat's now. */
    public List<String> swapped() {
        return List.copyOf(swapped);
    }

    public Game game() {
        return game;
    }

    public List<String> logLines() {
        return logLines;
    }

    public List<String> seatNames() {
        return new ArrayList<>(seatsByName.keySet());
    }

    /**
     * Starts the game on its own thread. Who plays first is the config's
     * startingPlayer: "host" hands the choice to the first seat; "toss" lets
     * the engine's coin toss pick who chooses; "roll" and "random" decide it
     * in GameImpl.init without a prompt (GameOptions.StartingPlayer).
     */
    public synchronized void start() {
        if (gameThread != null) {
            return;
        }
        UUID chooser = "host".equals(String.valueOf(config.startingPlayer())) ? players.get(0).getId() : null;
        gameThread = new Thread(() -> {
            try {
                if (resumed) {
                    // What GameImpl.start would have done for the collectors: open
                    // this session's record (game_start names the players), then
                    // play on from the snapshot's turn, phase and step (Turn.resumePlay).
                    DataCollectorServices.getInstance().onGameStart(game);
                    game.resume();
                } else {
                    game.start(chooser);
                }
            } catch (Throwable t) {
                gameError = t;
                LOG.error("game thread died: " + config.gameId(), t);
            } finally {
                // A seeded game bound its own generator to this thread (GameImpl.init).
                mage.util.RandomUtil.unbindThread();
            }
        }, "GAME " + config.gameId());
        gameThread.setDaemon(true);
        gameThread.start();
    }

    public boolean isOver() {
        return game.hasEnded() || (gameThread != null && !gameThread.isAlive());
    }

    // ---- the listener (game thread) --------------------------------------

    private void onQuery(PlayerQueryEvent e) {
        Seat seat = seatsById.get(e.getPlayerId());
        if (seat == null) {
            return;
        }
        switch (e.getQueryType()) {
            case PERSONAL_MESSAGE -> {
                seat.say("[System] " + Fmt.stripHtml(e.getMessage()));
                return;
            }
            case TOURNAMENT_CONSTRUCT, DRAFT_PICK_CARD -> {
                return;
            }
            default -> {
            }
        }
        int seq = game.nextGameSeq();
        DataCollectorServices.getInstance().onPlayerQuery(game, e, seq);
        if (config.replayFrom() != null && seq <= config.holdThroughSeq()) {
            return; // the replay feeder answers this one
        }
        if (answerFromBatch(seat, e)) {
            return;
        }
        endWindow(seat.name); // a seat is asked: the CPUs' window is over
        try {
            seat.deliver(renderer.render(game, seat.player, e, seq, seat.offerManaSources));
        } catch (RuntimeException ex) {
            LOG.error("rendering " + e.getQueryType() + " for " + seat.name, ex);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("action_pending", true);
            r.put("game_seq", seq);
            r.put("action_type", e.getQueryType().name());
            r.put("response_type", "boolean");
            r.put("message", Fmt.stripHtml(e.getMessage()));
            r.put("error", "render failed: " + ex);
            seat.deliver(new Decision(seq, e, r, List.of()));
        }
        maybeSnapshot(e);
    }

    /**
     * A pending batch (attackers=..., blockers=...) answers the engine's
     * re-asks itself. With two or more opponents the engine asks which
     * player, planeswalker or battle each attacker attacks
     * (HumanPlayer.selectDefender, one PICK_TARGET per attacker; one for
     * "All attack"): a DEFENDER step answers it, a step the engine never
     * asks for (one defender, or a forced one) is skipped, and a defender
     * question with no step behind it is the person's — the batch waits,
     * since the engine comes back to the attackers window after it.
     */
    private boolean answerFromBatch(Seat seat, PlayerQueryEvent e) {
        Seat.Step step;
        while ((step = seat.batch.peek()) != null) {
            Kind kind = classify(e);
            switch (step.kind()) {
                case CONFIRM -> {
                    if (kind == Kind.SELECT_ATTACKERS || kind == Kind.SELECT_BLOCKERS) {
                        seat.batch.poll();
                        auto.submit(() -> respondBoolean(seat, true));
                        return true;
                    }
                    if (kind == Kind.PICK_DEFENDER) {
                        return false; // theirs to answer; the confirm follows
                    }
                }
                case ATTACKER -> {
                    if (kind == Kind.SELECT_ATTACKERS) {
                        seat.batch.poll();
                        UUID id = step.id();
                        auto.submit(() -> respondUuid(seat, id));
                        return true;
                    }
                    if (kind == Kind.PICK_DEFENDER) {
                        return false; // the previous attacker's defender, asked of the person
                    }
                }
                case DEFENDER -> {
                    if (kind == Kind.PICK_DEFENDER && e.getTargets() != null && e.getTargets().contains(step.id())) {
                        seat.batch.poll();
                        UUID id = step.id();
                        auto.submit(() -> respondUuid(seat, id));
                        return true;
                    }
                    if (kind == Kind.SELECT_ATTACKERS) {
                        // One legal defender: the engine assigned it without asking.
                        seat.batch.poll();
                        continue;
                    }
                    if (kind == Kind.PICK_DEFENDER) {
                        // The asked-for defender isn't legal for this attacker (forced
                        // elsewhere): drop the step, the question is the person's.
                        seat.batch.poll();
                        seat.say("[System] " + Fmt.stripHtml(e.getMessage()) + ": the chosen defender isn't legal for this attacker");
                        return false;
                    }
                }
                case BLOCKER -> {
                    if (kind == Kind.SELECT_BLOCKERS) {
                        seat.batch.poll();
                        UUID id = step.id();
                        auto.submit(() -> respondUuid(seat, id));
                        return true;
                    }
                }
                case BLOCK_TARGET -> {
                    if (kind == Kind.PICK_TARGET && e.getTargets() != null && e.getTargets().contains(step.id())) {
                        seat.batch.poll();
                        UUID id = step.id();
                        auto.submit(() -> respondUuid(seat, id));
                        return true;
                    }
                    if (kind == Kind.SELECT_BLOCKERS) {
                        // One attacker: the engine assigned the block without asking.
                        seat.batch.poll();
                        continue;
                    }
                }
                default -> {
                }
            }
            seat.batch.clear();
            seat.say("[System] Combat declaration interrupted: " + Fmt.stripHtml(e.getMessage()));
        }
        return false;
    }

    private static Kind classify(PlayerQueryEvent e) {
        if (e.getQueryType() == PlayerQueryEvent.QueryType.PICK_TARGET) {
            // TargetDefender's own name: "player, planeswalker, or battle to attack".
            String msg = e.getMessage() != null ? e.getMessage() : "";
            return msg.contains(" to attack") ? Kind.PICK_DEFENDER : Kind.PICK_TARGET;
        }
        if (e.getQueryType() == PlayerQueryEvent.QueryType.SELECT && e.getOptions() != null) {
            // HumanPlayer sends the key on every re-ask of the loop, even once every
            // creature is attacking and the list is empty — that re-ask is the confirm.
            if (e.getOptions().containsKey("possibleAttackers")) {
                return Kind.SELECT_ATTACKERS;
            }
            if (e.getOptions().containsKey("possibleBlockers")) {
                return Kind.SELECT_BLOCKERS;
            }
        }
        return Kind.OTHER;
    }

    // ---- responses (never on the game thread) -----------------------------

    private void respondUuid(Seat seat, UUID id) {
        answering(seat, () -> {
            DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "uuid", id);
            seat.player.setResponseUUID(id);
        });
    }

    private void respondBoolean(Seat seat, boolean value) {
        answering(seat, () -> {
            DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "boolean", value);
            seat.player.setResponseBoolean(value);
        });
    }

    private void respondString(Seat seat, String value) {
        answering(seat, () -> {
            DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "string", value);
            seat.player.setResponseString(value);
        });
    }

    private void respondInteger(Seat seat, int value) {
        answering(seat, () -> {
            DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "integer", value);
            seat.player.setResponseInteger(value);
        });
    }

    private void respondManaType(Seat seat, ManaType type) {
        answering(seat, () -> {
            DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "manaType", type);
            seat.player.setResponseManaType(seat.player.getId(), type);
        });
    }

    /**
     * Every answer goes through here: it aborts a snapshot write in progress
     * (the counter moves before the lock is asked for, the write's stream sees
     * it within one buffer), then takes the lock the write holds. An answer
     * that still waited over 100 ms for it is a perf record.
     */
    private void answering(Seat seat, Runnable send) {
        answers.incrementAndGet();
        boolean writing = snapshotWritingSince != 0;
        long t0 = System.currentTimeMillis();
        synchronized (gameLock) {
            long waited = System.currentTimeMillis() - t0;
            if (waited > 100) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("kind", "answer_blocked");
                r.put("seat", seat.name);
                r.put("ms", waited);
                r.put("by", writing ? "snapshot" : "lock");
                perf(r);
            }
            send.run();
        }
    }

    // ---- the host API ------------------------------------------------------

    private Seat seat(String name) {
        Seat seat = seatsByName.get(name);
        if (seat == null) {
            throw new IllegalArgumentException("no such seat: " + name);
        }
        return seat;
    }

    /** Blocks until the seat has a question, the game is over, or the timeout passes. */
    public Map<String, Object> awaitDecision(String seatName, long timeoutMs) throws InterruptedException {
        return awaitDecision(seatName, timeoutMs, 0, null);
    }

    /**
     * As above, and with {@code busyMs} > 0 it also comes back early, without a
     * question, when what the table is waiting on changes: a CPU think or a
     * snapshot write that has run {@code busyMs} or more ({@code busy}, see
     * {@link #busy()}) that isn't {@code busyKnown} (the caller's last
     * {@code busy.key}), or the end of the one it knew ({@code busy: null}).
     */
    public Map<String, Object> awaitDecision(String seatName, long timeoutMs, long busyMs, String busyKnown) throws InterruptedException {
        Seat seat = seat(seatName);
        long deadline = System.currentTimeMillis() + timeoutMs;
        String known = busyKnown == null ? "" : busyKnown;
        Map<String, Object> busyNow = null;
        boolean busyChanged = false;
        synchronized (seat.lock) {
            while (seat.pending() == null && !isOver()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                if (busyMs > 0) {
                    busyNow = busy();
                    boolean long_ = busyNow != null && ((Number) busyNow.get("since_ms")).longValue() >= busyMs;
                    String key = long_ ? String.valueOf(busyNow.get("key")) : "";
                    if (long_ ? !key.equals(known) : (!known.isEmpty() && busyNow == null)) {
                        busyChanged = true;
                        break;
                    }
                }
                seat.lock.wait(Math.min(left, 100));
            }
        }
        Decision d = seat.pending();
        Map<String, Object> r = new LinkedHashMap<>();
        if (d != null) {
            r.putAll(d.result);
        } else if (busyChanged) {
            r.put("action_pending", false);
            r.put("busy", busyNow);
        } else {
            r.put("action_pending", false);
            if (!isOver()) {
                r.put("timed_out", true);
                // Where the engine is while nobody is asked: the answer to "is it
                // slow or stuck", in the result and the log.
                List<String> stack = engineStack(12);
                r.put("engine_stack", stack);
                LOG.warn("no decision for " + seatName + " within " + timeoutMs + " ms; game thread at " + String.join(" <- ", stack));
            }
        }
        finish(seat, r);
        return r;
    }

    /** The top of the game thread's stack, for a stall report. */
    private List<String> engineStack(int depth) {
        List<String> out = new ArrayList<>();
        Thread t = gameThread;
        if (t == null) {
            return out;
        }
        out.add(t.getState().name());
        StackTraceElement[] frames = t.getStackTrace();
        for (int i = 0; i < Math.min(depth, frames.length); i++) {
            out.add(frames[i].toString());
        }
        return out;
    }

    /** Game-over flags and this seat's unread messages, on every result. */
    private void finish(Seat seat, Map<String, Object> r) {
        if (isOver()) {
            r.put("game_over", true);
            String winner = game.getWinner();
            if (winner != null) {
                r.put("winner", winner);
            }
        }
        if (seat.player.hasLost()) {
            r.put("player_dead", true);
        }
        List<String> chat = seat.drainChat();
        if (!chat.isEmpty()) {
            r.put("recent_chat", chat);
        }
        if (gameError != null) {
            r.put("error", "game thread died: " + gameError);
        }
        if (snapshotPath != null && snapshotError != null) {
            r.put("snapshot_error", snapshotError); // the product says so on the stream: a resume would be refused
        }
        List<Map<String, Object>> records = drainPerf();
        if (!records.isEmpty()) {
            r.put("perf", records);
        }
    }

    // ---- perf ----------------------------------------------------------------

    private final SeatCpu.Hooks cpuHooks = new SeatCpu.Hooks() {
        @Override
        public int capFor(int seatCapSecs) {
            long ceiling = cpuWindowMs;
            if (ceiling <= 0) {
                return seatCapSecs;
            }
            int turn = game == null ? -1 : game.getTurnNum();
            if (turn != windowTurn) {
                // A turn with no question to a seat in it (every person out) still ends a window.
                windowTurn = turn;
                endWindow(null);
            }
            long left = ceiling - windowSpentMs.get();
            return (int) Math.max(1, Math.min(seatCapSecs, left / 1000));
        }

        @Override
        public void thought(long ms) {
            windowSpentMs.addAndGet(ms);
            windowThinks.incrementAndGet();
        }

        @Override
        public void record(Map<String, Object> record) {
            perf(record);
        }
    };

    /**
     * The CPUs' window is over: a seat was asked (or the turn changed). One
     * record when anyone thought in it — how long the table waited on CPU
     * thinking between two questions to a seat, the number the window ceiling
     * bounds.
     */
    private void endWindow(String askedSeat) {
        long spent = windowSpentMs.getAndSet(0);
        int thinks = windowThinks.getAndSet(0);
        if (thinks > 0) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "table_window");
            r.put("seat", askedSeat);
            r.put("ms", spent);
            r.put("thinks", thinks);
            perf(r);
        }
    }

    private void perf(Map<String, Object> record) {
        synchronized (perf) {
            if (perf.size() >= PERF_RING) {
                perf.pollFirst();
            }
            perf.addLast(record);
        }
    }

    private List<Map<String, Object>> drainPerf() {
        synchronized (perf) {
            List<Map<String, Object>> out = new ArrayList<>(perf);
            perf.clear();
            return out;
        }
    }

    /**
     * What the table is waiting on right now, when it isn't a person: a CPU
     * thinking ({@code cpu_think}, which seat, for how long, its cap) or a
     * snapshot being written ({@code snapshot}); null when neither. {@code key}
     * names this one think or write.
     */
    public Map<String, Object> busy() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, mage.player.ai.ComputerPlayer6> e : cpus.entrySet()) {
            if (e.getValue() instanceof SeatCpu cpu) {
                long since = cpu.thinkingSince();
                if (since > 0) {
                    Map<String, Object> b = new LinkedHashMap<>();
                    b.put("seat", e.getKey());
                    b.put("kind", "cpu_think");
                    b.put("since_ms", now - since);
                    b.put("cap_s", cpu.getMaxThinkSecs());
                    b.put("key", "cpu_think:" + e.getKey() + ":" + since);
                    return b;
                }
            }
        }
        long writing = snapshotWritingSince;
        if (writing > 0) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("seat", null);
            b.put("kind", "snapshot");
            b.put("since_ms", now - writing);
            b.put("key", "snapshot:" + writing);
            return b;
        }
        return null;
    }

    /** A CPU seat's think cap (whole seconds, at least 1), from its next think on. */
    public void setCpuMaxThinkSecs(String cpuName, int secs) {
        mage.player.ai.ComputerPlayer6 cpu = cpus.get(cpuName);
        if (cpu == null) {
            throw new IllegalArgumentException("no such CPU seat: " + cpuName);
        }
        if (cpu instanceof SeatCpu seatCpu) {
            seatCpu.setMaxThinkSecs(secs);
        } else {
            cpu.setMaxThinkTimeSecs(Math.max(1, secs));
        }
    }

    public boolean isCpu(String name) {
        return cpus.containsKey(name);
    }

    /** The ceiling on CPU thinking per window, in ms; 0 turns it off. */
    public void setCpuWindowMs(long ms) {
        this.cpuWindowMs = Math.max(0, ms);
    }

    /** How long a question must stay open before the snapshot is written; 0 writes at once. */
    public void setSnapshotDebounceMs(long ms) {
        this.snapshotDebounceMs = Math.max(0, ms);
    }

    /** The board as the seat sees it right now, without a question. */
    public Map<String, Object> state(String seatName) {
        Seat seat = seat(seatName);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action_pending", seat.pending() != null);
        r.put("game_seq", game.getGameSeq());
        if (snapshotPath != null) {
            r.put("snapshot", snapshotStatus());
        }
        try {
            r.putAll(renderer.situation(game, seat.player, renderer.viewFor(game, seat.player)));
        } catch (RuntimeException ex) {
            r.put("error", "state unavailable: " + ex);
        }
        finish(seat, r);
        return r;
    }

    /**
     * Answers the seat's pending question. Arguments as mage-bench's
     * choose_action: choice (index, short id, yes/no), attackers, blockers,
     * amount, amounts, pile, text — plus {@code remember}, which answers and
     * tells the engine to answer this question itself from now on
     * ({@link #rememberAnswer}).
     */
    public Map<String, Object> chooseAction(String seatName, Map<String, Object> args) {
        Seat seat = seat(seatName);
        synchronized (seat) {
            Decision d = seat.pending();
            if (d == null) {
                return error("no_pending_action", "No action is pending for " + seatName, false);
            }
            String type = d.actionType();
            String taken;
            try {
                if (args.get("attackers") != null) {
                    if (!"declare_attackers".equals(d.combatPhase())) {
                        return error("invalid_choice", "attackers= only answers a declare-attackers window", true);
                    }
                    taken = batchAttack(seat, d, list(args.get("attackers")));
                } else if (args.get("blockers") != null) {
                    if (!"declare_blockers".equals(d.combatPhase())) {
                        return error("invalid_choice", "blockers= only answers a declare-blockers window", true);
                    }
                    taken = batchBlock(seat, d, list(args.get("blockers")));
                } else if (args.get("amount") != null) {
                    if (!"GAME_GET_AMOUNT".equals(type)) {
                        return error("invalid_choice", "amount= only answers an amount question", true);
                    }
                    int amount = toInt(args.get("amount"));
                    int min = toInt(d.result.get("min"));
                    int max = toInt(d.result.get("max"));
                    if (amount < min || amount > max) {
                        return error("invalid_choice", "amount must be between " + min + " and " + max, true);
                    }
                    respondInteger(seat, amount);
                    taken = "amount_" + amount;
                } else if (args.get("amounts") != null) {
                    if (!"GAME_GET_MULTI_AMOUNT".equals(type)) {
                        return error("invalid_choice", "amounts= only answers a multi-amount question", true);
                    }
                    List<String> parts = new ArrayList<>();
                    for (Object o : list(args.get("amounts"))) {
                        parts.add(String.valueOf(toInt(o)));
                    }
                    respondString(seat, String.join(" ", parts));
                    taken = "amounts";
                } else if (args.get("pile") != null) {
                    if (!"GAME_CHOOSE_PILE".equals(type)) {
                        return error("invalid_choice", "pile= only answers a pile question", true);
                    }
                    respondBoolean(seat, toInt(args.get("pile")) == 1);
                    taken = "pile_" + toInt(args.get("pile"));
                } else if (args.get("text") != null) {
                    if (!"GAME_CHOOSE_CHOICE".equals(type)) {
                        return error("invalid_choice", "text= only answers a choice question", true);
                    }
                    respondString(seat, String.valueOf(args.get("text")));
                    taken = "text";
                } else {
                    Object choice = args.get("choice");
                    if (choice == null) {
                        return error("missing_param", "choice, attackers, blockers, amount, amounts, pile or text is required", true);
                    }
                    String remember = args.get("remember") == null ? null : String.valueOf(args.get("remember")).trim();
                    Map<String, Object> err = remember == null ? null : rememberAnswer(seat, d, String.valueOf(choice).trim(), remember);
                    if (err != null) {
                        return err;
                    }
                    err = answerChoice(seat, d, String.valueOf(choice).trim(), remember != null);
                    if (err != null) {
                        return err;
                    }
                    taken = String.valueOf(choice) + (remember == null ? "" : "_remembered");
                }
            } catch (RuntimeException ex) {
                LOG.error("choose_action failed for " + seatName, ex);
                return error("internal_error", String.valueOf(ex), true);
            }
            seat.clearPending(d);
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            r.put("action_taken", taken);
            return r;
        }
    }

    /**
     * "Always answer this the same way" (docs/board-ui.md "Remembered
     * answers"): the answer goes back as usual and the engine is told to
     * answer this question itself from here on. The memory is the engine's
     * own — {@link mage.player.human.HumanPlayer}'s per-player maps — so the
     * seat stores nothing, and a resume replays a remembered answer as the
     * engine's own non-question the way an auto-ordered trigger already does.
     *
     * <ul>
     *   <li>a yes/no ask: {@code ability} keys on the asking ability and the
     *       question, {@code text} on the question alone (any card asking it);</li>
     *   <li>a trigger-order pick: {@code first} / {@code last} puts that
     *       ability first or last every time it triggers with others;</li>
     *   <li>the replacement-effect order: {@code answer} is the engine's own
     *       "Remember answer" special, sent as a {@code #}-prefixed key.</li>
     * </ul>
     *
     * Registered before the answer is sent: the game thread is parked on the
     * question until then, so the triggered abilities a trigger memory looks
     * itself up in are still the ones this question was asked about.
     */
    private Map<String, Object> rememberAnswer(Seat seat, Decision d, String choice, String remember) {
        if ("GAME_ASK".equals(d.actionType())) {
            boolean yes = choice.equalsIgnoreCase("yes") || choice.equalsIgnoreCase("true");
            if (!yes && !choice.equalsIgnoreCase("no") && !choice.equalsIgnoreCase("false")) {
                return error("invalid_choice", "remember= on a yes/no question needs choice=yes or choice=no", true);
            }
            if (!"ability".equals(remember) && !"text".equals(remember)) {
                return error("invalid_choice", "remember= on a yes/no question is 'ability' or 'text'", true);
            }
            Map<String, java.io.Serializable> options = d.event.getOptions();
            Object autoAnswer = options == null ? null : options.get("autoAnswerMessage");
            Object originalId = options == null ? null : options.get("originalId");
            if (autoAnswer == null) {
                return error("invalid_choice", "this question carries nothing to remember it by", true);
            }
            // Nothing asked it (a mulligan): the question itself is the only key.
            boolean byAbility = "ability".equals(remember) && originalId != null;
            String key = byAbility ? originalId + "#" + autoAnswer : String.valueOf(autoAnswer);
            PlayerAction action = byAbility
                    ? (yes ? PlayerAction.REQUEST_AUTO_ANSWER_ID_YES : PlayerAction.REQUEST_AUTO_ANSWER_ID_NO)
                    : (yes ? PlayerAction.REQUEST_AUTO_ANSWER_TEXT_YES : PlayerAction.REQUEST_AUTO_ANSWER_TEXT_NO);
            recordRemember(seat, remember);
            seat.player.sendPlayerAction(action, game, key);
            return null;
        }
        if (d.event.getQueryType() == PlayerQueryEvent.QueryType.PICK_ABILITY) {
            if (!"first".equals(remember) && !"last".equals(remember)) {
                return error("invalid_choice", "remember= on a trigger-order question is 'first' or 'last'", true);
            }
            UUID ability = triggerOf(d, choice);
            if (ability == null) {
                return error("invalid_choice", "'" + choice + "' is not one of the triggers", true);
            }
            recordRemember(seat, remember);
            seat.player.sendPlayerAction("first".equals(remember)
                    ? PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_FIRST
                    : PlayerAction.TRIGGER_AUTO_ORDER_ABILITY_LAST, game, ability);
            return null;
        }
        if ("GAME_CHOOSE_CHOICE".equals(d.actionType())) {
            if (!"answer".equals(remember)) {
                return error("invalid_choice", "remember= on a list question is 'answer'", true);
            }
            if (d.event.getChoice() == null || !d.event.getChoice().isSpecialEnabled()) {
                return error("invalid_choice", "this list question has no remembered answer", true);
            }
            return null; // answerChoice sends the #-prefixed key
        }
        return error("invalid_choice", "this question can't remember an answer", true);
    }

    /**
     * Tells the record that the answer about to be sent also remembers itself,
     * so a resume applies it at the same decision and the resumed game stops
     * being asked exactly where this one did (ReplayFeederCollector). The
     * scope alone is recorded: the key is built from the live question, whose
     * ability id is new every game.
     */
    private void recordRemember(Seat seat, String remember) {
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(),
                ServerGameEventLogCollector.REMEMBER, remember);
    }

    /** The trigger a trigger-order answer (index or short id) stands for; null when it isn't one. */
    private UUID triggerOf(Decision d, String choice) {
        Object backing = null;
        try {
            int index = (int) Double.parseDouble(choice);
            if (index >= 0 && index < d.backing.size()) {
                backing = d.backing.get(index);
            }
        } catch (NumberFormatException ignored) {
            UUID id = views.resolve(choice);
            backing = id != null && d.backing.contains(id) ? id : null;
        }
        return backing instanceof UUID id ? id : null;
    }

    private Map<String, Object> answerChoice(Seat seat, Decision d, String choice, boolean remember) {
        String type = d.actionType();
        if (choice.equalsIgnoreCase("yes") || choice.equalsIgnoreCase("no") || choice.equalsIgnoreCase("true") || choice.equalsIgnoreCase("false")) {
            boolean yes = choice.equalsIgnoreCase("yes") || choice.equalsIgnoreCase("true");
            switch (type) {
                case "GAME_CHOOSE_ABILITY" -> {
                    if (yes) {
                        return error("invalid_choice", "an ability picker takes an index (or no to cancel)", true);
                    }
                    respondUuid(seat, null); // cancel: back out of the activation
                }
                case "GAME_CHOOSE_CHOICE" -> {
                    if (yes) {
                        return error("invalid_choice", "a choice takes an index or text (or no to cancel)", true);
                    }
                    respondString(seat, null);
                }
                default -> respondBoolean(seat, yes);
            }
            return null;
        }
        Object backing = null;
        try {
            // JSON numbers arrive as "3" or "3.0" depending on the client's parser.
            double parsed = Double.parseDouble(choice);
            if (parsed != Math.rint(parsed)) {
                throw new NumberFormatException(choice);
            }
            int index = (int) parsed;
            if (index < 0 || index >= d.backing.size()) {
                return error("index_out_of_range", "Index " + index + " is out of range (valid: 0-" + (d.backing.size() - 1) + ")", true);
            }
            backing = d.backing.get(index);
        } catch (NumberFormatException ignored) {
            if ((choice.equals("all") || choice.equals("special")) && d.backing.contains("special")) {
                // "All attack" on a declare-attackers window; a special action
                // (convoke, delve; Channel) at a mana prompt or a priority window.
                backing = "special";
            } else {
                UUID id = views.resolve(choice);
                if (id != null && d.backing.contains(id)) {
                    backing = id;
                } else if (id != null && d.event.getTargets() != null && d.event.getTargets().contains(id)) {
                    backing = id;
                }
            }
        }
        if (backing == null) {
            return error("invalid_choice", "'" + choice + "' is not one of the current choices", true);
        }
        if (backing instanceof UUID id) {
            respondUuid(seat, id);
        } else if (backing instanceof ManaType manaType) {
            respondManaType(seat, manaType);
        } else {
            // A remembered list answer is the same key with the engine's own
            // "Remember answer" marker on it (HumanPlayer.chooseReplacementEffect).
            respondString(seat, (remember ? "#" : "") + backing);
        }
        return null;
    }

    /**
     * {@code attackers=p1>P2,p3>P3}: each entry an attacker's short id, with
     * {@code >} and the defender's (a player, planeswalker or battle short id)
     * when there is a choice; {@code all>P2} sends everything at one
     * defender. A bare {@code p1} leaves the defender to the engine — assigned
     * when there is one, asked of the person otherwise.
     */
    private String batchAttack(Seat seat, Decision d, List<Object> ids) {
        seat.batch.clear();
        if (ids.size() == 1 && String.valueOf(ids.get(0)).startsWith("all")) {
            if (!d.backing.contains("special")) {
                throw new IllegalArgumentException("no 'all attack' option right now");
            }
            UUID defender = defenderOf(String.valueOf(ids.get(0)), d);
            if (defender != null) {
                seat.batch.add(new Seat.Step(Seat.StepKind.DEFENDER, defender));
            }
            seat.batch.add(new Seat.Step(Seat.StepKind.CONFIRM, null));
            respondString(seat, "special");
            return "batch_attack";
        }
        List<UUID> attackers = new ArrayList<>();
        List<UUID> defenders = new ArrayList<>();
        for (Object o : ids) {
            String entry = String.valueOf(o);
            String attacker = entry.contains(">") ? entry.substring(0, entry.indexOf('>')).trim() : entry.trim();
            UUID id = views.resolve(attacker);
            if (id == null || !d.backing.contains(id)) {
                throw new IllegalArgumentException("'" + attacker + "' can't attack right now");
            }
            attackers.add(id);
            defenders.add(defenderOf(entry, d));
        }
        if (attackers.isEmpty()) {
            respondBoolean(seat, true);
            return "no_attack";
        }
        for (int i = 0; i < attackers.size(); i++) {
            if (i > 0) {
                seat.batch.add(new Seat.Step(Seat.StepKind.ATTACKER, attackers.get(i)));
            }
            if (defenders.get(i) != null) {
                seat.batch.add(new Seat.Step(Seat.StepKind.DEFENDER, defenders.get(i)));
            }
        }
        seat.batch.add(new Seat.Step(Seat.StepKind.CONFIRM, null));
        respondUuid(seat, attackers.get(0));
        return "batch_attack";
    }

    /** The defender named after {@code >} in an attackers entry, checked against the window's defenders; null when none is named. */
    @SuppressWarnings("unchecked")
    private UUID defenderOf(String entry, Decision d) {
        int gt = entry.indexOf('>');
        if (gt < 0) {
            return null;
        }
        String name = entry.substring(gt + 1).trim();
        UUID byId = views.resolve(name);
        UUID found = null;
        if (d.result.get("defenders") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && (name.equals(m.get("name")) || (byId != null && views.shortId(byId).equals(m.get("id"))))) {
                    found = views.resolve(String.valueOf(m.get("id")));
                }
            }
        }
        if (found == null) {
            throw new IllegalArgumentException("'" + name + "' can't be attacked right now");
        }
        return found;
    }

    private String batchBlock(Seat seat, Decision d, List<Object> pairs) {
        seat.batch.clear();
        List<Seat.Step> steps = new ArrayList<>();
        for (Object o : pairs) {
            String pair = String.valueOf(o);
            int colon = pair.indexOf(':');
            if (colon <= 0) {
                throw new IllegalArgumentException("blockers entries are blocker:attacker, got '" + pair + "'");
            }
            UUID blocker = views.resolve(pair.substring(0, colon).trim());
            UUID attacker = views.resolve(pair.substring(colon + 1).trim());
            if (blocker == null || !d.backing.contains(blocker)) {
                throw new IllegalArgumentException("'" + pair.substring(0, colon) + "' can't block right now");
            }
            if (attacker == null) {
                throw new IllegalArgumentException("'" + pair.substring(colon + 1) + "' is not an attacker");
            }
            steps.add(new Seat.Step(Seat.StepKind.BLOCKER, blocker));
            steps.add(new Seat.Step(Seat.StepKind.BLOCK_TARGET, attacker));
        }
        if (steps.isEmpty()) {
            respondBoolean(seat, true);
            return "no_block";
        }
        // The first blocker goes now; the engine asks which attacker next.
        seat.batch.addAll(steps.subList(1, steps.size()));
        seat.batch.add(new Seat.Step(Seat.StepKind.CONFIRM, null));
        respondUuid(seat, steps.get(0).id());
        return "batch_block";
    }

    /**
     * Undo. The question the seat holds dies with the turn: rollbackTurns aborts
     * the player's wait and the game thread asks afresh from the restarted turn.
     * Forget that question here, or awaitDecision hands it out again and the
     * person is asked something the engine no longer wants answered (seen as
     * "undo takes two clicks"). Only that one is forgotten — the fresh question
     * may already have landed by the time the rollback returns (clearPending
     * compares) — and with it any combat batch it was mid-way through.
     */
    public boolean rollback(String seatName, int turns) {
        Seat seat = seat(seatName);
        if (!game.canRollbackTurns(turns)) {
            return false;
        }
        Decision stale = seat.pending();
        seat.batch.clear();
        answers.incrementAndGet(); // aborts a snapshot write in progress
        synchronized (gameLock) {
            game.rollbackTurns(turns);
        }
        seat.clearPending(stale);
        return true;
    }

    /**
     * Take back the mana the seat tapped at this priority window: XMage's
     * own UNDO. PlayerImpl bookmarks the state before a mana ability whose
     * undo is possible and {@code GameImpl.undo} restores it while the
     * bookmark stands — until the player passes priority, plays a land, or
     * completes a cast or activation (each resets it: in this XMage a spell
     * on the stack is not undone, only backed out of mid-payment, which is
     * the mana prompt's Cancel). So this is "untap what I tapped": the
     * misclicked land, the mana floated for a spell then thought better
     * of. The question the seat holds is the same priority window; it is
     * rendered again over the restored state.
     */
    public Map<String, Object> takeBack(String seatName) {
        Seat seat = seat(seatName);
        synchronized (seat) {
            Decision d = seat.pending();
            if (d == null || !"GAME_SELECT".equals(d.actionType()) || d.combatPhase() != null) {
                return error("no_take_back", "Nothing to take back: not at a priority window", false);
            }
            if (seat.player.getStoredBookmark() == -1 || !seat.player.getId().equals(game.getPriorityPlayerId())) {
                return error("no_take_back", "Nothing to take back", false);
            }
            answers.incrementAndGet();
            synchronized (gameLock) {
                game.undo(seat.player.getId());
                game.informPlayers(seat.player.getLogName() + " takes back the mana they tapped");
                seat.deliver(renderer.render(game, seat.player, d.event, d.seq, seat.offerManaSources));
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("success", true);
            return r;
        }
    }

    public void concede(String seatName) {
        Seat seat = seat(seatName);
        game.informPlayers(seat.player.getLogName() + " wants to concede");
        answers.incrementAndGet();
        synchronized (gameLock) {
            game.setConcedingPlayer(seat.player.getId());
        }
    }

    /**
     * A person sits here (the product's one switch for it): mana sources are
     * offered at priority so they can tap their own, and a payment the clean
     * sources could make in more than one way is asked, not chosen
     * ({@link AutoPay}). Off for pilots, which never see a mana prompt.
     */
    public void setOfferManaSources(String seatName, boolean enabled) {
        Seat seat = seat(seatName);
        seat.offerManaSources = enabled;
        seat.player.setAskWhenAmbiguous(enabled);
    }

    public void setAutoPay(String seatName, boolean enabled) {
        seat(seatName).player.setAutoPay(enabled);
    }

    /**
     * Full control: the seat is asked at every priority window, including
     * the one right after its own cast ({@link SeatPlayer#setPassAfterOwnAction}).
     * Off (the default), that window is passed by the engine itself.
     */
    public void setFullControl(String seatName, boolean enabled) {
        seat(seatName).player.setFullControl(enabled);
    }

    /**
     * Forgets every answer this seat told the engine to keep giving —
     * remembered yes/no answers, trigger order, the replacement-effect
     * choice — so every question is asked again (docs/board-ui.md
     * "Remembered answers"). XMage's own three reset actions.
     */
    public void forgetAnswers(String seatName) {
        Seat seat = seat(seatName);
        seat.player.sendPlayerAction(PlayerAction.REQUEST_AUTO_ANSWER_RESET_ALL, game, null);
        seat.player.sendPlayerAction(PlayerAction.TRIGGER_AUTO_ORDER_RESET_ALL, game, null);
        seat.player.sendPlayerAction(PlayerAction.RESET_AUTO_SELECT_REPLACEMENT_EFFECTS, game, null);
    }

    /** Ends the game (the engine tells the players) and waits briefly for the game thread. */
    public void end() {
        answers.incrementAndGet();
        synchronized (gameLock) {
            if (!game.hasEnded()) {
                game.end();
            }
        }
        snapshots.shutdown();
        try {
            if (gameThread != null) {
                gameThread.join(5_000);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        auto.shutdown();
        try {
            auto.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- snapshots ---------------------------------------------------------

    /**
     * Snapshot policy: after a top-level question — priority, or a combat
     * declaration — is delivered, write the game. Those are the questions
     * the turn loop asks again when the step is re-entered
     * (Phase.resumeStep); a question nested in a cast, a payment or a
     * resolution is not, so a snapshot there would resume a half-done
     * action. Before the first turn (mulligans) nothing is written either.
     */
    private void maybeSnapshot(PlayerQueryEvent e) {
        if (snapshotPath == null || e.getQueryType() != PlayerQueryEvent.QueryType.SELECT || game.executingRollback()) {
            return;
        }
        try {
            if (game.getTurnStepType() == null) {
                return;
            }
        } catch (RuntimeException ex) {
            return;
        }
        snapshots.submit(() -> writeSnapshot());
    }

    /**
     * Write the snapshot once the game thread is parked on an open question
     * (nobody can answer while the lock is held, so it stays parked). The
     * question was delivered on the game thread, which is then on its way
     * into HumanPlayer's wait: give it a moment. If no question is open by
     * then, skip — the next one asks again.
     *
     * Only a question still open {@code snapshotDebounceMs} after it was asked
     * is written (the wait is outside the lock): a stop answered at once — the
     * product's auto-passes, a quick answer — never pays for a write, and the
     * file is at most a few stops behind, which a resume accepts. An answer
     * during the write aborts it ({@link #answering}): the write stops within
     * a buffer, the previous file stays, and the answer goes through.
     */
    private void writeSnapshot() {
        writeSnapshot(snapshotDebounceMs);
    }

    private void writeSnapshot(long debounceMs) {
        Thread t = gameThread;
        if (t == null) {
            return;
        }
        long asked = System.currentTimeMillis();
        long until = asked + 2_000;
        while (!(parked(t) && anyPending())) {
            if (game.hasEnded() || System.currentTimeMillis() > until) {
                return;
            }
            if (!sleep(5)) {
                return;
            }
        }
        long parkWait = System.currentTimeMillis() - asked;
        int seq = game.getGameSeq();
        long answered = answers.get();
        long waitUntil = asked + debounceMs;
        while (System.currentTimeMillis() < waitUntil) {
            if (answers.get() != answered || game.getGameSeq() != seq || game.hasEnded()) {
                return; // answered at once: the next question writes, if it stays open
            }
            if (!sleep(Math.min(50, Math.max(1, waitUntil - System.currentTimeMillis())))) {
                return;
            }
        }
        synchronized (gameLock) {
            if (answers.get() != answered || game.getGameSeq() != seq || !(parked(t) && anyPending())) {
                return;
            }
            long t0 = System.currentTimeMillis();
            snapshotWritingSince = t0;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("kind", "snapshot");
            r.put("seq", seq);
            try {
                long bytes = Snapshot.write(game, snapshotPath, () -> answers.get() != answered);
                snapshotBytes = bytes;
                snapshotMs = System.currentTimeMillis() - t0;
                snapshotSeq = seq;
                snapshotError = null;
                r.put("bytes", bytes);
            } catch (Snapshot.Aborted ex) {
                r.put("aborted", true);
            } catch (Exception ex) {
                snapshotError = String.valueOf(ex);
                r.put("error", snapshotError);
                LOG.warn("snapshot of " + config.gameId() + " failed", ex);
            } finally {
                snapshotWritingSince = 0;
            }
            r.put("ms", System.currentTimeMillis() - t0);
            r.put("park_wait_ms", parkWait);
            perf(r);
        }
    }

    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean parked(Thread t) {
        Thread.State s = t.getState();
        return s == Thread.State.WAITING || s == Thread.State.TIMED_WAITING;
    }

    private boolean anyPending() {
        for (Seat s : seatsById.values()) {
            if (s.pending() != null) {
                return true;
            }
        }
        return false;
    }

    /** Write a snapshot now, if a question is open; the status either way. */
    public Map<String, Object> snapshotNow() {
        if (snapshotPath == null) {
            return error("no_snapshot", "This game keeps no snapshot", false);
        }
        writeSnapshot(0);
        return snapshotStatus();
    }

    /** The last snapshot: its game seq, size and cost, or the error that stopped it. */
    public Map<String, Object> snapshotStatus() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("path", snapshotPath == null ? null : snapshotPath.toString());
        r.put("seq", snapshotSeq < 0 ? null : snapshotSeq);
        r.put("bytes", snapshotSeq < 0 ? null : snapshotBytes);
        r.put("ms", snapshotSeq < 0 ? null : snapshotMs);
        r.put("error", snapshotError);
        return r;
    }

    // ---- small helpers -----------------------------------------------------

    private static Map<String, Object> error(String code, String message, boolean retryable) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("success", false);
        r.put("error", message);
        r.put("error_code", code);
        r.put("retryable", retryable);
        return r;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (value instanceof List<?> l) {
            return (List<Object>) l;
        }
        List<Object> out = new ArrayList<>();
        for (String part : String.valueOf(value).split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    private static int toInt(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        return Integer.parseInt(String.valueOf(value).trim());
    }
}
