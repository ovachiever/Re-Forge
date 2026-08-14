package forge.ai.llm;

import java.util.Locale;

/**
 * Runtime configuration for the LLM-piloted controller.
 * System properties override the environment; everything has a playable default.
 *
 * <p>Every property is read as {@code llm.X} first, then the legacy {@code claude.X},
 * then the built-in default — so pre-BYOM launch scripts keep working unchanged.
 */
public final class LlmConfig {
    private LlmConfig() {}

    /** AI profile name that routes a lobby seat to LlmPlayerController. */
    public static final String PROFILE_NAME = "Claude";

    // ------------------------------------------------------------------ property plumbing

    /** {@code llm.<suffix>} wins, {@code claude.<suffix>} is the legacy fallback, then the default. */
    static String prop(String suffix, String def) {
        String v = System.getProperty("llm." + suffix);
        if (v == null || v.isBlank()) {
            v = System.getProperty("claude." + suffix);
        }
        return v == null || v.isBlank() ? def : v;
    }

    /** First set property among the suffixes (each tried as llm.* then claude.*), else the default. */
    static String firstProp(String def, String... suffixes) {
        for (String s : suffixes) {
            String v = prop(s, null);
            if (v != null) {
                return v;
            }
        }
        return def;
    }

    static boolean flag(String suffix) {
        return Boolean.parseBoolean(prop(suffix, "false"));
    }

