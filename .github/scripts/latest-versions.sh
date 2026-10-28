#!/usr/bin/env bash
# Zeigt die neueste stabile Version der verwendeten Bibliotheken (Hilfe beim Aktualisieren).
set -euo pipefail
google=https://dl.google.com/dl/android/maven2
central=https://repo1.maven.org/maven2
latest() {
  local repo=$1 coord=$2
  local path="${coord%%:*}"; path="${path//./\/}/${coord##*:}"
  local v
  v=$(curl -fsSL "$repo/$path/maven-metadata.xml" | grep -o '<version>[^<]*</version>' | sed 's/<[^>]*>//g' \
    | grep -viE 'alpha|beta|rc|dev|eap|-m[0-9]' | sort -V | tail -1) || v="?"
  printf '%-60s %s\n' "$coord" "$v"
}
latest $google com.android.tools.build:gradle
latest $central org.jetbrains.kotlin:kotlin-gradle-plugin
latest $google androidx.core:core-ktx
latest $google androidx.activity:activity-compose
latest $google androidx.compose:compose-bom
latest $google androidx.compose.material3:material3
latest $google androidx.wear.compose:compose-material3
latest $google androidx.wear.compose:compose-foundation
latest $google androidx.wear.compose:compose-navigation
latest $google androidx.wear:wear-ongoing
latest $google androidx.wear:wear
latest $google androidx.wear.tiles:tiles
latest $google androidx.wear.protolayout:protolayout-material3
latest $google androidx.wear.watchface:watchface-complications-data-source-ktx
latest $google androidx.health:health-services-client
latest $google androidx.health.connect:connect-client
latest $google androidx.datastore:datastore-preferences
latest $google androidx.lifecycle:lifecycle-service
latest $google androidx.lifecycle:lifecycle-runtime-compose
latest $google androidx.navigation:navigation-compose
latest $google androidx.work:work-runtime-ktx
latest $google androidx.test.ext:junit
latest $google androidx.compose.ui:ui-test-junit4
latest $google com.google.android.gms:play-services-wearable
latest $central org.jetbrains.kotlinx:kotlinx-coroutines-core
latest $central org.jetbrains.kotlinx:kotlinx-coroutines-play-services
latest $central org.jetbrains.kotlinx:kotlinx-coroutines-test
latest $central org.jetbrains.kotlinx:kover-gradle-plugin
latest $central org.robolectric:robolectric
latest $central junit:junit
echo
gv=$(curl -fsSL https://services.gradle.org/versions/current | grep -o '"version" *: *"[^"]*"' | cut -d'"' -f4)
echo "gradle $gv sha256 $(curl -fsSL https://services.gradle.org/distributions/gradle-$gv-bin.zip.sha256)"

