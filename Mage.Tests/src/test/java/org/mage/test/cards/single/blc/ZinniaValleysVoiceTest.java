package org.mage.test.cards.single.blc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * {@link mage.cards.z.ZinniaValleysVoice Zinnia, Valley's Voice}
 * Flying
 * Zinnia gets +X/+0, where X is the number of other creatures you control with base power 1.
 * Creature spells you cast gain offspring {2} as you cast them.
 *
 * @author narfman0
 */
public class ZinniaValleysVoiceTest extends CardTestPlayerBase {

    private static final String zinnia = "Zinnia, Valley's Voice";

    @Test
    public void test_BoostCountsOtherBasePowerOneCreatures() {
        addCard(Zone.BATTLEFIELD, playerA, zinnia);
        addCard(Zone.BATTLEFIELD, playerA, "Memnite", 2); // 1/1
        addCard(Zone.BATTLEFIELD, playerA, "Ornithopter"); // 0/2, not counted
        addCard(Zone.BATTLEFIELD, playerB, "Memnite"); // not yours, not counted

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPowerToughness(playerA, zinnia, 3, 3);
    }

    @Test
    public void test_BoostUsesBasePowerNotModifiedPower() {
        addCard(Zone.BATTLEFIELD, playerA, zinnia);
        addCard(Zone.BATTLEFIELD, playerA, "Memnite"); // 1/1
        addCard(Zone.BATTLEFIELD, playerA, "Glorious Anthem"); // +1/+1, base power is unchanged

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPowerToughness(playerA, "Memnite", 2, 2);
        assertPowerToughness(playerA, zinnia, 1 + 1 + 1, 3 + 1);
    }

    @Test
    public void test_GrantedOffspringPaid() {
        addCard(Zone.BATTLEFIELD, playerA, zinnia);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 6);
        addCard(Zone.HAND, playerA, "Hill Giant"); // {3}{R} 3/3

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Hill Giant");
        setChoice(playerA, true); // pay offspring {2}

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 2);
        assertTokenCount(playerA, "Hill Giant", 1);
    }

    @Test
    public void test_GrantedOffspringDeclined() {
        addCard(Zone.BATTLEFIELD, playerA, zinnia);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        addCard(Zone.HAND, playerA, "Hill Giant");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Hill Giant");
        setChoice(playerA, false); // decline offspring

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertPermanentCount(playerA, "Hill Giant", 1);
        assertTokenCount(playerA, "Hill Giant", 0);
    }

    @Test
    public void test_NoOffspringForNoncreatureSpells() {
        addCard(Zone.BATTLEFIELD, playerA, zinnia);
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 5);
        addCard(Zone.HAND, playerA, "Lightning Bolt");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Lightning Bolt", playerB);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertLife(playerB, 17);
    }
}
