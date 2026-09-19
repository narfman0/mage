package org.mage.test.cards.single.dsc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.counters.CounterType;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * {@link mage.cards.w.WinterCynicalOpportunist Winter, Cynical Opportunist}
 * Deathtouch
 * Whenever Winter attacks, mill three cards.
 * Delirium — At the beginning of your end step, you may exile any number of cards from your
 * graveyard with four or more card types among them. If you do, put a permanent card from
 * among them onto the battlefield with a finality counter on it.
 *
 * @author narfman0
 */
public class WinterCynicalOpportunistTest extends CardTestPlayerBase {

    private static final String winter = "Winter, Cynical Opportunist";

    @Test
    public void test_AttackMillsThree() {
        addCard(Zone.BATTLEFIELD, playerA, winter);

        attack(1, playerA, winter);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, 3);
        assertLife(playerB, 18);
    }

    @Test
    public void test_NoDeliriumTriggerWithoutFourCardTypes() {
        addCard(Zone.BATTLEFIELD, playerA, winter);
        addCard(Zone.GRAVEYARD, playerA, "Silvercoat Lion"); // creature
        addCard(Zone.GRAVEYARD, playerA, "Forest"); // land

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, 2);
        assertPermanentCount(playerA, "Silvercoat Lion", 0);
    }

    @Test
    public void test_ExileFourTypesAndReturnAPermanent() {
        addCard(Zone.BATTLEFIELD, playerA, winter);
        addCard(Zone.GRAVEYARD, playerA, "Silvercoat Lion"); // creature
        addCard(Zone.GRAVEYARD, playerA, "Forest"); // land
        addCard(Zone.GRAVEYARD, playerA, "Pacifism"); // enchantment
        addCard(Zone.GRAVEYARD, playerA, "Lightning Bolt"); // instant

        setChoice(playerA, true); // use the ability
        setChoice(playerA, "Silvercoat Lion^Forest^Pacifism^Lightning Bolt"); // cards to exile
        setChoice(playerA, "Silvercoat Lion"); // permanent card to put onto the battlefield

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, 0);
        assertExileCount(playerA, 3);
        assertPermanentCount(playerA, "Silvercoat Lion", 1);
        assertCounterCount(playerA, "Silvercoat Lion", CounterType.FINALITY, 1);
    }

    @Test
    public void test_MayDecline() {
        addCard(Zone.BATTLEFIELD, playerA, winter);
        addCard(Zone.GRAVEYARD, playerA, "Silvercoat Lion");
        addCard(Zone.GRAVEYARD, playerA, "Forest");
        addCard(Zone.GRAVEYARD, playerA, "Pacifism");
        addCard(Zone.GRAVEYARD, playerA, "Lightning Bolt");

        setChoice(playerA, false); // decline

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.END_TURN);
        execute();

        assertGraveyardCount(playerA, 4);
        assertExileCount(playerA, 0);
    }
}
