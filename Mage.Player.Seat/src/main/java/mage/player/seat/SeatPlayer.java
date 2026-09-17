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
        setUserData(UserData.getDefaultUserDataView());
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

    @Override
    protected boolean playManaHandling(Ability abilityToCast, ManaCost unpaid, String promptText, Game game) {
        if (autoPay && unpaid != null && !mustAsk(abilityToCast, game)) {
            Map<UUID, ActivatedManaAbilityImpl> abilities = new HashMap<>();
            Map<UUID, Permanent> permanents = new HashMap<>();
            List<AutoPay.Source> sources = cleanSources(abilityToCast, game, abilities, permanents);
            List<AutoPay.Pip> pips = pips(unpaid);
            AutoPay.Plan plan = AutoPay.plan(pips, sources);
            if (LOG.isDebugEnabled()) {
                LOG.debug("autopay unpaid=" + unpaid.getText() + " sources=" + sources.size() + " plan=" + plan);
            }
            if (plan.payable() && (!plan.ambiguous() || !askWhenAmbiguous)) {
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
            if (!plan.payable() && !anySource(abilityToCast, game) && getManaPool().count() == 0) {
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
     * The untapped sources the payer may use for a person: a plain
     * tap-for-mana ability with no effect beyond the mana, on a permanent
     * that is not a creature, wears no attachment, has no becomes-tapped
     * trigger, and makes unrestricted mana. Anything else — a sacrifice or
     * life cost (Crystal Vein's second ability, Mana Confluence), Ancient
     * Tomb's damage, City of Brass's trigger, a Powerstone's conditional
     * mana, a mana creature — is the person's call, made by tapping it
     * themselves or in the prompt.
     */
    private List<AutoPay.Source> cleanSources(Ability abilityToCast, Game game, Map<UUID, ActivatedManaAbilityImpl> abilities, Map<UUID, Permanent> permanents) {
        List<AutoPay.Source> sources = new ArrayList<>();
        for (Permanent perm : game.getBattlefield().getAllActivePermanents(playerId)) {
            if (abilityToCast != null && perm.getId().equals(abilityToCast.getSourceId())) {
                continue;
            }
            if (perm.isTapped() || !cleanPermanent(perm, game)) {
                continue;
            }
            List<AutoPay.Output> outputs = new ArrayList<>();
            for (ActivatedManaAbilityImpl ability : getUseableManaAbilities(perm, Zone.BATTLEFIELD, game).values()) {
                if (!cleanAbility(ability)) {
                    continue;
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
            sources.add(new AutoPay.Source(perm.getId(), AutoPay.key(outputs, more ? perm.getName() : null), outputs));
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

    private static boolean cleanAbility(ActivatedManaAbilityImpl ability) {
        if (ability.getAbilityType() != AbilityType.ACTIVATED_MANA || ability.isPoolDependant() || !tapOnly(ability)) {
            return false;
        }
        for (Effect effect : ability.getEffects()) {
            if (!(effect instanceof ManaEffect) || effect instanceof AddConditionalManaEffect) {
                return false;
            }
        }
        return true;
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
