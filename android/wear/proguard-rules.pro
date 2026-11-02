# Release: Log.v/d/i entfernen (keine Nacht- oder Weckdaten im Systemprotokoll); Warnungen und Fehler bleiben.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
