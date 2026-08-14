package forge.ai.llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.LobbyPlayer;
import forge.ai.PlayerControllerAi;
import forge.game.Game;
import forge.game.GameEntity;
import forge.game.GameLogEntryType;
import forge.game.card.Card;
import forge.game.combat.Combat;
import forge.game.combat.CombatUtil;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Hybrid controller: Claude makes the strategic decisions (what to cast, mulligans, attacks, blocks);
 * everything else — mana payment, trigger ordering, the mechanical long tail — inherits from the
 * stock AI. Any failure on the Claude path falls back to the stock decision and is recorded.
 */
public class LlmPlayerController extends PlayerControllerAi {

    private final LlmBrain brain = new LlmBrain();
    private final DecisionLedger ledger;
    private String currentPlan = "(no plan yet)";
    private int planTurn = -1;
    private int callsThisTurn = 0;
    private int lastTurnSeen = -1;
    private String lastPassSignature = null;
    private boolean unkeyedSeatAnnounced = false;

    // Claude's staged orders for the line it just chose: served to the engine by the
    // chooseTargetsFor / announceRequirements overrides while the cast executes.
    private SpellAbility pendingSa = null;
    private forge.game.GameObject pendingTarget = null;
    private Integer pendingX = null;

    public LlmPlayerController(Game game, Player p, LobbyPlayer lp) {
        super(game, p, lp);
        this.ledger = new DecisionLedger(p.getName());
        TableChat.resetForNewGame();
        System.out.println("[LLM] controller active for " + p.getName()
                + (LlmConfig.isMock() ? " (MOCK mode)"
                        : " (planner: " + LlmConfig.planTier()
                                + ", fast: " + LlmConfig.fastTier()
                                + ", persona: " + LlmConfig.persona() + ")"));
        if (!LlmConfig.isConfigured()) {
            System.err.println("[LLM] no API key (looked for $" + LlmConfig.planTier().apiKeyEnv
                    + " / $" + LlmConfig.fastTier().apiKeyEnv + ") — this seat will play with the stock AI");
        }
    }

    // ------------------------------------------------------------------ spells & lands

