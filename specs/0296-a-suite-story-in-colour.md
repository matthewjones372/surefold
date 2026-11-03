# 0296 — A suite story in colour

## Problem

[0116](0116-a-story-in-colour.md) colours a story's console copy, and
[0118](0118-a-colour-setting-that-reaches-the-test.md) carries the setting to the test worker. Neither
reaches a `surefold-junit` suite. There, `tellingStories` binds `storyTranscript`, and `story` hands a bound
sink the transcript with `colour = false` (`Story.kt`). The suite logs it as one `LogLine`, and
`Report.finished` prints it under "… logged:" when the test fails. So `FORCE_COLOR=1`,
`-Psurefold.test.colour=always` and IntelliJ change nothing: the ✗ is plain.

A consumer service writes its tests as suite stories and sees no colour in the Given / When / Then.
Nothing tells them the setting was ignored rather than off.

## Not doing

- **No colour in anything kept.** The `logs` report entry, the JUnit XML and `StoryFailed`'s message stay
  plain, for 0116's reason: the XML writes the escape character as `?`.
- **No change to the colour rules.** `Colour.wanted` still decides, the same five rules.
- **No change to which tests print.** A passing story still prints nothing; that is 0255.
- **No colour for other log lines.** Only the story's own lines.

## Shape

No API for a suite author. The same suite, run with colour on, prints the failing test's logs with the
story coloured as under Jupiter:

```text
a guest on the list is let in logged:
INFO Story: a guest on the list is let in        ← bold
  ✓ Given a list with Ada        2 ms            ← green ✓, dim time
  ✗ Then Ada is let in           0 ms            ← red, bold
      expected:<true> but was:<false>            ← red
```

- The seam changes so a bound sink is given both copies: `storyTranscript` becomes
  `SurefoldLocal<((Transcript) -> Unit)?>`, with `Transcript(plain: String, coloured: String)`, where
  `coloured` follows `Colour.wanted` (so it equals `plain` when colour is off). It is `@StorySeam`, so no
  consumer outside Surefold is broken.
- `tellingStories` logs `plain` as today, and keeps `coloured` beside it, keyed by that message.
- `Report.finished` publishes the plain lines as the `logs` entry, unchanged, and prints the coloured copy
  in place of the plain one on the console.

## Why this shape

The console and the report want different text, so the story has to hand over both, since only `Story`
knows where the colours go. Two ways to carry the coloured copy to `Report`:

- **Recommended: a second list** beside the captured logs, matched to the story's `LogLine`. `LogLine` is
  unchanged, and 0255's "second list" for passing stories can reuse it.
- **Colour inside the `LogLine` message**, stripped when publishing. Less code, but every logger a test hands
  on would see escape codes, and stripping them by regex is fragile.

## Stack

- [x] **`spec-0296-suite-colour`** ([#1054](https://github.com/surefold/surefold/pull/1054)) — `surefold-test`: `Transcript` and the seam's new type;
      `surefold-junit`: keep the coloured copy and print it in `Report.finished`.
      Done when: a failing suite story run with `surefold.test.colour=always` prints its ✗ line starting
      with red and bold; with `never` the console holds no escape code; in both modes the `logs` report
      entry and the thrown message hold none.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold-test:test :surefold-junit:test
```

Then a failing suite story in a consumer service, run with `FORCE_COLOR=1` and the wiring plugin, prints its ✗ in red.

## Open questions

Answered with the recommendations, on the go-ahead to build (2026-11-03):

1. **Colour the `INFO` prefix too?** No: only the story's lines. The prefix comes from `rendered()`, shared
   with every log line.
2. **Wait for 0255?** No: this lands first, and 0255 prints passing stories from the same console copies.
3. **IntelliJ detection for suites?** Out of scope. Under "Run tests using: Gradle" the worker does not see
   `idea.test.cyclic.buffer.size`, so `FORCE_COLOR=1` in the run configuration is needed;
   `docs/testing.md` says so.
