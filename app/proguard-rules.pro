# PIGA Pocket Enterprise release rules.
# Keep Android components referenced from the manifest and WorkManager.
-keep class io.piga.phonebridge.PigaBridgeApp { *; }
-keep class io.piga.phonebridge.MainActivity { *; }
-keep class io.piga.phonebridge.PairingActivity { *; }
-keep class io.piga.phonebridge.LocalDiagnosticsActivity { *; }
-keep class io.piga.phonebridge.BridgeService { *; }
-keep class io.piga.phonebridge.BootReceiver { *; }
-keep class io.piga.phonebridge.BridgeRecoveryWorker { *; }

# Keep generic signatures and annotations used by Android tooling.
-keepattributes Signature,*Annotation*
