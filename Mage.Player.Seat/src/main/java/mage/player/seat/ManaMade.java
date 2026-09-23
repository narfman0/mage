package mage.player.seat;

import mage.Mana;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.game.Game;
import mage.game.events.TappedForManaEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * What tapping a source really makes.
 *
 * <p>What an ability reads is not what a board produces: Mana Reflection,
 * Nyxbloom Ancient, Caged Sun and Mirari's Wake replace the
 * {@code TAPPED_FOR_MANA} event, so a Forest printed "{T}: Add {G}" adds
 * {@code {G}{G}} while Reflection is out. {@code getNetMana} answers the
 * printed value, and report 3b9a9dfa92 is what that costs: the seat
 * planned a payment four taps long that the board pays in two, and the
 * browser's bar counted three of the four mana a selection of six made.
 *
 * <p>The engine applies the replacement itself when it works out what a
 * player could pay ({@code ManaOptions.checkManaReplacementAndTriggeredMana}),
 * over a copy of the game, because replacing an event is not a read-only
 * act. This is the same thing one ability at a time: one instance per
 * question, and the copy is made only if something is asked of it.
 *
 * <p>Limit: the replacements, not the triggers. Mana that arrives through
 * a triggered mana ability (Zendikar Resurgent) still counts as what it
 * reads, which can leave the seat asking a question it could have
 * answered — never claiming mana that isn't there.
 */
final class ManaMade {

    private final Game game;
    private Game sim;

    ManaMade(Game game) {
        this.game = game;
    }

    /**
     * What one way of activating this ability adds, replacements applied.
     * {@code net} is one entry of {@code ability.getNetMana(game)}; null
     * back means the production was replaced away entirely (Contamination).
     */
    Mana made(ActivatedManaAbilityImpl ability, Mana net) {
        if (!ability.hasTapCost()) {
            // Nothing is tapped, so nothing replaces it.
            return net;
        }
        Mana made = net.copy();
        TappedForManaEvent event = new TappedForManaEvent(
                ability.getSourceId(), ability, ability.getControllerId(), made, sim());
        return sim().replaceEvent(event) ? null : made;
    }

    /**
     * What the prompt says a source makes ("{G}{G}"), for the browser to
     * count the payment with instead of reading the rules text. Several
     * ways of the same size are that many {@code {Any}} ("add one mana of
     * any color" under a doubler is {@code {Any}{Any}}); ways of different
     * sizes are nobody's one answer, and the empty string leaves the
     * client its own reading of the text.
     */
    String text(ActivatedManaAbilityImpl ability) {
        List<Mana> made = new ArrayList<>();
        for (Mana net : ability.getNetMana(game)) {
            Mana one = made(ability, net);
            if (one != null && size(one) > 0) {
                made.add(one);
            }
        }
        if (made.isEmpty()) {
            return "";
        }
        String first = made.get(0).toString();
        boolean same = true;
        int size = size(made.get(0));
        for (Mana one : made) {
            same &= one.toString().equals(first);
            if (size(one) != size) {
                return "";
            }
        }
        if (same) {
            return first;
        }
        return "{Any}".repeat(size);
    }

    private static int size(Mana mana) {
        return mana.count() + mana.getAny();
    }

    private Game sim() {
        if (sim == null) {
            sim = game.createSimulationForPlayableCalc();
        }
        return sim;
    }
}
