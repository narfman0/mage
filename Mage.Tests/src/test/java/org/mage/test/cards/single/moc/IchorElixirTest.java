package org.mage.test.cards.single.moc;

import mage.constants.PhaseStep;
import mage.constants.Planes;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * {@link mage.cards.i.IchorElixir Ichor Elixir}
 * If you would roll one or more planar dice, instead roll that many planar dice plus one and ignore one.
 * {T}: Add {C}{C}.
 *
 * @author narfman0
 */
public class IchorElixirTest extends CardTestPlayerBase {

    // the planar die has 9 sides here: 1-2 chaos, 3-7 blank, 8-9 planar

    @Test
    public void test_KeepTheChaosRoll() {
        // Whenever you roll {CHAOS}, create a 7/7 colorless Eldrazi creature token with annihilator 1.
        addPlane(playerA, Planes.PLANE_HEDRON_FIELDS_OF_AGADEEM);
        addCard(Zone.BATTLEFIELD, playerA, "Ichor Elixir", 1);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{0}: Roll the planar");
        setDieRollResult(playerA, 4); // blank
        setDieRollResult(playerA, 1); // chaos
        setChoice(playerA, "Blank Roll"); // ignore the blank roll, keep the chaos roll

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Eldrazi Token", 1);
    }

    @Test
    public void test_IgnoreTheChaosRoll() {
        addPlane(playerA, Planes.PLANE_HEDRON_FIELDS_OF_AGADEEM);
        addCard(Zone.BATTLEFIELD, playerA, "Ichor Elixir", 1);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{0}: Roll the planar");
        setDieRollResult(playerA, 4); // blank
        setDieRollResult(playerA, 1); // chaos
        setChoice(playerA, "Chaos Roll"); // the choice is the roller's: ignore the chaos roll instead

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Eldrazi Token", 0);
    }

    @Test
    public void test_NoChoiceWhenBothRollsAreTheSame() {
        addPlane(playerA, Planes.PLANE_HEDRON_FIELDS_OF_AGADEEM);
        addCard(Zone.BATTLEFIELD, playerA, "Ichor Elixir", 1);

        activateAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{0}: Roll the planar");
        setDieRollResult(playerA, 1); // chaos
        setDieRollResult(playerA, 2); // chaos

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // one roll is ignored, so the chaos ability triggers once, not twice
        assertPermanentCount(playerA, "Eldrazi Token", 1);
    }

    @Test
    public void test_ManaAbility() {
        addCard(Zone.BATTLEFIELD, playerA, "Ichor Elixir", 1);
        addCard(Zone.HAND, playerA, "Ornithopter", 1);

        activateManaAbility(1, PhaseStep.PRECOMBAT_MAIN, playerA, "{T}: Add {C}{C}");
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Ornithopter");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Ornithopter", 1);
        assertTappedCount("Ichor Elixir", true, 1);
    }
}
