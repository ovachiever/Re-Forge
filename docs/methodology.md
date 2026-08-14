# Is It Actually Smarter?

An LLM playing Magic looks impressive. It narrates its reasoning, it names your threats, it explains why it held up mana. None of that is evidence. A player can sound thoughtful and still lose to a rules-based script that has no idea what a plan is.

This document describes how reForge intends to answer the question with numbers instead of impressions, what already exists, and what does not exist yet. It is written so that someone who has never played Magic can still follow the argument.

## Terms used here

| Term | What it means |
|---|---|
| Deck | A fixed list of 60 cards. Both players draw from their own shuffled deck. |
| Game | One playthrough. Ends when one player wins, or in a draw. |
| Match | A series of games between the same two decks. Forge tracks match state across games. |
| Seat | Which side of the table a deck sits on. Seat 1 and seat 2 are not symmetric, for reasons below. |
| On the play | Taking the first turn. In Magic this is generally an advantage, which is why who gets it has to be controlled for. |
| Stock AI | Forge's built-in rules-based opponent. No language model involved. |
| AI profile | A named bundle of tuning parameters for the stock AI. `Default` is the shipped one. |
| Win rate | Wins divided by decided games, expressed as a percent. |
| 95% CI | The range the true win rate is very likely to sit in, given the sample. Written as plus or minus some number of points. |

## The measurement problem

Magic is a high-variance game. Both players shuffle. Both draw a different opening hand every game. Two identical opponents playing the same two decks will not split 50/50 over a short run, and the difference between two genuinely different opponents can be smaller than the swing you get from shuffling.

So a claim like "the LLM won 7 of 10 games against the stock AI" carries almost no information. It is consistent with the LLM being much better, slightly better, or somewhat worse. The number of games is too small for the result to mean anything.

The fix is not clever statistics. It is a large enough sample and a known noise floor.

## The harness

Forge ships a headless simulation mode. It runs games with no window, no animation, and no human input, at whatever speed the CPU allows. That is the substrate for every measurement here.

```
java -jar forge-gui-desktop-<version>-jar-with-dependencies.jar \
  sim -d deck1.dck deck2.dck -n 250 -q -s 4201
```

Flags, from `SimulateMatch.argumentHelp()` in `forge/forge-gui-desktop/src/main/java/forge/view/SimulateMatch.java`:

| Flag | Meaning |
|---|---|
| `-d` | Deck names or filenames, in seat order. Seat 1 first. |
| `-D` | Absolute directory to load decks from. Tournament mode only. |
| `-n` | Number of games. Ignores match settings. |
| `-m` | Play a full match of N games instead, typically 1, 3, or 5. Overrides `-n`. |
| `-t` | Run a tournament across all provided decks: Bracket, RoundRobin, or Swiss. |
| `-p` | Players per match. Tournaments only. Defaults to 2. |
| `-f` | Game format. Defaults to constructed. |
| `-s` | RNG seed. |
| `-a` | AI profile per player, in the same order as the decks. |
| `-c` | Clock. Seconds before a slow game is called a draw. Defaults to 120. |
| `-q` | Quiet. Print the result, not the whole game log. |

`-a` is the flag that makes A/B testing possible at all. It sets the AI profile per seat, so one run can pit one set of AI settings against another and attribute the outcome to the difference. The source comment says as much:

> Optional AI profile per player, in the same order as the decks. Lets a run pit one set of AI settings against another, which is the only way to tell from the results whether an AI change actually helped.

### Pass `-a` explicitly, always

If `-a` is omitted, `GamePlayerUtil.createAiPlayer` falls back to the `UI_CURRENT_AI_PROFILE` preference stored in the user's Forge profile directory. If that preference happens to be set to the random-duel value, the profile rotates every game. Either way the run silently depends on local state that is not in the command line and not in the log.

A run whose configuration cannot be reconstructed from its own command is not a measurement. Name both profiles every time, even when both are `Default`.

