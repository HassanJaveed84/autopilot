# ChatGPT Round Trip: UX Design

Date: 2026-10-27 · Branch: `claude/fervent-archimedes-5dhxmq` (from `ccr-125b38a8-btl7ka` @ `62af1cd`)

**Goal.** I think in ChatGPT. Autopilot remembers what I decided and helps me follow through.

This note covers three touchpoints and nothing else: leaving Autopilot to discuss a task, coming back with
decisions, and reviewing and applying them before returning to the conversation. There's no general redesign.
Implementation notes and evidence are in `docs/CHATGPT_ROUND_TRIP_REPORT.md`.

## 1. Happy path

```
Task detail ──tap "Discuss in ChatGPT"──▶ Share sheet (what ChatGPT will see) ──"Open ChatGPT"──▶
ChatGPT composer, prefilled, not sent ──user sends, talks, decides──▶ "Send this to Autopilot" ──▶
ChatGPT replies [Open in Autopilot] ──tap──▶ Autopilot opens on top of ChatGPT: "From ChatGPT" review,
task already identified ──"Select all" · "Apply 4 changes"──▶ "Changes applied" ──"Return to ChatGPT"──▶
same ChatGPT conversation
```

Taps on the normal path: **2 to leave** (Discuss, Open ChatGPT) and **3 to come back** (the link, Select all,
Apply), then one to return. No typing, copying or pasting, and no destination screen when the task is recognised.

## 2. Touchpoint 1: Task detail → ChatGPT

### Placement

The detail screen already has the order: title and notes, review banners, follow-up card, Mark done and the
action tiles, steps, deadline, then the AI card ("Move this forward": Plan next step and Research online), then
saved research and history.

"Discuss in ChatGPT" goes **directly above the AI card**, as its own small card. The reasons:

- It sits where the user already looks for help moving a task forward, next to the steps it talks about.
- It isn't inside the AI card, because that card changes to "AI suggestions and research are off" when Claude
  isn't set up, and Discuss has to work without any API key. It's a separate card, so it's always there for an open task.
- It's a secondary action, not a primary button. Mark done stays the one strong action on the screen.

```
┌──────────────────────────────────────────────┐
│ (chat bubble)  Discuss in ChatGPT            │
│                Talk it through in your       │
│                ChatGPT app, then send the    │
│                decisions back here.       ›  │
└──────────────────────────────────────────────┘
┌─ Move this forward ──────────────────────────┐   (existing AI card, unchanged)
│  [Plan next step]   [Research online]        │
└──────────────────────────────────────────────┘
```

Hidden for done and cancelled tasks, just like the AI card. It doesn't depend on the AI-enabled setting.

### Share sheet: "What ChatGPT will see"

One bottom sheet, built from the existing `AutopilotSheet`. It answers "what am I sharing?" without becoming a form.

```
┌ Discuss in ChatGPT ─────────────────── Close ┐
│ ChatGPT will see                             │
│  ✓ Task       Choose a coffee grinder        │  always included
│  [✓] Steps    1 done, 2 to do                │  on by default
│  [✓] Deadline Fri 14 Nov                     │  only if the task has one
│  [ ] Notes    "Budget is around $1,000…"     │  OFF by default, preview of the first line
│                                              │
│ Autopilot also adds a short note telling     │
│ ChatGPT how to send your decisions back.     │
│ Nothing is sent until you press send in      │
│ ChatGPT. Your task doesn't change.           │
│                                              │
│ See the full message ▾                       │  collapsed; the exact text that will be shared
│                                              │
│ [          Open ChatGPT           ]          │
└──────────────────────────────────────────────┘
```

- **Defaults**: title (always), steps on, deadline on, **notes off**. Notes are where people keep account numbers
  and personal details. For an ordinary task, the user just taps **Open ChatGPT**.
- Rows that don't apply (no steps, no deadline, no notes) aren't shown.
- "See the full message" shows the exact prompt in a scrollable block. It's collapsed because it includes the
  link instructions, which are dull and technical. It's there for transparency, not as something the user has to read.
- **ChatGPT not installed**: the button becomes **Share…** and the sheet says *ChatGPT isn't on this phone. You can
  share the message to another app.* That opens Android's own chooser. Autopilot's own share target is
  left out of that chooser.
- Opening ChatGPT changes nothing in Autopilot. There's no history entry, no task write and no stored conversation.

### The prepared message

It starts as a normal conversation, not a request for a checklist:

