package forge.ai.llm;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import forge.game.GameObject;
import forge.game.card.Card;
import forge.game.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * One turn's worth of orders from the planner tier, executed locally window by window.
 * Steps match against each window's freshly built ActionCatalog by card name and target name,
 * so a spell that only becomes castable mid-turn still finds its option when its moment comes.
 */
final class TurnPlan {

    static final class Step {
        final String phase;    // MAIN1 | COMBAT | MAIN2 | EOT
        final String card;     // card to cast, or land to play
        final boolean isLand;
        final String target;   // target name, "opponent", or null
        final Integer x;
        final String say;      // optional planner-authored line delivered when this step executes
        boolean done;

        Step(String phase, String card, boolean isLand, String target, Integer x, String say) {
            this.phase = phase;
            this.card = card;
            this.isLand = isLand;
            this.target = target;
            this.x = x;
            this.say = say;
        }
    }

    final int turn;
    final List<Step> steps = new ArrayList<>();
    final boolean attacksDecided;
    final List<String> attacks = new ArrayList<>();
    final String contingencies;
    final String summary;

    private TurnPlan(int turn, boolean attacksDecided, String contingencies, String summary) {
        this.turn = turn;
        this.attacksDecided = attacksDecided;
        this.contingencies = contingencies;
        this.summary = summary;
    }

    static TurnPlan parse(JsonObject j, int turn) {
        boolean hasAttacks = j.has("attacks") && j.get("attacks").isJsonArray();
        TurnPlan p = new TurnPlan(turn, hasAttacks,
                optString(j, "contingencies"), optString(j, "plan"));
        if (j.has("plan_steps") && j.get("plan_steps").isJsonArray()) {
            for (JsonElement el : j.getAsJsonArray("plan_steps")) {
                try {
                    JsonObject s = el.getAsJsonObject();
                    String land = optString(s, "land");
                    String cast = optString(s, "cast");
                    if (land == null && cast == null) {
                        continue;
                    }
                    Integer x = s.has("x") && !s.get("x").isJsonNull() ? s.get("x").getAsInt() : null;
                    p.steps.add(new Step(normPhase(optString(s, "phase")),
                            land != null ? land : cast, land != null, optString(s, "target"), x,
                            optString(s, "say")));
                } catch (Exception ignored) {}
            }
        }
        if (hasAttacks) {
            for (JsonElement el : j.getAsJsonArray("attacks")) {
                try {
                    p.attacks.add(el.getAsString());
                } catch (Exception ignored) {}
            }
        }
        return p;
    }

    private static String normPhase(String s) {
        if (s == null) {
            return "MAIN1";
        }
        String u = s.toUpperCase();
        if (u.contains("2")) {
            return "MAIN2";
        }
        if (u.contains("COMBAT") || u.contains("ATTACK")) {
            return "COMBAT";
        }
        if (u.contains("END") || u.contains("EOT")) {
            return "EOT";
        }
        return "MAIN1";
    }

    private static String optString(JsonObject j, String key) {
        try {
            return j.has(key) && !j.get(key).isJsonNull() ? j.get(key).getAsString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** Next undone step appropriate for the current phase; MAIN2 also drains anything left over. */
    Step nextFor(String currentPhase) {
        String want = normPhase(currentPhase);
        for (Step s : steps) {
            if (s.done) {
                continue;
            }
            if (s.phase.equals(want) || (want.equals("MAIN2") && (s.phase.equals("MAIN1") || s.phase.equals("MAIN2")))) {
                return s;
            }
        }
        return null;
    }

    boolean hasUndoneSteps() {
        for (Step s : steps) {
            if (!s.done) {
                return true;
            }
        }
        return false;
    }

    /** Match a plan step against the current window's menu. Name-based, target-aware, forgiving. */
    static ActionCatalog.Option findOption(ActionCatalog cat, Step step) {
        for (ActionCatalog.Option o : cat.all()) {
            if (step.isLand) {
                if (o.kind == ActionCatalog.Kind.LAND && o.land != null
                        && o.land.getName().equalsIgnoreCase(step.card)) {
                    return o;
                }
                continue;
            }
            if (o.kind != ActionCatalog.Kind.SPELL || o.sa == null) {
                continue;
            }
            String host;
            try {
                host = o.sa.getHostCard() != null ? o.sa.getHostCard().getName() : "";
            } catch (Exception e) {
                host = "";
            }
            if (!host.equalsIgnoreCase(step.card)) {
                continue;
            }
            if (step.target == null) {
                return o; // untargeted step: first option of that card (covers untargeted spells)
            }
            if (o.target == null) {
                continue;
            }
            if (targetMatches(o.target, step.target)) {
                return o;
            }
        }
        return null;
    }

    private static boolean targetMatches(GameObject target, String wanted) {
        String w = wanted.toLowerCase();
        if (target instanceof Player p) {
            return w.contains("opponent") || w.contains("player") || w.contains("face")
                    || p.getName().toLowerCase().contains(w) || w.contains(p.getName().toLowerCase());
        }
        if (target instanceof Card c) {
            String n = c.getName().toLowerCase();
            return n.contains(w) || w.contains(n);
        }
        return String.valueOf(target).toLowerCase().contains(w);
    }
}