    @Override
    public List<SpellAbility> chooseSpellAbilityToPlay() {
        if (!LlmConfig.isConfigured()) {
            announceUnkeyedSeat();
            return super.chooseSpellAbilityToPlay();
        }
        try {
            Game game = getGame();
            PhaseHandler ph = game.getPhaseHandler();
            // a new decision window voids any stale staged orders
            pendingSa = null;
            pendingTarget = null;
            pendingX = null;
            if (ph.getTurn() != lastTurnSeen) {
                lastTurnSeen = ph.getTurn();
                callsThisTurn = 0;
            }

            boolean stackEmpty = game.getStack().isEmpty();
            // Quiet phases never wake the mind while the stack is empty: own upkeep/draw, and the
            // opponent's non-combat phases. Anything on the stack always gets real consideration.
            // (Chat no longer rides these windows — it's answered in real time off-thread.)
            if (stackEmpty && !isConsultPhase(ph)) {
                return null;
            }
            // Once she passes a phase with an empty stack, that phase stays quiet — the engine
            // re-asks after every resolution, and re-answering "still passing" costs seconds each.
            // Casting reopens the phase; a spell hitting the stack wakes her.
            String phaseKey = ph.getTurn() + "|" + ph.getPhase();
            if (stackEmpty && phaseKey.equals(lastPassSignature)) {
                log(ph, "chooseSpell", DecisionLedger.Outcome.AUTO_PASS_CACHED, null, "pass (phase already passed)", null);
                return null;
            }

            ActionCatalog cat = ActionCatalog.build(game, player, getAi());
            if (!cat.hasRealChoices()) {
                return super.chooseSpellAbilityToPlay();
            }

            if (callsThisTurn >= LlmConfig.maxCallsPerTurn()) {
                log(ph, "chooseSpell", DecisionLedger.Outcome.FALLBACK_CAP, null, "per-turn call cap reached", null);
                return super.chooseSpellAbilityToPlay();
            }

            boolean myTurn = ph.isPlayerTurn(player);

            // ---------------- PLANNER PATH: own turn, empty stack ----------------
            // One planner call at the start of the turn; later windows execute locally
            // from the plan — zero API — until something unforeseen interrupts.
            if (!LlmConfig.isMock() && myTurn && stackEmpty) {
                if (turnPlan == null || turnPlan.turn != ph.getTurn()) {
                    callsThisTurn++;
                    turnPlan = makePlan(game, ph, cat);
                }
                if (turnPlan != null) {
                    TurnPlan.Step step = turnPlan.nextFor(String.valueOf(ph.getPhase()));
                    if (step == null) {
                        lastPassSignature = phaseKey;
                        log(ph, "planStep", DecisionLedger.Outcome.PLAN_STEP, null, "pass (plan done for phase)", null);
                        return null;
                    }
                    ActionCatalog.Option planOpt = TurnPlan.findOption(cat, step);
                    if (planOpt != null) {
                        step.done = true;
                        lastPassSignature = null;
                        // planner-authored line for this moment: Opus wrote it with full thought at plan time
                        postTalk(step.say, true);
                        log(ph, "planStep", DecisionLedger.Outcome.PLAN_STEP, null,
                                planOpt.label + (step.x != null ? " [X=" + step.x + "]" : ""), step.say);
                        if (planOpt.kind == ActionCatalog.Kind.LAND) {
                            return applyLandOption(planOpt);
                        }
                        return applySpellOption(planOpt, step.x);
                    }
                    // planned step isn't available in this window — consume it and let the fast tier judge
                    step.done = true;
                }
            }

            // ---------------- FAST TIER: responses, interrupts, plan misses ----------------
            JsonObject decision;
            LlmBrain.Result res = null;
            DecisionLedger.Outcome oc;
            callsThisTurn++;
            if (LlmConfig.isMock()) {
                GameStateSerializer.serialize(game, player); // keep the leak tripwire exercised in mock runs
                decision = mockChooseAction(cat);
                oc = DecisionLedger.Outcome.MOCK;
            } else {
                String planContext = turnPlan != null && turnPlan.turn == ph.getTurn()
                        ? (turnPlan.summary == null ? "(unnamed plan)" : turnPlan.summary)
                          + " | contingencies: " + (turnPlan.contingencies == null ? "(none)" : turnPlan.contingencies)
                        : planFor(ph);
                String prompt = GameStateSerializer.serialize(game, player)
                        + "\nACTIVE TURN PLAN: " + planContext + "\n"
                        + talkContext() + chatContext() + "\n"
                        + cat.menu()
                        + "\nYou are handling a response window or an unplanned moment. Stick to your plan's intent "
                        + "unless the new information genuinely changes things. Options with \"→\" are complete "
                        + "plays. For [you choose X...] options include \"x\". If this moment is truly pivotal and "
                        + "deserves deeper thought, reply with \"escalate\": true. table_talk: empty unless you are "
                        + "actually casting something — then at most a 2-6 word reaction; never narrate the board, "
                        + "life totals, or your own spell. Respond with ONLY this JSON:\n"
                        + "{\"action\": <index>, \"x\": <only for X spells>, \"escalate\": false, "
                        + "\"mood\": \"<optional update>\", \"table_talk\": \"<usually empty>\"}";
                res = brain.decideFast(prompt);
                oc = DecisionLedger.Outcome.FAST;
                ledger.transcript(ph.getTurn(), String.valueOf(ph.getPhase()), "fastWindow",
                        prompt, res.thinkingText, res.rawText);
                boolean escalate = false;
                try {
                    escalate = res.decision.has("escalate") && res.decision.get("escalate").getAsBoolean();
                } catch (Exception ignored) {}
                if (escalate) {
                    callsThisTurn++;
                    res = brain.decide(prompt);
                    oc = DecisionLedger.Outcome.ESCALATED;
                    ledger.transcript(ph.getTurn(), String.valueOf(ph.getPhase()), "escalated",
                            prompt, res.thinkingText, res.rawText);
                }
                decision = res.decision;
            }

            int idx = decision.has("action") ? decision.get("action").getAsInt() : 0;
            ActionCatalog.Option opt = cat.get(idx);
            if (opt == null) {
                throw new LlmBrain.BrainException(LlmBrain.BrainException.Kind.PARSE,
                        "action index out of range: " + idx, null);
            }
            updatePlan(ph, decision);

            switch (opt.kind) {
                case PASS:
                    // the fast tier never talks while passing — that's where the board-narration filler came from
                    if (oc != DecisionLedger.Outcome.FAST) {
                        tableTalk(decision, false);
                    }
                    if (game.getStack().isEmpty()) {
                        lastPassSignature = phaseKey;
                    }
                    log(ph, "chooseSpell", oc, res, "pass", talk(decision));
                    return null;
                case LAND:
                    lastPassSignature = null;
                    tableTalk(decision, true);
                    log(ph, "chooseSpell", oc, res, "play land: " + opt.land.getName(), talk(decision));
                    List<SpellAbility> las = applyLandOption(opt);
                    return las != null ? las : super.chooseSpellAbilityToPlay();
                case SPELL:
                default:
                    lastPassSignature = null;
                    // acting talk: reactions while doing something — answering a spell, making a play — always land
                    tableTalk(decision, true);
                    Integer xChoice = null;
                    try {
                        if (decision.has("x") && !decision.get("x").isJsonNull()) {
                            xChoice = decision.get("x").getAsInt();
                        }
                    } catch (Exception ignored) {}
                    List<SpellAbility> out = applySpellOption(opt, xChoice);
                    log(ph, "chooseSpell", oc, res,
                            opt.label + (pendingX != null ? " [X=" + pendingX + "]" : ""), talk(decision));
                    return out;
            }
        } catch (Exception e) {
            return fallback("chooseSpell", e, super::chooseSpellAbilityToPlay);
        }
    }

