# Apple Music auf dem Philips 55OLED708/12

Dieses Projekt passt Apple Music 7.0.0 (Android, Build 1614, Paket `com.apple.android.music`) für den Philips 55OLED708/12 mit Google TV an. Ziel sind Dolby Atmos, Querformat und Bedienung ausschließlich mit der Fernbedienung. Die APK wird lokal aus dem Originalpaket gebaut. Das öffentliche Repository enthält die eigenen Änderungen und die Übergabe, keine Apple-APK, Kontodaten oder Signaturschlüssel.

## Stand am 24. November 2026

| Stand | Ergebnis |
| --- | --- |
| Dolby Atmos | Der Nutzer bestätigte Wiedergabe auf seinem Philips mit der gepatchten 32-Bit-App. Titel und Ausgabeformat können je nach Aufnahme und Audiopfad variieren. |
| Querformat | Auf dem Philips sichtbar bestätigt. Die App verwendet ihre vorhandene breite Oberfläche. |
| D-Pad mit Fokusrahmen | Die erste Version ohne Zeiger funktionierte auf dem Philips. Der Nutzer meldete, dass Inhalt, Menüleiste und Miniplayer noch gemeinsam in der Auswahl lagen. |
| D-Pad mit drei Bereichen | Gebaut, signiert und statisch geprüft. Die Bereichserkennung wurde anhand des gezeigten Layouts getestet. Der Gerätetest dieser Version steht aus. |
| Direktstart einzelner Titel | Der Nutzer bestätigte, dass der Direktstart nach **Anstößige Inhalte → Immer** funktioniert. Die versuchten Änderungen an der Warteschlange und die Diagnose gehören nicht zum aktuellen Stand. |

Die letzte APK heißt `Apple-Music-7.0.0-Atmos-Philips-55OLED708-DPad-Zones-armv7.apk`. Sie wurde im bisherigen Chat bereitgestellt.

```text
SHA-256 APK: 26c307642fef45a1745e70e826744cb47eac86242d7cf0573850221bf8d76b76
SHA-256 Signaturzertifikat: 422b86279f926e7587a148018f8c4036473b9cb7c26ea8753a695b7829570b95
```

Diese APK lässt sich über die Projektversionen **TV-Remote** und **DPad** installieren, da Paketname und Signatur übereinstimmen. Bei einer anderen Signatur erfordert Android eine Deinstallation. Dabei können Anmeldung und Einstellungen verloren gehen. Vor einer neuen Signierung den vorhandenen Schlüssel über einen privaten, autorisierten Weg beziehen, falls ein Update ohne Deinstallation erforderlich ist.

## Was geändert wurde und warum

