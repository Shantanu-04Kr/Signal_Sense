# Signal_Sense
# SignalSense 📡
### A Predictive Connectivity Intelligence System

> Built by **Fusion Force** for **FantomCode 2026**

---

## 🚀 Overview

SignalSense is a real-time mobile-based RF monitoring system that goes beyond
signal bars. It analyzes actual RF parameters, tests real-world connectivity,
and delivers actionable intelligence to users before they lose connectivity.

Instead of just showing signal bars, SignalSense:
- Monitors device-level network metrics every 5 seconds
- Tests real connectivity — ping, HTTP, and payment gateway reachability
- Classifies signal zones as Strong, Weak, or Dead
- Alerts users before complete signal loss
- Builds a crowd-sourced live signal heatmap on a real map

---

## 📌 The Problem

Even in the 5G era, mobile networks fail unpredictably in small geographic
pockets — inside campuses, offices, and public buildings.

**Real-world impact:**
- UPI transactions fail mid-payment
- Emergency calls drop without warning
- Internet disconnects during critical work
- Users have zero predictive visibility

> The problem is not lack of network — it is the lack of real-time signal intelligence.
> Connectivity failure today is reactive. SignalSense makes it predictive.

---

## ✨ Features

| Feature | Description |
|---|---|
| 📶 RF Monitoring | Polls dBm, SINR, RSRQ every 5 seconds |
| 🌐 Real Connectivity Test | Ping, HTTP, and payment gateway checks |
| 🗺 Live Heatmap | OSMDroid map with signal zone overlays |
| 🔴 Dead Zone Detection | Alerts after 3 consecutive dead readings |
| ☁️ Cloud Sync | Firebase Realtime Database integration |
| 📍 GPS Tracking | Location-tagged signal readings |
| 📱 5G/4G/3G/2G Support | Network-aware classification thresholds |

---

## 🏗 Architecture
Phone RF Hardware
↓
SignalMonitor (polls every 5s)
↓
ConnectivityTester (ping + HTTP + payment tests)
↓
ZoneClassifier (2G/3G/4G/5G aware thresholds)
↓
AlertEngine (smart consecutive-reading alerts)
↓
UI (Jetpack Compose — live updates)
↓
Firebase Realtime Database (cloud sync + GPS)
↓
HeatmapActivity (OSMDroid live signal map)

---

## 📱 Tech Stack

| Layer | Technology |
|---|---|
| Mobile | Kotlin, Native Android |
| UI | Jetpack Compose |
| RF Metrics | Android Telephony API |
| Maps | OSMDroid + OpenStreetMap |
| Backend | Firebase Realtime Database |
| Location | Android LocationManager |
| Build | Gradle 8.9, AGP 8.3.0 |

---

## 📊 Signal Classification

### Zone Thresholds by Network Type

| Metric | 2G | 3G | 4G LTE | 5G NR |
|---|---|---|---|---|
| dBm Strong | > -85 | > -85 | > -95 | > -100 |
| dBm Weak | > -100 | > -100 | > -110 | > -115 |
| SINR Strong | N/A | > 6 dB | > 12 dB | > 10 dB |
| SINR Weak | N/A | > 0 dB | > 0 dB | > -3 dB |
| RSRQ Strong | N/A | N/A | > -10 dB | > -10 dB |
| RSRQ Weak | N/A | N/A | > -15 dB | > -17 dB |

### Real-World Connectivity Tests

| Zone | Calls | Payments | Data | Ping |
|---|---|---|---|---|
| 🟢 Strong | ✓ | ✓ | ✓ | < 300ms |
| 🟡 Weak | ~ | ✗ | ~ | > 300ms |
| 🔴 Dead | ✗ | ✗ | ✗ | Unreachable |

---

## 🗂 Project Structure
app/src/main/java/com/signal_sense/
├── core/
│   ├── SignalData.kt           — RF data model
│   ├── SignalMonitor.kt        — 5s polling engine
│   ├── ZoneClassifier.kt       — Network-aware classification
│   ├── AlertEngine.kt          — Dead zone alert system
│   ├── ConnectivityTester.kt   — Real ping/HTTP/payment tests
│   ├── SignalMonitorService.kt — Background foreground service
│   └── FirebaseUploader.kt     — Cloud sync with GPS location
├── MainActivity.kt             — Live signal dashboard
└── HeatmapActivity.kt          — OSMDroid live signal heatmap

---

## 🛠 Setup Instructions

### Prerequisites
- Android Studio Hedgehog or later
- Android phone with API 26+ (Android 8.0)
- Firebase account

### Steps

1. **Clone the repository**
```bash
git clone https://github.com/YOURUSERNAME/SignalSense.git
cd SignalSense
```

2. **Set up Firebase**
   - Create a project at https://console.firebase.google.com
   - Add an Android app with package `com.signal_sense`
   - Download `google-services.json` and place it in `app/`
   - Enable Realtime Database in test mode

3. **Open in Android Studio**
   - File → Open → select the SignalSense folder
   - Wait for Gradle sync to complete

4. **Run on device**
   - Connect Android phone via USB
   - Enable USB Debugging in Developer Options
   - Press the Run ▶ button
   - Grant all permissions when prompted

---

## 📋 Required Permissions

```xml
READ_PHONE_STATE        — RF signal metrics
ACCESS_FINE_LOCATION    — GPS location for heatmap
ACCESS_COARSE_LOCATION  — Network location fallback
FOREGROUND_SERVICE      — Background monitoring
POST_NOTIFICATIONS      — Alert notifications
INTERNET                — Firebase + connectivity tests
```

---

## 🔥 Firebase Data Structure
signalsense-xxxxx-default-rtdb/
└── readings/
└── 2026-04-25/
└── 12:48:00/
├── dbm: -95
├── sinr: 12.0
├── rsrq: -10.5
├── network: "5G NR"
├── zone: "Strong"
├── isDeadZone: false
├── lat: 12.9716
├── lng: 77.5946
└── timestamp: 1745123280000

---

## 👥 Team

**Fusion Force** — FantomCode 2026

---

## 📄 License

This project is built for hackathon purposes.

---

> SignalSense — Because connectivity failure should never catch you off guard.
