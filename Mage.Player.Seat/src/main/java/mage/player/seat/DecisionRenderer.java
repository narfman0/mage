package mage.player.seat;

import mage.MageObject;
import mage.abilities.Ability;
import mage.cards.Card;
import mage.choices.Choice;
import mage.constants.ManaType;
import mage.constants.Zone;
import mage.game.Game;
import mage.game.events.PlayerQueryEvent;
import mage.game.permanent.Permanent;
import mage.players.PlayableObjectStats;
import mage.players.PlayableObjectsList;
import mage.util.MultiAmountMessage;
import mage.view.CardView;
import mage.view.CardsView;
import mage.view.CombatGroupView;
import mage.view.CommanderView;
import mage.view.GameView;
import mage.view.ManaPoolView;
import mage.view.PermanentView;
import mage.view.PlayerView;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns a PlayerQueryEvent — the engine's own question, with the objects
 * behind it — into the decision JSON mage-bench's bridge published
 * (action_type, response_type, choices with index/id/name, combat_phase,
 * board, stack, ...). The product's bridge_protocol.py reads exactly these
 * fields, so nothing downstream changes. Ported from
 * BridgePublishedQueryBuilder (MIT, Gregor Stocks); the difference is the
 * input: engine objects rather than a client message re-parsed.
 */
public final class DecisionRenderer {

    private static final org.apache.log4j.Logger LOG = org.apache.log4j.Logger.getLogger(DecisionRenderer.class);

    private static final Pattern[] SYMBOLS = {
        Pattern.compile("\\x7b.{0,2}W.{0,2}\\x7d"), Pattern.compile("\\x7b.{0,2}U.{0,2}\\x7d"),
        Pattern.compile("\\x7b.{0,2}B.{0,2}\\x7d"), Pattern.compile("\\x7b.{0,2}R.{0,2}\\x7d"),
        Pattern.compile("\\x7b.{0,2}G.{0,2}\\x7d"), Pattern.compile("\\x7b.{0,2}C.{0,2}\\x7d"),
    };
    private static final ManaType[] SYMBOL_TYPES = {
        ManaType.WHITE, ManaType.BLUE, ManaType.BLACK, ManaType.RED, ManaType.GREEN, ManaType.COLORLESS,
    };
    /** A pip any colour pays: generic, or twobrid ({2/W}: two of anything or one white). */
    private static final Pattern GENERIC = Pattern.compile("\\x7b\\d+(?:/[WUBRG])?\\x7d");

    private final Views views;

    public DecisionRenderer(Views views) {
        this.views = views;
    }

    /** A view of the game as this seat sees it, with what it may play right now. */
    public GameView viewFor(Game game, SeatPlayer player) {
        GameView view = new GameView(game.getState(), game, player.getId(), null);
        view.setCanPlayObjects(player.getPlayableObjects(game, Zone.ALL));
        return view;
    }

    /** Context, board, stack and combat: the part of every result that isn't the question. */
    public Map<String, Object> situation(Game game, SeatPlayer player, GameView view) {
        return situation(game, player, view, Map.of());
    }

    /**
     * The same, with why each of the seat's unlit objects is unlit
     * ({@link Unlit}) — computed once per render and only at a priority
     * window, so it rides here rather than being derived per card.
     */
    public Map<String, Object> situation(Game game, SeatPlayer player, GameView view, Map<UUID, Map<String, Object>> unlit) {
        Map<String, Object> r = new LinkedHashMap<>();
        UUID me = player.getId();
        boolean myTurn = me.equals(game.getActivePlayerId());
        boolean mainPhase = view.getPhase() != null && view.getPhase().isMain();
        StringBuilder ctx = new StringBuilder("T").append(game.getTurnNum());
        if (view.getPhase() != null) {
            ctx.append(' ').append(view.getPhase());
        }
        if (view.getStep() != null) {
            ctx.append('/').append(view.getStep());
        }
        ctx.append(" (").append(view.getActivePlayerName()).append(')');
        if (myTurn && mainPhase) {
            ctx.append(" YOUR_MAIN");
        }
        r.put("context", ctx.toString());
        r.put("board", views.players(view, me, game, unlit));
        List<Map<String, Object>> stack = views.stackItems(view, me, false);
        if (!stack.isEmpty()) {
            r.put("stack", stack);
        }
        List<Map<String, Object>> combat = views.combatGroups(view);
        if (combat != null) {
            r.put("combat", combat);
        }
        List<Map<String, Object>> revealed = views.revealed(view, game);
        if (!revealed.isEmpty()) {
            r.put("revealed", revealed);
        }
        List<Map<String, Object>> lookedAt = views.lookedAt(view, game);
        if (!lookedAt.isEmpty()) {
            r.put("looked_at", lookedAt);
        }
        PlayerView myView = view.getMyPlayer();
        if (myView != null && myView.getBattlefield() != null) {
            int untapped = 0;
            for (PermanentView perm : myView.getBattlefield().values()) {
                if (perm.isLand() && !perm.isTapped()) {
                    untapped++;
                }
            }
            if (untapped > 0) {
                r.put("untapped_lands", untapped);
            }
            if (myTurn && mainPhase) {
                r.put("land_drops_used", myView.getLandsPlayed());
            }
        }
        return r;
    }

