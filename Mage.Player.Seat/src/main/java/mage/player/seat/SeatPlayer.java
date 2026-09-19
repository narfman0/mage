package mage.player.seat;

import mage.ConditionalMana;
import mage.Mana;
import mage.abilities.Ability;
import mage.abilities.TriggeredAbility;
import mage.abilities.costs.Cost;
import mage.abilities.costs.common.TapSourceCost;
import mage.abilities.costs.mana.ColorlessHybridManaCost;
import mage.abilities.costs.mana.HybridManaCost;
import mage.abilities.costs.mana.ManaCost;
import mage.abilities.costs.mana.ManaCosts;
import mage.abilities.costs.mana.ManaCostsImpl;
import mage.abilities.costs.mana.MonoHybridManaCost;
import mage.abilities.costs.mana.SnowManaCost;
import mage.abilities.effects.Effect;
import mage.abilities.effects.mana.AddConditionalManaEffect;
import mage.abilities.effects.mana.ManaEffect;
import mage.abilities.mana.ActivatedManaAbilityImpl;
import mage.cards.Card;
import mage.cards.CardImpl;
import mage.constants.AbilityType;
import mage.abilities.costs.mana.ActivationManaAbilityStep;
import mage.constants.ColoredManaSymbol;
import mage.game.stack.Spell;
import mage.constants.RangeOfInfluence;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.permanent.Permanent;
import mage.player.human.HumanPlayer;
import mage.players.net.UserData;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import org.apache.log4j.Logger;
import java.util.Map;
import java.util.UUID;

/**
 * A human seat answered in-process. Everything a HumanPlayer does — the
 * priority stops, holding priority, combat loops, the response window — is
 * inherited; what changes is how mana is paid. The GUI client asks its
 * person to tap each source; a browser client wants the engine to tap for
 * it when nothing is at stake, and to ask when the choice is theirs
 * ({@link AutoPay}). Floating mana is spent from the pool before any
 * source is touched, so "tap your own mana, then cast" always wins.
 */
public class SeatPlayer extends HumanPlayer {

    private static final Logger LOG = Logger.getLogger(SeatPlayer.class);

    /** Pay costs from the clean sources; off, every payment is the engine's own prompt. */
    private boolean autoPay = true;

    /**
     * A person's seat: when the clean sources could pay in more than one way
     * that leaves different sources up, ask instead of choosing. A pilot's
     * seat takes the most flexible plan and is never asked (its driver
     * answers a mana prompt by cancelling).
     */
    private boolean askWhenAmbiguous = false;

    public SeatPlayer(String name, RangeOfInfluence range) {
        super(name, range, 0);
        UserData data = UserData.getDefaultUserDataView();
        // HumanPlayer.priority passes an empty-stack window itself unless the
        // step is one of the user's phase stops (checkPassStep), and the
        // defaults are the two mains plus the combat steps — so the seat never
        // saw a Begin Combat or End Turn window, and the product's own stops
        // for them (a tapper's window; flash at their end step; "pass until
        // X's end step") had nothing to act on (found 2026-09-19). Ask at
        // both, either turn: the server decides what to pass (its stops are
        // narrower than the engine's would be), and an empty window costs
        // nothing. Upkeep, draw and end of combat stay the engine's to pass.
        for (mage.players.net.SkipPrioritySteps turn : List.of(data.getUserSkipPrioritySteps().getYourTurn(), data.getUserSkipPrioritySteps().getOpponentTurn())) {
            turn.setBeforeCombat(true);
            turn.setEndOfTurn(true);
        }
        setUserData(data);
        setPassAfterOwnAction(true);
    }

    protected SeatPlayer(SeatPlayer player) {
        super(player);
        this.autoPay = player.autoPay;
        this.askWhenAmbiguous = player.askWhenAmbiguous;
    }

    @Override
    public SeatPlayer copy() {
        return new SeatPlayer(this);
    }

    public void setAutoPay(boolean autoPay) {
        this.autoPay = autoPay;
    }

    public void setAskWhenAmbiguous(boolean ask) {
        this.askWhenAmbiguous = ask;
    }

