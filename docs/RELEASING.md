# FastVNC builds and release identity

FastVNC is an independently maintained GPL-3.0-or-later derivative of AVNC. The release application ID is `tech.zhangbing.fvnc`; debug is `tech.zhangbing.fvnc.debug`. The implementation namespace remains `com.gaurav.avnc`, including the JNI bindings. This does not affect the installed app ID. AVNC and FastVNC can coexist; use JSON export/import to transfer saved connections.

## Signing

The local release identity is in **`private/signing/`**, excluded by the repository's existing `.gitignore`:

- `FastVNC-release.jks`: dedicated RSA-4096 private key, alias `fastvnc-release`, validity 10,000 days from creation.
- `release.properties`: store/key passwords and key location. Random passwords are saved only here, with owner-only file permissions.
- `FastVNC-release-certificate.pem`: public certificate for verification.
- `BACKUP-README.txt`: backup instructions.

Back up the whole directory securely offline. Future sideloaded APK updates must retain the same application ID and signing identity. Do not commit or publish the JKS or passwords, and do not regenerate a key over an existing identity.

Gradle reads `private/signing/release.properties` by default. To use a securely supplied file elsewhere, set `FASTVNC_SIGNING_PROPERTIES` to its absolute path. Properties format:

```properties
storeFile=FastVNC-release.jks
storePassword=<private store password>
keyAlias=fastvnc-release
keyPassword=<private key password>
```

Relative `storeFile` paths resolve against the properties file's directory. If no properties file is supplied, debug builds work normally and release builds are **unsigned**. An explicitly supplied but missing file fails configuration. CI builds remain unsigned even if local release credentials exist. Always verify the final release signature before distribution. No signing credentials are required to build from the published source.

## Build

Initialize submodules and use a JDK supported by the Android Gradle plugin (this workspace uses Android Studio's JBR 21). SDK 36, NDK 28.2.13676358 and CMake 3.22.1 are configured by the project.

```sh
git submodule update --init --recursive
./gradlew :app:assembleDebug :app:assembleRelease
```

The default build covers configured ABIs. For a targeted arm64 build:

```sh
./gradlew :app:assembleDebug :app:assembleRelease -Pandroid.injected.build.abi=arm64-v8a
```

Use the output locations reported by Gradle's `output-metadata.json` (AGP 9 writes APKs under `app/build/intermediates/apk/`). Check release identity with Android SDK tools:

```sh
apksigner verify --verbose --print-certs path/to/app-release.apk
aapt dump badging path/to/app-release.apk
```

The release package must be `tech.zhangbing.fvnc`, label `FastVNC`, debuggable=false, and signed with the certificate exported above. Debug uses the Android debug key and cannot update release installs. Increment `versionCode` before each subsequent distributed update.

## Artwork

The original FastVNC vector design uses a silver-white monitor, blue light-glass surface and two directional arrows representing remote VNC input/display exchange. The source geometry and gradients live in `scripts/generate_branding.py`; no Apple logo or original AVNC mark is used. It directly generates Android VectorDrawables, all five legacy launcher densities, round/adaptive/monochrome variants, SVG master and store artwork. No image generation API or API credentials are required.

Regenerate with Python 3, Pillow and `rsvg-convert`:

```sh
python3 scripts/generate_branding.py
```

Master and previews: `metadata/en-US/branding/icon.svg`, `fastvnc-icon-1024.png` and transparent `fastvnc-mark-1024.png`. Store artwork: `metadata/en-US/images/icon.png` (opaque 512×512). Android applies its own adaptive-icon mask; only legacy PNGs contain rounded silhouettes.

## Distribution

Distribute the complete corresponding source for the exact APK version, including modifications, build scripts, submodule revisions and native patches, under GPL-3.0-or-later. Preserve original author and dependency notices; the app's About page identifies the derivative and modification date. The Source code link points at `https://github.com/AceInAndroid/avnc`; push the corresponding source and preferably tag it before any public binary distribution. Local changes and generating a signed APK do not publish source or an app-store listing automatically.

FastVNC releases use a `fastvnc-v` tag prefix to distinguish them from inherited AVNC tags. The initial FastVNC release is `fastvnc-v3.3.1`; it retains the upstream base version 3.3.1 and uses its own application ID and signing identity.

Create the corresponding-source asset from a committed revision:

```sh
./gradlew -I scripts/collect_release_sources.gradle :app:collectReleaseSources
python3 scripts/package_release_source.py --revision fastvnc-v3.3.1 \
  --output build/FastVNC-3.3.1-corresponding-source.tar.gz
```

The script includes tracked application files, recursively populated submodules, hash-verified native dependency source archives and all resolved release runtime dependency source JARs, plus a revision/file manifest. Run the normal build first so `extern/vcpkg/downloads/` contains the native archives. Private signing files, local SDK configuration, test-device data and build outputs are excluded. Publish this asset alongside the APK, GPL license, third-party notices, public certificate and SHA-256 checksums; GitHub's automatic source ZIP alone omits submodule contents.