    public Decision render(Game game, SeatPlayer player, PlayerQueryEvent e, int seq, boolean offerManaSources) {
        GameView view = viewFor(game, player);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("action_pending", true);
        r.put("game_seq", seq);
        r.put("message", Fmt.stripHtml(e.getMessage()));
        // Why the unlit cards are unlit: only at a priority window (a combat
        // window offers creatures, not plays), only for this seat's objects,
        // once for the whole render.
        Map<UUID, Map<String, Object>> unlit = e.getQueryType() == PlayerQueryEvent.QueryType.SELECT && isPriorityWindow(e)
                ? Unlit.reasons(game, player, view)
                : Map.of();
        r.putAll(situation(game, player, view, unlit));
        List<Object> backing = switch (e.getQueryType()) {
            case ASK -> ask(r, e, view, game);
            case SELECT -> select(r, e, game, view, player, offerManaSources);
            case PICK_TARGET -> target(r, e, game, view, player.getId());
            case PICK_ABILITY -> pickAbility(r, e, game, view, player.getId());
            case PLAY_MANA, PLAY_X_MANA -> mana(r, e, game, view, player.getId());
            case CHOOSE_ABILITY -> abilityPicker(r, e, game);
            case CHOOSE_MODE -> modes(r, e);
            case CHOOSE_CHOICE -> choice(r, e);
            case CHOOSE_PILE -> pile(r, e, game);
            case AMOUNT -> amount(r, e, game, player);
            case MULTI_AMOUNT -> multiAmount(r, e);
            default -> {
                r.put("action_type", e.getQueryType().name());
                r.put("response_type", "unknown");
                r.put("error", "Unhandled query type: " + e.getQueryType());
                yield List.of();
            }
        };
        return new Decision(seq, e, r, backing);
    }

