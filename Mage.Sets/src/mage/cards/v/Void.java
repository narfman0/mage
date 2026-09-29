package mage.cards.v;

import mage.abilities.Ability;
import mage.abilities.effects.OneShotEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.cards.CardsImpl;
import mage.constants.CardType;
import mage.constants.ComparisonType;
import mage.constants.Outcome;
import mage.filter.FilterCard;
import mage.filter.predicate.Predicates;
import mage.filter.predicate.mageobject.ManaValuePredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.TargetPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author LevelX2
 */
public final class Void extends CardImpl {

    public Void(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.SORCERY}, "{3}{B}{R}");

        // Choose a number. Destroy all artifacts and creatures with converted mana cost equal to that number. Then target player reveals their hand and discards all nonland cards with converted mana cost equal to the number.
        this.getSpellAbility().addTarget(new TargetPlayer());
        this.getSpellAbility().addEffect(new VoidEffect());
    }

    private Void(final Void card) {
        super(card);
    }

    @Override
    public Void copy() {
        return new Void(this);
    }
}

class VoidEffect extends OneShotEffect {
    
    VoidEffect() {
        super(Outcome.DestroyPermanent);
        this.staticText = "Choose a number. Destroy all artifacts and creatures with mana value equal to that number. Then target player reveals their hand and discards all nonland cards with mana value equal to the number";
    }

    private VoidEffect(final VoidEffect effect) {
        super(effect);
    }

    @Override
    public VoidEffect copy() {
        return new VoidEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        
        // AI hint
        int number = controller.isComputer()
                ? chooseNumberAI(controller, source, game)
                : controller.getAmount(0, Integer.MAX_VALUE, "Choose a number (mana cost to destroy)", source, game);
        game.informPlayers(controller.getLogName() + " chooses " + number + '.');
     
        for (Permanent permanent : game.getBattlefield().getActivePermanents(source.getControllerId(), game)) {
            if ((permanent.isArtifact(game) || permanent.isCreature(game))
                    && permanent.getManaValue() == number) {
                permanent.destroy(source, game, false);
            }
        }
        FilterCard filterCard = new FilterCard();
        filterCard.add(new ManaValuePredicate(ComparisonType.EQUAL_TO, number));
        filterCard.add(Predicates.not(CardType.LAND.getPredicate()));

        Player targetPlayer = game.getPlayer(getTargetPointer().getFirst(game, source));
        if (targetPlayer == null) {
            return true;
        }
        targetPlayer.revealCards(source, targetPlayer.getHand(), game);
        targetPlayer.discard(new CardsImpl(targetPlayer.getHand().getCards(filterCard, game)), false, source, game);
        return true;
    }

    /**
     * The mana value with the best trade on the battlefield: each value an artifact or creature there has scores
     * its opponents' artifacts and creatures destroyed as a gain and its own as a loss, each weighted by mana value
     * (at least 1). The discard half reads a hidden hand, so it doesn't count. With no value ahead, the commonest
     * cheap mana value that destroys nothing of its own (2, else 3, 1, 4...), for the discard.
     */
    static int chooseNumberAI(Player controller, Ability source, Game game) {
        Map<Integer, Integer> score = new HashMap<>();
        for (Permanent permanent : game.getBattlefield().getActivePermanents(controller.getId(), game)) {
            if (!permanent.isArtifact(game) && !permanent.isCreature(game)) {
                continue;
            }
            int manaValue = permanent.getManaValue();
            int value = Math.max(1, manaValue);
            if (permanent.isControlledBy(controller.getId())) {
                score.merge(manaValue, -value, Integer::sum);
            } else if (game.isOpponent(controller, permanent.getControllerId())) {
                score.merge(manaValue, value, Integer::sum);
            } else {
                score.merge(manaValue, 0, Integer::sum);
            }
        }
        int best = -1;
        int bestScore = 0;
        for (Map.Entry<Integer, Integer> entry : score.entrySet()) {
            if (entry.getValue() > bestScore || (entry.getValue() == bestScore && best >= 0 && entry.getKey() < best)) {
                best = entry.getKey();
                bestScore = entry.getValue();
            }
        }
        if (bestScore > 0) {
            return best;
        }
        for (int number : new int[]{2, 3, 1, 4, 5, 6}) {
            if (score.getOrDefault(number, 0) >= 0) {
                return number;
            }
        }
        return 0;
    }
}
