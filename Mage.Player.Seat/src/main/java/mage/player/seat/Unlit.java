package mage.player.seat;

import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.ActivatedAbility;
import mage.abilities.ActivatedAbilityImpl;
import mage.abilities.PlayLandAbility;
import mage.abilities.SpellAbility;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.mana.ManaOptions;
import mage.cards.Card;
import mage.cards.CardWithSpellOption;
import mage.cards.ModalDoubleFacedCard;
import mage.cards.SplitCard;
import mage.cards.TransformingDoubleFacedCard;
import mage.constants.AsThoughEffectType;
import mage.constants.SpellAbilityType;
import mage.constants.TimingRule;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.players.Player;
import mage.players.PlayableObjectsList;
import mage.view.GameView;
import mage.view.PlayerView;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Why an unlit card is unlit. The engine leaves an object out of
 * {@code canPlayObjects} and never says why: {@code canActivate} answers
 * with an {@link mage.abilities.ActivationStatus} (yes/no plus the
 * permitting object), so XMage's own client can't say either. The owner's
 * case (2026-09-18) was Survival of the Fittest with {@code {G}} up and no
 * creature card in hand — right, and unexplainable.
 * <p>
 * So the reason is derived here, from the same checks {@code canActivate}
 * and {@code getPlayable} make, in the order the engine makes them — cheap
 * first: timing, a {@code {T}} cost on a tapped or sick source, a non-mana
 * cost that can't be paid, mana, then a restriction. One reason per object:
 * the first that fails. It is a reading, never a control — nothing the seat
 * offers, and nothing the engine is asked to act on.
 * <p>
 * Cost: the ladder runs on a simulation copy of the game (a playable
 * calculation must never touch the running one), for the seat's own unlit
 * objects only, once per render, and only at a priority window
 * ({@link DecisionRenderer}).
 */
public final class Unlit {

    private static final Logger LOG = Logger.getLogger(Unlit.class);

    /** Nothing above it failed, so an effect, a condition or a target does. */
    static final String GENERIC = "an effect or a condition stops it";

    private Unlit() {
    }

    /** The ladder, in the order the engine checks. The last one reached is the reason. */
    private enum Stage {
        TIMING, TAP, COST, MANA, RESTRICTION
    }

    private record Reason(Stage stage, String text) {
    }

    /** One way an object could be played, with the name to give it when there are several. */
    private record Candidate(ActivatedAbility ability, String label) {
    }

    /**
     * The seat's own unlit objects as {@code objectId -> {reason, ability?}}:
     * every hand card, and every battlefield permanent with a non-mana
     * activated ability, that {@code canPlayObjects} leaves out. {@code
     * ability} names which ability the reason is about, and only when the
     * object offered several.
     */
    public static Map<UUID, Map<String, Object>> reasons(Game realGame, SeatPlayer player, GameView view) {
        Map<UUID, Map<String, Object>> out = new HashMap<>();
        if (view == null) {
            return out;
        }
        try {
            PlayableObjectsList playable = view.getCanPlayObjects();
            List<UUID> unlit = new ArrayList<>();
            if (view.getMyHand() != null) {
                unlit.addAll(view.getMyHand().keySet());
            }
            PlayerView myView = view.getMyPlayer();
            if (myView != null && myView.getBattlefield() != null) {
                unlit.addAll(myView.getBattlefield().keySet());
            }
            unlit.removeIf(id -> playable != null && playable.containsObject(id));
            if (unlit.isEmpty()) {
                return out;
            }
            // The game must be immutable under a playable calculation, and
            // asking a cost whether it can be paid is one: work on the copy
            // PlayerImpl.getPlayable works on, made once for the whole render.
            Game game = realGame.createSimulationForPlayableCalc();
            Player seat = game.getPlayer(player.getId());
            if (!(seat instanceof SeatPlayer)) {
                return out;
            }
            ManaOptions available = seat.getManaAvailable(game);
            for (UUID id : unlit) {
                Map<String, Object> reason = reasonFor(game, (SeatPlayer) seat, id, available);
                if (reason != null) {
                    out.put(id, reason);
                }
            }
        } catch (RuntimeException ex) {
            // A reading, never a control: a game is never lost over one.
            LOG.warn("unlit reasons unavailable: " + ex, ex);
            return new HashMap<>();
        }
        return out;
    }

