# DOLE (Ditto Offline-first Ledger Exchange)

DOLE is a Proof-of-Concept for secure, offline peer-to-peer payments. By leveraging trusted hardware (Java Card) to enforce strict monotonic counters, the system prevents double-spending in partitioned networks without requiring global consensus.

This project was developed as part of the "New Trends for Local and Global Interconnects for P2P Applications" seminar at the University of Basel, Switzerland.

## Theoretical Foundation

The system is built upon the theoretical framework of the **GOC-Ledger**, proposed by Eric Lavoie. It utilizes **Grow-Only Counters (GOCs)** to track value transfers, ensuring that ledger states can mathematically converge through Strong Eventual Consistency without the need for a global consensus mechanism. DOLE extends this model by using certified hardware to locally enforce protocol rules, ensuring system integrity and preventing overspending or history forking.

## Key Features

* **Offline-First**: Fully functional without internet access. Transactions are exchanged via an ad-hoc mesh network (Bluetooth/Wi-Fi Aware) using the Ditto framework.
* **Trusted Hardware**: Uses the NXP J3R180 Secure Element to enforce the GOC-Ledger protocol, maintain internal sequence counters, and sign transactions within an isolated enclave.
* **No-ACK Optimization**: Implements an optimized model where reception is an implicit consequence of a SEND operation. This reduces the number of required log entries and the computational load for signature verification by 50%.
* **Cross-Platform**: Runs on Android (via NFC) and Desktop (Windows/Linux/macOS via USB Card Readers).
* **Modern UI**: Built with Kotlin Multiplatform and JetBrains Compose for a unified, responsive user experience across mobile and desktop devices.
* **Secure Provisioning**: Implements an issuer-centric provisioning model using manufacturer-issued Device Certificates to establish a cryptographic Root of Trust and verify hardware authenticity.

## Project Structure

The project is organized as a Gradle multi-module build:

* **common**: Shared data structures, protocol definitions, and constants used by both the Smart Card and the Client. Restricted to Java 8 for card compatibility.
* **card**: The Java Card Applet (J3R180). Contains the embedded logic for key management, GOC enforcement, and cryptographic signing. Includes the Provisioner tool for initialization.
* **client-core**: The central backend logic shared by all platforms. Handles P2P sync (Ditto), state management (Ledger), cryptography, and the ViewModels.
* **pc-app**: The desktop entry point. Implements the hardware driver using javax.smartcardio (isolated in a separate process for stability).
* **android-app**: The mobile entry point. Implements the hardware driver using Android's NfcAdapter.

## Prerequisites

### Hardware
* **Smart Card**: NXP J3R180 (Java Card 3.0.5 Classic Edition).
* **Reader**: Standard USB PC/SC Card Reader (for Desktop) or an NFC-enabled Android phone.

### Software
* **Java**: JDK 21 is required for the client applications.
* **Android SDK**: Required to build the mobile app.
* **Ditto**: A valid AppID and Token (configured in Constants.java).

## Building and Running

1. **Build the Project**
   Use the Gradle wrapper to build all modules:
   `./gradlew build`

2. **Provision a New Card**
   Before a smart card can be used, it must be initialized with the Applet and a device certificate.
   Connect a fresh card to your PC reader and run:
   `./gradlew :card:provision`

3. **Run on Desktop**
   Ensure your smart card reader is connected.
   `./gradlew :pc-app:run`

4. **Run on Android**
   Open the project in Android Studio and deploy the android-app module to your device. Ensure NFC, Bluetooth, and nearby Wi-Fi permissions are granted.

## Technology Stack

* **Language**: Java 21 (Client), Java 8 (Card), Kotlin (UI)
* **UI Framework**: JetBrains Compose Multiplatform
* **P2P Sync**: Ditto SDK
* **Build System**: Gradle (Kotlin DSL)
* **Card Tools**: GlobalPlatformPro, ant-javacard, Oracle JavaCard SDK