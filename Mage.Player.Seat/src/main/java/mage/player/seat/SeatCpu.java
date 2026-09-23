package mage.player.seat;

import mage.constants.RangeOfInfluence;
import mage.game.Game;
import mage.player.ai.ComputerPlayer7;
import mage.player.ai.SimulationNode2;

import java.util.LinkedHashMap;
import java.util.Map;

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
 * is the stretch between two questions to a person.
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

    private transient Hooks hooks;
    // While a think runs: when it started (epoch ms), else 0 — `busy` reads it.
    private transient volatile long thinkingSince;
    private transient Game current;
    private transient int windowThinks;
    // The seat's cap, before the window ceiling lowers it for one think.
    private int capSecs;

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
        try {
            return super.priority(game);
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
                hooks.record(thinkRecord(ms, cap));
            }
        }
    }

    private Map<String, Object> thinkRecord(long ms, int cap) {
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
        r.put("timed_out", ms >= cap * 1000L - 20);
        // The node counter is static in the JVM, shared by every game's thinks:
        // exact on a box running one think at a time, an upper bound otherwise.
        r.put("nodes", SimulationNode2.getCount());
        r.put("depth", maxDepth);
        r.put("at", System.currentTimeMillis());
        return r;
    }
}
