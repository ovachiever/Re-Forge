package forge.ai.llm;

import forge.game.Game;
import forge.game.card.Card;
import forge.game.phase.PhaseHandler;
import forge.game.player.Player;
import forge.game.spellability.SpellAbilityStackInstance;
import forge.game.zone.ZoneType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Renders the game state as compact text from one player's perspective.
 *
 * Censorship is by construction: the text-producing methods only ever read zones that player
 * may legally see — own hand, both battlefields, graveyards, face-up exile, the stack, and
 * SIZES of hidden zones. The leak check at the end is a tripwire, not the guarantee.
 */
public final class GameStateSerializer {
    private GameStateSerializer() {}

    public static String serialize(Game game, Player self) {
        StringBuilder sb = new StringBuilder(4096);
        PhaseHandler ph = game.getPhaseHandler();
        sb.append("TURN ").append(ph.getTurn()).append(" — PHASE ").append(ph.getPhase())
          .append(" — ").append(ph.getPlayerTurn() == self ? "YOUR turn" : "OPPONENT'S turn").append('\n');

        sb.append("YOU (").append(self.getName()).append("): ").append(self.getLife()).append(" life, ")
          .append(self.getCardsIn(ZoneType.Hand).size()).append(" cards in hand, ")
          .append(self.getCardsIn(ZoneType.Library).size()).append(" cards in library");
        appendPoison(sb, self);
        sb.append('\n');
        for (Player opp : game.getPlayers()) {
            if (opp == self) {
                continue;
            }
            sb.append("OPPONENT (").append(opp.getName()).append("): ").append(opp.getLife()).append(" life, ")
              .append(opp.getCardsIn(ZoneType.Hand).size()).append(" cards in hand (hidden), ")
              .append(opp.getCardsIn(ZoneType.Library).size()).append(" cards in library");
            appendPoison(sb, opp);
            sb.append('\n');
        }

        sb.append("\nYOUR HAND:\n");
        List<Card> hand = sorted(self.getCardsIn(ZoneType.Hand));
        if (hand.isEmpty()) {
            sb.append("  (empty)\n");
        }
        for (Card c : hand) {
            sb.append("  - ").append(describeCardFull(c)).append('\n');
        }

        appendBattlefield(sb, self, self, "YOUR BATTLEFIELD");
        for (Player opp : game.getPlayers()) {
            if (opp != self) {
                appendBattlefield(sb, opp, self, "OPPONENT BATTLEFIELD (" + opp.getName() + ")");
            }
        }

        for (Player p : game.getPlayers()) {
            List<Card> gy = sorted(p.getCardsIn(ZoneType.Graveyard));
            if (!gy.isEmpty()) {
                sb.append(p == self ? "YOUR GRAVEYARD: " : "OPPONENT GRAVEYARD: ");
                appendNameList(sb, gy);
                sb.append('\n');
            }
        }
        for (Player p : game.getPlayers()) {
            int hiddenExile = 0;
            List<Card> visibleExile = new ArrayList<>();
            for (Card c : sorted(p.getCardsIn(ZoneType.Exile))) {
                if (c.isFaceDown() && p != self) {
                    hiddenExile++;
                } else {
                    visibleExile.add(c);
                }
            }
            if (!visibleExile.isEmpty() || hiddenExile > 0) {
                sb.append(p == self ? "YOUR EXILE: " : "OPPONENT EXILE: ");
                appendNameList(sb, visibleExile);
                if (hiddenExile > 0) {
                    sb.append(visibleExile.isEmpty() ? "" : ", ").append(hiddenExile).append(" face-down card(s)");
                }
                sb.append('\n');
            }
        }

        sb.append("\nSTACK");
        if (game.getStack().isEmpty()) {
            sb.append(": empty\n");
        } else {
            sb.append(" (top resolves first):\n");
            for (SpellAbilityStackInstance si : game.getStack()) {
                try {
                    sb.append("  - ").append(si.getStackDescription())
                      .append(" [").append(si.getActivatingPlayer() == self ? "yours" : "opponent's").append("]\n");
                } catch (Exception e) {
                    sb.append("  - (stack item)\n");
                }
            }
        }

        int untappedLands = 0;
        for (Card c : self.getCardsIn(ZoneType.Battlefield)) {
            if (c.isLand() && !c.isTapped()) {
                untappedLands++;
            }
        }
        sb.append("YOUR UNTAPPED LANDS: ").append(untappedLands).append('\n');

        String out = sb.toString();
        leakCheck(game, self, out);
        TableChat.updateSnapshot(out);
        return out;
    }

    /** Combat context for block decisions: who is attacking what. */
    public static String serializeCombat(forge.game.combat.Combat combat, Player self) {
        StringBuilder sb = new StringBuilder("\nCOMBAT — attackers declared:\n");
        for (Card a : sorted(combat.getAttackers())) {
            Object def;
            try {
                def = combat.getDefenderByAttacker(a);
            } catch (Exception e) {
                def = null;
            }
            sb.append("  - ").append(describePermanent(a, a.getController() == self))
              .append(" attacking ").append(def == self ? "YOU" : String.valueOf(def)).append('\n');
        }
        return sb.toString();
    }

    private static void appendPoison(StringBuilder sb, Player p) {
        try {
            if (p.getPoisonCounters() > 0) {
                sb.append(", ").append(p.getPoisonCounters()).append(" poison");
            }
        } catch (Exception ignored) {}
    }

