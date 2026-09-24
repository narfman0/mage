package org.mage.test.AI.basic;

import mage.abilities.Ability;
import mage.abilities.TriggeredAbility;
import mage.constants.PhaseStep;
import mage.constants.Zone;
import mage.game.permanent.Permanent;
import mage.target.Target;
import org.junit.Assert;
import org.junit.Test;
import org.mage.test.serverside.base.CardTestPlayerBaseWithAIHelps;

/**
 * A trigger with many possible targets on a big board must not make the AI
 * simulate every combination: each simulated option is a whole game copy, and
 * on a late commander board (four players, full libraries) a few thousand of
 * them are more than any heap. Inferno Titan's "3 damage divided as you choose
 * among one, two, or three targets" over thirty distinct creatures used to be
 * about six thousand options — the out-of-memory that killed a four-player
 * game on turn 28.
 */
public class TriggerOptionsMemoryAITest extends CardTestPlayerBaseWithAIHelps {

    // distinct names and stats: target grouping can't fold them together
    private static final String[] CREATURES = {
            "Grizzly Bears", "Hill Giant", "Gray Ogre", "Craw Wurm", "Earth Elemental",
            "Fire Elemental", "Air Elemental", "Water Elemental", "Scathe Zombies", "Walking Corpse",
            "Goblin Piker", "Savannah Lions", "Elvish Warrior", "Centaur Courser", "Trained Armodon",
            "Horned Turtle", "Wind Drake", "Obsianus Golem", "Siege Mastodon", "Pillarfield Ox",
            "Coral Merfolk", "Alpha Myr", "Ornithopter", "Runeclaw Bear", "Balduvian Bears",
            "Giant Spider", "Serra Angel", "Shivan Dragon"
    };

    private void bigBoard() {
        for (int i = 0; i < CREATURES.length; i++) {
            addCard(Zone.BATTLEFIELD, i % 2 == 0 ? playerA : playerB, CREATURES[i]);
        }
        // a game copy carries every card in every zone: make them commander-sized
        addCard(Zone.LIBRARY, playerA, "Mountain", 90);
        addCard(Zone.LIBRARY, playerB, "Forest", 90);
    }

    @Test
    public void aDividedDamageTriggerOffersOptionsForAtMostTwiceItsAmountOfTargets() {
        bigBoard();
        addCard(Zone.BATTLEFIELD, playerA, "Inferno Titan");

        setStopAt(1, PhaseStep.PRECOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        Permanent titan = getPermanent("Inferno Titan", playerA);
        Ability trigger = titan.getAbilities(currentGame).stream()
                .filter(a -> a instanceof TriggeredAbility && !a.getTargets().isEmpty())
                .findFirst()
                .orElseThrow(() -> new AssertionError("no targeted trigger on Inferno Titan"))
                .copy();
        trigger.setControllerId(playerA.getId());
        trigger.setSourceId(titan.getId());
        Target target = trigger.getTargets().get(0);
        int possible = target.possibleTargets(playerA.getId(), trigger, currentGame).size();
        Assert.assertTrue("a big board: " + possible, possible > 20);

        int options = target.getTargetOptions(trigger, currentGame).size();
        // six targets (3 damage x 2): 6 single, 15 pairs x 2 splits, 20 triples
        Assert.assertTrue("options: " + options, options <= 6 + 15 * 2 + 20);
    }

    @Test
    public void theAiCastsInfernoTitanOnABigBoard() {
        bigBoard();
        addCard(Zone.HAND, playerA, "Inferno Titan"); // {4}{R}{R}
        addCard(Zone.BATTLEFIELD, playerA, "Mountain", 6);

        aiPlayPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, "Inferno Titan", 1);
    }

    @Test
    public void theAiCastsDeepglowSkateOnABigBoard() {
        // "any number of target permanents": eighteen targets after grouping are still
        // about four thousand combinations, so the trigger's sample is what keeps it small
        bigBoard();
        addCard(Zone.HAND, playerA, "Deepglow Skate"); // {4}{U}
        addCard(Zone.BATTLEFIELD, playerA, "Island", 5);

        aiPlayPriority(1, PhaseStep.PRECOMBAT_MAIN, playerA);

        setStopAt(1, PhaseStep.POSTCOMBAT_MAIN);
        setStrictChooseMode(true);
        execute();

        assertPermanentCount(playerA, "Deepglow Skate", 1);
    }
}