    /**
     * Pass priority after your own cast or non-mana activation instead of
     * being asked "respond to your own spell?" — XMage's own client defaults
     * to this ({@code passPriorityCast}/{@code passPriorityActivation} in its
     * preferences); {@link UserData#getDefaultUserDataView} does not, and a
     * seat that inherits that default is asked at every cast where it holds
     * an instant (owner decision, 2026-09-18: the window goes). Off under
     * the product's full control, which is how a person holds priority to
     * respond to their own spell. HumanPlayer.priority reads the flags from
     * the shared UserData, so this applies to every copy of the player.
     */
    public void setPassAfterOwnAction(boolean pass) {
        getUserData().setPassPriorityCast(pass);
        getUserData().setPassPriorityActivation(pass);
    }

    @Override
    protected boolean playManaHandling(Ability abilityToCast, ManaCost unpaid, String promptText, Game game) {
        // Convoke, delve, improvise, assist: an alternate way to pay that the
        // engine registers for this payment round and offers through the
        // prompt's "special" answer. For a person that makes the payment
        // theirs to choose (tap the Forest, or tap the Bear), so the silent
        // path is skipped; and nothing is ever cancelled for want of a source
        // while one of these can pay.
        boolean specialPayment = !game.getState().getSpecialActions().getControlledBy(playerId, true).isEmpty();
        if (autoPay && unpaid != null && !mustAsk(abilityToCast, game)) {
            Map<UUID, ActivatedManaAbilityImpl> abilities = new HashMap<>();
            Map<UUID, Permanent> permanents = new HashMap<>();
            List<AutoPay.Source> sources = sources(abilityToCast, game, askWhenAmbiguous, abilities, permanents);
            List<AutoPay.Pip> pips = pips(unpaid);
            AutoPay.Plan plan = AutoPay.plan(pips, sources);
            if (LOG.isDebugEnabled()) {
                LOG.debug("autopay unpaid=" + unpaid.getText() + " sources=" + sources.size() + " plan=" + plan);
            }
            if (plan.payable() && (!(plan.ambiguous() || specialPayment) || !askWhenAmbiguous) && !floatingPoolCanHelp(pips)) {
                AutoPay.Pick pick = plan.pick();
                ActivatedManaAbilityImpl ability = abilities.get(pick.output().abilityId());
                Permanent perm = permanents.get(pick.source().id());
                // The one ability, so the engine never asks "which" of a source's
                // several; a colour choice on the way is auto-picked by HumanPlayer
                // from currentlyUnpaidMana, so that is the one colour the plan wants.
                currentlyUnpaidMana = colourCost(pick.make(), unpaid);
                try {
                    activateAbility(Map.of(ability.getId(), ability), perm, game);
                } finally {
                    currentlyUnpaidMana = null;
                }
                return true;
            }
            if (!plan.payable() && !specialPayment && !anySource(abilityToCast, game) && getManaPool().count() == 0) {
                // Nothing on the board can pay: cancel the way a person would, with a word.
                game.informPlayer(this, "Couldn't pay " + Fmt.stripHtml(promptText) + ": no mana source can pay it");
                return false;
            }
        }
        return super.playManaHandling(abilityToCast, unpaid, promptText, game);
    }

    /**
     * The prompt's "special" answer: one special action is activated as is
     * (the Swing client's one-entry picker is a tap for nothing); several
     * are the engine's own picker, as upstream.
     */
    @Override
    protected void activateSpecialAction(Game game, ManaCost unpaidForManaAction) {
        Map<UUID, mage.abilities.SpecialAction> actions = game.getState().getSpecialActions().getControlledBy(playerId, unpaidForManaAction != null);
        if (actions.size() == 1) {
            activateAbility(actions.values().iterator().next(), game);
            return;
        }
        super.activateSpecialAction(game, unpaidForManaAction);
    }

