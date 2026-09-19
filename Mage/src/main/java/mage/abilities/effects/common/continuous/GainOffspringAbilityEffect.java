package mage.abilities.effects.common.continuous;

import mage.abilities.Ability;
import mage.abilities.costs.Cost;
import mage.abilities.effects.ContinuousEffectImpl;
import mage.abilities.keyword.OffspringAbility;
import mage.cards.Card;
import mage.constants.CommanderCardType;
import mage.constants.Duration;
import mage.constants.Layer;
import mage.constants.Outcome;
import mage.constants.SubLayer;
import mage.filter.FilterCard;
import mage.game.Game;
import mage.game.stack.Spell;
import mage.game.stack.StackObject;
import mage.players.Player;

/**
 * Grants offspring to the spells a player casts, for the cards matching a filter.
 *
 * @author narfman0
 */
public class GainOffspringAbilityEffect extends ContinuousEffectImpl {

    private final Cost cost;
    private final FilterCard filter;

    public GainOffspringAbilityEffect(Cost cost, FilterCard filter, String rule) {
        super(Duration.WhileOnBattlefield, Layer.AbilityAddingRemovingEffects_6, SubLayer.NA, Outcome.AddAbility);
        this.cost = cost;
        this.filter = filter;
        this.staticText = rule;
    }

    private GainOffspringAbilityEffect(final GainOffspringAbilityEffect effect) {
        super(effect);
        this.cost = effect.cost.copy();
        this.filter = effect.filter.copy();
    }

    @Override
    public GainOffspringAbilityEffect copy() {
        return new GainOffspringAbilityEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player player = game.getPlayer(source.getControllerId());
        if (player == null) {
            return false;
        }
        for (Card card : player.getHand().getCards(game)) {
            grant(card, player, source, game);
        }
        for (Card card : player.getLibrary().getCards(game)) {
            grant(card, player, source, game);
        }
        for (Card card : player.getGraveyard().getCards(game)) {
            grant(card, player, source, game);
        }
        for (Card card : game.getExile().getCardsInRange(game, player.getId())) {
            grant(card, player, source, game);
        }
        for (Card card : game.getCommanderCardsFromCommandZone(player, CommanderCardType.ANY)) {
            grant(card, player, source, game);
        }
        for (StackObject stackObject : game.getStack()) {
            if (!(stackObject instanceof Spell) || !stackObject.isControlledBy(player.getId())) {
                continue;
            }
            grant(game.getCard(stackObject.getSourceId()), player, source, game);
        }
        return true;
    }

    private void grant(Card card, Player player, Ability source, Game game) {
        if (card != null && filter.match(card, player.getId(), source, game)) {
            game.getState().addOtherAbility(card, new GrantedOffspringAbility(cost));
        }
    }

    private static class GrantedOffspringAbility extends OffspringAbility {

        GrantedOffspringAbility(Cost cost) {
            super(cost, false);
        }

        private GrantedOffspringAbility(final GrantedOffspringAbility ability) {
            super(ability);
        }

        @Override
        public GrantedOffspringAbility copy() {
            return new GrantedOffspringAbility(this);
        }

        @Override
        public void addOptionalAdditionalCosts(Ability ability, Game game) {
            super.addOptionalAdditionalCosts(ability, game);
            if (additionalCost.isActivated()) {
                addOffspringTriggeredAbility(game, ability);
            }
        }
    }
}
