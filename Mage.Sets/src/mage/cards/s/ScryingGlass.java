package mage.cards.s;

import java.util.UUID;
import mage.ObjectColor;
import mage.abilities.Ability;
import mage.abilities.common.SimpleActivatedAbility;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.effects.OneShotEffect;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.choices.ChoiceColor;
import mage.constants.CardType;
import mage.constants.Outcome;
import mage.constants.Zone;
import mage.filter.FilterCard;
import mage.filter.predicate.mageobject.ColorPredicate;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.target.common.TargetOpponent;

/**
 *
 * @author jeffwadsworth
 */
public final class ScryingGlass extends CardImpl {
    
    public ScryingGlass(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.ARTIFACT}, "{2}");

        // {3}, {tap}: Choose a number greater than 0 and a color. Target opponent reveals their hand. If that opponent reveals exactly the chosen number of cards of the chosen color, you draw a card.
        Ability ability = new SimpleActivatedAbility(new ScryingGlassEffect(), new ManaCostsImpl<>("{3}"));
        ability.addCost(new TapSourceCost());
        ability.addTarget(new TargetOpponent());
        this.addAbility(ability);
        
    }
    
    private ScryingGlass(final ScryingGlass card) {
        super(card);
    }
    
    @Override
    public ScryingGlass copy() {
        return new ScryingGlass(this);
    }
}

class ScryingGlassEffect extends OneShotEffect {
    
    public ScryingGlassEffect() {
        super(Outcome.Neutral);
        staticText = "Choose a number greater than 0 and a color. Target opponent reveals their hand. If that opponent reveals exactly the chosen number of cards of the chosen color, you draw a card";
    }
    
    private ScryingGlassEffect(final ScryingGlassEffect effect) {
        super(effect);
    }
    
    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        Player targetOpponent = game.getPlayer(source.getFirstTarget());
        ChoiceColor color = new ChoiceColor();
        int amount = 0;
        if (controller != null
                && targetOpponent != null) {
            // AI hint
            String aiColor = controller.isComputer() ? chooseColorAI(targetOpponent, game) : null;
            if (aiColor != null) {
                amount = 1;
                color.setChoice(aiColor);
            } else {
                amount = controller.getAmount(1, Integer.MAX_VALUE, "Choose a number", source, game);
                controller.choose(Outcome.Discard, color, game);
            }
            FilterCard filter = new FilterCard();
            filter.add(new ColorPredicate(color.getColor()));
            targetOpponent.revealCards(source, targetOpponent.getHand(), game);
            if (targetOpponent.getHand().count(filter, game) == amount) {
                game.informPlayers(controller.getLogName() + " has chosen the exact number and color of the revealed cards from " + targetOpponent.getName() + "'s hand. They draw a card.");
                controller.drawCards(1, source, game);
                return true;
            } else {
                game.informPlayers(controller.getLogName() + " has chosen incorrectly and will not draw a card.");
            }
        }
        return false;
    }
    
    /**
     * A hand usually holds one or two cards of the colour its owner plays most, so the AI names 1 and the colour most
     * seen among that opponent's permanents. With no coloured permanent to go on, null: the AI's own choices.
     */
    static String chooseColorAI(Player opponent, Game game) {
        String best = null;
        int bestCount = 0;
        for (String colorName : ChoiceColor.getBaseColors()) {
            ObjectColor objectColor = ChoiceColor.getColorFromString(colorName);
            int count = 0;
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponent.getId())) {
                if (permanent.getColor(game).shares(objectColor)) {
                    count++;
                }
            }
            if (count > bestCount) {
                best = colorName;
                bestCount = count;
            }
        }
        return best;
    }

    @Override
    public ScryingGlassEffect copy() {
        return new ScryingGlassEffect(this);
    }
}
