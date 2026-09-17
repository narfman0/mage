package mage.player.seat;

import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.constants.AbilityType;
import mage.abilities.costs.mana.ActivationManaAbilityStep;
import mage.game.stack.Spell;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.human.HumanPlayer;
import mage.players.net.UserData;

import java.util.List;
import org.apache.log4j.Logger;
import java.util.Map;
import java.util.UUID;

/**
 * A human seat answered in-process. Everything a HumanPlayer does — the
 * priority stops, holding priority, combat loops, the response window — is
 * inherited; what changes is that mana is paid without a click. The GUI
 * client asks its person to tap each source; a browser client wants the
 * engine to tap for it unless the person tapped sources first (floating mana
 * is spent from the pool before any source is touched, so "tap your own
 * mana, then cast" keeps working).
 */
public class SeatPlayer extends HumanPlayer {

    private static final Logger LOG = Logger.getLogger(SeatPlayer.class);

    /** Pay costs by tapping the obvious sources; ask only when nothing obvious pays. */
    private boolean autoPay = true;

    public SeatPlayer(String name, RangeOfInfluence range) {
        super(name, range, 0);
        setUserData(UserData.getDefaultUserDataView());
    }

    protected SeatPlayer(SeatPlayer player) {
        super(player);
        this.autoPay = player.autoPay;
    }

    @Override
    public SeatPlayer copy() {
        return new SeatPlayer(this);
    }

    public void setAutoPay(boolean autoPay) {
        this.autoPay = autoPay;
    }

    @Override
    protected boolean playManaHandling(Ability abilityToCast, ManaCost unpaid, String promptText, Game game) {
        if (autoPay && unpaid != null && !mustAsk(abilityToCast, game)) {
            Pick pick = pickSource(abilityToCast, unpaid, game);
            if (LOG.isDebugEnabled()) {
                LOG.debug("autopay unpaid=" + unpaid.getText() + " picked=" + (pick == null ? "none" : pick.perm().getName() + " / " + pick.ability().getRule()));
            }
            if (pick != null) {
                // The one ability, so the engine never asks "which" of a source's
                // several; a colour choice on the way is auto-picked by HumanPlayer
                // while currentlyUnpaidMana is set (its own rule).
                currentlyUnpaidMana = unpaid;
                try {
                    activateAbility(Map.of(pick.ability().getId(), pick.ability()), pick.perm(), game);
                } finally {
                    currentlyUnpaidMana = null;
                }
                return true;
            }
            if (!anySource(abilityToCast, game) && getManaPool().count() == 0) {
                // Nothing on the board can pay: cancel the way a person would, with a word.
                game.informPlayer(this, "Couldn't pay " + Fmt.stripHtml(promptText) + ": no mana source can pay it");
                return false;
            }
        }
        return super.playManaHandling(abilityToCast, unpaid, promptText, game);
    }

    /**
     * Cases HumanPlayer handles by asking, kept that way: a spell that cares
     * which colours paid for it (Sunburst, converge), and a cast whose special
     * mana payment (convoke, delve) is already past the point where normal
     * mana abilities may be used.
     */
    private boolean mustAsk(Ability abilityToCast, Game game) {
        if (abilityToCast == null || abilityToCast.getAbilityType() != AbilityType.SPELL) {
            return false;
        }
        Spell spell = game.getStack().getSpell(abilityToCast.getSourceId());
        if (spell != null && !spell.isResolving() && spell.getCurrentActivatingManaAbilitiesStep() == ActivationManaAbilityStep.AFTER) {
            return true;
        }
        Card card = game.getCard(abilityToCast.getSourceId());
        return card instanceof CardImpl impl && impl.caresAboutManaColor(game);
    }

    private record Pick(Permanent perm, ActivatedManaAbilityImpl ability) {
    }

    /**
     * The source and ability to tap next for {@code unpaid}: a plain
     * tap-for-mana ability whose output covers a coloured pip still owed,
     * else one that covers generic. Fixed-colour abilities go before
     * any-colour ones so a Command Tower stays flexible for what a basic
     * can't pay. An ability with a real cost (sacrifice, life, a counter, mana)
     * is never used for you — that is the person's call, and they can tap it
     * themselves at priority; a source's plain ability is still fair game
     * next to its costly one (Vivid lands).
     */
    private Pick pickSource(Ability abilityToCast, ManaCost unpaid, Game game) {
        Mana need = unpaid.getMana();
        boolean colourNeeded = need.getWhite() + need.getBlue() + need.getBlack() + need.getRed() + need.getGreen() > 0;
        boolean genericNeeded = need.getGeneric() + need.getColorless() + need.getAny() > 0;
        Pick best = null;
        int bestScore = 0;
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (abilityToCast != null && perm.getId().equals(abilityToCast.getSourceId())) {
                continue;
            }
            for (ActivatedManaAbilityImpl ability : getUseableManaAbilities(perm, Zone.BATTLEFIELD, game).values()) {
                if (!tapOnly(ability)) {
                    continue;
                }
                List<Mana> outputs = ability.getNetMana(game);
                if (outputs == null || outputs.isEmpty()) {
                    continue;
                }
                boolean fixed = outputs.size() == 1;
                boolean coversColour = false;
                boolean coversGeneric = false;
                for (Mana m : outputs) {
                    if ((m.getWhite() > 0 && need.getWhite() > 0) || (m.getBlue() > 0 && need.getBlue() > 0)
                            || (m.getBlack() > 0 && need.getBlack() > 0) || (m.getRed() > 0 && need.getRed() > 0)
                            || (m.getGreen() > 0 && need.getGreen() > 0) || (m.getAny() > 0 && colourNeeded)) {
                        coversColour = true;
                    }
                    if (m.count() > 0) {
                        coversGeneric = true;
                    }
                }
                int score = coversColour ? (fixed ? 4 : 3) : (genericNeeded && coversGeneric) ? (fixed ? 2 : 1) : 0;
                if (score > bestScore) {
                    bestScore = score;
                    best = new Pick(perm, ability);
                }
            }
        }
        return best;
    }

    private boolean anySource(Ability abilityToCast, Game game) {
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (abilityToCast != null && perm.getId().equals(abilityToCast.getSourceId())) {
                continue;
            }
            if (!getUseableManaAbilities(perm, Zone.BATTLEFIELD, game).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean tapOnly(ActivatedManaAbilityImpl ability) {
        if (!ability.getManaCosts().isEmpty()) {
            return false;
        }
        for (Cost cost : ability.getCosts()) {
            if (!(cost instanceof TapSourceCost)) {
                return false;
            }
        }
        return true;
    }
}
