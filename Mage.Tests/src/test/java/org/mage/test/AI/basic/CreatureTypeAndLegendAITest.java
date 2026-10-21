package org.mage.test.AI.basic;

import mage.abilities.effects.common.ChooseCreatureTypeEffect;
import mage.constants.PhaseStep;
import mage.constants.SubType;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import mage.game.permanent.PermanentToken;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBase;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Two AI choices that read as broken to a player.
 * <p>
 * "Choose a creature type": ChoiceCreatureType offers its types as keys, the AI looked for them as strings, never
 * found one and fell through to a random pick of every creature type -- a Sliver deck named Juggernaut for Titan of
 * Littjara. It now picks the type that counts most where the outcome lands.
 * <p>
 * The legend rule: a token copy scores the same as the card it copies, so the AI kept whichever came first by random
 * id, often the copy that is exiled at end of turn. Between equals it now keeps the card.
 */
public class CreatureTypeAndLegendAITest extends CardTestPlayerBase {

    private SubType chosenType(String cardName) {
        Permanent permanent = getPermanent(cardName, playerA);
        return ChooseCreatureTypeEffect.getChosenCreatureType(permanent.getId(), currentGame);
    }

    @Test
    public void test_GoodOutcome_ChoosesItsOwnTribe() {
        // As Titan of Littjara enters, choose a creature type (Outcome.Benefit)
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.HAND, playerA, "Titan of Littjara"); // {4}{U}{U}
        addCard(Zone.BATTLEFIELD, playerA, "Island", 6);
        addCard(Zone.BATTLEFIELD, playerA, "Striking Sliver");
        addCard(Zone.BATTLEFIELD, playerA, "Synapse Sliver");
        addCard(Zone.HAND, playerA, "Grizzly Bears"); // a Bear in hand counts for less than two Slivers out
        addCard(Zone.BATTLEFIELD, playerB, "Goblin Piker", 3); // an opponent's tribe doesn't count for a good outcome

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Titan of Littjara");

        setStrictChooseMode(false); // the choice is the AI's
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        Assert.assertEquals(SubType.SLIVER, chosenType("Titan of Littjara"));
    }

    @Test
    public void test_GoodOutcome_TribeInHandWithNothingOut() {
        removeAllCardsFromLibrary(playerA);
        addCard(Zone.HAND, playerA, "Icon of Ancestry"); // {3}, Outcome.BoostCreature
        addCard(Zone.BATTLEFIELD, playerA, "Island", 3);
        addCard(Zone.HAND, playerA, "Llanowar Elves", 2);
        addCard(Zone.HAND, playerA, "Grizzly Bears", 1);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Icon of Ancestry");

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        Assert.assertEquals(SubType.ELF, chosenType("Icon of Ancestry"));
    }

    @Test
    public void test_BadOutcome_ChoosesTheOpponentsTribe() {
        // As Engineered Plague enters, choose a creature type; creatures of that type get -1/-1 (UnboostCreature)
        removeAllCardsFromLibrary(playerA);
        removeAllCardsFromLibrary(playerB);
        addCard(Zone.HAND, playerA, "Engineered Plague"); // {2}{B}
        addCard(Zone.BATTLEFIELD, playerA, "Swamp", 3);
        addCard(Zone.BATTLEFIELD, playerA, "Llanowar Elves", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Goblin Piker", 2);
        addCard(Zone.BATTLEFIELD, playerB, "Llanowar Elves", 1); // an Elf of theirs doesn't outweigh two of its own

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Engineered Plague");

        setStrictChooseMode(false);
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        Assert.assertEquals(SubType.GOBLIN, chosenType("Engineered Plague"));
    }

    @Test
    public void test_LegendRule_KeepsTheCardNotTheTokenCopy() {
        // Create a token that's a copy of target creature you control -- three times, so a coin flip on each legend
        // rule passes this test one time in eight
        addCard(Zone.BATTLEFIELD, playerA, "Isamaru, Hound of Konda");
        addCard(Zone.HAND, playerA, "Cackling Counterpart", 3); // {1}{U}{U}
        addCard(Zone.BATTLEFIELD, playerA, "Island", 9);

        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cackling Counterpart", "Isamaru, Hound of Konda");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cackling Counterpart", "Isamaru, Hound of Konda");
        waitStackResolved(1, PhaseStep.PRECOMBAT_MAIN);
        castSpell(1, PhaseStep.PRECOMBAT_MAIN, playerA, "Cackling Counterpart", "Isamaru, Hound of Konda");

        setStrictChooseMode(false); // the legend rule's keep choice is the AI's
        setStopAt(1, PhaseStep.BEGIN_COMBAT);
        execute();

        assertPermanentCount(playerA, "Isamaru, Hound of Konda", 1);
        List<Permanent> kept = currentGame.getBattlefield().getAllActivePermanents(playerA.getId()).stream()
                .filter(p -> p.getName().equals("Isamaru, Hound of Konda"))
                .collect(Collectors.toList());
        Assert.assertFalse("the card is kept, not a token copy", kept.get(0) instanceof PermanentToken);
        assertGraveyardCount(playerA, "Isamaru, Hound of Konda", 0);
    }
}
