{
  description = "muster-android: dev shell (JDK 17, Android SDK via nixpkgs androidenv)";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  };

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          # The Android SDK licence. Accepting it here is a statement that the
          # developer accepts it; read it at https://developer.android.com/studio/terms
          android_sdk.accept_license = true;
        };
      };

      buildToolsVersion = "36.0.0"; # AGP 9.4.1 default
      platformVersion = "37.0"; # compileSdk; targetSdk stays 36 (see app/build.gradle.kts)

      # Build SDK: what CI and `./gradlew assembleDebug` need. No emulator.
      sdkArgs = {
        platformVersions = [ platformVersion ];
        buildToolsVersions = [ buildToolsVersion ];
        includeEmulator = false;
        includeSystemImages = false;
        includeNDK = false;
      };
      buildSdk = (pkgs.androidenv.composeAndroidPackages sdkArgs).androidsdk;

      # Spike SDK: adds the emulator and x86_64 google_apis images for API 35
      # and 36 (S3 asks for both), plus 37.0. Several GB; only `nix develop .#spikes`
      # pulls it.
      spikeSdk = (pkgs.androidenv.composeAndroidPackages (sdkArgs // {
        platformVersions = [ "35" "36" platformVersion ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      })).androidsdk;

      mkShell = sdk: extra: pkgs.mkShell {
        packages = [ pkgs.jdk17 sdk ] ++ extra;
        JAVA_HOME = pkgs.jdk17.home;
        ANDROID_HOME = "${sdk}/libexec/android-sdk";
        ANDROID_SDK_ROOT = "${sdk}/libexec/android-sdk";
        # Gradle fetches aapt2 from Maven as a dynamically linked binary that
        # cannot run on NixOS; point AGP at the SDK's patched copy instead.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/libexec/android-sdk/build-tools/${buildToolsVersion}/aapt2";
      };
    in
    {
      devShells.${system} = {
        default = mkShell buildSdk [ ];
        spikes = mkShell spikeSdk [ pkgs.ntfy-sh pkgs.python3 ];
      };
    };
}
