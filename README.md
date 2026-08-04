# Sol

A native Android assistant that runs on voice alone. Reminders, calendar and
email, with an always-listening wake word and a spoken confirmation step before
anything destructive happens. The app speaks Italian, because that's who it was
built for.

## Why it exists

I built this for one specific person: my father. He's an industrial surveyor, he
works on site with his hands full, and he is never going to open a task app and
type into it. So the constraint was fixed before any code existed — the whole
loop, dictation through confirmation through result, has to work without looking
at the screen.

That's a harsher requirement than it sounds. If nobody is watching the screen, a
silent failure is an invisible failure. Both rules below fall out of that.

## The two rules

**Claude proposes, the code disposes.** The model emits tool calls. The risk
class attached to each one — green, yellow, red — is decided by
`conferme/Rischio.kt`, never by the model. An LLM doesn't get to rate how
dangerous its own request is.

**Every outcome is spoken.** Success, cancellation, timeout, error: the turn
always closes with a sentence. Silence is never a valid answer.

## One turn, end to end

Wake word, beep, command, then Haiku 4.5 with a system prompt carrying the
current date, time and reminders. Every `tool_use` it returns passes through the
gate:

- **Green** (list, cancel) just runs.
- **Yellow** (create, complete, reschedule) runs immediately, then the mic stays
  open for about thirty seconds. Saying "annulla" — bare, no wake word needed —
  puts everything back.
- **Red** (delete) gets read back as a summary, and Sol waits for the passphrase
  "timbro e firma", matched deterministically. A timeout, a different phrase, or
  a cancel all abort the action, and it says so out loud.

## Stack

Kotlin, native Android, minSdk 26. Porcupine (Picovoice) for the wake word,
`SpeechRecognizer` for transcription, system TTS for the voice. Claude Haiku 4.5
over REST with a hand-rolled tool loop. Reminders live in a local JSON file
written atomically, alarms go through `AlarmManager` with exact scheduling. No
OAuth, and no cloud beyond the Anthropic call.

## Things worth knowing

**It degrades in steps instead of failing.** Without a Picovoice key the wake
word disappears but a push-to-talk button remains. Without an Anthropic key it
falls back to `CervelloEco`, which just repeats what it heard. The app always
starts.

**Exactly one component mutates state.** `EsecutoreProposte` is the only path
that touches anything, and the gate sits in front of it, so there's no side door.

**A platform limit, not a bug.** After a phone reboot Android withholds the
microphone from auto-started services. Sol notices, fires a notification, and one
tap brings it back.

## Status

M1 and M2 are done: the voice pipeline, six tools, the reminder store and alarms,
green and yellow with the hot undo window, and the spoken red path. Next is the
on-screen red confirmation card, then field testing with the person it's for.

API keys live in a `sol.properties` file outside the repository and are never
versioned.