    /**
     * Whether the still-floating pool has anything that could pay part of
     * what is left: spending it is always the player's own click on the
     * mana prompt's pool button, never silent and never forced (docs/
     * board-ui.md "Paying with several sources") — so AutoPay's silent
     * tap-a-source path must not preempt that click just because the board
     * alone can already cover the rest. Report ed184e0607 (2026-09-18):
     * Selvala floated six mana in three colours, two colours' worth were
     * spent through the prompt, and the remaining {@code {1}} of an
     * unrelated ability got tapped from untouched lands without ever
     * asking again — the four other floating mana sat unused because
     * {@link #sources} only looks at permanents, never the pool.
     */
    private boolean floatingPoolCanHelp(List<AutoPay.Pip> pips) {
        if (pips == null || getManaPool().isEmpty()) {
            return false;
        }
        Mana floating = getManaPool().getMana();
        for (AutoPay.Pip pip : pips) {
            EnumSet<AutoPay.Kind> accepts = pip.accepts();
            if ((accepts.contains(AutoPay.Kind.W) && floating.getWhite() > 0)
                    || (accepts.contains(AutoPay.Kind.U) && floating.getBlue() > 0)
                    || (accepts.contains(AutoPay.Kind.B) && floating.getBlack() > 0)
                    || (accepts.contains(AutoPay.Kind.R) && floating.getRed() > 0)
                    || (accepts.contains(AutoPay.Kind.G) && floating.getGreen() > 0)
                    || (accepts.contains(AutoPay.Kind.C) && floating.getColorless() > 0)) {
                return true;
            }
        }
        return false;
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

    /**
     * The mana still owed as pips the planner understands: a colour, a
     * hybrid pair, {C}, or generic. Costs the planner doesn't model (snow,
     * {2/W}) make the whole payment the engine's prompt — rare, and asking
     * is the safe side.
     */
    static List<AutoPay.Pip> pips(ManaCost unpaid) {
        List<AutoPay.Pip> out = new ArrayList<>();
        List<ManaCost> parts = new ArrayList<>();
        flatten(unpaid, parts);
        for (ManaCost part : parts) {
            if (part.isPaid()) {
                continue;
            }
            if (part instanceof SnowManaCost || part instanceof MonoHybridManaCost) {
                return null;
            }
            if (part instanceof HybridManaCost h) {
                if (kind(h.getMana1()) == null || kind(h.getMana2()) == null) {
                    return null;
                }
                out.add(AutoPay.Pip.of(kind(h.getMana1()), kind(h.getMana2())));
                continue;
            }
            if (part instanceof ColorlessHybridManaCost ch) {
                if (kind(ch.getManaColor()) == null) {
                    return null;
                }
                out.add(AutoPay.Pip.of(AutoPay.Kind.C, kind(ch.getManaColor())));
                continue;
            }
            Mana m = part.getMana();
            if (m == null || m.getAny() > 0) {
                return null;
            }
            for (int i = 0; i < m.getWhite(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.W));
            for (int i = 0; i < m.getBlue(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.U));
            for (int i = 0; i < m.getBlack(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.B));
            for (int i = 0; i < m.getRed(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.R));
            for (int i = 0; i < m.getGreen(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.G));
            for (int i = 0; i < m.getColorless(); i++) out.add(AutoPay.Pip.of(AutoPay.Kind.C));
            for (int i = 0; i < m.getGeneric(); i++) out.add(AutoPay.Pip.generic());
        }
        return out;
    }

    private static void flatten(ManaCost cost, List<ManaCost> into) {
        if (cost instanceof ManaCosts<?> many) {
            for (ManaCost c : many) {
                flatten(c, into);
            }
        } else {
            into.add(cost);
        }
    }

    private static AutoPay.Kind kind(ColoredManaSymbol s) {
        return switch (s) {
            case W -> AutoPay.Kind.W;
            case U -> AutoPay.Kind.U;
            case B -> AutoPay.Kind.B;
            case R -> AutoPay.Kind.R;
            case G -> AutoPay.Kind.G;
            default -> null; // gold, a symbol no card uses; the pip is not modelled
        };
    }

    /** "{U}" for a plan that wants blue from a source with a choice; the real unpaid cost for a fixed kind. */
    private static ManaCost colourCost(AutoPay.Kind make, ManaCost unpaid) {
        return switch (make) {
            case W -> new ManaCostsImpl<>("{W}");
            case U -> new ManaCostsImpl<>("{U}");
            case B -> new ManaCostsImpl<>("{B}");
            case R -> new ManaCostsImpl<>("{R}");
            case G -> new ManaCostsImpl<>("{G}");
            default -> unpaid;
        };
    }

    /**
     * The untapped sources the payer may plan with. A tap-only mana ability
     * (no mana, sacrifice, life or counter cost) never spends anything for
     * you; an ability with such a cost is nobody's to use but the player.
     * A source is <em>clean</em> when nothing but the mana happens: no
     * effect beyond it (Ancient Tomb's damage), no becomes-tapped trigger
     * (City of Brass), not a creature, no attachment, and unrestricted mana
     * (a Powerstone's is never planned with — its condition isn't
     * modelled). A person's seat ({@code cleanOnly}) plans over clean
     * sources only: the rest are theirs to tap, at priority or in the
     * prompt. A pilot has no one to ask, so it plans over every tap-only
     * source and prefers the clean ones.
     */
    private List<AutoPay.Source> sources(Ability abilityToCast, Game game, boolean cleanOnly, Map<UUID, ActivatedManaAbilityImpl> abilities, Map<UUID, Permanent> permanents) {
        List<AutoPay.Source> sources = new ArrayList<>();
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (abilityToCast != null && perm.getId().equals(abilityToCast.getSourceId())) {
                continue;
            }
            if (perm.isTapped()) {
                continue;
            }
            boolean clean = cleanPermanent(perm, game);
            if (cleanOnly && !clean) {
                continue;
            }
            List<AutoPay.Output> outputs = new ArrayList<>();
            for (ActivatedManaAbilityImpl ability : getUseableManaAbilities(perm, Zone.BATTLEFIELD, game).values()) {
                if (ability.getAbilityType() != AbilityType.ACTIVATED_MANA || ability.isPoolDependant() || !tapOnly(ability) || conditional(ability)) {
                    continue;
                }
                if (!cleanAbility(ability)) {
                    if (cleanOnly) {
                        continue;
                    }
                    clean = false;
                }
                for (Mana m : ability.getNetMana(game)) {
                    if (m instanceof ConditionalMana) {
                        continue;
                    }
                    List<AutoPay.Kind> units = AutoPay.units(m.getWhite(), m.getBlue(), m.getBlack(), m.getRed(), m.getGreen(), m.getColorless(), m.getAny());
                    if (!units.isEmpty()) {
                        outputs.add(new AutoPay.Output(ability.getId(), units));
                        abilities.put(ability.getId(), ability);
                    }
                }
            }
            if (outputs.isEmpty()) {
                continue;
            }
            // A permanent with another activated ability (a Castle, a manland,
            // Crystal Vein's sacrifice, Mind Stone's draw) is not the same as
            // a basic that makes the same mana: keep it its own group.
            boolean more = perm.getAbilities().getActivatedAbilities(Zone.BATTLEFIELD).size() > outputs.stream().map(AutoPay.Output::abilityId).distinct().count();
            permanents.put(perm.getId(), perm);
            sources.add(new AutoPay.Source(perm.getId(), AutoPay.key(outputs, more ? perm.getName() : null, clean), outputs, clean));
        }
        return sources;
    }

    private static boolean cleanPermanent(Permanent perm, Game game) {
        if (perm.isCreature(game) || !perm.getAttachments().isEmpty()) {
            return false;
        }
        for (TriggeredAbility t : perm.getAbilities().getTriggeredAbilities(Zone.BATTLEFIELD)) {
            String rule = t.getRule().toLowerCase();
            if (rule.contains("becomes tapped") || rule.contains("tapped for mana")) {
                return false;
            }
        }
        return true;
    }

    /** Nothing but mana happens: every effect is a mana effect. */
    private static boolean cleanAbility(ActivatedManaAbilityImpl ability) {
        for (Effect effect : ability.getEffects()) {
            if (!(effect instanceof ManaEffect)) {
                return false;
            }
        }
        return true;
    }

    /** Mana with a condition on what it may pay for: not modelled, so never planned with. */
    private static boolean conditional(ActivatedManaAbilityImpl ability) {
        for (Effect effect : ability.getEffects()) {
            if (effect instanceof AddConditionalManaEffect) {
                return true;
            }
        }
        return false;
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