Deck resolution has the same failure mode. Outside tournament mode, `deckFromCommandLineParameter` always resolves `-d` names against the Forge profile's constructed-deck directory. The deck the run actually used is whatever was sitting in that folder at the time, which is not recorded in the log. Keep the decks under version control and copy them into place as part of the run.

### Putting the LLM on a seat

AI profiles live in `forge/forge-gui/res/ai/` as `.ai` files, one per profile, name taken from the filename. The shipped set is `Default`, `Cautious`, `Reckless`, `Experimental`, and `Claude`.

`Claude.ai` is the routing profile. A seat assigned that profile gets the LLM-backed controller instead of the stock one. Its heuristic parameters mirror `Default.ai`, because they still govern the stock-AI code paths the LLM controller inherits: mana payment, trigger ordering, and the mechanical long tail are not model decisions.

So the arms are ordinary `-a` invocations:

```
sim -d basri.dck chandra.dck -n 250 -q -s 4201 -a Claude  Default   # LLM on seat 1
sim -d basri.dck chandra.dck -n 250 -q -s 4201 -a Default Claude    # LLM on seat 2
sim -d basri.dck chandra.dck -n 250 -q -s 4201 -a Default Default   # the baseline already run
```

### Turn on strict mode for measurement runs

During normal play, an API timeout or a malformed response hands the decision to the stock AI so the game keeps going. That is right for a human opponent and wrong for a measurement, because the result then describes a blend of two players.

`-Dllm.strict=true` makes those paths throw instead of falling through. A measurement run that hits trouble fails loudly rather than quietly reporting a partly-stock win rate. Set it on every campaign run, and treat a crash as a result worth investigating rather than a nuisance to route around.

Every property is read as `llm.<name>` first and then as the legacy `claude.<name>`, so `-Dclaude.strict=true` still works. Prefer the `llm.` form in new scripts.

## The baseline: what the noise floor actually is

Before measuring an LLM against the stock AI, you need to know how much a result can move when nothing has changed. That is what the 2026-07-30 baseline run establishes.

Everything about it is held fixed except the shuffle: stock AI on both seats, the same two decks, the same engine build.

| Property | Value |
|---|---|
| Date | 2026-07-30 |
| Seat 1 | `basri.dck`, Basri, Devoted Paladin. Forge's stock M21 white starter deck, 60 cards, 25 Plains. |
| Seat 2 | `chandra.dck`, Chandra, Flame's Catalyst. Forge's stock M21 red starter deck, 60 cards, 25 Mountains. |
| Opponent on both seats | Stock Forge AI |
| Shards | 8 parallel JVMs, 250 games each |
| Seeds | 4201 through 4208, one per shard |
| Total games | 2000 |
| Timeout | Default 120 seconds per game. Never triggered. |

Scripts: `experiments/baseline-2026-07-30/run.sh` launches the shards, `experiments/baseline-2026-07-30/aggregate.sh` tallies the logs.

### Result

| Metric | Value |
|---|---|
| Games completed | 2000 |
| Basri wins | 1272 |
| Chandra wins | 728 |
| Draws | 0 |
| Basri win rate | 63.6% |
| 95% CI | plus or minus 2.1 points |

Read that carefully, because the headline is easy to misread. This is not a finding about white beating red. It is a finding about **how far from 50/50 a completely unchanged setup already sits**. Two copies of the same AI, given these two decks, produce a 63.6% result. That 13.6-point gap is entirely deck and seat, not skill.

Any future claim about the LLM has to clear this bar, in this direction: not "the LLM won more than half its games" but "the LLM moved the number away from what the stock AI scores in the same seat with the same deck, by more than the interval."

## Why n matters

The eight shards are the cleanest illustration available, because they are eight independent 250-game runs of a configuration that never changed.

