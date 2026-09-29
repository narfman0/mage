package mage.cards.e;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.abilities.effects.common.DestroyAllEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.Outcome;
import mage.filter.StaticFilters;
import mage.filter.common.FilterCreaturePermanent;
import mage.filter.predicate.mageobject.PowerPredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.util.CardUtil;

import java.util.UUID;

/**
 * @author Susucr
 */
public final class ExpelTheInterlopers extends CardImpl {

    public ExpelTheInterlopers(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{3}{W}{W}");

        // Choose a number between 0 and 10. Destroy all creatures with power greater than or equal to the chosen number.
        this.getSpellAbility().addEffect(new ExpelTheInterlopersEffect());
    }

    private ExpelTheInterlopers(final ExpelTheInterlopers card) {
        super(card);
    }

    @Override
    public ExpelTheInterlopers copy() {
        return new ExpelTheInterlopers(this);
    }
}

class ExpelTheInterlopersEffect extends OneShotEffect {

    ExpelTheInterlopersEffect() {
        super(Outcome.DestroyPermanent);
        staticText = "choose a number between 0 and 10. Destroy all creatures with power greater than or equal to the chosen number";
    }

    private ExpelTheInterlopersEffect(final ExpelTheInterlopersEffect effect) {
        super(effect);
    }

    @Override
    public ExpelTheInterlopersEffect copy() {
        return new ExpelTheInterlopersEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }

        // Choose a number between 0 and 10.
        // AI hint
        int number = player.isComputer()
                ? chooseNumberAI(player, game)
                : player.getAmount(0, 10, "Choose a number between 0 and 10", source, game);
        game.informPlayers(player.getLogName() + " has chosen the number " + number + "." + CardUtil.getSourceLogName(game, source));

        // Destroy all creatures with power greater than or equal to the chosen number.
        FilterCreaturePermanent filter = new FilterCreaturePermanent();
        filter.add(new PowerPredicate(ComparisonType.OR_GREATER, number));

        return new DestroyAllEffect(filter).apply(game, source);
    }

    /**
     * The power threshold with the best trade: each number from 0 to 10 scores the creatures it destroys, its
     * opponents' as a gain and its own as a loss, each weighted by mana value (at least 1) so a big threat of its own
     * outweighs a handful of tokens. Ties go to the higher number, the narrower sweep. If no number comes out ahead,
     * 10 destroys the least.
     */
    static int chooseNumberAI(Player player, Game game) {
        int bestNumber = 10;
        int bestScore = 0;
        for (int number = 10; number >= 0; number--) {
            int score = 0;
            for (Permanent creature : game.getBattlefield().getActivePermanents(
                    StaticFilters.FILTER_PERMANENT_CREATURE, player.getId(), game)) {
                if (creature.getPower().getValue() < number) {
                    continue;
                }
                int value = Math.max(1, creature.getManaValue());
                if (creature.isControlledBy(player.getId())) {
                    score -= value;
                } else if (game.isOpponent(player, creature.getControllerId())) {
                    score += value;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestNumber = number;
            }
        }
        return bestNumber;
    }

}