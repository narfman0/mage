package mage.player.seat;

import mage.abilities.Ability;
import mage.abilities.TriggeredAbility;
import mage.abilities.common.PassAbility;
import mage.constants.PhaseStep;
import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.game.stack.StackAbility;
import mage.game.stack.StackObject;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.SimulationNode2;
import mage.target.Target;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The seat host's XMage CPU: {@link ComputerPlayer7} with a think cap the
 * host sets (and can change mid-game), and hooks that time every think, so a
 * slow game says why (fullpod docs/engine.md "Perf records").
 *
 * A think is one {@code addActionsTimed} — the simulation the CPU runs at a
 * main phase or a combat step, bounded by {@code maxThinkTimeSecs}. Its cap
 * is the seat's own, lowered by the game's per-window ceiling
 * ({@link Hooks#capFor}): once the CPUs have spent most of a window's budget
 * thinking, the ones still to act get what is left, at least 1 s. A window
 * is the stretch between two answers a person gave.
 *
 * <b>The memo</b> (the ask-once rule, report a374dd040c): a run of identical
 * triggers — 29 Scute Swarm landfalls — is the same question 29 times, and
 * ComputerPlayer7 simulates each from scratch until the clock stops it. So
 * after a think that ended in a pass with an <i>opponent's</i> triggered
 * ability on top of the stack, the CPU remembers the question ({@link Memo}:
 * turn, step, active player, the trigger's controller, rule text and targets,
 * and its own position — hand, permanents, untapped ones, life, playable
 * count); the next priority with the same key is passed without a
 * simulation and recorded as a {@code cpu_think} with {@code ms: 0, memo:
 * true}. Anything of its own changing, a different trigger or a spell on
 * top misses the memo and thinks as before. Its own triggers never memo.
 *
 * <b>The loop guard</b> (report 078b7f4b49): a think that chooses the same
 * thing at the same board for the fourth time in a step is a loop the search
 * does not see (Seeker of Skybreak untapping itself, 2,340 times over 18
 * minutes — the search itself no longer offers an action that changes
 * nothing, ComputerPlayer6.isNoOp; this is the backstop for the cycles it
 * can't see). The act becomes a pass, recorded as {@code cpu_loop_break}.
 *
 * Only this module changes: upstream's CPU is extended, never edited.
 */
public class SeatCpu extends ComputerPlayer7 {

    /** What the host gives the CPU: where records go, and the window's budget. */
    interface Hooks {
        /** This think's cap in seconds, given the seat's own. */
        int capFor(int seatCapSecs);

        /** A think or a window finished; {@code ms} spent thinking counts against the window. */
        void thought(long ms);

        void record(Map<String, Object> record);
    }

    /** The steps ComputerPlayer7 simulates at; everywhere else it passes without a think. */
    private static final Set<PhaseStep> THINK_STEPS = EnumSet.of(
            PhaseStep.PRECOMBAT_MAIN, PhaseStep.DECLARE_ATTACKERS, PhaseStep.DECLARE_BLOCKERS, PhaseStep.POSTCOMBAT_MAIN);

    /** The question a think answered with a pass: an opponent's trigger on top, and the CPU's own position. */
    record Memo(int turn, PhaseStep step, UUID active, UUID controller, String rule, List<UUID> targets,
                int hand, int permanents, int untapped, int life, int playable) {
    }

    /** Identical acts at one board in a step before the next is a pass. */
    static final int LOOP_BREAK = 3;

    /** The acts of the current turn and step, by what was done at which board. */
    static final class LoopGuard implements Serializable {
        private int turn = -1;
        private PhaseStep step;
        private final Map<String, Integer> seen = new HashMap<>();

        /** True when {@code key} has been acted on LOOP_BREAK times already this turn and step. */
        boolean repeated(int turnNum, PhaseStep stepNow, String key) {
            if (turnNum != turn || stepNow != step) {
                turn = turnNum;
                step = stepNow;
                seen.clear();
            }
            return seen.merge(key, 1, Integer::sum) > LOOP_BREAK;
        }
    }

    private transient Hooks hooks;
    // While a think runs: when it started (epoch ms), else 0 — `busy` reads it.
    private transient volatile long thinkingSince;
    private transient Game current;
    private transient int windowThinks;
    // The last question this CPU passed on (an opponent's trigger on top); null after it acted.
    private transient Memo memo;
    // Set by act(): the think chose something to do, so its question is not memoed.
    private transient boolean acted;
    // The seat's cap, before the window ceiling lowers it for one think.
    private int capSecs;
    // A copy or a restored snapshot starts a fresh one (null until first used).
    private LoopGuard loopGuard;

    public SeatCpu(String name, RangeOfInfluence range, int skill) {
        super(name, range, skill);
        this.capSecs = maxThinkTimeSecs;
    }

    /**
     * ComputerPlayer6's copy constructor drops the cap and the node limit (a
     * copy gets a 0 s cap); this one carries them. Only the live player
     * thinks, but a copy should be the same player.
     */
    public SeatCpu(final SeatCpu player) {
        super(player);
        this.maxThinkTimeSecs = player.maxThinkTimeSecs;
        this.maxNodes = player.maxNodes;
        this.capSecs = player.capSecs;
    }

    @Override
    public SeatCpu copy() {
        return new SeatCpu(this);
    }

    void setHooks(Hooks hooks) {
        this.hooks = hooks;
    }

    /** The seat's think cap, whole seconds (the engine waits in seconds); at least 1. Takes effect at the next think. */
    public void setMaxThinkSecs(int secs) {
        this.capSecs = Math.max(1, secs);
        this.maxThinkTimeSecs = this.capSecs;
    }

    public int getMaxThinkSecs() {
        return capSecs;
    }

    /** How many actions deep a think looks (ComputerPlayer6's maxDepth, which has no setter upstream). */
    public void setMaxDepth(int depth) {
        this.maxDepth = Math.max(1, depth);
    }

    public int getMaxDepth() {
        return maxDepth;
    }

    /** When the current think started (epoch ms), or 0 when it isn't thinking. */
    long thinkingSince() {
        return thinkingSince;
    }

    /** The whole priority window of this CPU: its think (if the step has one) and its action. */
    @Override
    public boolean priority(Game game) {
        long t0 = System.currentTimeMillis();
        current = game;
        windowThinks = 0;
        acted = false;
        try {
            Memo key = memoKey(game);
            if (key != null && key.equals(memo)) {
                // The same question this CPU just thought about and passed on:
                // pass again, no simulation (what priorityPlay does around its pass).
                game.getState().setPriorityPlayerId(playerId);
                game.firePriorityEvent(playerId);
                pass(game);
                windowThinks++;
                if (hooks != null) {
                    hooks.thought(0);
                    hooks.record(thinkRecord(0, capSecs, true));
                }
                return false;
            }
            boolean result = super.priority(game);
            // Remember the question when the CPU passed on it — after a think,
            // or from the plan a think left (ComputerPlayer6's chain, which
            // already skips the simulation when the state is the one it foresaw).
            memo = key != null && !acted ? key : null;
            return result;
        } finally {
            current = null;
            long ms = System.currentTimeMillis() - t0;
            if (hooks != null && windowThinks > 0) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("kind", "cpu_window");
                r.put("seat", getName());
                r.put("ms", ms);
                r.put("thinks", windowThinks);
                hooks.record(r);
            }
        }
    }

    @Override
    protected void act(Game game) {
        // The simulation's best line can be a PassAbility: that is a pass, not an action.
        acted = actions != null && actions.stream().anyMatch(a -> !(a instanceof PassAbility));
        if (acted) {
            if (loopGuard == null) {
                loopGuard = new LoopGuard();
            }
            String key = actKey(game);
            if (loopGuard.repeated(game.getTurnNum(), game.getTurnStepType(), key)) {
                // The same thing at the same board, again: pass instead, and say so.
                if (hooks != null) {
                    hooks.record(loopRecord(game));
                }
                actions.clear();
                acted = false;
            }
        }
        super.act(game);
    }

    /** What this act does, with its targets, at which board. */
    private String actKey(Game game) {
        StringBuilder sb = new StringBuilder();
        for (Ability a : actions) {
            sb.append(a.toString());
            for (Target t : a.getTargets()) {
                sb.append(t.getTargets());
            }
            sb.append(';');
        }
        return sb.append('@').append(game.getState().getValue(true, game).hashCode()).toString();
    }

    private Map<String, Object> loopRecord(Game game) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "cpu_loop_break");
        r.put("seat", getName());
        r.put("turn", game.getTurnNum());
        r.put("step", game.getTurnStepType() == null ? null : game.getTurnStepType().name());
        r.put("action", actions.isEmpty() ? null : actions.getFirst().toString());
        r.put("repeats", LOOP_BREAK);
        r.put("at", System.currentTimeMillis());
        return r;
    }

    /**
     * The memo key for this priority, or null when the question can't be
     * memoed: not a think step, an empty stack, a spell or one of the CPU's
     * own abilities on top.
     */
    private Memo memoKey(Game game) {
        try {
            PhaseStep step = game.getTurnStepType();
            if (step == null || !THINK_STEPS.contains(step) || game.getStack().isEmpty()) {
                return null;
            }
            StackObject top = game.getStack().getFirst();
            if (!(top instanceof StackAbility) || !(((StackAbility) top).getStackAbility() instanceof TriggeredAbility)
                    || playerId.equals(top.getControllerId())) {
                return null;
            }
            Ability trigger = ((StackAbility) top).getStackAbility();
            List<UUID> targets = new ArrayList<>();
            for (Target t : trigger.getTargets()) {
                targets.addAll(t.getTargets());
            }
            int permanents = 0;
            int untapped = 0;
            for (Permanent p : game.getBattlefield().getAllActivePermanents(playerId)) {
                permanents++;
                if (!p.isTapped()) {
                    untapped++;
                }
            }
            return new Memo(game.getTurnNum(), step, game.getActivePlayerId(), top.getControllerId(), trigger.getRule(), targets,
                    getHand().size(), permanents, untapped, getLife(), getPlayable(game, true).size());
        } catch (RuntimeException ex) {
            // No key, no memo: the think happens as it always has.
            return null;
        }
    }

    @Override
    protected Integer addActionsTimed() {
        int cap = hooks == null ? capSecs : Math.max(1, Math.min(capSecs, hooks.capFor(capSecs)));
        maxThinkTimeSecs = cap;
        long start = System.currentTimeMillis();
        thinkingSince = start;
        try {
            return super.addActionsTimed();
        } finally {
            thinkingSince = 0;
            long ms = System.currentTimeMillis() - start;
            maxThinkTimeSecs = capSecs;
            windowThinks++;
            if (hooks != null) {
                hooks.thought(ms);
                hooks.record(thinkRecord(ms, cap, false));
            }
        }
    }

    private Map<String, Object> thinkRecord(long ms, int cap, boolean memoed) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("kind", "cpu_think");
        r.put("seat", getName());
        Game g = current;
        if (g != null) {
            try {
                r.put("turn", g.getTurnNum());
                r.put("step", g.getTurnStepType() == null ? null : g.getTurnStepType().name());
                r.put("permanents", g.getBattlefield().getAllPermanents().size());
                r.put("stack", g.getStack().size());
            } catch (RuntimeException ignored) {
                // a record is best effort; the think already happened
            }
        }
        r.put("ms", ms);
        r.put("cap_s", cap);
        // The engine's own wait is task.get(cap, SECONDS); a think that ran that
        // long was stopped by it (it swallows the timeout itself).
        r.put("timed_out", !memoed && ms >= cap * 1000L - 20);
        if (memoed) {
            // Passed from the memo: the same question as the last think, no simulation.
            r.put("memo", true);
        } else {
            // The node counter is static in the JVM, shared by every game's thinks:
            // exact on a box running one think at a time, an upper bound otherwise.
            r.put("nodes", SimulationNode2.getCount());
        }
        r.put("depth", maxDepth);
        r.put("at", System.currentTimeMillis());
        return r;
    }
}
