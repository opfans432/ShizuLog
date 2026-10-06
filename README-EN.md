<p align="center">
  <img src=".github/assets/shizulog-icon.png" width="96" height="96" alt="ShizuLog">
</p>

<h1 align="center">ShizuLog</h1>

<p align="center">
  An Android Logcat recording, crash troubleshooting, and log history management tool powered by Shizuku
</p>

<p align="center">
  <strong>Current version: v2.0.0</strong>
</p>

---

## Introduction

ShizuLog is designed to collect and organize Logcat on Android devices through Shizuku. It is useful for investigating crashes, freezes, abnormal behavior, background process issues, and situations where you need to observe multiple apps together with system context.

It currently supports `single-app, multi-app, and global Logcat` recording scopes, along with dynamic PID / multi-process tracking, crash snapshots, full log viewing, log history management, and automatic splitting of large logs into multiple parts.

> ShizuLog can only record content that an app or the Android system actually writes to Logcat. Ordinary taps, gameplay actions, or business operations are not automatically recorded unless they produce Logcat output.

## Recording Modes

### Single App

Suitable for investigating one target app.

- Select the target from installed apps
- Or enter a package name manually
- Track relevant logs by target UID and dynamic PID
- Support multi-process target apps such as `:service` and `:remote`
- Open the target app directly while recording to reproduce the issue

### Multiple Apps

Suitable for investigating related apps at the same time, such as a main application, plugin, floating window, or companion tool.

- Search and select multiple installed apps
- Track multiple target UIDs simultaneously
- Dynamically track all target process PIDs
- Automatically deduplicate identical UIDs
- Keep logs containing target package names, PIDs, or related system context
- "Open Selected App" lets you choose one of the selected apps to launch directly

### Global Logcat

Records Logcat without filtering by target app, using whatever system logs Shizuku's current permissions allow it to read.

- Capture `main / system / crash / events`
- Suitable when the target is unknown, for system-level failures, or for cross-app issues
- Automatically split logs at approximately **50 MB**
- Split files use `part01 / part02 / ...`
- Warn about log volume and privacy risks before recording starts

> "Global" means ShizuLog does not actively filter by app UID. The logs it can actually read still depend on the Android version, ROM, and Shizuku's current Shell / Root permissions.

## Real-Time Logs

The home screen provides a real-time preview and recording status.

- Display logs in real time
- Filter by `All / WARN+ / ERROR` severity
- Keyword search
- Preserve the scroll position from before filtering
- Do not force the user back to the bottom while they are viewing older logs
- Display in real time:
  - Total log lines
  - WARN count
  - ERROR count
  - Current line rate
  - Written size
  - Current PID count
  - Current global-log volume number

For smooth performance, the home-screen preview keeps only a limited amount of output. **The complete raw log is always written to disk.**

## Crash Capture

ShizuLog keeps additional context for common crash scenarios.

- `FATAL EXCEPTION`
- `AndroidRuntime`
- `ANR`
- `SIGABRT`
- `SIGSEGV`
- `signal 6 / signal 11`
- Native crashes
- Related `ActivityManager / ActivityTaskManager` context
- Manual "Crash Snapshot"
- Automatically attempt to capture an additional crash snapshot after returning from the target app to ShizuLog

In multi-app mode, ShizuLog combines target UIDs, dynamic PIDs, process names, and package names to keep related context instead of losing system-process logs too early through a simple `logcat --uid` filter.

## Log History

v1.5.0 introduced a complete upgrade to the history-log page.

Supported features:

- Scan historical logs in the background without blocking the UI
- Search app names, package names, and file names
- Filter by mode:
  - All
  - Single App
  - Multiple Apps
  - Global
  - Has Crash
- Sort by:
  - Newest
  - Largest
  - Oldest
- Show the total number of logs and total storage usage
- Export individual logs
- Delete individual logs
- Prevent deletion of the file currently being recorded
- Bulk cleanup:
  - Older than 7 days
  - Older than 30 days
  - All historical logs
- Automatically recognize global split-log files

## Full Log Viewer

Both the home page and history-log page can open the full log viewer.

- Not limited by the home-screen preview length
- Read large files in pages of approximately **256 KB**
- First page / Previous / Next / Last page
- Long-press text to select and copy
- Support log files that are still growing
- Show `● Writing` while the current file is still being written
- Tap "Refresh" to recalculate the latest page count
- When already on the last page, refreshing continues to follow the newest page

