# Missing AAA resources on BBS FS 2.7

The installed addon always registers `AAAParticleFormRenderer`, including when the
form has no effect or references an unavailable effect. BBS uses that renderer
while collecting replay bounds/orbit targets, before an effect is drawn.
This cannot repair BBS's missing-renderer crash when the addon itself is absent;
the exporting app must reject AAA films for that destination.

Missing effects warn once per effect ID and draw nothing. A missing, unreadable,
or rejected texture/model/curve/material prevents registration of the entire
external effect, releases its temporary native effect, and logs the cause.
Use **Reload Effects** after repairing files to clear the failed-effect cache.
AAA's unsupported native-platform flag also makes loading a no-op with a warning.
Unrelated renderer/integration/programming errors are not swallowed.

## Build and unit tests

Use a full JDK 21 or newer for the existing Gradle/Loom tooling (addon bytecode
targets Java 17). Provision the official intermediary `bbs-2.7-1.20.1.jar` in
ignored `libs/`, or pass its external path:

```sh
./gradlew build -Pbbs_dev_jar=/absolute/path/bbs-2.7-1.20.1.jar
```

`build` runs the playback clock and required-asset I/O/decoder unit tests. No
Minecraft, BBS or AAA jars should be committed. The old CML-oriented integration
notes in `AAA_PARTICLES.md` do not describe this FS branch's build dependency.

## Opt-in real client regression

`MissingEffectReplayTest` adapts the world/tick harness from `fs-2.7-autotest`
without changing that shared branch. It is inactive unless the following exact
system property is set. Run in a disposable development directory:

```sh
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+$JAVA_TOOL_OPTIONS }-Dbbsaaa.autotest=missing-effect" ./gradlew runClient -Pbbs_dev_jar=/absolute/path/bbs-2.7-1.20.1.jar
```

PowerShell:

```powershell
$previousJavaOptions = $env:JAVA_TOOL_OPTIONS
try {
    $env:JAVA_TOOL_OPTIONS = "$previousJavaOptions -Dbbsaaa.autotest=missing-effect".Trim()
    .\gradlew.bat runClient '-Pbbs_dev_jar=C:\path\bbs-2.7-1.20.1.jar'
} finally {
    if ($null -eq $previousJavaOptions) { Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue }
    else { $env:JAVA_TOOL_OPTIONS = $previousJavaOptions }
}
```

The harness creates `aaa-missing-effect-<timestamp>`, builds an in-memory film
`aaa_missing_effect_regression` with
`effeks/__bbsaaa_missing__/does_not_exist.efkefc`, and selects its replay using the
actual `UIFilmPanel.replayEditor.setReplay` method. Orbit teleport on selection
is forced on for the check and restored afterward, exercising the reported BBS
bounds/orbit path. It repeats selection for 40 frames, asserts the renderer
remains registered and the loader returns no definition. Native support and an
initialized AAA resource registry are prerequisites, so a disabled runtime cannot
produce a false PASS. Existing worlds, films and assets are not modified.

Require `run/aaa-missing-effect-report.txt` to start with `PASS:`; a process exit
alone is not success. `FAIL:` contains the assertion/exception, with the stack in
the client log. A JVM/native crash may leave no report. Delete any old report
before each run. This harness needs a real graphical/OpenGL client and does not
prove Forge + Connector compatibility. Windows acceptance, effects visually
matching the app, FPS and world disconnect remain separate NOT_RUN gates until
executed on the user's instance.