    private static Map<String, Object> reasonFor(Game game, SeatPlayer seat, UUID objectId, ManaOptions available) {
        List<Candidate> candidates = candidates(game, seat.getId(), objectId);
        if (candidates.isEmpty()) {
            return null;
        }
        Permanent source = game.getPermanent(objectId);
        Candidate best = null;
        Reason reason = null;
        for (Candidate candidate : candidates) {
            Reason r = ladder(game, seat, candidate.ability(), source, available);
            // The one that got furthest: what is closest to being playable is
            // what a person can act on ("needs {1}{G}" beats "sorcery speed"
            // when the other half of a split card could be cast now).
            if (reason == null || r.stage().ordinal() > reason.stage().ordinal()) {
                reason = r;
                best = candidate;
            }
        }
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("reason", reason.text());
        if (candidates.size() > 1 && best.label() != null && !best.label().isEmpty()) {
            info.put("ability", best.label());
        }
        return info;
    }

    /**
     * What this object could be played as. A permanent: its non-mana
     * activated abilities (a permanent with none is quiet, not unlit). A
     * card in hand: the cast or the land play of each of its parts — a
     * split card's halves, an adventure's two spells.
     */
    private static List<Candidate> candidates(Game game, UUID me, UUID objectId) {
        List<Candidate> out = new ArrayList<>();
        Permanent permanent = game.getPermanent(objectId);
        if (permanent != null) {
            if (!permanent.isControlledBy(me)) {
                return out;
            }
            for (Ability ability : permanent.getAbilities(game)) {
                if (ability instanceof ActivatedAbility && !ability.isManaActivatedAbility()
                        && ability.getZone().match(Zone.BATTLEFIELD)) {
                    out.add(new Candidate((ActivatedAbility) ability, Fmt.stripHtml(ability.getRule())));
                }
            }
            return out;
        }
        Card card = game.getCard(objectId);
        if (card == null || !card.isOwnedBy(me)) {
            return out;
        }
        for (Card part : parts(card)) {
            ActivatedAbility play = playAbility(game, part);
            if (play != null) {
                out.add(new Candidate(play, part.getName()));
            }
        }
        return out;
    }

    /** The halves the engine casts separately: split, modal DFC, adventure. */
    private static List<Card> parts(Card card) {
        if (card instanceof SplitCard) {
            return List.of(((SplitCard) card).getLeftHalfCard(), ((SplitCard) card).getRightHalfCard());
        }
        if (card instanceof ModalDoubleFacedCard) {
            return List.of(((ModalDoubleFacedCard) card).getLeftHalfCard(), ((ModalDoubleFacedCard) card).getRightHalfCard());
        }
        if (card instanceof TransformingDoubleFacedCard) {
            // The back face is never cast: only the front is a play.
            return List.of(((TransformingDoubleFacedCard) card).getLeftHalfCard());
        }
        if (card instanceof CardWithSpellOption) {
            return List.of(card, ((CardWithSpellOption) card).getSpellCard());
        }
        return List.of(card);
    }

    /** How this card is played from hand: its land play, else its spell. */
    private static ActivatedAbility playAbility(Game game, Card card) {
        for (Ability ability : card.getAbilities(game)) {
            if (ability instanceof PlayLandAbility && ability.getZone().match(Zone.HAND)) {
                return (ActivatedAbility) ability;
            }
        }
        SpellAbility spell = card.getSpellAbility();
        if (spell == null || spell.getSpellAbilityType() == SpellAbilityType.SPLIT
                || spell.getSpellAbilityType() == SpellAbilityType.SPLIT_AFTERMATH
                || spell.getSpellAbilityType() == SpellAbilityType.TRANSFORMED_RIGHT) {
            // The whole-card ability of a split or an adventure is never cast
            // itself; its halves are, and they are their own candidates.
            return null;
        }
        return spell;
    }