    static int intProp(String suffix, int def) {
        try {
            return Integer.parseInt(prop(suffix, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ providers and tiers

    public static final String DEFAULT_PROVIDER = "anthropic";
    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    public static final String DEFAULT_KEY_ENV = "ANTHROPIC_API_KEY";

    /** The two thinking speeds. Each carries its own endpoint, model and reasoning depth. */
    public enum Tier {
        PLAN("plan", "medium"),
        FAST("fast", "low");

        final String key;
        final String defaultReasoning;

        Tier(String key, String defaultReasoning) {
            this.key = key;
            this.defaultReasoning = defaultReasoning;
        }
    }

    /** A resolved endpoint: who answers, where they live, with what key, model and reasoning depth. */
    public static final class TierConfig {
        public final Tier tier;
        public final String provider;
        public final String baseUrl;
        public final String apiKeyEnv;
        public final String model;
        public final String reasoning;

        TierConfig(Tier tier, String provider, String baseUrl, String apiKeyEnv, String model, String reasoning) {
            this.tier = tier;
            this.provider = provider;
            this.baseUrl = baseUrl;
            this.apiKeyEnv = apiKeyEnv;
            this.model = model;
            this.reasoning = reasoning;
        }

        /** Native Anthropic Messages API; everything else speaks the OpenAI chat-completions dialect. */
        public boolean isAnthropic() {
            return "anthropic".equals(provider);
        }

        public boolean reasoningOff() {
            return "off".equals(reasoning);
        }

        /** An explicit key property wins; otherwise the environment variable this tier names. */
        public String apiKey() {
            String k = prop("api.key", null);
            if (k == null || k.isBlank()) {
                k = System.getenv(apiKeyEnv);
            }
            return k;
        }

        /** Keys are mandatory for hosted providers and meaningless for local servers (Ollama). */
        public boolean usable() {
            String k = apiKey();
            return (k != null && !k.isBlank()) || !isAnthropic();
        }

        @Override
        public String toString() {
            return provider + "/" + model + "/" + reasoning;
        }
    }

    public static TierConfig tier(Tier t) {
        String provider = prop(t.key + ".provider", DEFAULT_PROVIDER).trim().toLowerCase(Locale.ROOT);
        String baseUrl = stripTrailingSlash(prop(t.key + ".base_url", DEFAULT_BASE_URL).trim());
        String keyEnv = prop(t.key + ".api_key_env", DEFAULT_KEY_ENV).trim();
        String model = t == Tier.PLAN
                ? firstProp(model(), "plan.model", "model.plan")
                : firstProp("claude-sonnet-5", "fast.model", "model.fast");
        String reasoning = firstProp(t.defaultReasoning, t.key + ".reasoning", "effort." + t.key)
                .trim().toLowerCase(Locale.ROOT);
        return new TierConfig(t, provider, baseUrl, keyEnv, model, reasoning);
    }

    public static TierConfig planTier() {
        return tier(Tier.PLAN);
    }

    public static TierConfig fastTier() {
        return tier(Tier.FAST);
    }

    private static String stripTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }

    // ------------------------------------------------------------------ credentials and modes

    /** The planner tier's key — what "is this thing configured at all" historically meant. */
    public static String apiKey() {
        return planTier().apiKey();
    }

    /** Mock mode exercises the full serialize→decide→apply pipeline without network calls. */
    public static boolean isMock() {
        return flag("mock");
    }

    /**
     * Force mode: every AI seat becomes LLM-piloted regardless of the lobby's per-seat profile
     * dropdown (which silently overrides the global preference with "Default").
     */
    public static boolean isForced() {
        return flag("force");
    }

    public static boolean isConfigured() {
        return isMock() || (planTier().usable() && fastTier().usable());
    }

    /** Strict mode: measurement runs hard-fail instead of silently blending in stock-AI decisions. */
    public static boolean isStrict() {
        return flag("strict");
    }

    /** "hard" = throw on a hidden-zone leak (test runs); "log" = alarm loudly but keep playing. */
    public static boolean hardLeakCheck() {
        return "hard".equals(prop("leakcheck", "log"));
    }

    public static String model() {
        return prop("model", "claude-opus-5");
    }

    /** Planner tier: lays out the whole turn, declares blocks, takes escalations. */
    public static String planModel() {
        return planTier().model;
    }

    /** Fast tier: triages unforeseen interrupts; may escalate to the planner. */
    public static String fastModel() {
        return fastTier().model;
    }

    public static String planEffort() {
        return planTier().reasoning;
    }

    public static String fastEffort() {
        return fastTier().reasoning;
    }

    /** Legacy extended-thinking budget in tokens; used only for pre-Claude-5 models (API minimum 1024). */
    public static int thinkingBudget() {
        int budget = intProp("thinking.budget", 3000);
        return budget > 0 && budget < 1024 ? 1024 : budget;
    }

    /** Reasoning depth for adaptive-thinking models (Claude 5 family): low | medium | high. */
    public static String effort() {
        return prop("effort", "high");
    }

    /** How the thinking request is shaped. Claude 4.6+ and the 5 family use adaptive + effort;
     *  Claude 4.5 and earlier use enabled + budget_tokens; budget 0 on a legacy model means off. */
    public enum ThinkingMode { ADAPTIVE, BUDGET, OFF }

    public static ThinkingMode thinkingMode() {
        return thinkingMode(model());
    }

    /** Shape for one specific model — each tier may run a different Anthropic generation. */
    public static ThinkingMode thinkingMode(String modelName) {
        String forced = prop("thinking.mode", "auto");
        switch (forced) {
            case "adaptive": return ThinkingMode.ADAPTIVE;
            case "budget":   return ThinkingMode.BUDGET;
            case "off":      return ThinkingMode.OFF;
            default:         break;
        }
        String m = modelName == null || modelName.isBlank() ? model() : modelName;
        boolean adaptiveFamily = m.contains("-5") || m.contains("4-6") || m.contains("4-7")
                || m.contains("4-8") || m.contains("fable") || m.contains("mythos");
        if (adaptiveFamily) {
            return ThinkingMode.ADAPTIVE;
        }
        return thinkingBudget() > 0 ? ThinkingMode.BUDGET : ThinkingMode.OFF;
    }

    public static int maxTextTokens() {
        return intProp("max.tokens", 1200);
    }

    /** Total output ceiling for adaptive mode, where thinking shares max_tokens with the answer.
     *  6000 bounds worst-case per-decision latency while leaving ample room at low/medium effort. */
    public static int maxTotalTokens() {
        return intProp("max.tokens.total", 6000);
    }

    public static int timeoutSeconds() {
        return intProp("timeout.seconds", 90);
    }

    public static int maxCallsPerTurn() {
        return intProp("maxcalls.per.turn", 25);
    }

    public static String temperature() {
        return prop("temperature", "1.0");
    }

    public static String logDir() {
        return prop("log.dir", System.getProperty("user.home") + "/.reforge/logs");
    }

    /** Optional file overriding the embedded system prompt — hot-editable between games. */
    public static String promptFile() {
        return prop("prompt.file", null);
    }

    /** The mind's table name: what she's called in the log and how she signs her speech. */
    public static String name() {
        return prop("name", "Sydney");
    }

    /** Table persona: sydney (default — the rebuilt early-Bing mind), kitchen, lgs, online. */
    public static String persona() {
        String p = prop("persona", "sydney");
        switch (p) {
            case "lgs":
            case "online":
            case "kitchen":
            case "sydney":
                return p;
            default:
                return "sydney";
        }
    }
}
