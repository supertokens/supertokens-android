# Instrumentation calls this app-owned entry point. SDK implementation remains shrinkable.
-keep class com.supertokens.upgrade.R8Smoke {
    public static void run(android.content.Context, android.os.Bundle);
}
