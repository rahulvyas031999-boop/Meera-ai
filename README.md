# मेरा AI (Kotlin + Jetpack Compose)

Splash → Dashboard (orb + mic) → ☰ मेन्यू: API Key, Live API / Modular.

## APK बनाना (GitHub पर)
1. GitHub पर नया repo बनाएँ, इस पूरे फ़ोल्डर की फ़ाइलें अपलोड करें (`.github/workflows/build-apk.yml` समेत).
   अगर hidden फ़ोल्डर अपलोड न हो तो repo में `.github/workflows/build-apk.yml` नाम से नई फ़ाइल बनाकर नीचे वाला कंटेंट पेस्ट करें.
2. Repo के **Actions** टैब में **Build APK** चलता है (या *Run workflow* दबाएँ). 4-8 मिनट लगते हैं.
3. रन ख़त्म होने पर नीचे **Artifacts → MeraAI-debug-apk** डाउनलोड करें, ज़िप खोलकर `app-debug.apk` फ़ोन में इंस्टॉल करें.
4. ऐप खोलें → ☰ → API Key में Gemini Key डालें → माइक दबाएँ.

## मॉडल नाम बदलना
सब `app/src/main/java/com/meraai/app/Config.kt` में हैं (`Cfg` ऑब्जेक्ट). वही नाम रखे हैं जो आपके AI Studio प्रोजेक्ट में थे.

## फ़ाइलें
- `Audio.kt` — माइक (Echo Cancel / Noise Suppress / AGC), PCM प्लेयर, Turn detection (VAD)
- `GeminiLive.kt` — Live API WebSocket
- `GeminiRest.kt` — Modular: STT, streaming LLM, TTS
- `VoiceViewModel.kt` — पूरा सेशन, barge-in, स्टेट मशीन
- `Ui.kt` — Splash, Dashboard, Orb, मेन्यू, Key डायलॉग

## build-apk.yml का कंटेंट
```yaml
name: Build APK
on:
  push:
    branches: [main, master]
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9
      - name: Build debug APK
        run: gradle assembleDebug --no-daemon --stacktrace
      - uses: actions/upload-artifact@v4
        with:
          name: MeraAI-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```