    private List<Object> ask(Map<String, Object> r, PlayerQueryEvent e, GameView view, Game game) {
        r.put("action_type", "GAME_ASK");
        r.put("response_type", "boolean");
        Map<String, Serializable> options = e.getOptions();
        if (options != null) {
            Object yes = options.get("UI.left.btn.text");
            Object no = options.get("UI.right.btn.text");
            if (yes != null && no != null) {
                r.put("yes_text", Fmt.stripHtml(yes.toString()));
                r.put("no_text", Fmt.stripHtml(no.toString()));
            }
            // The two keys an answer is remembered by: the asking ability
            // ({@code originalId}, absent on a mulligan and anything else with
            // no source) and the question with the source's name replaced by
            // {this}, so "always gain the life" can hold for this Soul's
            // Attendant or for every card that asks it (docs/board-ui.md
            // "Remembered answers"). The seat stores neither: choose_action's
            // remember= hands them back to the engine's own memory.
            Object originalId = options.get("originalId");
            if (originalId != null) {
                r.put("original_id", originalId.toString());
            }
            Object autoAnswer = options.get("autoAnswerMessage");
            if (autoAnswer != null) {
                r.put("auto_answer_text", Fmt.stripHtml(autoAnswer.toString()));
            }
        }
        source(r, e, game);
        String msg = e.getMessage();
        if (msg != null && msg.toLowerCase().contains("mulligan") && view.getMyHand() != null && !view.getMyHand().isEmpty()) {
            List<CardView> hand = new ArrayList<>(view.getMyHand().values());
            hand.sort(Comparator.comparing(views::displayName));
            List<Map<String, Object>> cards = new ArrayList<>();
            for (CardView card : hand) {
                cards.add(views.cardInfo(card));
            }
            r.put("your_hand", cards);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private List<Object> select(Map<String, Object> r, PlayerQueryEvent e, Game game, GameView view, SeatPlayer player, boolean offerManaSources) {
        UUID me = player.getId();
        r.put("action_type", "GAME_SELECT");
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        PlayableObjectsList playable = view.getCanPlayObjects();
        if (playable != null && !playable.isEmpty()) {
            List<Map.Entry<UUID, PlayableObjectStats>> sorted = new ArrayList<>(playable.getObjects().entrySet());
            sorted.sort(Comparator.<Map.Entry<UUID, PlayableObjectStats>, String>comparing(entry -> {
                CardView cv = views.findCardView(entry.getKey(), view);
                return cv != null ? views.displayName(cv) : "";
            }).thenComparingInt(entry -> views.sequence(entry.getKey())));
            for (Map.Entry<UUID, PlayableObjectStats> entry : sorted) {
                UUID objectId = entry.getKey();
                // A face of a double-faced or split card is its own object to the
                // engine, keyed beside the main card that carries the same plays;
                // the views know only the main card, so the face showed as
                // "Unknown (3cc3a2fd) (activate)" (a report, 2026-09-17).
                Card asCard = game.getCard(objectId);
                if (asCard != null && !asCard.getMainCard().getId().equals(objectId)) {
                    continue;
                }
                PlayableObjectStats stats = entry.getValue();
                List<String> abilityNames = stats.getPlayableAbilityNames();
                List<String> manaNames = stats.getAllManaAbilityNames();
                boolean manaOnly = !abilityNames.isEmpty() && manaNames.size() == abilityNames.size();
                if (manaOnly && !offerManaSources) {
                    continue;
                }
                CardView cv = views.findCardView(objectId, view);
                // The revealed top of a library (Courser's land drop, Bolas's
                // Citadel's cast): a real play, from there. So is a card in the
                // graveyard (flashback, escape, unearth, Muldrotha) or in exile (an
                // adventure's creature, foretell, an impulse draw's "you may play
                // it this turn"): a cast or a land play, from there, not a bare
                // "activate" (the engine-vs-product pass, 2026-09-18).
                Zone objectZone = game.getState().getZone(objectId);
                boolean fromLibrary = cv != null && objectZone == Zone.LIBRARY;
                boolean fromClosed = cv != null && (objectZone == Zone.GRAVEYARD || objectZone == Zone.EXILED);
                Map<String, Object> c = new HashMap<>();
                c.put("index", choices.size());
                c.put("id", views.shortId(objectId));
                if (fromLibrary) {
                    c.put("from", "library");
                } else if (fromClosed) {
                    c.put("from", objectZone == Zone.GRAVEYARD ? "graveyard" : "exile");
                }
                if (cv != null) {
                    c.put("name", views.displayName(cv));
                } else {
                    // Not in any zone the views render: name it from the game and say
                    // where it is, and log it — every one of these is a gap to close.
                    MageObject obj = game.getObject(objectId);
                    Zone zone = game.getState().getZone(objectId);
                    c.put("name", obj != null ? obj.getName() : "Unknown (" + objectId.toString().substring(0, 8) + ")");
                    if (zone != null) {
                        c.put("zone", zone.name().toLowerCase());
                    }
                    LOG.warn("playable object not in the view: " + objectId + " " + (obj != null ? obj.getClass().getSimpleName() + " " + obj.getName() : "?")
                            + " zone=" + zone + " abilities=" + abilityNames);
                }
                if (manaOnly) {
                    // A permanent whose only plays are mana abilities: choosing it taps
                    // it and the mana floats until spent — tapping your own lands first.
                    c.put("action", "mana");
                    c.put("mana_abilities", new ArrayList<>(manaNames));
                } else if (cv instanceof CommanderView && views.findPermanentView(objectId, view) == null) {
                    // The commander in the command zone: casting it is the play,
                    // and the tax is part of what it costs right now.
                    c.put("action", "cast");
                    c.put("from", "command");
                    String manaCost = cv.getManaCostStr();
                    if (manaCost != null && !manaCost.isEmpty()) {
                        c.put("mana_cost", manaCost);
                    }
                    if (cv.isCreature() && cv.getPower() != null) {
                        c.put("power", cv.getPower());
                        c.put("toughness", cv.getToughness());
                    }
                } else if (cv == null || (view.getMyHand().get(objectId) == null && view.getStack().get(objectId) == null && !fromLibrary && !fromClosed)
                        || (!stats.hasCast() && !stats.hasBasicPlay())) {
                    // Not castable or playable as a land from where it is — a hand
                    // card whose play is a granted ability (Satoru's ninjutsu on a
                    // Blightsteel Colossus, cycling) is an activation, not a
                    // twelve-mana cast.
                    c.put("action", "activate");
                    Set<String> manaSet = new HashSet<>(manaNames);
                    List<String> nonMana = new ArrayList<>();
                    for (String name : abilityNames) {
                        if (!manaSet.contains(name)) {
                            nonMana.add(name);
                        }
                    }
                    if (!nonMana.isEmpty()) {
                        c.put("playable_abilities", nonMana);
                    }
                } else {
                    c.put("action", cv.isLand() ? "land" : "cast");
                    String manaCost = cv.getManaCostStr();
                    // From the graveyard or exile the printed cost is rarely what is
                    // paid: the way it is cast from there — "Flashback {2}{U}",
                    // "Escape—{2}{B}{B}, Exile five other cards" — rides as `ability`
                    // (the first non-mana, non-basic-cast play), in the cost's place.
                    String how = fromClosed ? alternativeCast(abilityNames, manaNames) : null;
                    if (fromClosed && how == null && asCard != null) {
                        // The card's own cost: the view joins an adventure's two halves
                        // ("{2}{R}{*}{1}{R}"), and from exile only the creature is cast.
                        manaCost = asCard.getManaCost().getText();
                    }
                    if (how != null) {
                        c.put("ability", how);
                    } else if (manaCost != null && !manaCost.isEmpty()) {
                        c.put("mana_cost", manaCost);
                    }
                    if (cv.isCreature() && cv.getPower() != null) {
                        c.put("power", cv.getPower());
                        c.put("toughness", cv.getToughness());
                    }
                }
                choices.add(c);
                backing.add(objectId);
            }
        }
        Map<String, Serializable> options = e.getOptions();
        if (options != null) {
            List<UUID> attackers = (List<UUID>) options.get("possibleAttackers");
            List<UUID> blockers = (List<UUID>) options.get("possibleBlockers");
            List<Map<String, Object>> already = attackersInCombat(view);
            // Still the declare-attackers window when every creature is already
            // attacking (the engine re-asks to confirm): the key is present, the list empty.
            if (attackers != null && (!attackers.isEmpty() || !already.isEmpty())) {
                r.put("combat_phase", "declare_attackers");
                if (!already.isEmpty()) {
                    r.put("already_attacking", already);
                    if (attackers.isEmpty()) {
                        // Every creature is attacking: the engine re-asks only to confirm.
                        r.put("confirm", true);
                    }
                }
                // Who can be attacked — players, planeswalkers, battles — so a
                // client at a pod picks the defender with the attackers instead of
                // meeting the engine's per-attacker question (GameHost's batch).
                List<Map<String, Object>> defenders = defenders(game, view, me);
                r.put("defenders", defenders);
                for (UUID id : attackers) {
                    PermanentView perm = views.findPermanentView(id, view);
                    if (perm == null) {
                        continue;
                    }
                    Map<String, Object> c = creatureChoice(choices.size(), id, perm, "attacker");
                    // An attacker that may not attack every defender (goaded, "can't
                    // attack you"): its own subset, by id.
                    Permanent attacker = game.getPermanent(id);
                    if (attacker != null && defenders.size() > 1) {
                        List<String> can = new ArrayList<>();
                        for (Map<String, Object> def : defenders) {
                            UUID defId = views.resolve(String.valueOf(def.get("id")));
                            if (defId != null && attacker.canAttack(defId, game)) {
                                can.add(String.valueOf(def.get("id")));
                            }
                        }
                        if (can.size() < defenders.size()) {
                            c.put("defenders", can);
                        }
                    }
                    choices.add(c);
                    backing.add(id);
                }
                if (options.containsKey("specialButton")) {
                    Map<String, Object> all = new HashMap<>();
                    all.put("index", choices.size());
                    all.put("id", "all");
                    all.put("name", "All attack");
                    all.put("choice_type", "special");
                    choices.add(all);
                    backing.add("special");
                }
            }
            if (blockers != null && (!blockers.isEmpty() || !already.isEmpty())) {
                r.put("combat_phase", "declare_blockers");
                if (!already.isEmpty()) {
                    r.put("incoming_attackers", already);
                }
                for (UUID id : blockers) {
                    PermanentView perm = views.findPermanentView(id, view);
                    if (perm == null) {
                        continue;
                    }
                    Map<String, Object> c = creatureChoice(choices.size(), id, perm, "blocker");
                    choices.add(c);
                    backing.add(id);
                }
            }
        }
        if (isPriorityWindow(e)) {
            specialActions(choices, backing, game, me, false);
            // XMage's UNDO is on: the state before this seat's mana tap is
            // bookmarked until it passes, plays a land or completes a cast
            // (GameHost.takeBack).
            if (player.getStoredBookmark() != -1 && me.equals(game.getPriorityPlayerId())) {
                r.put("can_take_back", true);
            }
        }
        if (!choices.isEmpty()) {
            r.put("response_type", "select");
            r.put("choices", choices);
            return backing;
        }
        r.put("response_type", "boolean");
        return List.of();
    }

    /**
     * A SELECT window that is priority, not a combat declaration: the engine
     * marks the combat ones with the creatures they offer.
     */
    private static boolean isPriorityWindow(PlayerQueryEvent e) {
        Map<String, Serializable> options = e.getOptions();
        return options == null
                || (!options.containsKey("possibleAttackers") && !options.containsKey("possibleBlockers"));
    }

    /**
     * How a card is cast from a closed zone: the first playable ability that
     * is neither a mana ability nor the plain "Cast X" — a flashback, escape
     * or adventure-return line, as the engine names it (50 chars).
     */
    private static String alternativeCast(List<String> abilityNames, List<String> manaNames) {
        Set<String> mana = new HashSet<>(manaNames);
        for (String name : abilityNames) {
            if (!mana.contains(name) && !name.startsWith("Cast ") && !name.startsWith("Play ")) {
                return Fmt.stripHtml(name);
            }
        }
        return null;
    }

    /**
     * The engine's "Special" button: special actions the seat controls right
     * now, answered with the string {@code "special"} the way the Swing client
     * does. At a priority window these are the rare priority-window actions
     * (Channel's life for mana, Quenchable Fire's payment); at a mana prompt
     * they are the alternate ways to pay — convoke, delve, improvise, assist —
     * which {@code ManaCostsImpl.pay} registers for each payment round and
     * which are reachable no other way (the engine-UI sweep, fullpod
     * docs/engine-ui-surface.md, 2026-09-18). One choice per action, named
     * from its rule ("Convoke", "Delve") with the reminder text as
     * {@code ability}; every one is backed by "special" — the engine asks
     * which when there are several ({@link SeatPlayer#activateSpecialAction}).
     */
    private void specialActions(List<Map<String, Object>> choices, List<Object> backing, Game game, UUID me, boolean manaAction) {
        for (mage.abilities.SpecialAction action : game.getState().getSpecialActions().getControlledBy(me, manaAction).values()) {
            // "Exile up to seven cards from your graveyard: Delve (Each card you
            // exile …)" → "Delve": the keyword is the effect's text before its
            // reminder, after any cost.
            String rule = Fmt.stripHtml(action.getRule());
            String name = rule;
            int paren = name.indexOf(" (");
            if (paren > 0) {
                name = name.substring(0, paren);
            }
            int colon = name.lastIndexOf(": ");
            if (colon >= 0) {
                name = name.substring(colon + 2);
            }
            Map<String, Object> c = new HashMap<>();
            c.put("index", choices.size());
            c.put("id", "special");
            c.put("choice_type", "special");
            c.put("name", name);
            c.put("ability", rule);
            choices.add(c);
            backing.add("special");
        }
    }

    /**
     * The combat's defenders: each player, planeswalker and battle that can
     * be attacked, as {id, name, kind} — players first, each group by name
     * (the engine keeps them in a hash set, and the goldens compare the list).
     */
    private List<Map<String, Object>> defenders(Game game, GameView view, UUID me) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<UUID> ids = new ArrayList<>(game.getCombat().getDefenders());
        ids.sort(Comparator.<UUID, Integer>comparing(id -> game.getPlayer(id) != null ? 0 : 1)
                .thenComparing(id -> {
                    mage.players.Player pl = game.getPlayer(id);
                    Permanent pm = game.getPermanent(id);
                    return pl != null ? pl.getName() : pm != null ? pm.getName() : "";
                }, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(UUID::toString));
        for (UUID id : ids) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("id", views.shortId(id));
            mage.players.Player player = game.getPlayer(id);
            if (player != null) {
                d.put("name", player.getName());
                d.put("kind", "player");
            } else {
                Permanent perm = game.getPermanent(id);
                if (perm == null) {
                    continue;
                }
                d.put("name", perm.getName());
                d.put("kind", perm.isBattle(game) ? "battle" : "planeswalker");
                mage.players.Player controller = game.getPlayer(perm.getControllerId());
                if (controller != null) {
                    d.put("controller", controller.getName());
                }
            }
            out.add(d);
        }
        return out;
    }

    private List<Map<String, Object>> attackersInCombat(GameView view) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (view.getCombat() == null) {
            return out;
        }
        for (CombatGroupView group : view.getCombat()) {
            out.addAll(views.creatureList(group.getAttackers().values()));
        }
        return out;
    }

