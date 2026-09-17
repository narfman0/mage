package mage.player.seat;

import mage.player.seat.AutoPay.Kind;
import mage.player.seat.AutoPay.Output;
import mage.player.seat.AutoPay.Pip;
import mage.player.seat.AutoPay.Plan;
import mage.player.seat.AutoPay.Source;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The planner on hand-built boards: what is automatic, what is the player's, and which source the one plan taps first. */
public class AutoPayTest {

    private static Source source(String name, List<Kind>... outputs) {
        List<Output> outs = new ArrayList<>();
        for (List<Kind> units : outputs) {
            outs.add(new Output(UUID.randomUUID(), units));
        }
        return new Source(UUID.nameUUIDFromBytes((name + outs.hashCode()).getBytes()), AutoPay.key(outs, null), outs);
    }

    private static Source basic(Kind k) {
        return source(k.name(), List.of(k));
    }

    private static Source forest() {
        return basic(Kind.G);
    }

    private static Source island() {
        return basic(Kind.U);
    }

    /** Breeding Pool: two abilities, {G} or {U}. */
    private static Source pool() {
        return source("pool", List.of(Kind.G), List.of(Kind.U));
    }

    /** Sol Ring: one ability, {C}{C}. */
    private static Source solRing() {
        return source("sol", List.of(Kind.C, Kind.C));
    }

    /** An any-colour rock or Command Tower. */
    private static Source anyColour() {
        return source("any", List.of(Kind.ANY));
    }

    private static List<Pip> cost(String text) {
        List<Pip> out = new ArrayList<>();
        for (char ch : text.toCharArray()) {
            switch (ch) {
                case 'W' -> out.add(Pip.of(Kind.W));
                case 'U' -> out.add(Pip.of(Kind.U));
                case 'B' -> out.add(Pip.of(Kind.B));
                case 'R' -> out.add(Pip.of(Kind.R));
                case 'G' -> out.add(Pip.of(Kind.G));
                case 'C' -> out.add(Pip.of(Kind.C));
                default -> {
                    for (int i = 0; i < ch - '0'; i++) {
                        out.add(Pip.generic());
                    }
                }
            }
        }
        return out;
    }

    private static Plan plan(String cost, Source... sources) {
        return AutoPay.plan(cost(cost), List.of(sources));
    }

    private static void auto(Plan p, Source expectFirst) {
        Assert.assertTrue("payable: " + p, p.payable());
        Assert.assertFalse("unambiguous: " + p, p.ambiguous());
        Assert.assertEquals("first tap", expectFirst.key(), p.pick().source().key());
    }

    private static void ask(Plan p) {
        Assert.assertTrue("payable: " + p, p.payable());
        Assert.assertTrue("ambiguous: " + p, p.ambiguous());
    }

    @Test
    public void whichForestPaysGreenDoesNotMatter() {
        auto(plan("G", forest(), forest(), forest()), forest());
        auto(plan("1G", forest(), forest(), forest()), forest());
    }

    @Test
    public void tappingOutIsAutomatic() {
        auto(plan("2", forest(), island()), forest());
        auto(plan("GU", forest(), island()), forest());
    }

    @Test
    public void forestOrIslandForAGenericPipIsThePlayers() {
        ask(plan("1", forest(), island()));
        ask(plan("1G", forest(), forest(), island()));
    }

    @Test
    public void aDualIsKeptUpWhenABasicCanPay() {
        // The Forest leaves the Pool up ({G} or {U}); the Pool leaves a Forest ({G}): the first covers the second.
        auto(plan("G", forest(), pool()), forest());
        auto(plan("U", island(), pool()), island());
        // Only the Pool makes blue: forced.
        auto(plan("U", forest(), pool()), pool());
        // Forest + Island for {1}{G} leaves the Pool; every other way leaves a basic: unique.
        auto(plan("1G", forest(), island(), pool()), forest());
    }

    @Test
    public void anyColourSourceIsKeptUp() {
        auto(plan("G", forest(), anyColour()), forest());
        auto(plan("1", forest(), anyColour()), forest());
        auto(plan("U", forest(), anyColour()), anyColour());
    }

    @Test
    public void aRockThatPaysExactlyBeatsWastingIt() {
        // Sol Ring pays {2} alone and leaves the Forest; Forest + Sol Ring wastes one and leaves nothing.
        auto(plan("2", forest(), solRing()), solRing());
        // {1}: the Forest leaves {C}{C}, the Ring leaves {G}. Neither covers the other.
        ask(plan("1", forest(), solRing()));
        // {1}{G}: two Forests leave the Ring; Ring + Forest wastes one and leaves a Forest. Not comparable: ask.
        ask(plan("1G", forest(), forest(), solRing()));
        // {C}{C}: only the Ring makes colorless.
        auto(plan("CC", forest(), solRing()), solRing());
        // {3}: Ring + Forest is the only way; which is tapped first is nobody's concern.
        Plan both = plan("3", forest(), solRing());
        Assert.assertTrue(both.payable());
        Assert.assertFalse(both.ambiguous());
    }

    @Test
    public void nothingCleanCanPay() {
        Plan p = plan("U", forest(), forest());
        Assert.assertFalse(p.payable());
        Assert.assertFalse(plan("3", forest(), island()).payable());
        Assert.assertFalse(AutoPay.plan(null, List.of(forest())).payable());
        Assert.assertFalse(AutoPay.plan(List.of(), List.of(forest())).payable());
    }

    @Test
    public void hybridPipTakesEitherColour() {
        List<Pip> gu = List.of(Pip.of(Kind.G, Kind.U));
        // Forest or Island: leaves the other; not comparable.
        Assert.assertTrue(AutoPay.plan(gu, List.of(forest(), island())).ambiguous());
        // Forest or Pool: the Forest leaves the Pool, which covers a Forest.
        Plan p = AutoPay.plan(gu, List.of(forest(), pool()));
        Assert.assertFalse(p.ambiguous());
        Assert.assertEquals(forest().key(), p.pick().source().key());
    }

    @Test
    public void pilotPickWastesLeastThenKeepsMostKindsUp() {
        // Ambiguous for a person; a pilot takes the plan that keeps the most kinds of mana up.
        Plan p = plan("1", forest(), island(), anyColour());
        // Forest leaves {U, any}; Island leaves {G, any}; the rock leaves {G, U}: any covers all.
        Assert.assertFalse("the rock is never the one to tap: " + p, p.pick().source().key().equals(anyColour().key()));
        Assert.assertTrue(p.ambiguous());
    }

    @Test
    public void bigBoardStaysFast() {
        List<Source> board = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            board.add(forest());
            board.add(island());
            board.add(basic(Kind.B));
        }
        board.add(pool());
        board.add(solRing());
        board.add(anyColour());
        long t0 = System.currentTimeMillis();
        Plan p = AutoPay.plan(cost("4UUB"), board);
        Assert.assertTrue(p.payable());
        Assert.assertTrue("a 21-source board plans in well under a second", System.currentTimeMillis() - t0 < 1000);
    }
}