1. **32-Bit-Build für den Philips.** Die erste APK enthielt nur ARM64-Bibliotheken. Für die dokumentierte Firmware des 55OLED708/12 wurde anschließend `armeabi-v7a` aus dem vollständigen Apple-Music-Bundle verwendet. Die 32-Bit-APK enthält den Basis-Split, den ARMv7-Split und die Dichte-Ressourcen. Das Originalpaket benötigt mindestens Android 11.
2. **Atmos-Erkennung für Apple Music 7.0.0.** [VinkyVs ReVanced-Patch](https://github.com/VinkyV/AppleMusicPatches) suchte in dieser Version noch nach der alten Companion-Klasse. Die entsprechende Methode liegt in Build 1614 in `com.apple.android.music.util.AudioDeviceCapabilities.isDolbyAtmosAvailable(AudioCapabilities)`. [PatchAtmos.java](tools/PatchAtmos.java) ändert dort genau die geprüfte MIME-Konstante von `audio/eac3-joc` zu `audio/eac3`. Die App erkennt damit vorhandenes Dolby Digital Plus als geeigneten Audiopfad. Die HLS-Tonspur, die Codec-Auswahl und die übrigen Vorkommen von `audio/eac3-joc` bleiben erhalten.
3. **TV-Launcher und Querformat.** Der ReVanced-Patch **Fix leanback for TV** machte die App im TV-Launcher startbar. Die Hauptaktivität wurde anschließend im Manifest und in ihrem Startcode auf Querformat gesetzt. `MainActivity.onCreate` ruft nach dem Aufruf der Oberklasse `TvControls.install(Activity)` auf. [BuildUi.java](tools/BuildUi.java) schreibt die angepasste Klasse und das Manifest in separate Build-Dateien.
4. **Zeiger als erste Bedienhilfe.** Die erste TV-Remote-Version übersetzte Pfeiltasten und OK in Zeigerbewegung und Berührungsereignisse. Der Nutzer wollte anschließend direkt zwischen Bedienelementen wechseln. Der Zeiger wurde durch eine Fokussteuerung ersetzt. Ein unveröffentlichter Entwurf mit einer Überschreibung von `dispatchKeyEvent` war ungeeignet, da die geerbte Methode final ist.
5. **Auswahl über vorhandene Aktionen.** [TvControls.java](src/tv/local/applemusic/TvControls.java) verarbeitet Fernbedienungstasten über den bestehenden `Window.Callback`. Es liest anklickbare Android-Views und die sichtbaren Compose-Semantikknoten, markiert ein Element mit einem Rahmen und ruft bei OK dessen vorhandene Klickaktion auf. Bei Compose verwendet es `AccessibilityNodeProvider.performAction` innerhalb der App. Dafür muss kein Bedienungshilfedienst eingeschaltet werden. Die derzeitigen Namen der internen Compose-Klassen und Methoden sind an Build 1614 gebunden.
6. **Menü, Inhalt und Miniplayer.** Der jüngste Stand beschränkt kurze Pfeiltasten auf den aktiven Bereich. **↑ halten** wählt die Menüleiste, **↓ halten** den Miniplayer. Oben kurz **↓** oder unten kurz **↑** führt zurück zur vorherigen Auswahl im Hauptinhalt, sofern sie noch sichtbar ist. Die Miniplayer-Erkennung nutzt die gemeinsame Höhe mehrerer kompakter Bedienelemente am unteren Rand. Layouts mit verstecktem Miniplayer, Dialogen oder Vollbild-Player sind noch auf dem Gerät zu prüfen.

### Aktueller Fernbedienungs-Patch

- **↑ / ↓** bewegen den Fokus beim Drücken. Ist am oberen oder unteren Rand kein weiteres Element erreichbar und kann der Inhalt nicht weiter scrollen, geht der Fokus zur sichtbaren Menüleiste oder zum Miniplayer. **↑ / ↓ halten** wählt diese Leisten weiterhin direkt. Von dort führt die Pfeiltaste Richtung Mitte zurück zur vorherigen Auswahl im Inhalt, sofern sie noch sichtbar ist.
- **⏪ / ⏩** und dedizierte Vorheriger-/Nächster-Tasten senden pro Tastendruck einen Titelsprung an die aktive Android-Mediensitzung. Wiedergabe/Pause verarbeitet Android regulär.

### Verworfene Ansätze

- **Direct Play und Playback-Diagnose.** Ein vermeintlicher Atmos-Fehler beim Einzelstart führte zu einem Versuch mit Katalog-IDs und zu einer Diagnose-APK mit Anzeige der Wiedergabestufe. Der Nutzer stellte fest, dass nur als anstößig gekennzeichnete Titel betroffen waren. Nach der Einstellung **Immer** funktionierte der Klick. Beide Versuche wurden bei der weiteren Arbeit durch die vorherige TV-Remote-Basis ersetzt. Die Inhaltsprüfung der App wurde nicht umgangen.
- **Andere TV-Clients.** [AM for TV 0.7.0](https://github.com/Sam20060720/am_atv) ist ein eigenständiger Android-TV-Client mit Atmos-Option und ARMv7-Bibliotheken. Er verwendet einen anderen Wiedergabepfad. Das Projekt baut auf der offiziellen Android-App auf, weil deren gepatchte Atmos-Wiedergabe auf dem Philips bestätigt wurde.

## Test auf dem Philips

Mit einer APK, die den aktuellen Fernbedienungs-Patch enthält, diese Tastenfolgen prüfen.

1. In der Mediathek zwischen zwei Albumcovern wechseln und ein Album öffnen.
2. Im Album zu einer Titelzeile wechseln und den Titel mit OK starten.
3. **↑ halten**, horizontal durch die obere Leiste gehen, dann mit kurzem **↓** zum Inhalt zurückkehren.
4. **↓ halten**, Wiedergabe oder Pause im Miniplayer auslösen, dann mit kurzem **↑** zum Inhalt zurückkehren.
5. Vom ersten oder letzten erreichbaren Element mit einem kurzen **↑** bzw. **↓** zur sichtbaren Leiste gehen. Bei weiter scrollbaren Listen soll die Taste zunächst den Inhalt scrollen.
6. Bei einer Wiedergabeliste mit mehreren Titeln kurz **⏩**, **⏪** und Wiedergabe/Pause drücken. **⏩** einmal halten und nach dem Loslassen prüfen, ob nur ein Titelsprung ausgelöst wurde. Die Medientasten sowohl im Hauptinhalt als auch im geöffneten Player prüfen.

Falls Fokus springt oder ein Element fehlt, Bildschirm, Tastenfolge, sichtbaren Fokus und ausgelöste Aktion festhalten. Prüfen, ob ein Ziel im falschen Bereich landet oder die App nach einer Ansichtsänderung neue Semantik-IDs erzeugt. Wenn die Bereichserkennung unzuverlässig ist, die Zugehörigkeit zur Menüleiste oder zum Miniplayer anhand des Compose-Semantikbaums bestimmen. Der heutige Code schätzt sie über das Layout. Zusätzlich Einstellungen, Suche mit Bildschirmtastatur und Vollbild-Player prüfen. Bei einem Fehler beim Direktstart zunächst die Inhaltsfreigabe prüfen, bevor der Wiedergabepfad geändert wird.

## Lokaler Build

### Bestehende DPad-APK weiterentwickeln

Die vorhandene Projekt-APK **DPad** oder **DPad-Zones** als Eingabe verwenden. Sie enthält bereits ARMv7, Atmos, Querformat und den Aufruf von `TvControls.install`. Für eine reine Navigationsänderung genügt es, die eigene Datei `classes9.dex` neu zu bauen und im APK zu ersetzen.

```bash
mkdir -p build/classes build/dex
javac -source 8 -target 8 -classpath "$ANDROID_JAR" \
  -d build/classes src/tv/local/applemusic/TvControls.java
find build/classes -name '*.class' -print0 | \
  xargs -0 java -cp "$R8_JAR" com.android.tools.r8.D8 \
  --min-api 30 --lib "$ANDROID_JAR" --output build/dex
```

`build/dex/classes.dex` ersetzt `classes9.dex`. Beim Neupacken die alten Signatureinträge unter `META-INF` entfernen, die APK ausrichten und neu signieren. Die frühere Ausführung nutzte OpenJDK 21, eine Android-31-`android.jar`, R8/D8 8.3.37 und uber-apk-signer 1.3.0. Die Android-SDK-Werkzeuge `zipalign` und `apksigner` eignen sich ebenfalls für diesen Schritt. Für ein Update den bisherigen Schlüssel nutzen und das öffentliche Zertifikat mit dem oben dokumentierten Fingerabdruck vergleichen.

Vor Ausgabe mit `apksigner verify --print-certs` und `unzip -t` prüfen. Bei einer reinen Änderung der Fernbedienung muss der Vergleich der entpackten Dateien zur Eingabe ausschließlich `classes9.dex` und Signaturdateien als verändert zeigen. Besonders `classes6.dex` mit dem Atmos-Patch und die ARMv7-Bibliotheken vergleichen.

### Vom Apple-Original neu aufbauen

Als Eingabe wurde das vollständige Apple-Music-7.0.0-Bundle (Build 1614) mit CPU- und Dichte-Splits verwendet. [APKMirror-Eintrag](https://www.apkmirror.com/apk/apple/apple-music/apple-music-7-0-0-release/apple-music-7-0-0-android-apk-download/)

```text
SHA-256 Bundle: 08219d7f9ab143e4ca5858964c630e8b197881eff0e8dff0742cdc7510bf4b1c
SHA-256 Apple-Zertifikat: 88ba590ec2e1ea33c4458daf59489faee2cef297a9b4071e18cf82ef531100aa
```

1. Originaldatei und ausgewählte Split-Signaturen prüfen. `base.apk`, `split_config.armeabi_v7a.apk` und `split_config.*dpi.apk` extrahieren. Mit APKEditor 1.4.9 zusammenführen.
2. VinkyVs Patchbundle 1.2.0 mit ReVanced CLI 6.0.0 verwenden. Ausschließlich **Fix leanback for TV** aktivieren. Der Atmos-Patch des Bundles wird durch Schritt 3 ersetzt.
3. Mit [FindAudio.java](tools/FindAudio.java) den DEX-Eintrag der konkreten Atmos-Methode ermitteln. Im damaligen zusammengeführten und per ReVanced vorbereiteten Philips-Build war es `classes6.dex`. Den Eintrag mit `java -cp "$REVANCED_JAR" tools/PatchAtmos.java leanback.apk atmos-classes6.dex classes6.dex` patchen und beim Neupacken gezielt ersetzen. Das Werkzeug verlangt einen eindeutigen Treffer.
4. `MainActivity` im vorbereiteten Build mit baksmali disassemblieren. In `onCreate` den Wert unmittelbar vor dem vorhandenen `setRequestedOrientation(I)` auf `0` für Querformat setzen. Unmittelbar nach `invoke-super/range ... ->onCreate` den statischen Aufruf `TvControls.install(Activity)` einfügen. Die einzelne geänderte Klasse mit smali assemblieren. [BuildUi.java](tools/BuildUi.java) erwartet in dieser Reihenfolge die Eingabe-APK, den DEX-Namen der Hauptaktivität, die frisch assemblierte DEX-Datei mit der einzelnen Klasse, den Pfad für die ersetzende DEX-Datei und den Pfad für das ersetzende Manifest. Als Klassenpfad ReVanced-CLI-JAR und APKEditor-JAR verwenden. Im damaligen Build lag die Hauptaktivität in `classes4.dex`.
5. Die korrigierten DEX-Einträge und das Manifest übernehmen, die eigene Hilfs-DEX als nächsten freien DEX-Eintrag beilegen (im damaligen Build `classes9.dex`), ausrichten, signieren und prüfen. Die DEX-Nummern und Klassenreferenzen vor jedem Neubau verifizieren, insbesondere bei einer anderen Apple-Music-Version.

Die handgeschriebene Änderung in der bestehenden App ist an Build 1614 gekoppelt. Eine neue Apple-Music-Version kann Namen, Argumente und Ressourcen-IDs ändern.

### JVM-Tests der Fernbedienung

[FocusZonesTest.java](tests/FocusZonesTest.java) prüft die Geometrie der drei Bereiche und die Übergänge an ihren Rändern. [TrackKeysTest.java](tests/tv/local/applemusic/TrackKeysTest.java) prüft die Tastenzuordnung, Wiederholungen und abgebrochene Tastendrücke. Nach dem Kompilieren von `TvControls.java` ausführen.

```bash
mkdir -p build/test
javac -cp build/classes -d build/test tests/android/graphics/Rect.java \
  tests/android/view/KeyEvent.java tests/FocusZonesTest.java \
  tests/tv/local/applemusic/TrackKeysTest.java
java -cp "build/test:build/classes:$ANDROID_JAR" FocusZonesTest
java -cp "build/test:build/classes:$ANDROID_JAR" tv.local.applemusic.TrackKeysTest
```

Installation, Bluetooth- oder IR-Tastencodes, Wiedergabe und Dolby-Atmos-Ausgabe werden auf dem Philips geprüft.