    private Map<String, Object> creatureChoice(int index, UUID id, PermanentView perm, String choiceType) {
        Map<String, Object> c = new HashMap<>();
        c.put("index", index);
        c.put("id", views.shortId(id));
        c.put("name", views.displayName(perm));
        if (perm.getPower() != null) {
            c.put("power", perm.getPower());
            c.put("toughness", perm.getToughness());
        }
        c.put("choice_type", choiceType);
        return c;
    }

    private List<Object> target(Map<String, Object> r, PlayerQueryEvent e, Game game, GameView view, UUID me) {
        r.put("action_type", "GAME_TARGET");
        CardsView offered = null;
        Set<UUID> targets = e.getTargets();
        if (e.getCards() != null) {
            offered = new CardsView(game, e.getCards().getCards(game), me);
            if (targets == null || targets.isEmpty()) {
                // A card search (library/hand/graveyard) fires with the whole
                // zone as e.getCards() so the player sees it all, but
                // HumanPlayer.choose/chooseTarget(Cards, TargetCard, ...) only
                // ever hands the legal subset back as options["possibleTargets"]
                // (it never populates e.getTargets() for this query shape) — so
                // without this, every card in the zone looked equally legal
                // (report 7b2f2ef77e: Worldly Tutor offering lands alongside
                // creatures). Falls back to the whole zone when the option
                // isn't there, for callers of this query shape that never set it.
                targets = possibleTargets(e).orElseGet(() -> new HashSet<>(e.getCards()));
            }
        } else if (e.getPerms() != null) {
            List<CardView> perms = new ArrayList<>();
            Set<UUID> ids = new HashSet<>();
            for (Permanent p : e.getPerms()) {
                perms.add(new PermanentView(p, game.getCard(p.getId()), me, game));
                ids.add(p.getId());
            }
            offered = new CardsView(perms);
            if (targets == null || targets.isEmpty()) {
                targets = ids;
            }
        }
        List<Object> backing = targetChoices(r, targets, offered, view, me, e.isRequired());
        chosenSoFar(r, e);
        source(r, e, game);
        return backing;
    }