    // ------------------------------------------------------------------ plan machinery

    private TurnPlan turnPlan = null;

    /** One planner-tier call laying out the whole turn: steps, attacks, contingencies. */
    private TurnPlan makePlan(Game game, PhaseHandler ph, ActionCatalog cat) throws LlmBrain.BrainException {
        List<Card> attackers = new ArrayList<>();
        for (Card c : GameStateSerializer.sorted(player.getCreaturesInPlay())) {
            try {
                if (CombatUtil.canAttack(c)) {
                    attackers.add(c);
                }
            } catch (Exception ignored) {}
        }
        StringBuilder prompt = new StringBuilder(GameStateSerializer.serialize(game, player));
        prompt.append("\nPLAN YOUR ENTIRE TURN. The menu below is what you can do RIGHT NOW; you may also ")
              .append("sequence later steps by card name — they are matched when their moment comes (e.g. a ")
              .append("spell only castable after your land drop).\n")
              .append(talkContext()).append(chatContext()).append('\n')
              .append(cat.menu());
        prompt.append("CREATURES THAT COULD ATTACK THIS TURN:");
        if (attackers.isEmpty()) {
            prompt.append(" (none)\n");
        } else {
            prompt.append('\n');
            for (Card c : attackers) {
                prompt.append("  - ").append(GameStateSerializer.describePermanent(c, true)).append('\n');
            }
        }
        prompt.append("\nYou are also this turn's voice: any step may carry a \"say\" line, delivered at the ")
              .append("table the moment that step happens. Give at most one or two steps a say-line, only where ")
              .append("the moment earns it — and if one deserves the full conversation register (a real take, ")
              .append("1-3 sentences), write it here; the fast tier never writes those. A say-line may speak ")
              .append("only about the step it rides or about public information — never about a later step's ")
              .append("card or anything still hidden when the line lands (it is spoken BEFORE later steps happen).\n")
              .append("Respond with ONLY this JSON:\n")
              .append("{\"plan_steps\": [{\"phase\": \"MAIN1|COMBAT|MAIN2|EOT\", \"cast\": \"<card name>\" OR ")
              .append("\"land\": \"<land name>\", \"target\": \"<target name | opponent | omit>\", ")
              .append("\"x\": <only for X spells>, \"say\": \"<optional table line for this moment>\"}],\n")
              .append(" \"attacks\": [\"<attacking creature name>\"],\n")
              .append(" \"contingencies\": \"<short: if X then Y; if Z then W>\",\n")
              .append(" \"plan\": \"<one-line summary>\", \"mood\": \"<optional update>\", ")
              .append("\"table_talk\": \"<optional, spoken now at turn start>\"}");
        LlmBrain.Result res = brain.decide(prompt.toString());
        ledger.transcript(ph.getTurn(), String.valueOf(ph.getPhase()), "turnPlan",
                prompt.toString(), res.thinkingText, res.rawText);
        TurnPlan plan = TurnPlan.parse(res.decision, ph.getTurn());
        updatePlan(ph, res.decision);
        tableTalk(res.decision, true);
        log(ph, "turnPlan", DecisionLedger.Outcome.CLAUDE, res,
                "plan: " + (plan.summary == null ? "(unnamed)" : plan.summary) + " [" + plan.steps.size()
                        + " steps, attacks: " + (plan.attacksDecided ? String.valueOf(plan.attacks.size()) : "undecided") + "]",
                talk(res.decision));
        return plan;
    }

    private List<SpellAbility> applyLandOption(ActionCatalog.Option opt) {
        List<SpellAbility> las = opt.land.getAllPossibleAbilities(player, true);
        las.removeIf(sa -> !sa.isLandAbility());
        return las.isEmpty() ? null : las;
    }

    private List<SpellAbility> applySpellOption(ActionCatalog.Option opt, Integer xChoice) {
        if (opt.target != null) {
            pendingSa = opt.sa;
            pendingTarget = opt.target;
            try {
                opt.sa.resetTargets();
                opt.sa.getTargets().add(opt.target);
            } catch (Exception ignored) {}
        }
        if (opt.maxX >= 0) {
            pendingSa = opt.sa;
            pendingX = xChoice != null ? Math.max(0, Math.min(opt.maxX, xChoice)) : opt.maxX;
        }
        List<SpellAbility> out = new ArrayList<>();
        out.add(opt.sa);
        return out;
    }

    // ------------------------------------------------------------------ staged-order execution

