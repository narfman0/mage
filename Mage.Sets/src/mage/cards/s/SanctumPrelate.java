package mage.cards.s;

import mage.MageInt;
import mage.MageObject;
import mage.abilities.Ability;
import mage.abilities.common.AsEntersBattlefieldAbility;
import mage.abilities.common.SimpleStaticAbility;
import mage.abilities.effects.ContinuousRuleModifyingEffectImpl;
import mage.abilities.effects.OneShotEffect;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.cards.CardSetInfo;
import mage.constants.*;
import mage.game.Game;
import mage.game.events.GameEvent;
import mage.game.permanent.Permanent;
import mage.game.stack.Spell;
import mage.players.Player;

import java.util.UUID;

/**
 * @author maxlebedev
 */
public final class SanctumPrelate extends CardImpl {

    public SanctumPrelate(UUID ownerId, CardSetInfo setInfo) {
        super(ownerId, setInfo, new CardType[]{CardType.CREATURE}, "{1}{W}{W}");
        this.subtype.add(SubType.HUMAN);
        this.subtype.add(SubType.CLERIC);
        this.power = new MageInt(2);
        this.toughness = new MageInt(2);

        // As Sanctum Prelate enters the battlefield, choose a number.
        this.addAbility(new AsEntersBattlefieldAbility(new ChooseNumberEffect()));

        // Noncreature spells with converted mana cost equal to the chosen number can't be cast.
        this.addAbility(new SimpleStaticAbility(new SanctumPrelateReplacementEffect()));
    }

    private SanctumPrelate(final SanctumPrelate card) {
        super(card);
    }

    @Override
    public SanctumPrelate copy() {
        return new SanctumPrelate(this);
    }
}

class ChooseNumberEffect extends OneShotEffect {

    ChooseNumberEffect() {
        super(Outcome.Detriment);
        staticText = "choose a number";
    }

    private ChooseNumberEffect(final ChooseNumberEffect effect) {
        super(effect);
    }

    @Override
    public boolean apply(Game game, Ability source) {
        Player controller = game.getPlayer(source.getControllerId());
        if (controller != null) {
            // AI hint
            int numberChoice = controller.isComputer()
                    ? chooseNumberAI(controller, game)
                    : controller.getAmount(0, Integer.MAX_VALUE, "Choose a number (mana cost to restrict)", source, game);
            game.getState().setValue(source.getSourceId().toString(), numberChoice);

            Permanent permanent = game.getPermanentEntering(source.getSourceId());
            if (permanent != null) {
                permanent.addInfo("chosen players", "<font color = 'blue'>Chosen Number: " + numberChoice + "</font>", game);

                game.informPlayers(permanent.getLogName() + ", chosen number: " + numberChoice);
            }
        }
        return true;
    }

    /**
     * The noncreature mana value that hurts its opponents most and itself least. Each value from 0 to 10 scores
     * the noncreature spells its opponents have shown at it (nonland, noncreature permanents on the battlefield and
     * cards in their graveyards) minus its own noncreature spells at it (hand and library). Ties go to the value
     * nearest 2, the commonest mana value for cheap interaction.
     */
    static int chooseNumberAI(Player controller, Game game) {
        int[] score = new int[11];
        for (UUID opponentId : game.getOpponents(controller.getId())) {
            Player opponent = game.getPlayer(opponentId);
            if (opponent == null) {
                continue;
            }
            for (Permanent permanent : game.getBattlefield().getAllActivePermanents(opponentId)) {
                if (isNoncreatureSpell(permanent, game)) {
                    add(score, permanent.getManaValue(), 1);
                }
            }
            for (Card card : opponent.getGraveyard().getCards(game)) {
                if (isNoncreatureSpell(card, game)) {
                    add(score, card.getManaValue(), 1);
                }
            }
        }
        for (Card card : controller.getHand().getCards(game)) {
            if (isNoncreatureSpell(card, game)) {
                add(score, card.getManaValue(), -1);
            }
        }
        for (Card card : controller.getLibrary().getCards(game)) {
            if (isNoncreatureSpell(card, game)) {
                add(score, card.getManaValue(), -1);
            }
        }
        int best = 2;
        for (int number = 0; number < score.length; number++) {
            if (score[number] > score[best]
                    || (score[number] == score[best] && Math.abs(number - 2) < Math.abs(best - 2))) {
                best = number;
            }
        }
        return best;
    }

    private static boolean isNoncreatureSpell(Card card, Game game) {
        return !card.isLand(game) && !card.isCreature(game);
    }

    private static void add(int[] score, int manaValue, int amount) {
        if (manaValue >= 0 && manaValue < score.length) {
            score[manaValue] += amount;
        }
    }

    @Override
    public ChooseNumberEffect copy() {
        return new ChooseNumberEffect(this);
    }
}

class SanctumPrelateReplacementEffect extends ContinuousRuleModifyingEffectImpl {

    SanctumPrelateReplacementEffect() {
        super(Duration.WhileOnBattlefield, Outcome.Detriment);
        staticText = "Noncreature spells with mana value equal to the chosen number can't be cast";
    }

    private SanctumPrelateReplacementEffect(final SanctumPrelateReplacementEffect effect) {
        super(effect);
    }

    @Override
    public SanctumPrelateReplacementEffect copy() {
        return new SanctumPrelateReplacementEffect(this);
    }

    @Override
    public String getInfoMessage(Ability source, GameEvent event, Game game) {
        MageObject mageObject = game.getObject(source);
        if (mageObject != null) {
            return "You can't cast a noncreature card with that mana value (" + mageObject.getIdName() + " in play).";
        }
        return null;
    }

    @Override
    public boolean checksEventType(GameEvent event, Game game) {
        return event.getType() == GameEvent.EventType.CAST_SPELL_LATE;
    }

    @Override
    public boolean applies(GameEvent event, Ability source, Game game) {
        Integer choiceValue = (Integer) game.getState().getValue(source.getSourceId().toString());
        Spell spell = game.getStack().getSpell(event.getTargetId());

        if (spell != null && !spell.isCreature(game) && choiceValue != null) {
            return spell.getManaValue() == choiceValue;
        }
        return false;
    }

}