    private static final Pattern LOG_REF = Pattern.compile("\\[([0-9a-f]{3})\\]");

    /**
     * Which card is asking. HumanPlayer sends an ask or target prompt as a
     * MessageToClient whose second line is the source's log name ("Lightning
     * Bolt [a3f]"; options["secondMessage"] via GameImpl.addMessageToOptions)
     * — the Swing client's smaller line under the question. The target
     * message itself never names its source ("Select a creature to destroy"),
     * so with two triggers on the stack nothing said which one was asking
     * (the engine-UI sweep, fullpod docs/engine-ui-surface.md, 2026-09-18).
     * {@code source: {name, id?}} — the id resolved from the log ref, so the
     * board can open the live card and glow its stack item.
     */
    private void source(Map<String, Object> r, PlayerQueryEvent e, Game game) {
        Map<String, Serializable> options = e.getOptions();
        Object raw = options != null ? options.get("secondMessage") : null;
        if (raw == null) {
            return;
        }
        String text = raw.toString();
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("name", Fmt.stripHtml(text));
        java.util.regex.Matcher m = LOG_REF.matcher(text);
        if (m.find()) {
            // The name beside the ref disambiguates a shared three-digit ref.
            String name = Fmt.stripHtml(text.substring(0, m.start())).trim();
            UUID id = views.byLogRef(m.group(1), name, game);
            if (id != null) {
                source.put("id", views.shortId(id));
            }
        }
        r.put("source", source);
    }

    /**
     * A prompt for several targets comes round once per pick: HumanPlayer
     * sends what is picked so far as options["chosenTargets"] (picking one
     * of those again removes it), and once the minimum is met the prompt is
     * no longer required and its right button reads "Done"
     * (options["UI.right.btn.text"]). Both were dropped, so the board
     * re-offered the chosen targets unmarked and labelled Done "Cancel"
     * (the engine-UI sweep, fullpod docs/engine-ui-surface.md, 2026-09-18).
     * Each chosen choice is flagged and listed; the label rides as done_text.
     */
    @SuppressWarnings("unchecked")
    private void chosenSoFar(Map<String, Object> r, PlayerQueryEvent e) {
        Map<String, Serializable> options = e.getOptions();
        if (options == null) {
            return;
        }
        Object raw = options.get("chosenTargets");
        if (raw instanceof Set<?> ids && !ids.isEmpty()) {
            List<String> chosen = new ArrayList<>();
            for (Object id : ids) {
                if (id instanceof UUID uuid) {
                    chosen.add(views.shortId(uuid));
                }
            }
            for (Object c : (List<Object>) r.get("choices")) {
                Map<String, Object> choice = (Map<String, Object>) c;
                if (chosen.contains(String.valueOf(choice.get("id")))) {
                    choice.put("chosen", true);
                }
            }
            r.put("chosen", chosen);
        }
        Object done = options.get("UI.right.btn.text");
        if (done != null) {
            r.put("done_text", Fmt.stripHtml(done.toString()));
        }
    }