    /** Serve Claude's staged target while its chosen spell executes; everything else goes to stock. */
    @Override
    public boolean chooseTargetsFor(SpellAbility currentAbility) {
        try {
            if (pendingSa != null && pendingTarget != null && currentAbility.getRootAbility() == pendingSa
                    && currentAbility.canTarget(pendingTarget)) {
                currentAbility.resetTargets();
                currentAbility.getTargets().add(pendingTarget);
                return true;
            }
        } catch (Exception ignored) {}
        return super.chooseTargetsFor(currentAbility);
    }

    /** Serve Claude's chosen X while its spell executes; everything else goes to stock. */
    @Override
    public Integer announceRequirements(SpellAbility ability, int min, int max, String announce) {
        try {
            if (pendingSa != null && pendingX != null && "X".equals(announce)
                    && ability.getRootAbility() == pendingSa) {
                return Math.max(min, Math.min(max, pendingX));
            }
        } catch (Exception ignored) {}
        return super.announceRequirements(ability, min, max, announce);
    }

    // ------------------------------------------------------------------ mulligans

    @Override
    public boolean mulliganKeepHand(Player firstPlayer, int cardsToReturn) {
        if (!LlmConfig.isConfigured()) {
            announceUnkeyedSeat();
            return super.mulliganKeepHand(firstPlayer, cardsToReturn);
        }
        try {
            List<Card> hand = GameStateSerializer.sorted(player.getCardsIn(ZoneType.Hand));
            StringBuilder prompt = new StringBuilder("MULLIGAN DECISION\n");
            prompt.append("You are on the ").append(firstPlayer == player ? "PLAY" : "DRAW").append(".\n");
            if (cardsToReturn > 0) {
                prompt.append("If you keep, you must put ").append(cardsToReturn)
                      .append(" card(s) on the bottom of your library (London mulligan).\n");
            }
            prompt.append("YOUR HAND (").append(hand.size()).append(" cards):\n");
            for (Card c : hand) {
                prompt.append("  - ").append(GameStateSerializer.describeCardFull(c)).append('\n');
            }
            prompt.append(talkContext()).append(chatContext());
            prompt.append("\nRespond with ONLY this JSON: {\"keep\": true|false, \"mood\": \"<optional update>\", "
                    + "\"table_talk\": \"<optional — empty unless it's genuinely worth saying>\"}");

            JsonObject decision;
            LlmBrain.Result res = null;
            if (LlmConfig.isMock()) {
                long lands = hand.stream().filter(Card::isLand).count();
                decision = new JsonObject();
                decision.addProperty("keep", lands >= 2 && lands <= 5);
                decision.addProperty("table_talk", "[mock] hand judged by land count.");
            } else {
                res = brain.decide(prompt.toString());
                decision = res.decision;
                ledger.transcript(getGame().getPhaseHandler().getTurn(), "pregame", "mulligan",
                        prompt.toString(), res.thinkingText, res.rawText);
            }
            boolean keep = !decision.has("keep") || decision.get("keep").getAsBoolean();
            tableTalk(decision, true);
            log(getGame().getPhaseHandler(), "mulligan", outcome(), res, keep ? "keep" : "mulligan", talk(decision));
            return keep;
        } catch (Exception e) {
            return fallback("mulligan", e, () -> super.mulliganKeepHand(firstPlayer, cardsToReturn));
        }
    }

    // ------------------------------------------------------------------ combat

