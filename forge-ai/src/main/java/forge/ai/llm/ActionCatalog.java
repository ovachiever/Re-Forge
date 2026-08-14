package forge.ai.llm;

import forge.ai.AiController;
import forge.ai.ComputerUtilAbility;
import forge.ai.ComputerUtilCard;
import forge.ai.ComputerUtilCost;
import forge.ai.simulation.SpellAbilityPicker;
import forge.game.Game;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Enumerates the legal, affordable actions in the current priority window as a numbered menu.
 * Claude answers with an index, which makes an illegal move structurally impossible.
 */
public final class ActionCatalog {

    public enum Kind { PASS, LAND, SPELL }

    public static final class Option {
        public final Kind kind;
        public final SpellAbility sa;
        public final Card land;
        public final String label;
        /** Claude-staged target for this line (null = untargeted or stock-staged fallback). */
        public final forge.game.GameObject target;
        /** Max announceable X for this line; -1 = no X involved. */
        public final int maxX;

        Option(Kind kind, SpellAbility sa, Card land, String label) {
            this(kind, sa, land, label, null, -1);
        }

        Option(Kind kind, SpellAbility sa, Card land, String label, forge.game.GameObject target, int maxX) {
            this.kind = kind;
            this.sa = sa;
            this.land = land;
            this.label = label;
            this.target = target;
            this.maxX = maxX;
        }
    }

    private final List<Option> options = new ArrayList<>();

    private ActionCatalog() {}

    public static ActionCatalog build(Game game, Player player, AiController brains) {
        ActionCatalog cat = new ActionCatalog();
        cat.options.add(new Option(Kind.PASS, null, null, "Pass priority (do nothing)"));

        try {
            CardCollection lands = ComputerUtilAbility.getAvailableLandsToPlay(game, player);
            if (lands != null) {
                Set<String> seen = new HashSet<>();
                for (Card land : GameStateSerializer.sorted(lands)) {
                    if (seen.add(land.getName())) {
                        cat.options.add(new Option(Kind.LAND, null, land, "Play land: " + land.getName()));
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            CardCollection cards = ComputerUtilAbility.getAvailableCards(game, player);
            cards = ComputerUtilCard.dedupeCards(cards);
            for (SpellAbility sa : ComputerUtilAbility.getSpellAbilities(cards, player)) {
                try {
                    if (sa == null || sa.isLandAbility() || sa.isSkip()) {
                        continue;
                    }
                    if (!sa.canPlay()) {
                        continue;
                    }
                    if (!ComputerUtilCost.canPayCost(sa, player, false)) {
                        continue;
                    }
                    boolean targeted = false;
                    boolean hasX = false;
                    try {
                        targeted = sa.usesTargeting();
                        hasX = sa.getPayCosts() != null && sa.getPayCosts().hasXInAnyCostPart();
                    } catch (Exception ignored) {}

                    // General expansion: single-target spells become complete lines — one option per legal
                    // target, targets staged by Claude's choice, X chosen by Claude. Mechanic-agnostic.
                    List<Option> lines = targeted ? expandSingleTarget(sa, player, hasX) : null;
                    if (lines != null && !lines.isEmpty()) {
                        cat.options.addAll(lines);
                        continue;
                    }

                    // Fallback executor: spells the expander can't parameterize (multi-target, sub-ability
                    // targeting, modal). Stock stages these; it must be willing or the cast degenerates.
                    if ((targeted || hasX) && brains != null) {
                        boolean stockCanAim;
                        try {
                            stockCanAim = brains.canPlaySa(sa).willingToPlay();
                        } catch (Exception e) {
                            stockCanAim = false;
                        }
                        if (!stockCanAim) {
                            continue;
                        }
                    }
                    cat.options.add(new Option(Kind.SPELL, sa, null, baseLabel(sa)));
                } catch (Exception ignored) {
                    // an ability that errors during evaluation is simply not offered
                }
            }
        } catch (Exception ignored) {}

        return cat;
    }

    private static String baseLabel(SpellAbility sa) {
        try {
            return SpellAbilityPicker.abilityToString(sa).replace(" (targets: [])", "");
        } catch (Exception e) {
            return String.valueOf(sa);
        }
    }

    /** One complete line per legal target for root-level single-target spells; null = not expandable here. */
    private static List<Option> expandSingleTarget(SpellAbility sa, Player player, boolean hasX) {
        try {
            forge.game.spellability.TargetRestrictions tgt = sa.getTargetRestrictions();
            if (tgt == null) {
                return null;
            }
            sa.setActivatingPlayer(player);
            sa.resetTargets();
            if (tgt.getMinTargets(sa.getHostCard(), sa) > 1) {
                return null; // true multi-target minimums stay on the stock-staged fallback
            }
            // "Any number of targets" spells (old Fireball) are offered as focused single-target
            // lines — everything at one target. Split-fire across several targets can come later.
            List<forge.game.GameObject> candidates = new ArrayList<>();
            for (forge.game.GameObject o : tgt.getAllCandidates(sa)) {
                candidates.add(o);
                if (candidates.size() >= 12) {
                    break; // menu sanity; 90s boards rarely exceed this
                }
            }
            if (candidates.isEmpty()) {
                return null;
            }
            int maxX = -1;
            if (hasX) {
                try {
                    maxX = Math.max(0, forge.ai.ComputerUtilMana.getAvailableManaEstimate(player, true)
                            - sa.getPayCosts().getTotalMana().getCMC());
                } catch (Exception e) {
                    maxX = -1;
                }
            }
            List<Option> lines = new ArrayList<>();
            String base = baseLabel(sa);
            for (forge.game.GameObject o : candidates) {
                String label = base + "  →  " + describeTarget(o)
                        + (maxX >= 0 ? "  [you choose X, up to " + maxX + "]" : "");
                lines.add(new Option(Kind.SPELL, sa, null, label, o, maxX));
            }
            return lines;
        } catch (Exception e) {
            return null; // any wobble: fall back to the stock-staged path
        }
    }

    private static String describeTarget(forge.game.GameObject o) {
        if (o instanceof Card c) {
            return GameStateSerializer.describePermanent(c, false);
        }
        if (o instanceof Player p) {
            return "Player " + p.getName() + " (" + p.getLife() + " life)";
        }
        return String.valueOf(o);
    }

    public boolean hasRealChoices() {
        return options.size() > 1;
    }

    public List<Option> all() {
        return options;
    }

    public Option get(int idx) {
        return idx >= 0 && idx < options.size() ? options.get(idx) : null;
    }

    public String menu() {
        StringBuilder sb = new StringBuilder("LEGAL ACTIONS:\n");
        for (int i = 0; i < options.size(); i++) {
            sb.append("  ").append(i).append(". ").append(options.get(i).label).append('\n');
        }
        return sb.toString();
    }
}