> I'd like your help with something on my to-do list: "Choose a coffee grinder". I keep track of it in my
> Autopilot app. Let's talk it through first. Research where it helps, ask me questions, and tell me what you
> honestly think. Please don't jump straight to a checklist.
>
> What I have so far:
> - Deadline: Fri 14 Nov 2026
> - Done: Set a budget
> - Still to do: Read reviews
>
> (Notes, only if the user ticked them)
>
> ---
> For later: only when I say "send this to Autopilot", reply with one Markdown link labelled Open in Autopilot. …

The technical part comes after a divider and is kept short (see the URL contract in the report). It tells
ChatGPT to treat only what the user actually decided as a decision, and never to say something is done unless
the user said so.

## 3. Touchpoint 2: ChatGPT → Autopilot

The user says "Send this to Autopilot". ChatGPT replies with **Open in Autopilot**. A tap opens Autopilot's
import screen **on top of ChatGPT, in its own short-lived window** (separate Android task). Today, Inbox and any
open Autopilot screens are left alone.

### Recognised task (the normal case): straight to review

The link carries the task's reference. When it matches a task exactly, there's **no destination screen**. The
review opens with the destination shown at the top and a way to change it:

```
 ✕  Review changes                                (✕ = Return to ChatGPT)
 From ChatGPT
 ChatGPT conversation · 27 Oct 2026, 14:03
 Task: Choose a coffee grinder
 Nothing changes until you apply. Your task stays as it is while you review.
┌──────────────────────────────────────────────┐
│ Adding to  Choose a coffee grinder   Change  │  destination card
└──────────────────────────────────────────────┘
 0 of 4 selected                     Select all
 Changes
 [ ] New step       Compare retailer prices                 Edit · Reject
 [ ] New step       Check warranty coverage                 Edit · Reject
 [ ] Add to notes   Decided: Choose Model A                 Edit · Reject
 [ ] Add to notes   Budget is $1,000                        Edit · Reject
──────────────────────────────────────────────
 [ Reject all ]                 [ Apply 4 changes ]
```

- Nothing is pre-selected, as everywhere else in Proposal Review. **Select all** (the handoff wording for
  "Select recommended") then **Apply** is two taps. The "Recommended" badge isn't shown on rows from ChatGPT,
  because every row is something the user asked to send.
- Decisions read as notes starting "Decided: …", so later they're easy to tell apart from plain context notes.
- The title ChatGPT used never renames the task. When it differs from the task's title, the destination card
  adds *ChatGPT called it "…"*, which also makes a wrong match easy to spot.

### Unrecognised task: choose one

When the link has no reference, the reference doesn't match, or the task was deleted, the review can't start
yet, so a picker opens instead:

```
 ✕  Where should this go?
 ChatGPT sent 2 steps and 2 notes about "Choose a coffee grinder".
 We couldn't find that task in Autopilot. It may have been deleted.       (or: "This link doesn't say which task it's for.")
 [ Search your tasks              ]
 ○ Choose a coffee grinder        Actionable · 2 steps
 ○ Buy espresso machine           Inbox
 …
 [ + New task "Choose a coffee grinder" ]
```

- The list is the open tasks, most recently changed first, with a search box. **It's never pre-chosen from a
  similar title.** A task whose title matches exactly is listed first but still needs a tap.
- **New task** creates a plain Inbox task with ChatGPT's title, but only on that explicit tap. The review then
  opens for it, and nothing beyond the title is written until the user applies.
- **Change** on the review's destination card opens the same picker. Changes still waiting for the first task
  are removed if they're untouched, so they don't linger as a banner there.

## 4. Touchpoint 3: Review, apply, return

The **existing Proposal Review** composable and view model are reused unchanged in behaviour (reconciliation,
conflicts, edits, material changes, transactional apply, undo). The import screen only supplies the destination
card, labels and exits.

```
 ✕  Changes applied
 ┌ Applied 4 changes ───────────────────────────┐
 │ Your task has the changes you chose. Undo is │
 │ available until you edit the task.           │
 └──────────────────────────────────────────────┘
 What happened
 ✓ Compare retailer prices         Applied · New step
 …
──────────────────────────────────────────────
 [ Undo ]                      [ Return to ChatGPT ]
```

After applying, the destination card links to **Open the task in Autopilot** for anyone who wants to look at it
right away. That leaves the ChatGPT window, so it's a small link rather than the main button.

## 5. Edge cases