| Shard | Seed | Basri | Chandra | Basri rate |
|---|---|---|---|---|
| 1 | 4201 | 159 | 91 | 63.6% |
| 2 | 4202 | 156 | 94 | 62.4% |
| 3 | 4203 | 166 | 84 | 66.4% |
| 4 | 4204 | 150 | 100 | 60.0% |
| 5 | 4205 | 161 | 89 | 64.4% |
| 6 | 4206 | 165 | 85 | 66.0% |
| 7 | 4207 | 161 | 89 | 64.4% |
| 8 | 4208 | 154 | 96 | 61.6% |
| **Pooled** | | **1272** | **728** | **63.6%** |

Nothing differed between shard 4 and shard 3 except the seed, and they landed 6.4 points apart. If you had run only shard 4 and only shard 3, and told yourself the seeds were "before" and "after" an AI change, you would have reported a 6.4-point improvement that does not exist.

The confidence interval shrinks with the square root of the sample, so buying precision gets expensive fast. Quadrupling the games halves the interval.

| Games | 95% CI half-width at p = 0.636 |
|---|---|
| 100 | plus or minus 9.4 points |
| 250 | plus or minus 6.0 points |
| 500 | plus or minus 4.2 points |
| 1000 | plus or minus 3.0 points |
| 2000 | plus or minus 2.1 points |
| 4000 | plus or minus 1.5 points |

At n = 100 the interval is roughly plus or minus 10 points. Almost any AI change you could make is smaller than that, so a 100-game run cannot detect it. At n = 2000 the interval is plus or minus 2.1 points, which is small enough to be useful. That is the whole reason the baseline is 2000 games and not 100.

### Comparing two arms costs more than measuring one

Detecting a difference between two runs is harder than pinning down one number, because both sides carry error.

| Games per arm | Smallest difference resolvable at 95% |
|---|---|
| 100 | 13.3 points |
| 250 | 8.4 points |
| 500 | 6.0 points |
| 1000 | 4.2 points |
| 2000 | 3.0 points |
| 4000 | 2.1 points |

With 2000 games on each side, a real effect smaller than about 3 points will not separate from noise. That number sets the budget for any future LLM campaign, and it is worth knowing before spending the API calls rather than after.

## Seat and deck asymmetry

The baseline result is 63.6% for seat 1, and that number is doing two jobs at once that need to be pulled apart.

**Deck asymmetry.** Basri and Chandra are not balanced against each other. Some of the 13.6-point gap is simply that one 60-card list beats the other more often than not, no matter who is piloting.

**Seat asymmetry.** Forge decides the first turn in `GameAction.determineFirstTurnPlayer`. On the first game of a match the starting player is chosen at random. On every game after that, the player who **lost** the previous game goes first. The stock AI, offered the choice, always takes it: `PlayerControllerAi.chooseStartingPlayer` returns its own player with the comment "AI is brave".

That rule matters for how these runs behave. Because `-n 250` plays all 250 games on a single `Match` object, the loser-goes-first rule applies across the whole shard rather than resetting each game. The trailing side keeps getting handed the first turn, which is a negative feedback loop: whoever is behind keeps receiving the advantage, pulling the observed rate toward 50/50. On that reasoning the 63.6% is a conservative reading of the deck gap rather than an inflated one. That is an inference from the rule, not a separately measured quantity.

It also means the 2000 games are not strictly independent draws. Game N+1's starting player is a function of game N's result. The 95% interval reported by `aggregate.sh` treats them as independent Bernoulli trials, which is a reasonable approximation but not exactly right. It is stated here rather than buried.

### The prescription for LLM runs

A single-seat comparison confounds "the LLM is better" with "seat 1 is better". The fix is to run both directions and pool.

| Arm | `-a` | Seat 1 | Seat 2 | Purpose |
|---|---|---|---|---|
| A | `Claude Default` | LLM with Basri | Stock with Chandra | LLM in the favored seat |
| B | `Default Claude` | Stock with Basri | LLM with Chandra | LLM in the unfavored seat |
| Baseline | `Default Default` | Stock with Basri | Stock with Chandra | Already done: 63.6% |

