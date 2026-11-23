// Sicherheit (OSV): AGP bringt im Plugin-Klassenpfad Bibliotheken mit bekannten Schwachstellen mit (nur Build, nicht
// in den Apps). Nur die Version anheben, nichts zusätzlich einbinden; Versionen in libs.versions.toml.
buildscript {
    dependencies {
        constraints {
            classpath("com.google.guava:guava:${libs.versions.buildGuava.get()}") {
                because("OSV: Guava 33.4.0-jre (GHSA-xxph-c9ww-hj94)")
            }
            listOf("bcprov", "bcpkix", "bcutil").forEach {
                classpath("org.bouncycastle:$it-jdk18on:${libs.versions.bouncycastle.get()}") {
                    because("OSV: Bouncy Castle 1.80.2 (GHSA-9pwp-9qqc-pr26, GHSA-qp49-qgx5-5m26, GHSA-wg6q-6289-32hp)")
                }
            }
            classpath("org.jdom:jdom2:${libs.versions.jdom2.get()}") {
                because("OSV: jdom2 2.0.6 (GHSA-2363-cqg2-863c)")
            }
            classpath("org.bitbucket.b_c:jose4j:${libs.versions.jose4j.get()}") {
                because("OSV: jose4j 0.9.5 (GHSA-3677-xxcr-wjqv)")
            }
            classpath("org.apache.commons:commons-lang3:${libs.versions.commonsLang3.get()}") {
                because("OSV: commons-lang3 3.16.0 (GHSA-j288-q9x7-2f5v)")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.screenshot) apply false
}
