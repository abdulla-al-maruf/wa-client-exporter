<div align="center">

# WA Client Exporter

**Export selected WhatsApp chats — with full context — into clean, AI-ready files.**

Text, images, video, voice notes (and their WhatsApp transcripts), replies/quotes,
and reactions — all preserved, structured for feeding to an AI or keeping as records.
On-device, offline, for rooted Android.

![Platform](https://img.shields.io/badge/platform-Android%208%2B%20(rooted)-3DDC84)
![Root](https://img.shields.io/badge/root-Magisk%20%7C%20KernelSU-7DD9A0)
![Offline](https://img.shields.io/badge/100%25-offline-2E9E68)
![License](https://img.shields.io/badge/license-MIT-brightgreen)

</div>

---

## Why this exists

WhatsApp's built-in "Export chat" loses the context that actually matters:
which image belongs to which message, reply/quote chains, voice notes, reactions.
When you later try to understand a client conversation — or hand it to an AI — half
the meaning is gone.

**WA Client Exporter** reads WhatsApp's own database (with root) and rebuilds the
**complete** conversation for the clients you pick, then writes it as a structured
`messages.json` + readable `transcript.txt` (+ a `media/` folder). Perfect for
feeding a whole client thread to an AI for analysis, or keeping a faithful record.

> Built for agency owners, freelancers, and anyone who needs the *full* story of a
> client chat — not a stripped-down text dump.

## Features

- **Pick the clients you want** — searchable chat list, multi-select, names + recency.
- **Full context preserved** — text, replies/quotes (with the quoted text), images,
  video, documents, stickers, location, deleted markers, and **reactions**.
- **Voice notes + transcripts** — includes WhatsApp's own voice-note transcriptions
  when you've transcribed them in the app.
- **AI-ready output** — clean `transcript.txt` and structured `messages.json`; just
  drop a thread into your AI of choice.
- **Smart incremental exports** — first run is full; later runs only add what's new.
  Media is never re-copied. Send a small update to your PC, unzip, done.
- **Two modes** — Full export, or a small "last 7 days" update bundle.
- **Manage exports** — per-client list with sizes, lock to protect, delete to clean up.
- **100% offline** — nothing is uploaded anywhere; everything stays on your device.

## Requirements

- A **rooted** Android phone (**Magisk** or **KernelSU**), root granted to the app.
- Android 8 (API 26) or newer.
- WhatsApp installed (`com.whatsapp`).

## Install

1. Download the latest `app-debug.apk` from [Releases](../../releases).
2. Install it (allow "install from unknown sources").
3. Open the app and **grant root** when your root manager asks.

(Or build from source in Android Studio — see [Building](#building).)

## How to use

1. **Refresh chats** — pulls the latest from WhatsApp (do this after transcribing new
   voice notes so their text is included).
2. **Select** one or more clients.
3. **Export** → choose **Full** (everything) or **Update (last 7 days)**.
4. **Share** the produced zip to your PC (e.g. via KDE Connect).
5. On the PC, **unzip into that client's folder** — text is refreshed, new media added,
   nothing lost. Then hand `transcript.txt` (or `messages.json`) to your AI.

## Output

Each client gets a folder containing:

```
<client>/
├── messages.json        structured: who, when, text, reply_to, media, reactions, transcript
├── transcript.txt        clean readable conversation
├── new_since_last.txt    only what changed since the previous export
└── media/                images, video, voice notes, documents
```

## Privacy & responsible use

- **Your data stays on your device.** The app never uploads anything; there is no
  server, no account, no analytics.
- This tool exports **your own** WhatsApp conversations (chats you are part of). Only
  export and share data you have the right to, and respect the privacy of the people
  in those chats.
- The exported files contain private messages in plain text — store and transfer them
  carefully (don't put them in public/cloud-synced locations unencrypted).

## Disclaimer

Reading WhatsApp's local database requires root and is **not** an officially supported
WhatsApp feature; using it may be against WhatsApp's Terms of Service. Use at your own
risk. This project is **not affiliated with WhatsApp or Meta**, and is provided "as is"
without warranty.

## Roadmap

- No-root mode (via WhatsApp's encrypted `.crypt15` backup + key).
- WhatsApp Business (`com.whatsapp.w4b`) support.
- Optional on-device voice transcription for untranscribed notes.
- Encrypted export bundles.

## Building

Open the project in Android Studio (JDK 17), let Gradle sync, connect a rooted device,
and Run. Min SDK 26, target 35.

## Credits

- Root access via [libsu](https://github.com/topjohnwu/libsu).
- Inspired by [KnugiHK/WhatsApp-Chat-Exporter](https://github.com/KnugiHK/WhatsApp-Chat-Exporter).

## License

MIT — see [LICENSE](LICENSE).

---

<sub>Keywords: WhatsApp export, WhatsApp chat exporter, rooted Android, Magisk, KernelSU,
msgstore.db, WhatsApp to AI, chat backup, export WhatsApp with media and replies, voice
note transcription.</sub>