    /**
     * The first check this ability fails, in the engine's own order. A
     * mana cost is asked last because it is the dearest: {@code ManaOptions}
     * against the cost the reductions leave.
     */
    private static Reason ladder(Game game, SeatPlayer seat, ActivatedAbility ability, Permanent source, ManaOptions available) {
        UUID me = seat.getId();
        // Cost reduction and a dynamic X, the way getPlayable applies them.
        ActivatedAbility copy = ability.copy();
        copy.adjustX(game);
        game.getContinuousEffects().costModification(copy, game);

        // 1. Timing. A land is its own sentence: the drop is spent, or it isn't your main.
        if (ability instanceof PlayLandAbility) {
            Player player = game.getPlayer(me);
            if (player != null && player.getLandsPlayed() >= player.getLandsPerTurn()) {
                return new Reason(Stage.TIMING, "land already played this turn");
            }
        }
        if (sorcerySpeed(game, ability, me)) {
            return new Reason(Stage.TIMING, "sorcery speed");
        }

        // 2. A {T} cost the source can't pay: tapped, or a creature that arrived this turn.
        if (source != null) {
            for (Cost cost : copy.getCosts()) {
                if (cost instanceof TapSourceCost && !cost.canPay(copy, copy, me, game)) {
                    return new Reason(Stage.TAP, source.isTapped() ? "tapped" : "summoning sick");
                }
            }
        }

        // 3. A non-mana cost that can't be paid — the Survival answer.
        for (Cost cost : copy.getCosts()) {
            if (cost instanceof TapSourceCost) {
                continue;
            }
            if (!cost.canPay(copy, copy, me, game)) {
                String text = Fmt.stripHtml(cost.getText());
                return new Reason(Stage.COST, text == null || text.isEmpty()
                        ? "a cost you can't pay" : "can't pay: " + text);
            }
        }

        // 4. Mana: what it costs now against what the seat could make.
        if (!seat.canAffordMana(copy, available, game)) {
            return new Reason(Stage.MANA, "needs " + costText(copy) + ", you can make " + manaText(available));
        }

        // 5. A restriction: the per-turn limit, a condition, a target it has none for,
        // an effect that forbids it. The engine names none of them; its own rules
        // hints (`hints`) are what render beside this.
        if (ability instanceof ActivatedAbilityImpl
                && ((ActivatedAbilityImpl) ability).getMaxMoreActivationsThisTurn(game) == 0) {
            return new Reason(Stage.RESTRICTION, "no activations left this turn");
        }
        return new Reason(Stage.RESTRICTION, GENERIC);
    }

    /**
     * Sorcery speed and this isn't a moment for it. A spell asks the engine
     * itself ({@code spellCanBeActivatedNow} folds in flash and every
     * cast-as-instant effect); an activated ability is its {@link TimingRule}
     * plus the same asThough check {@code canActivate} makes.
     */
    private static boolean sorcerySpeed(Game game, ActivatedAbility ability, UUID me) {
        if (game.canPlaySorcery(me)) {
            return false;
        }
        if (ability instanceof SpellAbility) {
            return ((SpellAbility) ability).spellCanBeActivatedNow(me, game).isEmpty();
        }
        if (!(ability instanceof ActivatedAbilityImpl)
                || ((ActivatedAbilityImpl) ability).getTiming() != TimingRule.SORCERY) {
            return false;
        }
        return game.getContinuousEffects()
                .asThough(ability.getSourceId(), AsThoughEffectType.ACTIVATE_AS_INSTANT, ability, me, game)
                .isEmpty();
    }

    /** What the ability costs to activate right now, reductions applied. */
    private static String costText(ActivatedAbility ability) {
        String text = ability.getManaCostsToPay().getText();
        return text == null || text.isEmpty() ? "mana" : text;
    }

    /** The most mana the seat could make in one of its ways to make it. */
    private static String manaText(ManaOptions available) {
        Mana best = null;
        for (Mana option : available) {
            if (best == null || option.count() > best.count()) {
                best = option;
            }
        }
        return best == null || best.count() == 0 ? "nothing" : best.toString();
    }
}
