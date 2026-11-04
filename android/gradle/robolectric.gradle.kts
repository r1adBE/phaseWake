// Android-Abbilder für Robolectric über Gradle statt zur Testzeit (eingebunden von app, wear und shared).
//
// Robolectric lädt sie sonst erst im Test selbst von Maven Central, ohne Wiederholung und ohne den Gradle-Cache der CI:
// Antwortet Maven Central mit 429 (zu viele Anfragen), scheitern alle Robolectric-Tests einer Klasse («classMethod
// FAILED»). Hier holt Gradle sie (mit Wiederholung, im Cache der CI), und Robolectric läuft offline.
//
// Die Versionen gehören zu Robolectric (DefaultSdkProvider) und zu den SDKs der Tests (@Config(sdk = [33]), [35]); bei
// einem Robolectric-Update mitziehen, sonst melden die Tests «jar not present».
val robolectricSdks = configurations.create("robolectricSdks") {
    isCanBeConsumed = false
    isTransitive = false
}
dependencies {
    add("robolectricSdks", "org.robolectric:android-all-instrumented:13-robolectric-9030017-i7")
    add("robolectricSdks", "org.robolectric:android-all-instrumented:15-robolectric-13954326-i7")
}
val robolectricSdkDir = layout.buildDirectory.dir("robolectric-sdks")
val syncRobolectricSdks = tasks.register("syncRobolectricSdks", Sync::class.java) {
    from(robolectricSdks)
    into(robolectricSdkDir)
}
tasks.withType(Test::class.java).configureEach {
    dependsOn(syncRobolectricSdks)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDir.get().asFile.absolutePath)
}