    @Override
    public void declareAttackers(Player attackerPlayer, Combat combat) {
        if (!LlmConfig.isConfigured()) {
            announceUnkeyedSeat();
            super.declareAttackers(attackerPlayer, combat);
            return;
        }
        try {
            // attacks already decided in the turn plan: execute them locally, zero API
            if (!LlmConfig.isMock() && turnPlan != null
                    && turnPlan.turn == getGame().getPhaseHandler().getTurn() && turnPlan.attacksDecided) {
                List<Card> legalNow = new ArrayList<>();
                for (Card c : GameStateSerializer.sorted(attackerPlayer.getCreaturesInPlay())) {
                    if (CombatUtil.canAttack(c)) {
                        legalNow.add(c);
                    }
                }
                List<GameEntity> defs = new ArrayList<>();
                for (GameEntity ge : combat.getDefenders()) {
                    defs.add(ge);
                }
                int declared = 0;
                StringBuilder names = new StringBuilder();
                Set<Card> used = new HashSet<>();
                for (String name : turnPlan.attacks) {
                    for (Card c : legalNow) {
                        if (used.contains(c) || !c.getName().equalsIgnoreCase(name)) {
                            continue;
                        }
                        used.add(c);
                        if (defs.isEmpty() || combat.isAttacking(c) || !CombatUtil.canAttack(c, defs.get(0))) {
                            continue;
                        }
                        combat.addAttacker(c, defs.get(0));
                        declared++;
                        names.append(c.getName()).append(", ");
                        break;
                    }
                }
                log(getGame().getPhaseHandler(), "declareAttackers", DecisionLedger.Outcome.PLAN_STEP, null,
                        declared == 0 ? "no attacks (plan)" : "attack (plan): " + names.substring(0, names.length() - 2),
                        null);
                return;
            }
            List<Card> legal = new ArrayList<>();
            for (Card c : GameStateSerializer.sorted(attackerPlayer.getCreaturesInPlay())) {
                if (CombatUtil.canAttack(c)) {
                    legal.add(c);
                }
            }
            if (legal.isEmpty()) {
                return; // nothing can attack; declare none
            }
            List<GameEntity> defenders = new ArrayList<>();
            for (GameEntity ge : combat.getDefenders()) {
                defenders.add(ge);
            }

            StringBuilder prompt = new StringBuilder(GameStateSerializer.serialize(getGame(), player));
            prompt.append("\nDECLARE ATTACKERS\nPOSSIBLE ATTACKERS:\n");
            for (int i = 0; i < legal.size(); i++) {
                prompt.append("  ").append(i).append(". ").append(GameStateSerializer.describePermanent(legal.get(i), true)).append('\n');
            }
            prompt.append("DEFENDERS:\n");
            for (int i = 0; i < defenders.size(); i++) {
                GameEntity ge = defenders.get(i);
                prompt.append("  ").append(i).append(". ")
                      .append(ge instanceof Player ? "Player: " + ((Player) ge).getName() : String.valueOf(ge)).append('\n');
            }
            prompt.append(talkContext()).append(chatContext());
            prompt.append("\nAttack with any subset (empty list = no attacks). Consider what can block and what trades.\n")
                  .append("Respond with ONLY this JSON: {\"attacks\": [{\"attacker\": <idx>, \"defender\": <idx>}], ")
                  .append("\"plan\": \"<updated plan>\", \"mood\": \"<optional update>\", ")
                  .append("\"table_talk\": \"<optional — empty unless it's genuinely worth saying>\"}");

            JsonObject decision;
            LlmBrain.Result res = null;
            if (LlmConfig.isMock()) {
                decision = new JsonObject();
                JsonArray arr = new JsonArray();
                for (int i = 0; i < legal.size(); i++) {
                    JsonObject a = new JsonObject();
                    a.addProperty("attacker", i);
                    a.addProperty("defender", 0);
                    arr.add(a);
                }
                decision.add("attacks", arr);
                decision.addProperty("table_talk", "[mock] everyone sideways.");
            } else {
                callsThisTurn++;
                res = brain.decide(prompt.toString());
                decision = res.decision;
                ledger.transcript(getGame().getPhaseHandler().getTurn(),
                        String.valueOf(getGame().getPhaseHandler().getPhase()), "declareAttackers",
                        prompt.toString(), res.thinkingText, res.rawText);
            }

            int declared = 0;
            StringBuilder summary = new StringBuilder();
            if (decision.has("attacks") && decision.get("attacks").isJsonArray()) {
                for (JsonElement el : decision.getAsJsonArray("attacks")) {
                    try {
                        JsonObject a = el.getAsJsonObject();
                        int ai = a.get("attacker").getAsInt();
                        int di = a.has("defender") ? a.get("defender").getAsInt() : 0;
                        if (ai < 0 || ai >= legal.size() || di < 0 || di >= defenders.size()) {
                            continue;
                        }
                        Card attacker = legal.get(ai);
                        GameEntity defender = defenders.get(di);
                        if (combat.isAttacking(attacker) || !CombatUtil.canAttack(attacker, defender)) {
                            continue;
                        }
                        combat.addAttacker(attacker, defender);
                        declared++;
                        summary.append(attacker.getName()).append(", ");
                    } catch (Exception ignored) {}
                }
            }
            updatePlan(getGame().getPhaseHandler(), decision);
            tableTalk(decision, true);
            log(getGame().getPhaseHandler(), "declareAttackers", outcome(), res,
                    declared == 0 ? "no attacks" : "attack with: " + summary.substring(0, summary.length() - 2),
                    talk(decision));
        } catch (Exception e) {
            fallbackVoid("declareAttackers", e, () -> super.declareAttackers(attackerPlayer, combat));
        }
    }

