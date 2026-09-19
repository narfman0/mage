package mage.cards.a;

import mage.MageInt;
import mage.abilities.Ability;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ContinuousEffectImpl;
import mage.abilities.effects.keyword.SurveilEffect;
import mage.abilities.keyword.MiracleAbility;
import mage.abilities.triggers.BeginningOfUpkeepTriggeredAbility;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.CardType;
import mage.constants.Duration;
import mage.constants.Layer;
import mage.constants.Outcome;
import mage.constants.SubLayer;
import mage.constants.SubType;
import mage.constants.SuperType;
import mage.filter.FilterCard;
import mage.filter.common.FilterEnchantmentCard;
import mage.game.Game;
import mage.players.Player;
import mage.util.CardUtil;

import java.util.UUID;

/**
 * @author narfman0
 */
public final class AminatouVeilPiercer extends CardImpl {

    public AminatouVeilPiercer(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}{U}{B}");

        this.supertype.add(SuperType.LEGENDARY);
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.WIZARD);
        this.power = new MageInt(2);
        this.toughness = new MageInt(4);

        // At the beginning of your upkeep, surveil 2.
        this.addAbility(new BeginningOfUpkeepTriggeredAbility(new SurveilEffect(2)));

        // Each enchantment card in your hand has miracle. Its miracle cost is equal to its mana cost reduced by {4}.
        this.addAbility(new SimpleStaticAbility(new AminatouVeilPiercerEffect()));
    }

    private AminatouVeilPiercer(final AminatouVeilPiercer card) {
        super(card);
    }

    @Override
    public AminatouVeilPiercer copy() {
        return new AminatouVeilPiercer(this);
    }
}

class AminatouVeilPiercerEffect extends ContinuousEffectImpl {

    private static final FilterCard filter = new FilterEnchantmentCard("enchantment card in your hand");

    AminatouVeilPiercerEffect() {
        super(Duration.WhileOnBattlefield, Layer.AbilityAddingRemovingEffects_6, SubLayer.NA, Outcome.AddAbility);
        this.staticText = "each enchantment card in your hand has miracle. Its miracle cost is equal to its mana cost "
                + "reduced by {4}. <i>(You may cast a card for its miracle cost when you draw it if it's the first "
                + "card you drew this turn.)</i>";
    }

    private AminatouVeilPiercerEffect(final AminatouVeilPiercerEffect effect) {
        super(effect);
    }

    @Override
    public AminatouVeilPiercerEffect copy() {
        return new AminatouVeilPiercerEffect(this);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller == null) {
            return false;
        }
        for (Card card : controller.getHand().getCards(filter, game)) {
            if (card.getManaCost().isEmpty()) {
                continue;
            }
            game.getState().addOtherAbility(
                    card, new MiracleAbility(CardUtil.reduceCost(card.getManaCost(), 4).getText())
            );
        }
        return true;
    }
}
