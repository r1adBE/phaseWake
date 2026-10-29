# MediaPipe (Geräusche einordnen): Klassen werden aus nativem Code über JNI und Reflexion angesprochen.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.mediapipe.**
-dontwarn com.google.protobuf.**
# Nur zur Übersetzungszeit gebrauchte Anmerkungen der MediaPipe-Abhängigkeiten.
-dontwarn com.google.auto.value.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
