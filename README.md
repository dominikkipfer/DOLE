# DOLE (Ditto Offline-first Ledger Exchange)

DOLE is a Proof-of-Concept for secure, offline peer-to-peer payments. By leveraging trusted hardware (Java Card) to enforce strict monotonic counters, the system prevents double-spending in partitioned networks without requiring global consensus.

This project was developed as part of the "New Trends for Local and Global Interconnects for P2P Applications" seminar at the University of Basel, Switzerland.

## Theoretical Foundation

The system is built upon the theoretical framework of the **GOC-Ledger**, proposed by Erick Lavoie. It utilizes **Grow-Only Counters (GOCs)** to track value transfers, ensuring that ledger states can mathematically converge through Strong Eventual Consistency without the need for a global consensus mechanism. DOLE extends this model by using certified hardware to locally enforce protocol rules, ensuring system integrity and preventing overspending or history forking.

## Key Features

* **Offline-First**: Fully functional without internet access. Transactions are exchanged via an ad-hoc mesh network (Bluetooth/Wi-Fi Aware) using the Ditto framework.
* **Trusted Hardware**: Uses the NXP J3R180 Secure Element to enforce the GOC-Ledger protocol, maintain internal sequence counters, and sign transactions within an isolated enclave.
* **No-ACK Optimization**: Implements an optimized model where reception is an implicit consequence of a SEND operation. This reduces the number of required log entries and the computational load for signature verification by 50%.
* **Unified Codebase**: Built entirely with **Kotlin Multiplatform (KMP)** and **Compose Multiplatform**, allowing business logic, P2P sync, and UI to be shared seamlessly across mobile and desktop devices.
* **Cross-Platform Hardware Access**: Runs natively on Android (via NFC) and Desktop (Windows/Linux/macOS via USB PC/SC Card Readers) utilizing platform-specific actual/expect implementations.
* **Secure Provisioning**: Implements an issuer-centric provisioning model using manufacturer-issued Device Certificates to establish a cryptographic Root of Trust and verify hardware authenticity.

## Project Structure

The project has been migrated to a modern Kotlin Multiplatform architecture:

* **card**: The Java Card Applet (J3R180) written in Java 8. Contains the embedded logic for key management, GOC enforcement, and cryptographic signing. Includes the `Provisioner` tool for initialization.
* **composeApp**: The unified Kotlin Multiplatform module containing the entire client application:
   * `commonMain`: Shared business logic (Ditto Sync, Ledger state, ViewModels) and the shared JetBrains Compose UI.
   * `desktopMain`: Desktop-specific implementations, including the PC/SC Smart Card driver (`javax.smartcardio`) and local file storage.
   * `androidMain`: Mobile-specific implementations, including the Android NFC hardware driver and Android context management.

## Prerequisites

### Hardware
* **Smart Card**: NXP J3R180 (Java Card 3.0.5 Classic Edition).
* **Reader**: Standard USB PC/SC Card Reader (for Desktop) or an NFC-enabled Android phone.

### Software
* **Java**: JDK 21 is required for the client applications.
* **Android SDK**: Required to build the mobile app.
* **Ditto**: A valid AppID and Token (configured in `Constants.java`).

## Building and Running

1. **Build the Project**
   Use the Gradle wrapper to build all modules:
   `./gradlew build`

2. **Provision a New Card**
   Before a smart card can be used, it must be initialized with the Applet and a device certificate.
   Connect a fresh card to your PC reader and run:
   `./gradlew :card:provision`

3. **Run on Desktop**
   Ensure your smart card reader is connected to your PC.
   `./gradlew desktopRun`

4. **Run on Android**
   Open the project in Android Studio and deploy the android-app module to your device. Ensure NFC, Bluetooth, and nearby Wi-Fi permissions are granted.

## Technology Stack

* **Language**: Kotlin (Client & UI), Java 8 (Smart Card)
* **UI Framework**: JetBrains Compose Multiplatform
* **Architecture**: Kotlin Multiplatform
* **P2P Sync**: Ditto SDK (Kotlin & Binaries)
* **Build System**: Gradle (Kotlin DSL)
* **Card Tools**: GlobalPlatformPro, ant-javacard, Oracle JavaCard SDK