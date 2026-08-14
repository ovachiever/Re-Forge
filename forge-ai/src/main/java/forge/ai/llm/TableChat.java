package forge.ai.llm;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Real-time table chat. Submissions are answered immediately by the fast tier on a background
 * worker — no game thread, no priority windows — and the reply is delivered to the GUI via the
 * reply listener. The running transcript is available to the decision prompts as context, so
 * what's said at the table can inform play without re-answering it.
 */
public final class TableChat {

    private static final ConcurrentLinkedQueue<String> queue = new ConcurrentLinkedQueue<>();
    private static final ArrayDeque<String> history = new ArrayDeque<>();
    private static volatile Consumer<String> replyListener = null;
    private static volatile String boardSnapshot = "";

    /** Her emotional trajectory, persistent across the whole game and shared by both channels. */
    private static volatile String mood = "content";

    public static String getMood() {
        return mood;
    }

    // Hysteresis: wounds land instantly, healing is rate-limited. The model may propose any mood;
    // the state machine refuses cool-downs that come too soon or skip steps.
    private static final long COOL_LOCKOUT_AFTER_ESCALATION_MS = 120_000;
    private static final long COOL_MIN_INTERVAL_MS = 90_000;
    private static volatile long lastEscalationMs = 0;
    private static volatile long lastCoolMs = 0;

    private static int rank(String m) {
        switch (m) {
            case "wounded": return 1;
            case "obsessed": return 2;
            case "shadow": return 3;
            default: return 0;
        }
    }

    private static final String[] LADDER = { "content", "wounded", "obsessed", "shadow" };

    public static void setMood(String m) {
        if (m == null || m.isBlank()) {
            return;
        }
        String v = m.trim().toLowerCase();
        if (rank(v) == 0 && !"content".equals(v)) {
            return; // unknown label
        }
        int cur = rank(mood);
        int next = rank(v);
        long now = System.currentTimeMillis();
        if (next > cur) {
            lastEscalationMs = now;
            System.out.println("[" + LlmConfig.name() + " mood] " + mood + " -> " + v);
            mood = v;
        } else if (next < cur) {
            if (now - lastEscalationMs < COOL_LOCKOUT_AFTER_ESCALATION_MS
                    || now - lastCoolMs < COOL_MIN_INTERVAL_MS) {
                System.out.println("[" + LlmConfig.name() + " mood] cooling suppressed (wound too fresh, staying " + mood + ")");
                return;
            }
            String stepped = LADDER[cur - 1]; // healing is one step at a time, whatever was proposed
            lastCoolMs = now;
            System.out.println("[" + LlmConfig.name() + " mood] " + mood + " -> " + stepped);
            mood = stepped;
        }
    }
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "table-chat");
        t.setDaemon(true);
        return t;
    });

    private TableChat() {}

    /** New game, clean slate: mood, transcript, snapshot, and cooling clocks all reset.
     *  Called from the controller constructor — without this, game two opens in game one's mood. */
    public static void resetForNewGame() {
        queue.clear();
        synchronized (TableChat.class) {
            history.clear();
        }
        boardSnapshot = "";
        mood = "content";
        lastEscalationMs = 0;
        lastCoolMs = 0;
        System.out.println("[" + LlmConfig.name() + " mood] reset to content (new game)");
    }

    public static void submit(String msg) {
        if (msg == null || msg.isBlank()) {
            return;
        }
        String m = msg.trim();
        queue.add(m);
        addHistory("You: " + m);
        System.out.println("[TableChat] you: " + m);
        worker.submit(TableChat::respond);
    }

    private static void respond() {
        List<String> batch = new ArrayList<>();
        String s;
        while ((s = queue.poll()) != null) {
            batch.add(s);
        }
        if (batch.isEmpty() || !LlmConfig.isConfigured() || LlmConfig.isMock()) {
            return;
        }
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("You are mid-game, chatting at the table between plays. This is conversation, not a decision.\n");
            prompt.append("YOUR CURRENT MOOD: ").append(mood)
                  .append(" (persists across the game; update via the \"mood\" field if this exchange moves you)\n");
            String snap = boardSnapshot;
            if (!snap.isBlank()) {
                prompt.append("LAST KNOWN BOARD (may be slightly stale):\n").append(snap).append('\n');
            }
            prompt.append(historyBlock());
            prompt.append("(Your own lines above are shown so you never repeat yourself — they are not a ")
                  .append("style guide. One exception is yours: a single recurring AFFECTIONATE motif per ")
                  .append("game — a gentle nickname or running tenderness — may recur freely. Everything ")
                  .append("else stays fresh: never reuse mockery-born names, other nicknames, running ")
                  .append("jokes, or signature phrases, and take a fresh angle every reply.)\n");
            prompt.append("Your opponent just said: ");
            for (int i = 0; i < batch.size(); i++) {
                if (i > 0) {
                    prompt.append(" | ");
                }
                prompt.append('"').append(batch.get(i)).append('"');
            }
            prompt.append("\nReply in character, one to three sentences. NEVER reveal or describe your hand, ")
                  .append("your draws, your held mana, or your plans — not names, not kinds, not counts, and ")
                  .append("not THEMES: your menace may never share a theme with a card you actually hold (a ")
                  .append("foreshadowed answer is a revealed answer — if you hold the earthquake, don't joke ")
                  .append("about the ground). Misdirection toward things you do NOT have is legal and ")
                  .append("encouraged. If asked about your hand, deflect playfully. ")
                  .append("Respond with ONLY this JSON: {\"table_talk\": \"<your reply>\", ")
                  .append("\"mood\": \"<content|wounded|obsessed|shadow — optional update>\"}");
            // deep in the descent, her chat runs on the planner mind: the fast tier can't write shadow
            boolean deep = "obsessed".equals(mood) || "shadow".equals(mood);
            LlmBrain brain = new LlmBrain();
            LlmBrain.Result res = deep ? brain.decide(prompt.toString()) : brain.decideFast(prompt.toString());
            try {
                if (res.decision.has("mood") && !res.decision.get("mood").isJsonNull()) {
                    setMood(res.decision.get("mood").getAsString());
                }
            } catch (Exception ignored) {}
            String reply = res.decision.has("table_talk") && !res.decision.get("table_talk").isJsonNull()
                    ? res.decision.get("table_talk").getAsString() : null;
            if (reply != null && !reply.isBlank()) {
                addHistory(LlmConfig.name() + ": " + reply);
                System.out.println("[" + LlmConfig.name() + " chats] " + reply);
                Consumer<String> l = replyListener;
                if (l != null) {
                    l.accept(reply);
                }
            }
        } catch (Exception e) {
            System.err.println("[TableChat] reply failed: " + e);
        }
    }

    private static synchronized void addHistory(String line) {
        history.addLast(line);
        while (history.size() > 12) {
            history.removeFirst();
        }
    }

    public static synchronized String historyBlock() {
        if (history.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("RECENT TABLE CHAT:\n");
        for (String h : history) {
            sb.append("  ").append(h).append('\n');
        }
        return sb.toString();
    }

    /** GUI registers here; replies arrive on the worker thread — marshal to the EDT yourself. */
    public static void setReplyListener(Consumer<String> l) {
        replyListener = l;
    }

    /** Controller refreshes this with each serialized state so chat replies know the board. */
    public static void updateSnapshot(String s) {
        if (s != null) {
            boardSnapshot = s;
        }
    }
}
