package mage.player.seat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plans a mana payment from the clean sources on a board and says whether
 * the choice is the player's to make.
 *
 * <p>The rule (owner decision, 2026-09-17): the engine must not make a mana
 * choice for a person that changes what they have left. A payment is
 * automatic only when every way of paying it from the clean sources leaves
 * the same mana up — which of three Forests pays {G}, tapping out, a
 * mono-colour board — or when one way leaves strictly more up than every
 * other (tap the Forest, keep the dual). When two ways leave different
 * mana up (a Forest or an Island for a generic pip, a rock or a land for
 * {1}) the payment is ambiguous, and a person is asked.
 *
 * <p>Pure: no engine types beyond ids, so it is unit-tested with hand-built
 * boards. {@link SeatPlayer} gathers the sources and applies the pick.
 */
final class AutoPay {

    /** A kind of mana a source makes, or a pip accepts. {@code ANY} is "one mana of any colour". */
    enum Kind { W, U, B, R, G, C, ANY }

    private static final EnumSet<Kind> COLOURS = EnumSet.of(Kind.W, Kind.U, Kind.B, Kind.R, Kind.G);

    /** One mana still owed: the kinds that may pay it. Generic accepts every kind but a colour choice; {C} only colorless. */
    record Pip(EnumSet<Kind> accepts) {

        static Pip generic() {
            return new Pip(EnumSet.of(Kind.W, Kind.U, Kind.B, Kind.R, Kind.G, Kind.C));
        }

        static Pip of(Kind... kinds) {
            return new Pip(EnumSet.copyOf(Arrays.asList(kinds)));
        }

        /** The colours an any-colour unit could make to pay this pip. */
        List<Kind> coloursFor() {
            List<Kind> out = new ArrayList<>();
            for (Kind k : COLOURS) {
                if (accepts.contains(k)) {
                    out.add(k);
                }
            }
            return out;
        }
    }

    /** One way to tap a source: the ability to activate and the units it makes. */
    record Output(UUID abilityId, List<Kind> units) {
    }

    /**
     * An untapped clean source. {@code key} is what makes two sources
     * interchangeable (same outputs, and nothing else about the permanent
     * a player would keep it up for): sources with the same key are one
     * group, and a plan that swaps one for another is the same plan.
     */
    record Source(UUID id, String key, List<Output> outputs) {
    }

    /** The next tap: the source, the ability, and the kind it should make for the pip it pays. */
    record Pick(Source source, Output output, Kind make) {
    }

    /**
     * {@code payable}: the clean sources cover the cost. {@code ambiguous}:
     * more than one way does and none leaves the most up, so the choice is
     * the player's. {@code pick}: the first tap of the one plan — or, when
     * ambiguous, of the plan that wastes least and keeps the most kinds of
     * mana up, for a seat that never asks.
     */
    record Plan(boolean payable, boolean ambiguous, Pick pick) {
        static final Plan NONE = new Plan(false, false, null);
    }

    /** Search budget; past it the plan counts as ambiguous rather than wrong. */
    private static final int MAX_NODES = 20_000;

    private record Group(String key, List<Source> members) {
    }

    private record Tap(int group, Output output, Kind make) {
    }

    private record Terminal(int[] counts, List<Tap> taps, int overpay) {
    }

    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Terminal> terminals = new LinkedHashMap<>();
    private int nodes;
    private boolean truncated;

    private AutoPay(List<Source> sources) {
        Map<String, Group> byKey = new LinkedHashMap<>();
        for (Source s : sources) {
            byKey.computeIfAbsent(s.key(), k -> new Group(k, new ArrayList<>())).members().add(s);
        }
        groups.addAll(byKey.values());
    }

    static Plan plan(List<Pip> pips, List<Source> sources) {
        if (pips == null || pips.isEmpty()) {
            return Plan.NONE;
        }
        AutoPay search = new AutoPay(sources);
        search.dfs(new ArrayList<>(pips), new ArrayList<>(), new int[search.groups.size()], new ArrayList<>(), 0);
        if (search.terminals.isEmpty()) {
            return Plan.NONE;
        }
        List<Terminal> distinct = search.undominated(new ArrayList<>(search.terminals.values()));
        boolean ambiguous = search.truncated || distinct.size() > 1;
        Terminal chosen = ambiguous ? search.pilotChoice(distinct) : distinct.get(0);
        Tap first = chosen.taps().get(0);
        Group g = search.groups.get(first.group());
        return new Plan(true, ambiguous, new Pick(g.members().get(0), first.output(), first.make()));
    }

