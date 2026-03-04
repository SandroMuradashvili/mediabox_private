# Mediabox — Android TV App

A lean-back Android TV application for live IPTV streaming with archive (time-shift) support.

## Features

- **QR code login** — scan with phone to authenticate, no keyboard needed on TV
- **Live TV** — channel list with logo, number, category
- **Archive / time-shift** — rewind up to the channel's supported hours back
- **EPG (TV Guide)** — full programme guide with date dividers, archive jump, locked channel handling
- **Favourites** — star/unstar channels, synced to server
- **Jump to time** — scroll picker to seek to any archived point
- **Play/Pause, rewind & forward** — 15s / 1m / 5m controls
- **Subscription awareness** — locked channels shown dimmed; inaccessible channels blocked

## Architecture

```
ui/
  LoginActivity       — QR pairing flow
  MainActivity        — Home menu (Watch TV / Profile / Settings)
  UserActivity        — Account info + plan details
  player/
    PlayerActivity    — ExoPlayer host + key routing
    ControlOverlayManager   — top/bottom HUD
    EpgOverlayManager       — TV Guide overlay
    TimeRewindOverlayManager — jump-to-time picker

data/
  api/ApiService      — raw HttpURLConnection calls (channels, streams, EPG, favourites)
  remote/AuthApiService — Retrofit client for plans/account (Bearer token injected automatically)
  model/Channel, Program
  repository/ChannelRepository — single source of truth, coordinates API + state
```

## Setup

1. Clone the repo
2. Open in Android Studio
3. Build and run on an Android TV device or emulator (API 23+)

API base URL is set in `build.gradle.kts` via `BuildConfig`:
```
BASE_URL     = https://tv-api.telecomm1.com/
BASE_API_URL = https://tv-api.telecomm1.com/api
```

## TODO

- Settings screen (display, audio, about)
- Multi-language audio track switching (UI exists, logic not wired)
- Watch history / recents
- 18+ channel PIN lock
- Fast-forward / rewind at 2×, 4×, 8× speed
- Replace loading spinner with branded animation
- Ad support (subscription tier)