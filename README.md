# 🛡️ Aegis Tactical - Off-Grid Coded PHY SOS

**Aegis** is a decentralized, peer-to-peer emergency communication system designed for Android. It leverages the **Bluetooth 5.0 Long Range (Coded PHY)** specification to transmit encrypted SOS signals over extreme distances (1km+ Line-of-Sight) without reliance on cellular networks, Wi-Fi, or satellites.

> **"When the grid goes down, Aegis wakes up."**

---

## 🤖 Project Origin & AI Transparency

**This project is a technological demonstration of AI-assisted software engineering.**

*   **Architect & Developer:** **Gemini 3 Pro AI**.
*   **Human Operator:** Prompt Engineering & Testing via AndroidIDE.
*   **Codebase:** 100% AI-Generated Kotlin.
*   **Documentation:** AI-Generated.

This repository proves that high-level tactical software involving complex hardware APIs (Bluetooth LE, Crypto, GPS) can be built entirely on a mobile device using AI.

---

## ⚠️ Maintenance Status: ARCHIVED

**Status:** `[EOOL] End of Life / No Maintenance`

I am releasing this project as **Open Source / As-Is**. I will **not** be providing updates, bug fixes, or responding to issues.

**Why is this archived?**
1.  **Tooling Constraints:** This app was built entirely on a smartphone using **AndroidIDE**. The environment is currently limited to **Android 14 (SDK 34)**, preventing further modernization without migrating to a PC workflow. (will work on newer Androids maybe with a warning i tested this on a phone with Android 16 it worked perfectly fine)
2.  **Scope Complete:** The project successfully met its design goals as a functional prototype.
---

## 📡 Critical Hardware Requirements

**READ THIS BEFORE INSTALLING.**

For the **1km+ Long Range** capabilities to function, **BOTH** the Sender and the Receiver devices must support specific hardware standards.

| Feature | Requirement | Why? |
| :--- | :--- | :--- |
| **Bluetooth Version** | **5.0 or higher** | Required for Extended Advertising. |
| **Chipset Feature** | **LE Coded PHY** | The physical radio modulation that allows long-range transmission. |
| **Advertising** | **Extended Advertising** | Required to send payloads larger than 31 bytes (Aegis packets are ~113 bytes). |

### 🛑 What happens if my phone is old?
*   **One Device Unsupported:** If the Sender has Coded PHY but the Receiver does not, the Receiver **will not see the signal**.
*   **Both Unsupported:** The app will attempt to fall back to **Legacy Mode (1M PHY)**. The app will work, but the range will drop from **1km** to **~30-50 meters**.

> **Verified Devices:** Google Pixel 6/7/8, Samsung Galaxy S21/S22/S23/S24, OnePlus 9/10/11.
> **Likely Unsupported:** Older budget phones, devices released before 2018.

---

## 🚨 Operational Protocol: Mutual Pairing

Aegis uses **Symmetric AES-256 Encryption**. This means the key used to lock the message is the same key used to unlock it.

For **Two-Way Communication** (SOS + ACK), you must perform a **Mutual Handshake**:

1.  **Step 1:** User A shows QR Code -> User B scans it. (Now B can hear A).
2.  **Step 2:** User B shows QR Code -> User A scans it. (Now A can hear B).

**❌ If you skip Step 2:** User A will be able to scream for help, but will **never hear the acknowledgement** that help is coming.

---

## ⚙️ Technical Features

### 1. Radio Physics (Coded PHY)
*   **S=8 Error Correction:** The app forces the Bluetooth hardware into `PHY_LE_CODED` mode. This transmits data slower but with heavy redundancy, allowing the signal to be reconstructed even if 50% of the packet is corrupted by noise or distance.
*   **Anti-Jamming Logic:** Implements a 150ms hardware cool-down between broadcasts to prevent Bluetooth stack overflows on Samsung/Pixel devices.

### 2. Military-Grade Security
*   **Algorithm:** AES-256-GCM (Galois/Counter Mode).
*   **Integrity:** Authenticated Encryption ensures packets cannot be spoofed or tampered with.
*   **Hardware Backed:** Private keys are wrapped using the device's **Hardware Keystore (TEE)**, making them impossible to extract even on rooted devices.
*   **Anti-Replay:** All packets contain a millisecond-precision timestamp. Recorded signals played back by attackers are automatically rejected.

### 3. Tactical Telemetry
*   **GPS Injection:** Automatically acquires a GPS lock and embeds Latitude/Longitude into the encrypted payload.
*   **Ghost Signal Detection:** The radio engine can detect "Ghost Packets"—signals that are on the correct frequency but too weak to decrypt. This alerts the user that a contact is nearby but out of data range.
*   **Proximity Tracker:** A visual signal strength bar (RSSI) allows for "Hot or Cold" tracking to locate a victim without GPS.

### 4. Robust Architecture
*   **WakeLock Engine:** The background service acquires a partial CPU WakeLock, ensuring the radio continues scanning even if the phone is in "Deep Sleep" mode in a pocket.
*   **Zero Dependencies:** The app uses **0 external libraries** (except ZXing for QR). It relies entirely on native Android APIs for maximum stability and minimal APK size.

---

## 📦 Installation Guide

1.  Navigate to the **[Releases]** section of this repository.
2.  Download `app-release.apk`.
3.  Install on two compatible Android devices.
4.  **Permissions:** Open the app and grant Location (for Bluetooth scanning), Camera (for QR), and Notifications.

### ⚠️ IMPORTANT: First Run Protocol
**After granting permissions for the first time, you MUST fully close the app and open it again.**
1.  Grant Permissions.
2.  Swipe the app away from your "Recent Apps" list (Force Close).
3.  Re-open the app.
4.  Press **"BOOT SYSTEM"**.

*This ensures the Bluetooth and GPS engines initialize correctly with the newly granted permissions.*

---

*This project is provided for educational and research purposes only.*