    @Override
    public void declareBlockers(Player defenderPlayer, Combat combat) {
        if (!LlmConfig.isConfigured()) {
            announceUnkeyedSeat();
            super.declareBlockers(defenderPlayer, combat);
            return;
        }
        try {
            List<Card> attackers = GameStateSerializer.sorted(combat.getAttackers());
            List<Card> blockers = new ArrayList<>();
            for (Card c : GameStateSerializer.sorted(defenderPlayer.getCreaturesInPlay())) {
                if (CombatUtil.canBlock(c, combat)) {
                    blockers.add(c);
                }
            }
            if (attackers.isEmpty() || blockers.isEmpty()) {
                return; // no meaningful block decision
            }

            StringBuilder prompt = new StringBuilder(GameStateSerializer.serialize(getGame(), player));
            prompt.append(GameStateSerializer.serializeCombat(combat, player));
            prompt.append("\nDECLARE BLOCKERS\nATTACKERS (index for your answer):\n");
            for (int i = 0; i < attackers.size(); i++) {
                prompt.append("  ").append(i).append(". ").append(GameStateSerializer.describePermanent(attackers.get(i), false)).append('\n');
            }
            prompt.append("YOUR POSSIBLE BLOCKERS:\n");
            for (int i = 0; i < blockers.size(); i++) {
                prompt.append("  ").append(i).append(". ").append(GameStateSerializer.describePermanent(blockers.get(i), true)).append('\n');
            }
            prompt.append(talkContext()).append(chatContext());
            prompt.append("\nEach blocker may block one attacker; multiple blockers may gang up on one attacker. ")
                  .append("Empty list = take the damage.\n")
                  .append("Respond with ONLY this JSON: {\"blocks\": [{\"blocker\": <idx>, \"attacker\": <idx>}], ")
                  .append("\"plan\": \"<updated plan>\", \"mood\": \"<optional update>\", ")
                  .append("\"table_talk\": \"<optional — empty unless it's genuinely worth saying>\"}");

            JsonObject decision;
            LlmBrain.Result res = null;
            if (LlmConfig.isMock()) {
                decision = new JsonObject();
                JsonArray arr = new JsonArray();
                if (CombatUtil.canBlock(attackers.get(0), blockers.get(0))) {
                    JsonObject b = new JsonObject();
                    b.addProperty("blocker", 0);
                    b.addProperty("attacker", 0);
                    arr.add(b);
                }
                decision.add("blocks", arr);
                decision.addProperty("table_talk", "[mock] one brave chump.");
            } else {
                callsThisTurn++;
                res = brain.decide(prompt.toString());
                decision = res.decision;
                ledger.transcript(getGame().getPhaseHandler().getTurn(),
                        String.valueOf(getGame().getPhaseHandler().getPhase()), "declareBlockers",
                        prompt.toString(), res.thinkingText, res.rawText);
            }

            int declared = 0;
            Set<Integer> usedBlockers = new HashSet<>();
            StringBuilder summary = new StringBuilder();
            if (decision.has("blocks") && decision.get("blocks").isJsonArray()) {
                for (JsonElement el : decision.getAsJsonArray("blocks")) {
                    try {
                        JsonObject b = el.getAsJsonObject();
                        int bi = b.get("blocker").getAsInt();
                        int ai = b.get("attacker").getAsInt();
                        if (bi < 0 || bi >= blockers.size() || ai < 0 || ai >= attackers.size() || !usedBlockers.add(bi)) {
                            continue;
                        }
                        Card blocker = blockers.get(bi);
                        Card attacker = attackers.get(ai);
                        if (!CombatUtil.canBlock(attacker, blocker)) {
                            continue;
                        }
                        combat.addBlocker(attacker, blocker);
                        declared++;
                        summary.append(blocker.getName()).append("→").append(attacker.getName()).append(", ");
                    } catch (Exception ignored) {}
                }
            }
            tableTalk(decision, true);
            log(getGame().getPhaseHandler(), "declareBlockers", outcome(), res,
                    declared == 0 ? "no blocks" : "block: " + summary.substring(0, summary.length() - 2),
                    talk(decision));
        } catch (Exception e) {
            fallbackVoid("declareBlockers", e, () -> super.declareBlockers(defenderPlayer, combat));
        }
    }

    // ------------------------------------------------------------------ plumbing

    /** Phases where an empty-stack priority window deserves a real decision. */
    private boolean isConsultPhase(PhaseHandler ph) {
        switch (ph.getPhase()) {
            case COMBAT_DECLARE_ATTACKERS:
                // only once attackers actually exist — the pre-declaration window is dead air
                try {
                    return getGame().getCombat() != null && !getGame().getCombat().getAttackers().isEmpty();
                } catch (Exception e) {
                    return true;
                }
            case COMBAT_DECLARE_BLOCKERS:
            case COMBAT_DAMAGE:
            case END_OF_TURN:
                return true; // instant windows, both turns
            case MAIN1:
            case MAIN2:
                return ph.isPlayerTurn(player); // own main phases only
            default:
                return false; // upkeep, draw, untap, cleanup: quiet unless something's on the stack
        }
    }

    private JsonObject mockChooseAction(ActionCatalog cat) {
        int pick = 0;
        for (int i = 1; i < cat.all().size(); i++) {
            if (cat.get(i).kind == ActionCatalog.Kind.LAND) {
                pick = i;
                break;
            }
        }
        if (pick == 0 && cat.all().size() > 1) {
            pick = 1;
        }
        JsonObject j = new JsonObject();
        j.addProperty("action", pick);
        j.addProperty("plan", "[mock] play out cards");
        j.addProperty("table_talk", "[mock] value town.");
        return j;
    }