Arm A tells you whether the LLM beats 63.6% while holding Basri. Arm B tells you whether the LLM beats 36.4% while holding Chandra. Both are needed. If the LLM only improves in one arm, the finding is about the deck or the seat, not the opponent.

Same rules for both arms: same seeds, same game count, same engine build, `-a` named explicitly on both seats, and the whole command recorded next to the logs.

## What can go wrong, stated plainly

| Hazard | Why it matters | Mitigation |
|---|---|---|
| Too few games | Anything under about 1000 games cannot see the effects being looked for | Budget 2000 per arm |
| Single seat | Confounds opponent strength with seat advantage | Run both arms |
| Implicit AI profile | The run depends on unlogged local preferences | Always pass `-a` |
| Engine drift | A build change between arms invalidates the comparison | Pin the jar, record the version line from the log |
| Timeout draws | The 120-second clock scores a slow game as a draw, silently removing it from the denominator | Count draws, report them. The baseline had zero. |
| Reused seeds across arms | Correlated shuffles look like a real effect | Keep the seed set fixed and identical across arms so the comparison is paired, and say so |
| Cherry-picked shards | Shard-to-shard spread was 6.4 points with nothing changed | Report pooled totals, never a single shard |
| Silent stock fallback | A run with many fallbacks is partly a stock-AI run wearing a different name | Run with `-Dllm.strict=true`, and report the fallback rate from the ledger |
| Missing API key | Produces a clean-looking stock-AI result with no ledger written at all | Confirm the ledger file exists and has rows before reading the result |

## The decision ledger

Win rate is the outcome. The ledger is the audit trail that explains it.

Every decision the LLM opponent makes is appended as one JSON object per line. Two files are written per game:

```
game-<epoch-millis>-<player>.jsonl              the decision ledger
game-<epoch-millis>-<player>-transcript.jsonl   prompts, thinking, raw replies
```

The ledger is the compact per-decision record. The transcript carries the full prompt, the model's thinking, and the raw response for the same decision, so a suspicious line in the ledger can be opened up and read.

Both full paths are printed to standard output when each file opens, prefixed `[LLM]`. Capture that line with the run. For a campaign, set `-Dllm.log.dir=<path>` to a per-run directory so the ledgers sort themselves instead of piling into one folder.

### Ledger fields

| Field | What it holds |
|---|---|
| `ts` | Wall-clock milliseconds when the decision was recorded |
| `turn` | Game turn number |
| `phase` | Phase the decision was made in |
| `method` | Which decision: `mulligan`, `turnPlan`, `planStep`, `chooseSpell`, `declareAttackers`, `declareBlockers` |
| `outcome` | Who actually decided, and how. See below. |
| `latencyMs` | Wall time for the call |
| `inTokens` / `outTokens` | Input and output tokens |
| `cacheRead` / `cacheWrite` | Prompt-cache tokens read and written |
| `thinkingTokens` | Extended-thinking tokens spent |
| `decision` | The decision itself, in short text |
| `tableTalk` | The line said at the table, when there was one |

A real line, from a mulligan:

```json
{"ts":1785994635062,"turn":0,"phase":"null","method":"mulligan","outcome":"CLAUDE","latencyMs":2794,"inTokens":590,"outTokens":48,"cacheRead":0,"cacheWrite":3576,"thinkingTokens":0,"decision":"keep","tableTalk":"keeping. four lands and a plan is all I ever really want out of an opener."}
```

### Outcome attribution

`outcome` is the field that decides whether a win-rate number means anything.