    /** The legal subset of a card search's zone, when HumanPlayer computed one
     *  (options["possibleTargets"], set only when non-empty). */
    @SuppressWarnings("unchecked")
    private Optional<Set<UUID>> possibleTargets(PlayerQueryEvent e) {
        Map<String, Serializable> options = e.getOptions();
        Object raw = options != null ? options.get("possibleTargets") : null;
        return raw instanceof Set ? Optional.of((Set<UUID>) raw) : Optional.empty();
    }

    private List<Object> pickAbility(Map<String, Object> r, PlayerQueryEvent e, Game game, GameView view, UUID me) {
        // "Pick triggered ability (goes to the stack first)": the server sends
        // these as targets over ability views; keep that shape.
        r.put("action_type", "GAME_TARGET");
        List<? extends Ability> abilities = e.getAbilities();
        CardsView offered = abilities != null ? new CardsView(abilities, game) : null;
        Set<UUID> targets = new HashSet<>();
        if (abilities != null) {
            for (Ability a : abilities) {
                targets.add(a.getId());
            }
        }
        return targetChoices(r, targets, offered, view, me, true);
    }

    private record TargetChoice(UUID id, Map<String, Object> entry) {
    }

    private List<Object> targetChoices(Map<String, Object> r, Set<UUID> targets, CardsView offered, GameView view, UUID me, boolean required) {
        r.put("response_type", "index");
        r.put("required", required);
        r.put("can_cancel", !required);
        List<TargetChoice> entries = new ArrayList<>();
        if (targets != null) {
            for (UUID id : targets) {
                Map<String, Object> entry = new HashMap<>();
                views.targetInfo(entry, id, offered, view, me);
                entries.add(new TargetChoice(id, entry));
            }
        }
        entries.sort((a, b) -> {
            int youCmp = Boolean.compare(Boolean.TRUE.equals(b.entry().get("is_you")), Boolean.TRUE.equals(a.entry().get("is_you")));
            if (youCmp != 0) {
                return youCmp;
            }
            int nameCmp = String.CASE_INSENSITIVE_ORDER.compare(
                    String.valueOf(a.entry().get("name")), String.valueOf(b.entry().get("name")));
            return nameCmp != 0 ? nameCmp : Integer.compare(views.sequence(a.id()), views.sequence(b.id()));
        });
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        for (TargetChoice t : entries) {
            t.entry().put("id", views.shortId(t.id()));
            t.entry().put("index", choices.size());
            choices.add(t.entry());
            backing.add(t.id());
        }
        r.put("choices", choices);
        return backing;
    }

    private List<Object> mana(Map<String, Object> r, PlayerQueryEvent e, Game game, GameView view, UUID me) {
        r.put("action_type", e.getQueryType() == PlayerQueryEvent.QueryType.PLAY_X_MANA ? "GAME_PLAY_XMANA" : "GAME_PLAY_MANA");
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        UUID payingFor = payingForId(e.getMessage());
        PlayableObjectsList playable = view.getCanPlayObjects();
        if (playable != null) {
            List<Map.Entry<UUID, PlayableObjectStats>> sorted = new ArrayList<>(playable.getObjects().entrySet());
            sorted.sort(Comparator.<Map.Entry<UUID, PlayableObjectStats>, String>comparing(entry -> {
                CardView cv = views.findCardView(entry.getKey(), view);
                return cv != null ? views.displayName(cv) : "";
            }).thenComparingInt(entry -> views.sequence(entry.getKey())));
            for (Map.Entry<UUID, PlayableObjectStats> entry : sorted) {
                UUID id = entry.getKey();
                if (id.equals(payingFor)) {
                    continue;
                }
                List<String> manaAbilities = entry.getValue().getAllManaAbilityNames();
                if (manaAbilities.isEmpty()) {
                    continue;
                }
                CardView cv = views.findCardView(id, view);
                String name = cv != null ? views.displayName(cv) : "Unknown (" + id.toString().substring(0, 8) + ")";
                for (String ability : manaAbilities) {
                    Map<String, Object> c = new HashMap<>();
                    c.put("index", choices.size());
                    c.put("id", views.shortId(id));
                    c.put("choice_type", ability.contains("{T}") ? "tap_source" : "mana_source");
                    c.put("name", name);
                    c.put("ability", ability);
                    choices.add(c);
                    backing.add(id);
                }
            }
        }
        ManaPoolView pool = view.getMyPlayer() != null ? view.getMyPlayer().getManaPool() : null;
        for (ManaType type : poolChoices(pool, e.getMessage())) {
            Map<String, Object> c = new HashMap<>();
            c.put("index", choices.size());
            c.put("choice_type", "pool_mana");
            c.put("name", prettyManaType(type));
            c.put("count", poolCount(pool, type));
            choices.add(c);
            backing.add(type);
        }
        int before = choices.size();
        specialActions(choices, backing, game, me, true);
        if (choices.size() > before) {
            // The engine's order (ActivatedManaAbilityImpl.canActivate): once a
            // special payment has been used on a spell, no mana ability may pay
            // for it — so sources go first, the special action pays what is left.
            List<String> names = new ArrayList<>();
            for (int i = before; i < choices.size(); i++) {
                names.add(String.valueOf(choices.get(i).get("name")));
            }
            r.put("note", "Tap sources first; " + String.join(" / ", names) + " pays what is left — once used, lands can't pay for this spell.");
        }
        if (!choices.isEmpty()) {
            r.put("response_type", "select");
            r.put("choices", choices);
            return backing;
        }
        r.put("response_type", "boolean");
        return List.of();
    }

