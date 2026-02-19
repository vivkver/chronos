# Project CHRONOS — Setup Guide

## 🚨 Gradle Required

To build and run this project, you need **Gradle 9.x** installed.
The automatic installation via Chocolatey failed due to lack of administrative privileges.

### 1. Install Gradle Manually

Please choose one of the following methods:

**Option A: Scoop (Recommended for User-Scope)**
```powershell
scoop install gradle
```

**Option B: Chocolatey (Requires Admin)**
```powershell
choco install gradle
```

**Option C: Manual Download**
Download the binary-only distribution from [gradle.org/releases](https://gradle.org/releases/) and add the `bin` folder to your PATH.

### 2. Verify Installation

```powershell
gradle -v
```

### 3. Build the Project

Once installed, run:

```powershell
gradle build
```

### 4. Run Benchmarks

**Simulated Fix vs SBE Encoding:**
```powershell
gradle :chronos-benchmarks:jmh
```

**Wire-to-Wire Latency Test:**
```powershell
gradle :chronos-benchmarks:runWireToWire
```
*(Added a convenient Gradle task for this in the build file!)*
