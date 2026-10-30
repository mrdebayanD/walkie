# Concentus uses no reflection; keep it intact anyway (small, and avoids surprises).
-keep class org.concentus.** { *; }
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
