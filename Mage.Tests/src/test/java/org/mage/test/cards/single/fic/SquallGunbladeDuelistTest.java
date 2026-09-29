package org.mage.test.cards.single.fic;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

import java.util.Map;

/**
 * Squall, Gunblade Duelist {R}{W}{B}, 3/2: as it enters, choose a number. Whenever creatures attack one of your
 * opponents, if any of them have power or toughness equal to the chosen number, Squall deals damage equal to its
 * power to that player.
 * <p>
 * The AI (playerA, under AI control while it resolves) names the power or toughness most common among its creatures.
 */
public class SquallGunbladeDuelistTest extends CardTestPlayerBaseWithAIHelps {

    private static final String squall = "Squall, Gunblade Duelist";

    private void castSquall() {
        addCard(Zone.HAND, playerA, squall, 1);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Plains", 1);
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 1);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, squall);
        aiPlayStep(1, PhaseStep.PRECOMBAT_MAIN, playerA);
    }

    private int chosenNumber() {
        Permanent permanent = getPermanent(squall, playerA);
        Map<String, Object> values = currentGame.getState().getValues("chosenNumber" + permanent.getId());
        Assert.assertEquals("one chosen number", 1, values.size());
        return (Integer) values.values().iterator().next();
    }

    @Test
    public void test_AI_NamesItsBearsSize() {
        addCard(Zone.BATTLEFIELD, playerA, "Grizzly Bears", 2);
        castSquall();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 2: both Bears and Squall's toughness
        Assert.assertEquals(2, chosenNumber());
    }

    @Test
    public void test_AI_NamesItsGiantsSize() {
        addCard(Zone.BATTLEFIELD, playerA, "Hill Giant", 2);
        castSquall();

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        // 3: both Giants and Squall's power
        Assert.assertEquals(3, chosenNumber());
    }
}
