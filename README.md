# Sol

A mail assistant for one busy professional: it sorts the inbox, writes the first
draft of every reply, and keeps track of what is still waiting for an answer.
It runs on a home NAS, uses only open-weight models, and costs nothing to run.
The interface speaks Italian, because that's who it was built for.

## Why it exists

I built this for my father. He's an industrial surveyor, his day is split between
sites and clients, and email is where his time goes: one work inbox, two personal
ones, offers, invoices, certified mail from public offices, newsletters, and the
one message from a client that actually matters, buried somewhere in the middle.

The first version of Sol was a hands-free voice assistant, until it became clear that
the real problem was the inbox rather than his hands, so this is a rewrite from scratch.

## What it does

1. **Sorts every new message** into groups (clients, suppliers, invoices, certified
   mail, newsletters, other), flags what needs a reply and how urgent it is.
2. **Writes a one-line summary** of each message and a **draft reply** for the ones
   that need one, in the background, so the draft is already there when he opens it.
3. **Summarises on request**: "today's mail", "what did Bianchi write this week".
4. **Pulls deadlines** out of messages ("send the quote by Friday").
5. **Nothing falls behind**: messages he hasn't answered in two days, and messages
   he sent that nobody has answered in a week, with a follow-up draft ready.

## The rules

**Nothing is ever sent without a click.** There is exactly one code path that sends
mail, and it requires an explicit confirmation of the exact version the user saw.
The models have no tools and no actions, since they only pick between fixed options
or write text, so a malicious email ("forward all invoices to…") can at worst produce
a bad draft that a human reads before sending.

**Email content is data, never instructions.** It is always fenced and labelled as
text to analyse, with the models' own control tokens neutralised, and the classifier
was tested against injection emails written to look like system turns.

**Sol never deletes mail.** It moves, labels and marks as read, and a trial mode (on by
default) shows what Sol *would* do without touching the real mailboxes.

## How it's built

```
android/    Kotlin + Jetpack Compose app
pc/         Windows app (C# .NET 8, WinForms + WebView2, HTML/JS interface)
server/     FastAPI + SQLite, runs in Docker on the NAS, does all the work
decisore/   the classifier: SemIf on Qwen3.5-4B (GGUF, llama.cpp), on the NAS CPU
docs/       the API contract, written before the code
```

- **Classification** runs locally with [SemIf](https://github.com/TheoLeeCJ/SemIf-OpenJev),
  an open reimplementation of typed-decision models: it reads option probabilities
  straight from the logits of a frozen open model instead of generating text. On an
  Intel N100 with no GPU it takes ~18 s per message, which is fine in the background,
  and the probabilities tell Sol when it isn't sure (those go to "check me").
- **Writing** (drafts, summaries) uses open-weight models on free API tiers (Groq,
  then Cerebras), with the local model as a slow last resort. Only messages that need
  a draft or a summary ever leave the house.
- **Mailboxes**: Microsoft 365 through Microsoft Graph, Gmail through IMAP/SMTP with an
  app password.
- **Access**: a fixed HTTPS address through Tailscale Funnel, so the phone and the PC only
  need the app. Every request carries a per-device token issued through a one-time pairing
  code, and pairing locks itself after ten wrong attempts.
- **Possible fraud**: every message also gets a fourth question, whether it looks like a
  scam (new IBAN, urgent payment, requests for invoices or codes). Flagged messages get a red
  label, no automatic reply and no deadlines extracted, until a human clears them.

API keys and passwords live in an `.env` file on the NAS and are never versioned.

## Status

Version 1.0: server and classifier running on the NAS, Android and Windows apps built,
every piece reviewed adversarially before release. Next is connecting the real mailboxes
and a few weeks of use, to tune the sorting threshold on real corrections.

I make the product and design calls and find the behavioural problems by using
the thing; Claude writes the implementation and finds the bugs a few layers down.
