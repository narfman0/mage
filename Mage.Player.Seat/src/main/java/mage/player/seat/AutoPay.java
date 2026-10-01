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

    /**
     * One way to tap a source: the ability to activate, the units it makes,
     * and the mana it costs on top of the tap ({@code cost}, empty for a
     * tap-only ability). A Signet is {@code [W, R]} for one generic pip: a
     * plan that taps it must also pay that pip, from another source tapped
     * for it (never from the Signet's own mana, which arrives after the cost
     * is paid, and never from another costly ability: one level deep).
     */
    record Output(UUID abilityId, List<Kind> units, List<Pip> cost) {

        Output(UUID abilityId, List<Kind> units) {
            this(abilityId, units, List.of());
        }

        boolean costly() {
            return !cost.isEmpty();
        }
    }

    /**
     * An untapped source. {@code key} is what makes two sources
     * interchangeable (same outputs, and nothing else about the permanent
     * a player would keep it up for): sources with the same key are one
     * group, and a plan that swaps one for another is the same plan.
     * {@code clean}: nothing but the mana happens when it is tapped (a
     * person's seat plans over clean sources only; a pilot's over every
     * tap-only one, preferring the clean).
     */
    record Source(UUID id, String key, List<Output> outputs, boolean clean) {
    }

    /**
     * The next tap: the source, the ability, and the kind it should make for
     * the pip it pays. A costly output's pick carries {@code costPicks}, the
     * taps that pay its own cost: the engine asks for them inside the
     * activation, and they are this plan's, not a fresh plan's.
     */
    record Pick(Source source, Output output, Kind make, List<Pick> costPicks) {

        Pick(Source source, Output output, Kind make) {
            this(source, output, make, List.of());
        }
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

    /** {@code costOf}: the index in the plan's taps of the costly tap whose cost this one pays, or -1. */
    private record Tap(int group, Output output, Kind make, int costOf) {
    }

    /** A pip a costly tap owes: paid only by a fresh, cost-free tap. */
    private record Owed(Pip pip, int owner) {
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
        search.dfs(new ArrayList<>(pips), new ArrayList<>(), new ArrayList<>(), new int[search.groups.size()], new ArrayList<>(), 0);
        if (search.terminals.isEmpty()) {
            return Plan.NONE;
        }
        List<Terminal> distinct = search.undominated(new ArrayList<>(search.terminals.values()));
        boolean ambiguous = search.truncated || distinct.size() > 1;
        Terminal chosen = ambiguous ? search.pilotChoice(distinct) : distinct.get(0);
        return new Plan(true, ambiguous, search.firstPick(chosen));
    }

    /**
     * The tap to make now. A costly tap goes first, with the taps that pay
     * its cost: the engine pays that cost inside the activation, so they
     * are made there, and the rest of the plan is planned again afterwards.
     * Each tap of a group is its own member, in tap order, so two Swamps
     * paying one cost are two Swamps.
     */
    private Pick firstPick(Terminal plan) {
        List<Tap> taps = plan.taps();
        int[] used = new int[groups.size()];
        List<Source> member = new ArrayList<>();
        for (Tap tap : taps) {
            member.add(groups.get(tap.group()).members().get(used[tap.group()]++));
        }
        int first = 0;
        for (int i = 0; i < taps.size(); i++) {
            if (taps.get(i).output().costly()) {
                first = i;
                break;
            }
        }
        List<Pick> costPicks = new ArrayList<>();
        for (int i = 0; i < taps.size(); i++) {
            if (taps.get(i).costOf() == first && taps.get(first).output().costly()) {
                costPicks.add(new Pick(member.get(i), taps.get(i).output(), taps.get(i).make()));
            }
        }
        Tap tap = taps.get(first);
        return new Pick(member.get(first), tap.output(), tap.make(), costPicks);
    }

    /**
     * Depth first over the ways to pay: {@code floating} is mana a tapped
     * source made beyond the pip it was tapped for (a Sol Ring's second
     * {C}); it pays a pip before anything else is tapped, or is wasted.
     * {@code owed} is what costly taps still owe, paid by fresh cost-free
     * taps only.
     */
    private void dfs(List<Pip> pips, List<Owed> owed, List<Kind> floating, int[] counts, List<Tap> taps, int overpay) {
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
                dfs(left, owed, rest, counts, taps, overpay);
            }
            if (!paidSomething) {
                dfs(pips, owed, rest, counts, taps, overpay + 1);
            }
            return;
        }
        if (pips.isEmpty() && owed.isEmpty()) {
            String sig = Arrays.toString(counts);
            Terminal seen = terminals.get(sig);
            if (seen == null || overpay < seen.overpay()) {
                terminals.put(sig, new Terminal(counts.clone(), new ArrayList<>(taps), overpay));
            }
            return;
        }
        // The most constrained pip first keeps the tree small; every pip must
        // be paid by something, so fixing which pip is paid next loses no plan.
        // Index i < pips.size() is a pip of the cost; past it, one a costly tap owes.
        int at = 0;
        int fewest = Integer.MAX_VALUE;
        for (int i = 0; i < pips.size() + owed.size(); i++) {
            boolean isOwed = i >= pips.size();
            Pip candidate = isOwed ? owed.get(i - pips.size()).pip() : pips.get(i);
            int ways = 0;
            for (int g = 0; g < groups.size(); g++) {
                if (counts[g] < groups.get(g).members().size()) {
                    for (Output o : groups.get(g).members().get(0).outputs()) {
                        if (isOwed && o.costly()) {
                            continue;
                        }
                        for (Kind u : o.units()) {
                            if (canPay(candidate, u)) {
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
        boolean payingOwed = at >= pips.size();
        Pip pip;
        int costOf;
        List<Pip> left = new ArrayList<>(pips);
        List<Owed> owedLeft = new ArrayList<>(owed);
        if (payingOwed) {
            Owed o = owedLeft.remove(at - pips.size());
            pip = o.pip();
            costOf = o.owner();
        } else {
            pip = left.remove(at);
            costOf = -1;
        }
        for (int g = 0; g < groups.size(); g++) {
            Group group = groups.get(g);
            if (counts[g] >= group.members().size()) {
                continue;
            }
            for (Output o : group.members().get(0).outputs()) {
                if (payingOwed && o.costly()) {
                    continue;
                }
                List<Owed> nextOwed = owedLeft;
                if (o.costly()) {
                    nextOwed = new ArrayList<>(owedLeft);
                    for (Pip c : o.cost()) {
                        nextOwed.add(new Owed(c, taps.size()));
                    }
                }
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
                        taps.add(new Tap(g, o, make, costOf));
                        dfs(left, nextOwed, spare, counts, taps, overpay);
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
     * plans that cover each other are the same plan for mana — the one
     * kept is the pilot's preference among them (tap the Forest, not the
     * dork that makes the same {G}); a plan covered by a different one is
     * not a choice anyone would make for mana.
     */
    private List<Terminal> undominated(List<Terminal> all) {
        // Equivalence classes first: mutual cover.
        List<List<Terminal>> classes = new ArrayList<>();
        for (Terminal t : all) {
            List<Terminal> home = null;
            for (List<Terminal> c : classes) {
                if (covers(c.get(0), t) && covers(t, c.get(0))) {
                    home = c;
                    break;
                }
            }
            if (home == null) {
                home = new ArrayList<>();
                classes.add(home);
            }
            home.add(t);
        }
        List<Terminal> reps = new ArrayList<>();
        for (List<Terminal> c : classes) {
            reps.add(c.size() == 1 ? c.get(0) : pilotChoice(c));
        }
        List<Terminal> out = new ArrayList<>();
        for (int i = 0; i < reps.size(); i++) {
            boolean dominated = false;
            for (int j = 0; j < reps.size() && !dominated; j++) {
                dominated = i != j && covers(reps.get(j), reps.get(i));
            }
            if (!dominated) {
                out.add(reps.get(i));
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

    /**
     * Whether source a can make everything source b can: for each of b's
     * outputs, one of a's covers it. A costly output (a Signet) stands in
     * only for another costly one: it needs mana of its own, so a Signet
     * left up is not a Plains left up.
     */
    static boolean covers(Source a, Source b) {
        for (Output ob : b.outputs()) {
            boolean covered = false;
            for (Output oa : a.outputs()) {
                if ((!oa.costly() || ob.costly()) && covers(oa.units(), ob.units())) {
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

    /**
     * For a seat that never asks: waste least, then the fewest unclean
     * taps (a Forest before a dork or an Ancient Tomb), then keep the most
     * kinds of mana up, then the fewest taps.
     */
    private Terminal pilotChoice(List<Terminal> candidates) {
        Terminal best = null;
        int bestOverpay = Integer.MAX_VALUE;
        int bestUnclean = Integer.MAX_VALUE;
        int bestKinds = -1;
        int bestTaps = Integer.MAX_VALUE;
        for (Terminal t : candidates) {
            int unclean = 0;
            for (Tap tap : t.taps()) {
                if (!groups.get(tap.group()).members().get(0).clean()) {
                    unclean++;
                }
            }
            EnumSet<Kind> left = EnumSet.noneOf(Kind.class);
            // A costly source left up counts only while the free sources left
            // up could pay its cost (a Signet with no land beside it makes nothing).
            int freeLeft = 0;
            for (int g = 0; g < groups.size(); g++) {
                boolean free = groups.get(g).members().get(0).outputs().stream().anyMatch(o -> !o.costly());
                if (free) {
                    freeLeft += groups.get(g).members().size() - t.counts()[g];
                }
            }
            for (int g = 0; g < groups.size(); g++) {
                for (int k = t.counts()[g]; k < groups.get(g).members().size(); k++) {
                    for (Output o : groups.get(g).members().get(0).outputs()) {
                        if (o.costly()) {
                            if (o.cost().size() > freeLeft) {
                                continue;
                            }
                            freeLeft -= o.cost().size();
                        }
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
                    || (t.overpay() == bestOverpay && (unclean < bestUnclean
                    || (unclean == bestUnclean && (left.size() > bestKinds || (left.size() == bestKinds && taps < bestTaps)))));
            if (better) {
                best = t;
                bestOverpay = t.overpay();
                bestUnclean = unclean;
                bestKinds = left.size();
                bestTaps = taps;
            }
        }
        return best;
    }

    /** A source's key from its outputs: the same outputs (and no other reason to keep it up) means interchangeable. */
    static String key(List<Output> outputs, String distinguishing, boolean clean) {
        List<String> parts = new ArrayList<>();
        for (Output o : outputs) {
            parts.add(o.units().toString() + (o.costly() ? "+cost" + o.cost().size() + o.cost() : ""));
        }
        parts.sort(String::compareTo);
        return parts + (clean ? "" : "|unclean") + (distinguishing == null ? "" : "|" + distinguishing);
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