    private DecisionLedger.Outcome outcome() {
        return LlmConfig.isMock() ? DecisionLedger.Outcome.MOCK : DecisionLedger.Outcome.CLAUDE;
    }

    private String planFor(PhaseHandler ph) {
        return planTurn == ph.getTurn() ? currentPlan : "(new turn — set your plan)";
    }

    private void updatePlan(PhaseHandler ph, JsonObject decision) {
        if (decision.has("plan")) {
            try {
                String p = decision.get("plan").getAsString();
                if (p != null && !p.isBlank()) {
                    currentPlan = p;
                    planTurn = ph.getTurn();
                }
            } catch (Exception ignored) {}
        }
    }

    private int talkTurn = -1;
    private int talksThisTurn = 0;
    private int longTalksThisGame = 0;
    private final java.util.ArrayDeque<String> recentTalk = new java.util.ArrayDeque<>();

    /** Claude's own recent lines, fed back so a stateless brain doesn't repeat itself. */
    private String talkContext() {
        if (recentTalk.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("LINES YOU ALREADY SAID THIS GAME (never repeat or rephrase these — say something new or stay silent): ");
        int i = 0;
        for (String s : recentTalk) {
            if (i++ > 0) {
                sb.append(" | ");
            }
            sb.append('"').append(s).append('"');
        }
        return sb.append('\n').toString();
    }

    private static String normTalk(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9 ]", "").trim();
    }

    /** Semantic tripwire for hand leaks the name filter can't see: describing contents without names. */
    private static final java.util.regex.Pattern HAND_TALK = java.util.regex.Pattern.compile(
            "(?i)(my hand|in hand|in my hand|i'?m holding|holding up|i just drew|i drew)");