All of these appear inside the same import screen, as a card with a title, one sentence and the exit button.
There are no extra screens.

| Situation | What the user sees |
| --- | --- |
| No ChatGPT installed | Share sheet: "ChatGPT isn't on this phone…" and a **Share…** button that opens the chooser |
| User picks another app from the chooser | It works the same way. The message is plain text |
| Malformed link (bad encoding, unknown field, stray `+`, …) | **"This link didn't come through right"**: *Ask ChatGPT to send it to Autopilot again.* No values are echoed back · **Return to ChatGPT** |
| Oversized link (over 4,096 characters or item limits) | **"That's more than one link can carry"**: *Ask ChatGPT for fewer or shorter items (up to 10 steps, 5 decisions, 5 notes).* Nothing is cut silently |
| Unsupported version (`ap=v2`) | **"This link needs a newer Autopilot"** |
| Task no longer exists | Picker, with *We couldn't find that task in Autopilot. It may have been deleted.* |
| Title and reference disagree | The reference wins (it's exact). The destination card shows *ChatGPT called it "…"*, and **Change** is one tap |
| Nothing new (every step already on the task, notes already there) | **"Already in Autopilot"**: *Everything ChatGPT sent is already on "…". Nothing to add.* |
| Duplicate link, still pending | The same review reopens with the same items. Nothing is duplicated |
| Duplicate link, already applied or declined | The same review opens in its settled state: *You've already reviewed this from ChatGPT.* Nothing is applied twice |
| User declines everything | Items are marked rejected. The bottom bar becomes **Return to ChatGPT** |
| Task edited since the conversation | The existing stale banner and per-row reasons (for example *A step with this wording is already on the task*). Conflicts need **Apply anyway** or **Keep yours** |
| Task is done or cancelled | Rows show *Can't be applied · The task is done; reopen it first*. **Change** picks another task |
| Cancel | ✕ or Back returns to ChatGPT. Unapplied changes stay on the task as *"N changes waiting for your review · From ChatGPT"* |

## 6. Navigation and Back

- The import screen is a **separate activity in its own Android task** (`taskAffinity=""`, `singleTop`,
  `persistNever`), the same shape as the App Link test screen that already returns to ChatGPT on the phone. It
  never opens Today. Autopilot's main task and back stack are untouched.
- **Return to ChatGPT / ✕ / system Back** end the import task. If ChatGPT started the import screen in its own
  task, as it did in the spike, the task is removed and Android shows ChatGPT again. If Android had put the screen
  on top of the caller's task, only the screen is closed, so ChatGPT is never closed with it.
- Back from the picker reached through **Change** returns to the review. Back from the review returns to ChatGPT.
- A second link while the screen is open (warm launch) replaces what's shown. Rotation and process death restore
  the validated fields, the chosen task and the review's own selection and edits, never the raw link.
- The button reads **Return to ChatGPT** when ChatGPT opened the link, or when Android doesn't report which app
  did. It reads **Close** when another app opened it.

## 7. Tradeoffs

- **A separate import activity instead of a deep link into the main navigation graph.** Pushing review onto
  `MainActivity` would put Autopilot's own task, with Today underneath, in front, so Back and Close would land on
  Today instead of ChatGPT. Embedding the existing `ProposalReviewScreen` in a small activity keeps the proven return
  behaviour, and it costs one composable host, not a second review.
- **The pending changes are saved when the review opens**, not when the user applies. This is what makes repeat
  taps idempotent and lets the user leave and finish later from the task's review banner. It's the same lifecycle
  research proposals already use.
- **Nothing pre-selected.** The review's rule stays: one extra tap ("Select all") instead of a special case.
- **Notes off by default** in the share sheet. That's safer, and a single tap turns them on.
- **Plain share intent, no API.** ChatGPT opens with the text prefilled and the user presses send. There's no
  OpenAI key, no account linking and no reading back the conversation. The link ChatGPT writes is the only way
  anything comes back.

## 8. Reused components

`AutopilotSheet`, `SurfaceCard`, `PrimaryButton` / `SecondaryButton` / `LinkButton`, `IconCircle`, the detail
screen's card style, `ProposalReviewScreen` + `ProposalReviewViewModel` (with three small optional parameters:
the exit label, the back description and a header slot), `ProposalApplier`, `ProposalReconciler`,
`ProposalSetRepository`, `HandoffFragment` (the strict `ap=v1` parser) and the App Link envelope checks.