## Usage

1. Install and start **Shizuku**.
2. Open **ShizuLog** and grant it Shizuku permission.
3. Choose a recording scope: `Single App / Multiple Apps / Global`.
4. In Single App or Multiple Apps mode, select the target app(s).
5. Tap **Start Recording**.
6. Open the target app and reproduce the issue. In Multiple Apps mode, "Open Selected App" lets you choose which selected app to launch.
7. Return to ShizuLog after reproducing the issue.
8. Stop recording, then inspect the real-time log, full log, or log history.
9. When you need to send the logs to someone else for analysis, use the export feature to save a `.log` file.

## Permissions and Backend

ShizuLog performs log collection through Shizuku, so users do not need to separately run an ADB authorization workflow on a computer.

The app shows the current Shizuku backend, for example:

- ADB Shell
- Root

The Logcat range that can be read is determined by Android and the permissions available through the current Shizuku backend.

## Building

Build environment:

- JDK 17
- Android SDK 35
- Gradle 8.9
- Android Gradle Plugin 8.7.3

Build command:

```bash
gradle --no-daemon clean :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions automatically builds the APK and publishes it in the corresponding version Release.

## Main Dependencies

```text
dev.rikka.shizuku:api:13.1.0
dev.rikka.shizuku:provider:13.1.0
androidx.appcompat:appcompat:1.7.1
com.google.android.material:material:1.13.0
```

## Notes

- Logcat is not the same as a complete record of user activity.
- Some production apps simply output very little logging; this is normal.
- Global mode may generate a large amount of output and may contain sensitive information produced by other apps or the system.
- Long-running global captures automatically split into multiple files, but you should still monitor available device storage.
- If Shizuku is stopped, restarted, or loses permission, the connection or authorization must be restored.
- Different Android ROMs may impose different restrictions on which Shell-visible logs can be read.

## Version

Current version: **v2.0.0**

See [`RELEASE_NOTES.md`](RELEASE_NOTES.md) for detailed changes.

## License

This project is licensed under the [MIT License](LICENSE).

## Crash Analysis

v1.5.1 introduced the local crash analyzer.

It can be opened from the current log or historical logs. It automatically detects common Java / Kotlin `FATAL EXCEPTION`, ANR, SIGSEGV, and SIGABRT crashes and attempts to extract:

- Crash type
- Process / PID
- Thread
- Exception class or Native signal
- `Caused by` root cause
- Key call locations
- Crash stack trace snippets

Analysis is performed locally on the device and logs are not uploaded automatically.

## Diagnostic Pack Privacy

Starting with v1.6.1, diagnostic packs use redaction mode by default. ShizuLog attempts to mask common tokens, Authorization headers, cookies, API keys, session fields, password fields, and JWTs. When complete raw information is required, you can manually choose the "Raw" version.

Historical logs can also be used to generate diagnostic packs directly, with the recording mode and target-app information from the log file header taking priority.

Each diagnostic pack includes a `manifest-sha256.txt` integrity manifest.

## Professional Log Viewer

v1.7.0's full-log page supports searching the entire log, regular expressions, previous / next match navigation, jumping to ERROR entries, exact line numbers, highlighting target lines, copying error blocks, adjusting font size, automatic line wrapping, and paged reading for large files.

Search and ERROR indexing scan logs as a stream instead of loading an entire large log into memory at once.

## Log Analysis Workspace

v1.8.0 introduced combined filtering across an entire log by Tag / PID / process / package / log level, custom filter presets, and bookmarks persisted per log file. Search results, ERROR entries, and filter results can all be bookmarked, allowing you to jump back to the corresponding line when reopening the same historical log later.

## Real-Time Logcat Analysis

v1.9.0 added real-time statistics during recording for log levels, log rate, ERROR rate, Top Tags, and PID / process changes over the most recent 60 seconds. The home-screen preview also gained combined D+ / I+ / W+ / E+, Tag, PID, and process/package-name filters. Real-time filtering affects only the on-screen preview and does not affect the complete log written to disk.

## Recording Sessions

v2.0.0 introduced a session-based troubleshooting workflow. Every time Logcat recording starts, a session is created automatically and stores the target apps, recording mode, start/end time, log path, log size, and final status.

The new workspace home screen provides unified access to real-time logs, sessions, log history, the analysis workspace, and crash analysis.