    /**
     * Depth first over the ways to pay: {@code floating} is mana a tapped
     * source made beyond the pip it was tapped for (a Sol Ring's second
     * {C}); it pays a pip before anything else is tapped, or is wasted.
     */
    private void dfs(List<Pip> pips, List<Kind> floating, int[] counts, List<Tap> taps, int overpay) {
        if (truncated) {
            return;
        }
        if (++nodes > MAX_NODES) {
            truncated = true;
            return;
        }
        if (!floating.isEmpty()) {
            Kind unit = floating.get(0);
            List<Kind> rest = floating.subList(1, floating.size());
            List<EnumSet<Kind>> tried = new ArrayList<>();
            boolean paidSomething = false;
            for (int p = 0; p < pips.size(); p++) {
                Pip pip = pips.get(p);
                if (!pip.accepts().contains(unit) || tried.contains(pip.accepts())) {
                    continue;
                }
                tried.add(pip.accepts());
                paidSomething = true;
                List<Pip> left = new ArrayList<>(pips);
                left.remove(p);
                dfs(left, rest, counts, taps, overpay);
            }
            if (!paidSomething) {
                dfs(pips, rest, counts, taps, overpay + 1);
            }
            return;
        }
        if (pips.isEmpty()) {
            String sig = Arrays.toString(counts);
            Terminal seen = terminals.get(sig);
            if (seen == null || overpay < seen.overpay()) {
                terminals.put(sig, new Terminal(counts.clone(), new ArrayList<>(taps), overpay));
            }
            return;
        }
        // The most constrained pip first keeps the tree small; every pip must
        // be paid by something, so fixing which pip is paid next loses no plan.
        int at = 0;
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < pips.size(); i++) {
            int ways = 0;
            for (int g = 0; g < groups.size(); g++) {
                if (counts[g] < groups.get(g).members().size()) {
                    for (Output o : groups.get(g).members().get(0).outputs()) {
                        for (Kind u : o.units()) {
                            if (canPay(pips.get(i), u)) {
                                ways++;
                            }
                        }
                    }
                }
            }
            if (ways < fewest) {
                fewest = ways;
                at = i;
            }
        }
        if (fewest == 0) {
            return; // a pip nothing clean can pay: no plan down this branch
        }
        Pip pip = pips.get(at);
        List<Pip> left = new ArrayList<>(pips);
        left.remove(at);
        for (int g = 0; g < groups.size(); g++) {
            Group group = groups.get(g);
            if (counts[g] >= group.members().size()) {
                continue;
            }
            for (Output o : group.members().get(0).outputs()) {
                EnumSet<Kind> tried = EnumSet.noneOf(Kind.class);
                for (int i = 0; i < o.units().size(); i++) {
                    Kind unit = o.units().get(i);
                    if (!canPay(pip, unit) || !tried.add(unit)) {
                        continue;
                    }
                    // A fixed unit makes itself; an any-colour unit makes each colour
                    // the pip takes (the source's other any-colour units follow it:
                    // "three mana of any one colour").
                    List<Kind> makes = unit == Kind.ANY ? pip.coloursFor() : List.of(unit);
                    for (Kind make : makes) {
                        List<Kind> spare = new ArrayList<>();
                        for (int j = 0; j < o.units().size(); j++) {
                            if (j != i) {
                                spare.add(o.units().get(j) == Kind.ANY ? make : o.units().get(j));
                            }
                        }
                        counts[g]++;
                        taps.add(new Tap(g, o, make));
                        dfs(left, spare, counts, taps, overpay);
                        taps.remove(taps.size() - 1);
                        counts[g]--;
                    }
                }
            }
        }
    }

    private static boolean canPay(Pip pip, Kind unit) {
        return unit == Kind.ANY ? !pip.coloursFor().isEmpty() : pip.accepts().contains(unit);
    }

    /**
     * Plans whose leftover no other plan's leftover can do the work of. Plan
     * A leaves at least as much as plan B when B's untapped sources map
     * one-to-one onto A's with each A source making everything its B source
     * makes (a dual covers a basic, an any-colour rock covers a dual). Two
     * plans that cover each other are the same plan; a plan covered by a
     * different one is not a choice anyone would make for mana.
     */
    private List<Terminal> undominated(List<Terminal> all) {
        List<Terminal> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < all.size() && !dominated; j++) {
                if (i == j) {
                    continue;
                }
                boolean jCoversI = covers(all.get(j), all.get(i));
                boolean iCoversJ = covers(all.get(i), all.get(j));
                // Strictly covered, or equivalent to an earlier plan already kept.
                dominated = (jCoversI && !iCoversJ) || (jCoversI && iCoversJ && j < i);
            }
            if (!dominated) {
                out.add(all.get(i));
            }
        }
        return out;
    }

    /** Whether a's leftover sources can stand in for b's, one for one. */
    private boolean covers(Terminal a, Terminal b) {
        List<Source> left = new ArrayList<>();
        List<Source> need = new ArrayList<>();
        for (int g = 0; g < groups.size(); g++) {
            Group group = groups.get(g);
            for (int k = a.counts()[g]; k < group.members().size(); k++) {
                left.add(group.members().get(0));
            }
            for (int k = b.counts()[g]; k < group.members().size(); k++) {
                need.add(group.members().get(0));
            }
        }
        if (need.size() > left.size()) {
            return false;
        }
        int[] matchedTo = new int[left.size()];
        Arrays.fill(matchedTo, -1);
        for (int n = 0; n < need.size(); n++) {
            if (!augment(n, need, left, matchedTo, new boolean[left.size()])) {
                return false;
            }
        }
        return true;
    }

    private static boolean augment(int n, List<Source> need, List<Source> left, int[] matchedTo, boolean[] seen) {
        for (int l = 0; l < left.size(); l++) {
            if (seen[l] || !covers(left.get(l), need.get(n))) {
                continue;
            }
            seen[l] = true;
            if (matchedTo[l] < 0 || augment(matchedTo[l], need, left, matchedTo, seen)) {
                matchedTo[l] = n;
                return true;
            }
        }
        return false;
    }

    /** Whether source a can make everything source b can: for each of b's outputs, one of a's covers it. */
    static boolean covers(Source a, Source b) {
        for (Output ob : b.outputs()) {
            boolean covered = false;
            for (Output oa : a.outputs()) {
                if (covers(oa.units(), ob.units())) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                return false;
            }
        }
        return true;
    }

    /** Units a cover units b: each of b's units matched by an unused unit of a of the same kind, or any-colour for a colour. */
    private static boolean covers(List<Kind> a, List<Kind> b) {
        List<Kind> pool = new ArrayList<>(a);
        for (Kind kb : b) {
            int at = pool.indexOf(kb);
            if (at < 0 && COLOURS.contains(kb)) {
                at = pool.indexOf(Kind.ANY);
            }
            if (at < 0) {
                return false;
            }
            pool.remove(at);
        }
        return true;
    }

    /** For a seat that never asks: waste least, then keep the most kinds of mana up, then the fewest taps. */
    private Terminal pilotChoice(List<Terminal> candidates) {
        Terminal best = null;
        int bestOverpay = Integer.MAX_VALUE;
        int bestKinds = -1;
        int bestTaps = Integer.MAX_VALUE;
        for (Terminal t : candidates) {
            EnumSet<Kind> left = EnumSet.noneOf(Kind.class);
            for (int g = 0; g < groups.size(); g++) {
                if (t.counts()[g] < groups.get(g).members().size()) {
                    for (Output o : groups.get(g).members().get(0).outputs()) {
                        for (Kind u : o.units()) {
                            if (u == Kind.ANY) {
                                left.addAll(COLOURS);
                            } else {
                                left.add(u);
                            }
                        }
                    }
                }
            }
            int taps = t.taps().size();
            boolean better = t.overpay() < bestOverpay
                    || (t.overpay() == bestOverpay && (left.size() > bestKinds || (left.size() == bestKinds && taps < bestTaps)));
            if (better) {
                best = t;
                bestOverpay = t.overpay();
                bestKinds = left.size();
                bestTaps = taps;
            }
        }
        return best;
    }

    /** A source's key from its outputs: the same outputs (and no other reason to keep it up) means interchangeable. */
    static String key(List<Output> outputs, String distinguishing) {
        List<String> parts = new ArrayList<>();
        for (Output o : outputs) {
            parts.add(o.units().toString());
        }
        parts.sort(String::compareTo);
        return parts + (distinguishing == null ? "" : "|" + distinguishing);
    }

    /** A Mana object as units, in WUBRG-C-any order; generic in an output (rare) is ignored. */
    static List<Kind> units(int w, int u, int b, int r, int g, int c, int any) {
        List<Kind> out = new ArrayList<>();
        add(out, Kind.W, w);
        add(out, Kind.U, u);
        add(out, Kind.B, b);
        add(out, Kind.R, r);
        add(out, Kind.G, g);
        add(out, Kind.C, c);
        add(out, Kind.ANY, any);
        return out;
    }

    private static void add(List<Kind> out, Kind k, int n) {
        for (int i = 0; i < n; i++) {
            out.add(k);
        }
    }
}