| Outcome | Meaning |
|---|---|
| `CLAUDE` | The planner model made the call |
| `FAST` | The cheaper interrupt-tier model made the call |
| `ESCALATED` | The fast tier punted and the planner answered |
| `PLAN_STEP` | Executed locally from a plan already made this turn. No API call. |
| `AUTO_PASS_CACHED` | Passed a phase already passed under the same conditions. No API call. |
| `MOCK` | Mock mode. No network. |
| `FALLBACK_TIMEOUT` | The call timed out. Stock AI decided. |
| `FALLBACK_PARSE` | The response could not be parsed. Stock AI decided. |
| `FALLBACK_API` | The API returned an error. Stock AI decided. |
| `FALLBACK_ERROR` | Anything else went wrong. Stock AI decided. |
| `FALLBACK_CAP` | The per-turn call budget was exhausted. Stock AI decided. |

The four questions the ledger answers, that a scoreboard cannot:

- **Did the model actually play this game?** Sum the `FALLBACK_*` rows. If they are a meaningful share of decisions, the run measured a hybrid. This is the single most important number to report alongside any win rate.
- **What did it cost?** Token columns multiply out to the real price of a campaign, and make the split between planner and fast tier visible.
- **How slow was it?** `latencyMs` decides whether a 2000-game campaign is feasible at all. `PLAN_STEP` and `AUTO_PASS_CACHED` rows are the ones that cost nothing, so their share is the throughput story.
- **Where did the reasoning go wrong?** A lost game with a transcript is debuggable. A lost game with only a win rate is not.

### The unkeyed-run trail

If no API key is configured, every decision returns to the stock AI. The run
still leaves an audit trail: one `FALLBACK_NO_KEY` row is written per game
(method `seat`), and the same fact is announced once in the visible game log.
A ledger whose only row is `FALLBACK_NO_KEY` is a pure stock-AI game and must
not be reported as a model result. Strict mode does not cover this path, so
check for that row before reading any campaign number.

## Status

Honest accounting of what exists as of this writing.

| Piece | Status |
|---|---|
| Headless simulation harness | Exists. Ships with Forge. |
| Per-seat AI profile selection via `-a` | Exists. |
| `Claude` AI profile for routing a seat to the model | Exists. `forge/forge-gui/res/ai/Claude.ai`. |
| Strict mode to prevent silent stock blending | Exists. `-Dllm.strict=true`. |
| Stock-vs-stock noise floor at n = 2000 | Exists. `experiments/baseline-2026-07-30/`. |
| Shard launcher and aggregator scripts | Exist. `run.sh`, `aggregate.sh`. |
| Per-decision JSONL ledger and transcript | Exist. Written during play. |
| LLM-vs-stock win-rate campaign | **Not run.** Future work. |
| Both-seat pooled LLM result | **Not run.** Future work. |
| Cost and latency profile from a full campaign | **Not run.** Future work. |
| Aggregator that reads fallback rate out of the ledger | **Does not exist.** `aggregate.sh` reads game logs only. |

No claim is made here that the LLM opponent wins more games than the stock AI. That measurement has not been taken. What has been built is the apparatus that would make the answer trustworthy when it is, and the noise floor that any answer will have to clear.

## Reproducing the baseline

The decks must exist in the Forge profile's constructed-deck directory, because `SimulateMatch` prepends that path to `-d` arguments. Outside tournament mode there is no flag that changes it.

```bash
# launch 8 shards of 250 games, seeds 4201-4208
bash experiments/baseline-2026-07-30/run.sh

# tally the shard logs
bash experiments/baseline-2026-07-30/aggregate.sh
```

Two environment notes carried in `run.sh`, both macOS specific:

- Do not set `-Djava.awt.headless=true`. `FModel.initialize` touches AWT, and the crash handler dies under a headless JVM.
- `-Dapple.awt.UIElement=true` keeps the eight JVMs out of the dock.

`aggregate.sh` reads the last `Match Result:` line from each shard log, which is cumulative across that shard's games, sums the per-seat totals, counts draws and completed games separately as a cross-check, and prints a Wald 95% interval on the pooled proportion.
