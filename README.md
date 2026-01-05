# 🛡️ Aegis Tactical - Off-Grid Coded PHY SOS

**Aegis** is a decentralized, peer-to-peer emergency communication system designed for Android. It leverages the **Bluetooth 5.0 Long Range (Coded PHY)** specification to transmit encrypted SOS signals over extreme distances (1km+ Line-of-Sight) without reliance on cellular networks, Wi-Fi, or satellites.
NOTE: THIS APP DOES NOT USE BLUETOOTH MESH NETWORKING. IT USES CODED PHY S=8 FOR THE 1KM+ POINT TO POINT BLUETOOTH SOS COMMUNICATION. ALL ON YOUR ANDROID PHONE

> **"When the grid goes down, Aegis wakes up."**

---

## ⚠️ Project Status: Hobby / Low Maintenance

**This is a personal hobby project, not a commercial product.**

*   **Development Pace:** Updates will be slow and sporadic. I have other commitments and maintain this repository in my free time.
*   **Issue Tracking:** I do **not** monitor the "Issues" tab frequently. Please do not expect immediate support or bug fixes.
*   **Contribution:** The code is Open Source. Feel free to fork it and modify it for your own needs.

### 📱 Android Compatibility Note
While this project was built using **AndroidIDE** (which currently caps at **Target SDK 34 / Android 14**), the application architecture is forward-compatible.
*   **Built for:** Android 14.
*   **Tested on:** Android 15 & Android 16 (Developer Preview).
*   **Result:** The app functions correctly on newer Android versions without modification.

---

## 🤖 AI Transparency

**This entire project was architected and coded by Gemini 3 Pro AI.**

*   **Codebase:** 100% AI-Generated Kotlin.
*   **Architecture:** Monolithic Service (Optimized for Mobile IDEs).
*   **Documentation:** AI-Generated.

This repository serves as a proof-of-concept that high-level tactical software involving complex hardware APIs (Bluetooth LE, Crypto, GPS) can be built entirely on a smartphone using AI assistance.

---

## 📡 Critical Hardware Requirements

**READ THIS BEFORE INSTALLING.**

For the **1km+ Long Range** capabilities to function, **BOTH** the Sender and the Receiver devices must support specific hardware standards.

| Feature | Requirement | Why? |
| :--- | :--- | :--- |
| **Bluetooth Version** | **5.0 or higher** | Required for Extended Advertising. |
| **Chipset Feature** | **LE Coded PHY** | The physical radio modulation that allows long-range transmission. |
| **Advertising** | **Extended Advertising** | Required to send payloads larger than 31 bytes (Aegis packets are ~113 bytes). |

> **Verified Devices:** Google Pixel 6/7/8/9, Samsung Galaxy S21-S24, OnePlus 9+.
> **Fallback:** If a device does not support Coded PHY, the app automatically falls back to **Legacy Mode (1M PHY)**, reducing range to ~30-50 meters.

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
4.  **Permissions:** Grant Location (for Bluetooth scanning), Camera (for QR), and Notifications.

### ⚠️ IMPORTANT: First Run Protocol
**After granting permissions for the first time, you MUST fully close the app and open it again.**
1.  Grant Permissions.
2.  Swipe the app away from your "Recent Apps" list (Force Close).
3.  Re-open the app.
4.  Press **"BOOT SYSTEM"**.

*This ensures the Bluetooth and GPS engines initialize correctly with the newly granted permissions.*

---

*This project is provided for educational and research purposes only.*