    private List<Object> abilityPicker(Map<String, Object> r, PlayerQueryEvent e, Game game) {
        r.put("action_type", "GAME_CHOOSE_ABILITY");
        r.put("response_type", "index");
        String objectName = e.getChoices() != null && !e.getChoices().isEmpty() ? e.getChoices().iterator().next() : null;
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        boolean allMana = e.getAbilities() != null && !e.getAbilities().isEmpty();
        if (e.getAbilities() != null) {
            for (Ability ability : e.getAbilities()) {
                String desc = Fmt.stripHtml(objectName != null ? ability.getRule(objectName) : ability.getRule());
                Map<String, Object> c = new HashMap<>();
                c.put("index", choices.size());
                c.put("description", desc);
                choices.add(c);
                backing.add(ability.getId());
                if (desc == null || !desc.contains("Add {")) {
                    allMana = false;
                }
            }
        }
        if (allMana && objectName != null) {
            r.put("message", "Choose which mana to produce from " + objectName);
        }
        r.put("choices", choices);
        return backing;
    }

    private List<Object> modes(Map<String, Object> r, PlayerQueryEvent e) {
        // The server shows modes through the ability picker; so do we.
        r.put("action_type", "GAME_CHOOSE_ABILITY");
        r.put("response_type", "index");
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        if (e.getModes() != null) {
            for (Map.Entry<UUID, String> entry : e.getModes().entrySet()) {
                Map<String, Object> c = new HashMap<>();
                c.put("index", choices.size());
                c.put("description", Fmt.stripOrdinal(Fmt.stripHtml(entry.getValue()), choices.size()));
                choices.add(c);
                backing.add(entry.getKey());
            }
        }
        r.put("choices", choices);
        return backing;
    }

    private List<Object> choice(Map<String, Object> r, PlayerQueryEvent e) {
        r.put("action_type", "GAME_CHOOSE_CHOICE");
        r.put("response_type", "index");
        Choice choice = e.getChoice();
        List<Map<String, Object>> choices = new ArrayList<>();
        List<Object> backing = new ArrayList<>();
        if (choice != null) {
            if (r.get("message") == null || String.valueOf(r.get("message")).isEmpty()) {
                r.put("message", Fmt.stripHtml(choice.getMessage()));
            }
            r.put("required", choice.isRequired());
            if (choice.getSubMessage() != null && !choice.getSubMessage().isEmpty()) {
                r.put("sub_message", Fmt.stripHtml(choice.getSubMessage()));
            }
            // What the Swing dialog has and the board didn't (the engine-UI
            // sweep, fullpod docs/engine-ui-surface.md, 2026-09-18): each
            // item's card or object (hintData — the replacement-effect
            // question sets the effect's source), the engine's sort order,
            // and a search box for a long list (a creature type is ~300).
            Map<String, List<String>> hints = choice.getHintData() != null ? choice.getHintData() : Map.of();
            Map<String, Integer> sorts = choice.getSortData() != null ? choice.getSortData() : Map.of();
            if (choice.isKeyChoice()) {
                if (choice.getKeyChoices() != null) {
                    for (Map.Entry<String, String> entry : choice.getKeyChoices().entrySet()) {
                        Map<String, Object> c = new HashMap<>();
                        c.put("index", choices.size());
                        c.put("description", Fmt.stripHtml(entry.getValue()));
                        itemHint(c, hints.get(entry.getKey()));
                        if (sorts.get(entry.getKey()) != null) {
                            c.put("sort", sorts.get(entry.getKey()));
                        }
                        choices.add(c);
                        backing.add(entry.getKey());
                    }
                }
            } else if (choice.getChoices() != null) {
                for (String value : choice.getChoices()) {
                    Map<String, Object> c = new HashMap<>();
                    c.put("index", choices.size());
                    c.put("description", value);
                    itemHint(c, hints.get(value));
                    if (sorts.get(value) != null) {
                        c.put("sort", sorts.get(value));
                    }
                    choices.add(c);
                    backing.add(value);
                }
            }
            if (choice.isSearchEnabled() && choices.size() > 12) {
                r.put("searchable", true);
            }
            if (choice.isSpecialEnabled()) {
                // The engine's own "Remember answer" on a list question — set
                // only for the replacement-effect order, whose answer it keeps
                // for the rest of the game (HumanPlayer.autoSelectReplacementEffects).
                // Answered with remember= on choose_action (docs/engine.md).
                r.put("remember_text", Fmt.stripHtml(choice.getSpecialText()));
            }
        }
        r.put("choices", choices);
        return backing;
    }

