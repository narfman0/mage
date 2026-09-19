package org.mage.test.cards.single.dsc;

import mage.constants.PhaseStep;
import mage.constants.Zone;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

/**
 * {@link mage.cards.a.AminatouVeilPiercer Aminatou, Veil Piercer}
 * At the beginning of your upkeep, surveil 2.
 * Each enchantment card in your hand has miracle. Its miracle cost is equal to its mana cost reduced by {4}.
 *
 * @author narfman0
 */
public class AminatouVeilPiercerTest extends CardTestPlayerBase {

    private static final String aminatou = "Aminatou, Veil Piercer";
    private static final String surveilA = "Alaborn Trooper";
    private static final String surveilB = "Barbtooth Wurm";

    /**
     * Library from the top: the two cards Aminatou's upkeep surveil sees, then the card drawn.
     */
    private void initLibrary(String drawnCard) {
        skipInitShuffling();
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.LIBRARY, playerA, "Swamp");
        addCard(Zone.LIBRARY, playerA, drawnCard);
        addCard(Zone.LIBRARY, playerA, surveilA);
        addCard(Zone.LIBRARY, playerA, surveilB);
    }

    @Test
    public void test_EnchantmentIsCastForItsReducedMiracleCost() {
        initLibrary("Omniscience"); // {7}{U}{U}{U} enchantment, so miracle {3}{U}{U}{U}
        addCard(Zone.BATTLEFIELD, playerA, aminatou);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 8); // {1}{U} for Think Twice, {3}{U}{U}{U} for the miracle
        addCard(Zone.HAND, playerA, "Think Twice"); // draw a card

        addTarget(playerA, surveilB + "^" + surveilA); // surveil 2: both into the graveyard
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Think Twice");
        setChoice(playerA, true); // reveal Omniscience for miracle
        setChoice(playerA, true); // use the miracle trigger

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Omniscience", 1);
    }

    @Test
    public void test_NoMiracleWithoutAminatou() {
        initLibrary("Omniscience");
        addCard(Zone.BATTLEFIELD, playerA, "Island", 6);
        addCard(Zone.HAND, playerA, "Think Twice");

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Think Twice");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        // no surveil without Aminatou, so the drawn card is the first surveil card
        assertPermanentCount(playerA, "Omniscience", 0);
        assertHandCount(playerA, surveilB, 1);
    }

    @Test
    public void test_NoMiracleForNonEnchantmentCards() {
        initLibrary("Serra Angel"); // a creature, not an enchantment
        addCard(Zone.BATTLEFIELD, playerA, aminatou);
        addCard(Zone.BATTLEFIELD, playerA, "Island", 6);
        addCard(Zone.HAND, playerA, "Think Twice");

        addTarget(playerA, surveilB + "^" + surveilA);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Think Twice");

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Serra Angel", 0);
        assertHandCount(playerA, "Serra Angel", 1);
    }

    @Test
    public void test_UpkeepSurveilTwo() {
        initLibrary("Omniscience");
        addCard(Zone.BATTLEFIELD, playerA, aminatou);

        addTarget(playerA, surveilB + "^" + surveilA);

        setStrictChooseMode(true);
        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        execute();

        assertGraveyardCount(playerA, surveilA, 1);
        assertGraveyardCount(playerA, surveilB, 1);
    }
}