    private static void appendBattlefield(StringBuilder sb, Player owner, Player self, String header) {
        sb.append('\n').append(header).append(":\n");
        List<Card> cards = sorted(owner.getCardsIn(ZoneType.Battlefield));
        if (cards.isEmpty()) {
            sb.append("  (empty)\n");
        }
        for (Card c : cards) {
            sb.append("  - ").append(describePermanent(c, owner == self)).append('\n');
        }
    }

    private static void appendNameList(StringBuilder sb, List<Card> cards) {
        for (int i = 0; i < cards.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(cards.get(i).getName());
        }
    }

    /** Full description with cost, type, P/T and rules text — for cards whose identity the player may see. */
    public static String describeCardFull(Card c) {
        StringBuilder sb = new StringBuilder(c.getName());
        try {
            String cost = c.getManaCost() == null ? "" : c.getManaCost().toString();
            if (!cost.isEmpty() && !"no cost".equals(cost)) {
                sb.append(" {").append(cost).append('}');
            }
        } catch (Exception ignored) {}
        sb.append(" — ").append(c.getType());
        if (c.isCreature()) {
            sb.append(' ').append(c.getNetPower()).append('/').append(c.getNetToughness());
        }
        try {
            String text = c.getOracleText();
            if (text != null && !text.isBlank()) {
                text = text.replace('\n', ' ').replace('\r', ' ').trim();
                if (text.length() > 240) {
                    text = text.substring(0, 240) + "…";
                }
                sb.append(" — ").append(text);
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** Battlefield description honoring face-down censorship for cards the player doesn't own. */
    public static String describePermanent(Card c, boolean own) {
        if (c.isFaceDown() && !own) {
            return "Face-down " + (c.isCreature() ? c.getNetPower() + "/" + c.getNetToughness() + " creature" : "permanent")
                    + (c.isTapped() ? " [tapped]" : " [untapped]");
        }
        StringBuilder sb = new StringBuilder(describeCardFull(c));
        sb.append(c.isTapped() ? " [tapped]" : " [untapped]");
        if (c.isCreature()) {
            if (c.getDamage() > 0) {
                sb.append(" [").append(c.getDamage()).append(" damage marked]");
            }
            try {
                if (c.isSick()) {
                    sb.append(" [summoning sick]");
                }
            } catch (Exception ignored) {}
        }
        try {
            var counters = c.getCounters();
            if (counters != null && !counters.isEmpty()) {
                sb.append(" [counters:");
                for (var e : counters.entrySet()) {
                    sb.append(' ').append(e.getElement()).append('=').append(e.getCount());
                }
                sb.append(']');
            }
        } catch (Exception ignored) {}
        return sb.toString();
    }

    /** Public-info permanent description (used for combat menus). */
    public static String describePermanentPublic(Card c) {
        return describePermanent(c, false);
    }

    public static List<Card> sorted(Iterable<Card> cards) {
        List<Card> l = new ArrayList<>();
        if (cards != null) {
            for (Card c : cards) {
                l.add(c);
            }
        }
        l.sort(Comparator.comparingInt(Card::getId));
        return l;
    }

    /**
     * Tripwire: no card name that exists ONLY in a hidden zone may appear in the output.
     * Names also present in a visible zone are excluded (another copy may be legitimately shown).
     */
    private static void leakCheck(Game game, Player self, String output) {
        Set<String> visible = new HashSet<>();
        StringBuilder visibleText = new StringBuilder();
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Battlefield)) {
                if (!c.isFaceDown() || p == self) {
                    noteVisible(c, visible, visibleText);
                }
            }
            for (Card c : p.getCardsIn(ZoneType.Graveyard)) {
                noteVisible(c, visible, visibleText);
            }
            for (Card c : p.getCardsIn(ZoneType.Exile)) {
                if (!c.isFaceDown()) {
                    noteVisible(c, visible, visibleText);
                }
            }
            if (p == self) {
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    noteVisible(c, visible, visibleText);
                }
            }
        }
        String publicText = visibleText.toString();
        List<String> leaks = new ArrayList<>();
        for (Player p : game.getPlayers()) {
            for (Card c : p.getCardsIn(ZoneType.Library)) {
                checkHidden(output, visible, publicText, c.getName(), "library", leaks);
            }
            if (p != self) {
                for (Card c : p.getCardsIn(ZoneType.Hand)) {
                    checkHidden(output, visible, publicText, c.getName(), "opponent hand", leaks);
                }
            }
        }
        if (!leaks.isEmpty()) {
            String msg = "[LLM][LEAK] hidden card names in serialized state: " + leaks;
            if (LlmConfig.hardLeakCheck()) {
                throw new AssertionError(msg);
            }
            System.err.println(msg);
        }
    }

    private static void noteVisible(Card c, Set<String> visible, StringBuilder visibleText) {
        visible.add(c.getName());
        try {
            String t = c.getOracleText();
            if (t != null) {
                visibleText.append(t).append('\n');
            }
        } catch (Exception ignored) {}
    }

    private static void checkHidden(String output, Set<String> visible, String publicText,
                                    String name, String zone, List<String> leaks) {
        if (name == null || name.isEmpty() || visible.contains(name)) {
            return;
        }
        // A hidden card's name mentioned by a visible card's rules text is public information, not a leak.
        if (publicText.contains(name)) {
            return;
        }
        if (output.contains(name)) {
            leaks.add(name + " (" + zone + ")");
        }
    }
}