    /** A choice item's hint: {@code [type, value]} — a card by name, or a game object by UUID. */
    private void itemHint(Map<String, Object> c, List<String> hint) {
        if (hint == null || hint.size() < 2 || hint.get(1) == null) {
            return;
        }
        String type = hint.get(0);
        String value = hint.get(1);
        if ("GAME_OBJECT".equals(type)) {
            try {
                c.put("id", views.shortId(UUID.fromString(value)));
            } catch (IllegalArgumentException ignored) {
                // not a UUID: nothing to link
            }
        } else if ("CARD".equals(type) || "CARD_DUNGEON".equals(type)) {
            c.put("card", Fmt.stripHtml(value));
        }
    }

    private List<Object> pile(Map<String, Object> r, PlayerQueryEvent e, Game game) {
        r.put("action_type", "GAME_CHOOSE_PILE");
        r.put("response_type", "pile");
        r.put("pile1", pileCards(e.getPile1(), game));
        r.put("pile2", pileCards(e.getPile2(), game));
        return List.of();
    }

    private List<Map<String, Object>> pileCards(List<? extends Card> pile, Game game) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (pile != null) {
            for (Card card : pile) {
                Map<String, Object> info = views.cardInfo(new CardView(card, game));
                info.put("id", views.shortId(card.getId()));
                out.add(info);
            }
        }
        return out;
    }

    private List<Object> amount(Map<String, Object> r, PlayerQueryEvent e, Game game, SeatPlayer player) {
        r.put("action_type", "GAME_GET_AMOUNT");
        r.put("response_type", "amount");
        r.put("min", e.getMin());
        int max = e.getMax();
        if (max == Integer.MAX_VALUE && e.getMessage() != null && e.getMessage().contains("{X}")) {
            // The engine announces {X} with no upper bound (VariableManaCost.maxX);
            // the most mana the seat could make is the bound a person or a pilot
            // can use — the largest of the mana options, the way getPlayable sizes
            // affordability.
            int available = 0;
            for (mage.Mana mana : player.getManaAvailable(game)) {
                available = Math.max(available, mana.count());
            }
            r.put("mana_available", available);
            max = Math.max(e.getMin(), available);
        }
        r.put("max", max);
        return List.of();
    }

    private List<Object> multiAmount(Map<String, Object> r, PlayerQueryEvent e) {
        r.put("action_type", "GAME_GET_MULTI_AMOUNT");
        r.put("response_type", "multi_amount");
        r.put("total_min", e.getMin());
        r.put("total_max", e.getMax());
        List<Map<String, Object>> items = new ArrayList<>();
        if (e.getMessages() != null) {
            for (MultiAmountMessage item : e.getMessages()) {
                Map<String, Object> info = new HashMap<>();
                info.put("description", Fmt.stripHtml(item.message));
                info.put("min", item.min);
                info.put("max", item.max);
                info.put("default", item.defaultValue);
                items.add(info);
            }
        }
        r.put("items", items);
        if ((r.get("message") == null || String.valueOf(r.get("message")).isEmpty()) && e.getOptions() != null) {
            Object header = e.getOptions().get("header");
            if (header instanceof String s) {
                r.put("message", Fmt.stripHtml(s));
            }
        }
        return List.of();
    }

    // ---- helpers --------------------------------------------------------

    private static UUID payingForId(String message) {
        if (message == null) {
            return null;
        }
        int idx = message.indexOf("object_id='");
        if (idx < 0) {
            return null;
        }
        int start = idx + "object_id='".length();
        int end = message.indexOf('\'', start);
        if (end <= start) {
            return null;
        }
        try {
            return UUID.fromString(message.substring(start, end));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<ManaType> poolChoices(ManaPoolView pool, String prompt) {
        List<ManaType> out = new ArrayList<>();
        if (pool == null) {
            return out;
        }
        boolean explicit = false;
        if (prompt != null) {
            for (int i = 0; i < SYMBOLS.length; i++) {
                if (SYMBOLS[i].matcher(prompt).find()) {
                    explicit = true;
                    if (poolCount(pool, SYMBOL_TYPES[i]) > 0 && !out.contains(SYMBOL_TYPES[i])) {
                        out.add(SYMBOL_TYPES[i]);
                    }
                }
            }
        }
        // A generic pip in what's left ("{1}", "{2}"...) takes mana of any
        // colour, so a floating colour with no matching coloured pip still
        // needs a button (report a9bddc7747, 2026-09-17: "Pay {1}{B}" with
        // {B} and {G} both floating offered only "Black" — nothing spent the
        // green, and nothing said the tap-a-source options were the only way).
        boolean generic = prompt != null && GENERIC.matcher(prompt).find();
        if (explicit && !generic) {
            return out;
        }
        for (ManaType type : SYMBOL_TYPES) {
            if (poolCount(pool, type) > 0 && !out.contains(type)) {
                out.add(type);
            }
        }
        return out;
    }

    static int poolCount(ManaPoolView pool, ManaType type) {
        if (pool == null) {
            return 0;
        }
        return switch (type) {
            case WHITE -> pool.getWhite();
            case BLUE -> pool.getBlue();
            case BLACK -> pool.getBlack();
            case RED -> pool.getRed();
            case GREEN -> pool.getGreen();
            case COLORLESS -> pool.getColorless();
            case GENERIC -> 0;
        };
    }

    private static String prettyManaType(ManaType type) {
        return switch (type) {
            case WHITE -> "White";
            case BLUE -> "Blue";
            case BLACK -> "Black";
            case RED -> "Red";
            case GREEN -> "Green";
            case COLORLESS -> "Colorless";
            case GENERIC -> "Generic";
        };
    }
}
