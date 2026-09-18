package mage.player.seat;

import mage.cards.Card;
import mage.cards.decks.Deck;
import mage.cards.decks.DeckCardLists;
import mage.cards.decks.importer.DeckImporter;
import mage.collectors.DataCollectorServices;
import mage.constants.ManaType;
import mage.constants.MultiplayerAttackOption;
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
import mage.player.ai.ComputerPlayer7;
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

    public record SeatSpec(String name, String kind, String deck, int skill) {
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
                         int freeMulligans, String startingPlayer) {
        public Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats, boolean offerManaSources) {
            this(gameId, format, seed, gameLogDir, seats, offerManaSources, null, 0);
        }

        public Config(String gameId, String format, Long seed, String gameLogDir, List<SeatSpec> seats,
                      boolean offerManaSources, String replayFrom, int holdThroughSeq) {
            this(gameId, format, seed, gameLogDir, seats, offerManaSources, replayFrom, holdThroughSeq, 0, "host");
        }
    }

    private enum Kind { SELECT_ATTACKERS, SELECT_BLOCKERS, PICK_TARGET, OTHER }

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

    /** Once per JVM: the data collectors, so decisions land in server_game_events.jsonl when a game has a log dir. */
    public static synchronized void initCollectors() {
        DataCollectorServices.init(false, false);
    }

    public GameHost(Config config) throws Exception {
        this.config = config;
        initCollectors();
        boolean commander = "commander".equals(config.format());
        Match match;
        // London mulligan (XMage's GAME_DEFAULT) with the table's free count.
        int free = Math.max(0, config.freeMulligans());
        if (commander) {
            game = new CommanderFreeForAll(MultiplayerAttackOption.MULTIPLE, RangeOfInfluence.ALL,
                    MulliganType.GAME_DEFAULT.getMulligan(free), 40, 7);
            match = new CommanderFreeForAllMatch(new MatchOptions(config.gameId(), "Commander Free For All", true));
        } else {
            game = new TwoPlayerDuel(MultiplayerAttackOption.LEFT, RangeOfInfluence.ONE,
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
        game.setGameOptions(options);
        views = new Views(game.getShortIdRegistry());
        renderer = new DecisionRenderer(views);
        RangeOfInfluence range = commander ? RangeOfInfluence.ALL : RangeOfInfluence.ONE;
        for (SeatSpec spec : config.seats()) {
            Player player;
            if ("cpu".equals(spec.kind())) {
                player = new ComputerPlayer7(spec.name(), range, spec.skill() > 0 ? spec.skill() : 6);
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
            game.loadCards(deck.getCards(), player.getId());
            game.loadCards(deck.getSideboard(), player.getId());
            game.addPlayer(player, deck);
            match.addPlayer(player, deck);
            players.add(player);
            // Short ids in a fixed order (seat order, then the decklist's order) so
            // a card is the same "p12" in the game and in its resumed replay,
            // whatever is rendered first.
            for (Card card : deck.getCards()) {
                game.getShortIdRegistry().getOrAssign(card.getId());
            }
            for (Card card : deck.getSideboard()) {
                game.getShortIdRegistry().getOrAssign(card.getId());
            }
        }
        game.addTableEventListener(event -> {
            if (event.getEventType() == TableEvent.EventType.INFO && event.getMessage() != null) {
                logLines.add(event.getMessage());
            }
        });
        game.addPlayerQueryEventListener(this::onQuery);
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
                game.start(chooser);
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
    }

    /** A pending batch (attackers=..., blockers=...) answers the engine's re-asks itself. */
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
                }
                case ATTACKER -> {
                    if (kind == Kind.SELECT_ATTACKERS) {
                        seat.batch.poll();
                        UUID id = step.id();
                        auto.submit(() -> respondUuid(seat, id));
                        return true;
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
            return Kind.PICK_TARGET;
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
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "uuid", id);
        seat.player.setResponseUUID(id);
    }

    private void respondBoolean(Seat seat, boolean value) {
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "boolean", value);
        seat.player.setResponseBoolean(value);
    }

    private void respondString(Seat seat, String value) {
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "string", value);
        seat.player.setResponseString(value);
    }

    private void respondInteger(Seat seat, int value) {
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "integer", value);
        seat.player.setResponseInteger(value);
    }

    private void respondManaType(Seat seat, ManaType type) {
        DataCollectorServices.getInstance().onPlayerResponse(game, seat.player.getId(), "manaType", type);
        seat.player.setResponseManaType(seat.player.getId(), type);
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
        Seat seat = seat(seatName);
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (seat.lock) {
            while (seat.pending() == null && !isOver()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                seat.lock.wait(Math.min(left, 100));
            }
        }
        Decision d = seat.pending();
        Map<String, Object> r = new LinkedHashMap<>();
        if (d != null) {
            r.putAll(d.result);
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
    }

    /** The board as the seat sees it right now, without a question. */
    public Map<String, Object> state(String seatName) {
        Seat seat = seat(seatName);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action_pending", seat.pending() != null);
        r.put("game_seq", game.getGameSeq());
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
     * amount, amounts, pile, text.
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
                    Map<String, Object> err = answerChoice(seat, d, String.valueOf(choice).trim());
                    if (err != null) {
                        return err;
                    }
                    taken = String.valueOf(choice);
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

    private Map<String, Object> answerChoice(Seat seat, Decision d, String choice) {
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
            if (choice.equals("all") && d.backing.contains("special")) {
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
            respondString(seat, String.valueOf(backing));
        }
        return null;
    }

    private String batchAttack(Seat seat, Decision d, List<Object> ids) {
        seat.batch.clear();
        if (ids.size() == 1 && "all".equals(String.valueOf(ids.get(0)))) {
            if (!d.backing.contains("special")) {
                throw new IllegalArgumentException("no 'all attack' option right now");
            }
            seat.batch.add(new Seat.Step(Seat.StepKind.CONFIRM, null));
            respondString(seat, "special");
            return "batch_attack";
        }
        List<UUID> attackers = new ArrayList<>();
        for (Object o : ids) {
            UUID id = views.resolve(String.valueOf(o));
            if (id == null || !d.backing.contains(id)) {
                throw new IllegalArgumentException("'" + o + "' can't attack right now");
            }
            attackers.add(id);
        }
        if (attackers.isEmpty()) {
            respondBoolean(seat, true);
            return "no_attack";
        }
        for (int i = 1; i < attackers.size(); i++) {
            seat.batch.add(new Seat.Step(Seat.StepKind.ATTACKER, attackers.get(i)));
        }
        seat.batch.add(new Seat.Step(Seat.StepKind.CONFIRM, null));
        respondUuid(seat, attackers.get(0));
        return "batch_attack";
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
        game.rollbackTurns(turns);
        seat.clearPending(stale);
        return true;
    }

    public void concede(String seatName) {
        Seat seat = seat(seatName);
        game.informPlayers(seat.player.getLogName() + " wants to concede");
        game.setConcedingPlayer(seat.player.getId());
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
        seat(seatName).player.setPassAfterOwnAction(!enabled);
    }

    /** Ends the game (the engine tells the players) and waits briefly for the game thread. */
    public void end() {
        if (!game.hasEnded()) {
            game.end();
        }
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
