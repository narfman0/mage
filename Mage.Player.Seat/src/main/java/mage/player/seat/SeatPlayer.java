package mage.player.seat;

import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.human.HumanPlayer;
import mage.players.net.UserData;

import java.util.List;
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
        if (autoPay && unpaid != null) {
            UUID source = pickSource(abilityToCast, unpaid, game);
            if (source != null) {
                playManaAbilities(source, abilityToCast, unpaid, game);
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
     * The source to tap next for {@code unpaid}: a plain tap-for-mana ability
     * whose output covers a coloured pip still owed, else one that covers
     * generic. Fixed-colour sources go before any-colour ones so a Command
     * Tower stays flexible for what a basic can't pay. Anything with a real
     * cost (sacrifice, life, mana) is never tapped for you: that is the
     * person's call, and they can tap it themselves at priority.
     */
    private UUID pickSource(Ability abilityToCast, ManaCost unpaid, Game game) {
        Mana need = unpaid.getMana();
        boolean colourNeeded = need.getWhite() + need.getBlue() + need.getBlack() + need.getRed() + need.getGreen() > 0;
        boolean genericNeeded = need.getGeneric() + need.getColorless() + need.getAny() > 0;
        UUID best = null;
        int bestScore = 0;
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (abilityToCast != null && perm.getId().equals(abilityToCast.getSourceId())) {
                continue;
            }
            Map<UUID, ActivatedManaAbilityImpl> useable = getUseableManaAbilities(perm, Zone.BATTLEFIELD, game);
            if (useable.isEmpty()) {
                continue;
            }
            int score = 0;
            for (ActivatedManaAbilityImpl ability : useable.values()) {
                if (!tapOnly(ability)) {
                    score = 0;
                    break;
                }
                List<Mana> outputs = ability.getNetMana(game);
                if (outputs == null || outputs.isEmpty()) {
                    continue;
                }
                boolean fixed = outputs.size() == 1;
                boolean coversColour = false;
                for (Mana m : outputs) {
                    if ((m.getWhite() > 0 && need.getWhite() > 0) || (m.getBlue() > 0 && need.getBlue() > 0)
                            || (m.getBlack() > 0 && need.getBlack() > 0) || (m.getRed() > 0 && need.getRed() > 0)
                            || (m.getGreen() > 0 && need.getGreen() > 0) || (m.getAny() > 0 && colourNeeded)) {
                        coversColour = true;
                    }
                }
                int s = coversColour ? (fixed ? 4 : 3) : genericNeeded ? (fixed ? 2 : 1) : 0;
                score = Math.max(score, s);
            }
            if (score > bestScore) {
                bestScore = score;
                best = perm.getId();
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
