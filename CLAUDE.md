# VaniseTV

Sideloaded Fire TV live-TV app (Kotlin, Android Views, Media3/ExoPlayer, Fire OS 6 / API 25). **Read `HANDOFF.md` first:** it has the architecture, machine setup, CI, open items and how the user likes to work.

Essentials:
- Every visible text in English **and** Portuguese (`res/values` + `res/values-pt`, and `TX.en`/`TX.pt` in the preview). `TranslationsTest` enforces it.
- UI changes: update `preview/vanisetv-preview.html` and publish it to https://claude.ai/artifact/HFz8Ruw2SKixtCRD8fmLnL before building, unless the user says to build straight away.
- Build: GitHub Actions builds every push to `main`. Locally (needs JDK 17 + Android SDK, see HANDOFF.md): set `JAVA_HOME` to the JDK 17 folder, then `./gradlew --no-daemon -q testReleaseUnitTest assembleRelease`.
- Commit and push only when the user asks; then report the GitHub Actions result for the push.
- Never print or commit `keystore.properties` or the keystore.