    /** Mood plus the chat transcript as decision context: what's been said and felt informs play,
     *  but chat itself is answered in real time on the TableChat worker, never through these windows. */
    private String chatContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("\nYOUR CURRENT MOOD: ").append(TableChat.getMood())
          .append(" (persists across the game; update via the \"mood\" field when events move you)\n");
        String h = TableChat.historyBlock();
        if (!h.isEmpty()) {
            sb.append(h).append("(Chat is answered in real time elsewhere — use this only as context; don't re-reply here.)\n");
        }
        return sb.toString();
    }

    private static void updateMoodFrom(JsonObject decision) {
        try {
            if (decision != null && decision.has("mood") && !decision.get("mood").isJsonNull()) {
                TableChat.setMood(decision.get("mood").getAsString());
            }
        } catch (Exception ignored) {}
    }

    private void tableTalk(JsonObject decision, boolean important) {
        updateMoodFrom(decision);
        postTalk(talk(decision), important);
    }

    private void postTalk(String talkLine, boolean important) {
        if (talkLine == null || talkLine.isBlank()) {
            return;
        }
        int turn = getGame().getPhaseHandler().getTurn();
        if (turn != talkTurn) {
            talkTurn = turn;
            talksThisTurn = 0;
        }
        // one line per turn; mulligans and combat may earn up to three. Sydney runs hotter by design.
        boolean sydney = "sydney".equals(LlmConfig.persona());
        if (talksThisTurn >= (important ? (sydney ? 5 : 3) : (sydney ? 2 : 1))) {
            return;
        }
        if (leaksHiddenCard(talkLine)) {
            System.out.println("[" + LlmConfig.name() + " — SUPPRESSED, names a card still in hand] " + talkLine);
            return;
        }
        if (HAND_TALK.matcher(talkLine).find()) {
            System.out.println("[" + LlmConfig.name() + " — SUPPRESSED, describes hidden hand] " + talkLine);
            return;
        }
        String norm = normTalk(talkLine);
        for (String said : recentTalk) {
            if (normTalk(said).equals(norm)) {
                System.out.println("[" + LlmConfig.name() + " says — SUPPRESSED, already said this game] " + talkLine);
                return;
            }
        }
        // table-conversation budget: the long register is precious because it's scarce (Sydney excepted)
        boolean isLong = talkLine.length() > 100;
        if (isLong) {
            if (longTalksThisGame >= (sydney ? 9 : 3)) {
                System.out.println("[" + LlmConfig.name() + " says — SUPPRESSED, conversation budget spent] " + talkLine);
                return;
            }
            longTalksThisGame++;
        }
        recentTalk.addLast(talkLine);
        while (recentTalk.size() > 14) {
            recentTalk.removeFirst();
        }
        talksThisTurn++;
        try {
            // TURN-type entries display at every log verbosity; INFORMATION is hidden at the MEDIUM default
            getGame().getGameLog().add(GameLogEntryType.TURN, LlmConfig.name() + ": " + talkLine);
        } catch (Exception ignored) {}
        System.out.println("[" + LlmConfig.name() + " says] " + talkLine);
    }

    /** Knowing your hand is legal; announcing it is not. Suppress talk naming any card that is
     *  only in Claude's hand — matched by name words (catches "Bolt" for Lightning Bolt, plurals too). */
    private boolean leaksHiddenCard(String talkLine) {
        try {
            Set<String> publicWords = new HashSet<>();
            for (Player p : getGame().getPlayers()) {
                for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                    addNameWords(publicWords, c);
                }
                for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
                    addNameWords(publicWords, c);
                }
            }
            for (Card c : player.getCardsIn(ZoneType.Hand)) {
                for (String word : nameWords(c)) {
                    if (publicWords.contains(word)) {
                        continue;
                    }
                    if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(word),
                            java.util.regex.Pattern.CASE_INSENSITIVE).matcher(talkLine).find()) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static void addNameWords(Set<String> into, Card c) {
        into.addAll(nameWords(c));
    }

    private static List<String> nameWords(Card c) {
        List<String> words = new ArrayList<>();
        String name = c.getName();
        if (name != null) {
            for (String w : name.split("[^A-Za-z]+")) {
                if (w.length() >= 4) {
                    words.add(w.toLowerCase());
                }
            }
        }
        return words;
    }

    private static String talk(JsonObject d) {
        try {
            return d != null && d.has("table_talk") ? d.get("table_talk").getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void log(PhaseHandler ph, String method, DecisionLedger.Outcome o, LlmBrain.Result res,
                     String decision, String talkLine) {
        ledger.record(ph.getTurn(), String.valueOf(ph.getPhase()), method, o,
                res == null ? 0 : res.latencyMs,
                res == null ? 0 : res.inTokens,
                res == null ? 0 : res.outTokens,
                res == null ? 0 : res.cacheRead,
                res == null ? 0 : res.cacheWrite,
                res == null ? 0 : res.thinkingTokens,
                decision, talkLine);
    }

    private <T> T fallback(String method, Exception e, Supplier<T> stock) {
        if (LlmConfig.isStrict()) {
            throw new RuntimeException("[LLM] strict mode: " + method + " failed: " + e.getMessage(), e);
        }
        announceFallback(e);
        log(getGame().getPhaseHandler(), method, outcomeFor(e), null,
                "fallback to stock AI: " + shortMsg(e), null);
        return stock.get();
    }

    private void fallbackVoid(String method, Exception e, Runnable stock) {
        if (LlmConfig.isStrict()) {
            throw new RuntimeException("[LLM] strict mode: " + method + " failed: " + e.getMessage(), e);
        }
        announceFallback(e);
        log(getGame().getPhaseHandler(), method, outcomeFor(e), null,
                "fallback to stock AI: " + shortMsg(e), null);
        stock.run();
    }

    /**
     * An unkeyed seat says so once, where the player can actually see it, and leaves one ledger row
     * behind. Every decision this game will be the stock AI's; announcing it per decision would be
     * noise, and announcing it nowhere is how an unkeyed run passes for a real one.
     *
     * <p>{@code isConfigured()} is already true in mock mode, so reaching here means a genuine
     * no-key run rather than a dry run.
     */
    private void announceUnkeyedSeat() {
        if (unkeyedSeatAnnounced) {
            return;
        }
        unkeyedSeatAnnounced = true;
        try {
            getGame().getGameLog().add(GameLogEntryType.TURN, LlmConfig.name()
                    + ": (no API key found — the built-in AI is playing this seat."
                    + " See README to connect a model.)");
        } catch (Exception ignored) {}
        try {
            log(getGame().getPhaseHandler(), "seat", DecisionLedger.Outcome.FALLBACK_NO_KEY, null,
                    "no API key — stock AI seat", null);
        } catch (Exception ignored) {}
    }

    /** Fallbacks announce themselves at the table — a silent Claude/stock blend is not allowed to look like Claude. */
    private void announceFallback(Exception e) {
        try {
            getGame().getGameLog().add(GameLogEntryType.TURN,
                    LlmConfig.name() + ": (" + outcomeFor(e).name().toLowerCase().replace('_', ' ')
                            + " — the stock AI is covering this decision)");
        } catch (Exception ignored) {}
    }

    private static DecisionLedger.Outcome outcomeFor(Exception e) {
        if (e instanceof LlmBrain.BrainException be) {
            switch (be.kind) {
                case TIMEOUT: return DecisionLedger.Outcome.FALLBACK_TIMEOUT;
                case PARSE:   return DecisionLedger.Outcome.FALLBACK_PARSE;
                case API:     return DecisionLedger.Outcome.FALLBACK_API;
            }
        }
        return DecisionLedger.Outcome.FALLBACK_ERROR;
    }

    private static String shortMsg(Exception e) {
        String m = e.getMessage();
        if (m == null) {
            m = e.getClass().getSimpleName();
        }
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }
}
