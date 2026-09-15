# Luvia — Android, iOS, host, and tests.
# `make` prints targets. Run targets prefer a physical device over emulator/sim.
# CI calls the same targets (GitHub Actions sets CI=true → gradle --stacktrace).

SHELL := /bin/bash
.DEFAULT_GOAL := help

UNAME_S := $(shell uname -s)

CARGO := cargo
GRADLE := ./gradlew
ifneq ($(CI),)
GRADLE := ./gradlew --stacktrace
endif

ANDROID_PACKAGE := tech.asahiart.luvia
ANDROID_ACTIVITY := $(ANDROID_PACKAGE)/.MainActivity
ANDROID_SERIAL ?=
ANDROID_API ?= 26
ANDROID_STAMP := build/make/android-serials
ANDROID_APK := androidApp/build/outputs/apk/debug/androidApp-debug.apk

IOS_PROJECT := iosApp/iosApp.xcodeproj
IOS_SCHEME := iosApp
IOS_BUNDLE_ID := tech.asahiart.luvia
IOS_APP_NAME := Luvia.app
IOS_DERIVED := build/ios
IOS_CONFIGURATION ?= Debug
IOS_UDID ?=
IOS_PICK := scripts/pick-ios-device.py
IOS_APP_SIM := $(IOS_DERIVED)/Build/Products/$(IOS_CONFIGURATION)-iphonesimulator/$(IOS_APP_NAME)
IOS_APP_DEVICE := $(IOS_DERIVED)/Build/Products/$(IOS_CONFIGURATION)-iphoneos/$(IOS_APP_NAME)

ADB ?= $(shell command -v adb 2>/dev/null)
EMULATOR ?= $(shell command -v emulator 2>/dev/null)
ifeq ($(ADB),)
  ifneq ($(ANDROID_HOME),)
    ADB := $(ANDROID_HOME)/platform-tools/adb
  endif
endif
ifeq ($(EMULATOR),)
  ifneq ($(ANDROID_HOME),)
    EMULATOR := $(ANDROID_HOME)/emulator/emulator
  endif
endif

.PHONY: help doctor \
	mobile android android-build android-install android-release android-device \
	ios ios-build ios-device ios-sim ios-open ios-compile ios-test ios-framework \
	host host-dev host-test \
	shared-test test test-rust test-kotlin test-android \
	check-transport-android check-transport-ios \
	fmt check clean

help: ## List targets
	@awk 'BEGIN {FS = ":.*##"} /^[a-zA-Z0-9_-]+:.*##/ {printf "  %-24s %s\n", $$1, $$2}' $(MAKEFILE_LIST)
	@echo
	@echo "  ANDROID_SERIAL=...     pin one adb serial (else every physical phone)"
	@echo "  IOS_UDID=...           pin one iPhone (else every paired phone)"
	@echo "  make ios-device        physical iPhones only"
	@echo "  make ios-sim           Simulator only"

doctor: ## Check local toolchains and attached devices
	@command -v rustc >/dev/null && rustc --version || echo "missing rustc (need 1.98.0)"
	@command -v cargo >/dev/null && cargo --version || echo "missing cargo"
	@command -v java >/dev/null && java -version 2>&1 | head -1 || echo "missing java (need 21)"
	@test -x $(GRADLE) && echo "gradlew ok" || echo "missing ./gradlew"
	@if [ -n "$(ADB)" ] && [ -x "$(ADB)" ]; then \
	  "$(ADB)" version | head -1; \
	  "$(ADB)" devices -l; \
	else echo "adb not on PATH (Android SDK platform-tools)"; fi
	@if [ "$(UNAME_S)" = Darwin ]; then \
	  xcodebuild -version | head -1; \
	  python3 "$(IOS_PICK)" || true; \
	else echo "iOS skipped (not macOS)"; fi

# --- Android ---------------------------------------------------------------

android-device: ## List every physical phone; start an AVD only if none
	@set -euo pipefail; \
	if [ -z "$(ADB)" ] || [ ! -x "$(ADB)" ]; then \
	  echo "adb not found. Install Android platform-tools and put them on PATH, or set ANDROID_HOME." >&2; \
	  exit 1; \
	fi; \
	mkdir -p build/make; \
	picks=""; \
	if [ -n "$(ANDROID_SERIAL)" ]; then \
	  picks="$(ANDROID_SERIAL)"; \
	  echo "Using ANDROID_SERIAL=$$picks"; \
	  ANDROID_SERIAL="$$picks" "$(ADB)" wait-for-device; \
	else \
	  picks="$$("$(ADB)" devices | awk 'NR>1 && $$2=="device" && $$1 !~ /^emulator-/ {print $$1}')"; \
	  if [ -n "$$picks" ]; then \
	    echo "Android physical devices:"; echo "$$picks"; \
	  else \
	    picks="$$("$(ADB)" devices | awk 'NR>1 && $$2=="device" {print $$1}')"; \
	    if [ -n "$$picks" ]; then echo "Android emulator: $$picks"; fi; \
	  fi; \
	fi; \
	if [ -z "$$picks" ]; then \
	  unauthorized="$$("$(ADB)" devices | awk 'NR>1 && $$2=="unauthorized" {print $$1}')"; \
	  if [ -n "$$unauthorized" ]; then \
	    echo "Unauthorized devices:"; echo "$$unauthorized" >&2; \
	    echo "Accept USB debugging on the phone." >&2; \
	    exit 1; \
	  fi; \
	  if [ -z "$(EMULATOR)" ] || [ ! -x "$(EMULATOR)" ]; then \
	    echo "No Android device online, and emulator not found. Plug in a phone or create an AVD." >&2; \
	    exit 1; \
	  fi; \
	  avd="$$("$(EMULATOR)" -list-avds | awk 'NF{print; exit}')"; \
	  if [ -z "$$avd" ]; then \
	    echo "No AVDs. Create one in Android Studio (x86_64 or arm64-v8a)." >&2; \
	    exit 1; \
	  fi; \
	  echo "No physical device; starting emulator $$avd"; \
	  "$(EMULATOR)" -avd "$$avd" -netdelay none -netspeed full >/dev/null 2>&1 & \
	  "$(ADB)" wait-for-device; \
	  picks="$$("$(ADB)" devices | awk 'NR>1 && $$2=="device" {print $$1}')"; \
	fi; \
	printf '%s\n' "$$picks" | awk 'NF' > "$(ANDROID_STAMP)"; \
	while IFS= read -r serial; do \
	  ANDROID_SERIAL="$$serial" "$(ADB)" wait-for-device; \
	  until [ "$$(ANDROID_SERIAL="$$serial" "$(ADB)" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do sleep 2; done; \
	done < "$(ANDROID_STAMP)"; \
	echo "Android ready: $$(tr '\n' ' ' < "$(ANDROID_STAMP)")"

android-build: ## Assemble Android debug APK
	$(GRADLE) :androidApp:assembleDebug

android-release: ## Assemble Android release APK (R8)
	$(GRADLE) :androidApp:assembleRelease

android-install: android-device android-build ## Install Android debug onto every chosen device
	@set -euo pipefail; \
	if [ ! -f "$(ANDROID_APK)" ]; then echo "missing $(ANDROID_APK)" >&2; exit 1; fi; \
	fail=0; pids=(); \
	while IFS= read -r serial; do \
	  ( echo "Installing on $$serial"; ANDROID_SERIAL="$$serial" "$(ADB)" install -r "$(ANDROID_APK)" ) & \
	  pids+=("$$!"); \
	done < "$(ANDROID_STAMP)"; \
	if [ "$${#pids[@]}" -gt 0 ]; then for pid in "$${pids[@]}"; do wait "$$pid" || fail=1; done; fi; \
	if [ "$$fail" != 0 ]; then echo "Android install failed on at least one device." >&2; exit 1; fi

mobile: ios android ## Install and launch on every iOS and Android phone

android: android-install ## Install and launch Android on every physical phone
	@set -euo pipefail; \
	fail=0; pids=(); \
	while IFS= read -r serial; do \
	  ( echo "Launching on $$serial"; ANDROID_SERIAL="$$serial" "$(ADB)" shell am start -n "$(ANDROID_ACTIVITY)" ) & \
	  pids+=("$$!"); \
	done < "$(ANDROID_STAMP)"; \
	if [ "$${#pids[@]}" -gt 0 ]; then for pid in "$${pids[@]}"; do wait "$$pid" || fail=1; done; fi; \
	if [ "$$fail" != 0 ]; then echo "Android launch failed on at least one device." >&2; exit 1; fi

# --- iOS -------------------------------------------------------------------

ios-compile: ## Compile shared commonMain + iosArm64 (device target)
	$(GRADLE) :shared:compileCommonMainKotlinMetadata :shared:compileKotlinIosArm64

ios-test: ## Shared tests on all Kotlin targets
	$(GRADLE) :shared:allTests

ios-framework: ## Link the shared debug framework for the Simulator
	$(GRADLE) :shared:linkDebugFrameworkIosSimulatorArm64

ifeq ($(UNAME_S),Darwin)

ios-build: ## Build Luvia for the iOS Simulator (unsigned; CI uses this)
	xcodebuild \
		-project "$(IOS_PROJECT)" \
		-scheme "$(IOS_SCHEME)" \
		-configuration "$(IOS_CONFIGURATION)" \
		-sdk iphonesimulator \
		-destination 'generic/platform=iOS Simulator' \
		-derivedDataPath "$(IOS_DERIVED)" \
		CODE_SIGNING_ALLOWED=NO \
		CODE_SIGNING_REQUIRED=NO \
		build

ios: ## Build, install, and launch on every paired iPhone (else Simulator)
	@set -euo pipefail; \
	export IOS_UDID="$(IOS_UDID)"; \
	pick="$$(python3 "$(IOS_PICK)")"; \
	devices="$$(printf '%s\n' "$$pick" | awk '/^device /{print}')"; \
	if [ -n "$$devices" ]; then \
	  echo "$$devices" | while read -r _k udid _ident how; do echo "iOS physical device $$udid ($$how)"; done; \
	  xcodebuild \
	    -project "$(IOS_PROJECT)" \
	    -scheme "$(IOS_SCHEME)" \
	    -configuration "$(IOS_CONFIGURATION)" \
	    -sdk iphoneos \
	    -destination 'generic/platform=iOS' \
	    -derivedDataPath "$(IOS_DERIVED)" \
	    -allowProvisioningUpdates \
	    build; \
	  fail=0; pids=(); \
	  while read -r _k udid ident how; do \
	    ( \
	      echo "Installing on $$udid ($$how)"; \
	      xcrun devicectl device install app --device "$$ident" "$(IOS_APP_DEVICE)"; \
	      xcrun devicectl device process launch --device "$$ident" "$(IOS_BUNDLE_ID)"; \
	      echo "Launched $(IOS_BUNDLE_ID) on $$udid ($$how)"; \
	    ) & \
	    pids+=("$$!"); \
	  done <<<"$$devices"; \
	  if [ "$${#pids[@]}" -gt 0 ]; then for pid in "$${pids[@]}"; do wait "$$pid" || fail=1; done; fi; \
	  if [ "$$fail" != 0 ]; then echo "iOS install/launch failed on at least one device." >&2; exit 1; fi; \
	else \
	  udid="$$(printf '%s\n' "$$pick" | awk '/^simulator/{print $$2}')"; \
	  if [ -z "$$udid" ]; then \
	    udid="$$(xcrun simctl list devices booted | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1 || true)"; \
	  fi; \
	  if [ -z "$$udid" ]; then \
	    udid="$$(xcrun simctl list devices available | awk '/iPhone/{print}' | grep -oE '[0-9A-F]{8}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{4}-[0-9A-F]{12}' | head -1 || true)"; \
	  fi; \
	  if [ -z "$$udid" ]; then \
	    echo "No iPhone simulator. Install one in Xcode → Settings → Platforms." >&2; \
	    exit 1; \
	  fi; \
	  echo "No connected iPhone; using Simulator $$udid"; \
	  xcodebuild \
	    -project "$(IOS_PROJECT)" \
	    -scheme "$(IOS_SCHEME)" \
	    -configuration "$(IOS_CONFIGURATION)" \
	    -sdk iphonesimulator \
	    -destination "id=$$udid" \
	    -derivedDataPath "$(IOS_DERIVED)" \
	    CODE_SIGNING_ALLOWED=NO \
	    CODE_SIGNING_REQUIRED=NO \
	    build; \
	  xcrun simctl bootstatus "$$udid" -b >/dev/null; \
	  open -a Simulator --args -CurrentDeviceUDID "$$udid"; \
	  xcrun simctl install "$$udid" "$(IOS_APP_SIM)"; \
	  xcrun simctl launch "$$udid" "$(IOS_BUNDLE_ID)"; \
	  echo "Launched $(IOS_BUNDLE_ID) on Simulator $$udid"; \
	fi

ios-device: ## Install and launch on every paired iPhone (USB or wireless)
	IOS_FORCE_DEVICE=1 IOS_UDID="$(IOS_UDID)" $(MAKE) ios

ios-sim: ## Install and launch on the iOS Simulator only
	IOS_FORCE_SIM=1 IOS_UDID="$(IOS_UDID)" $(MAKE) ios

ios-open: ## Open the iOS project in Xcode
	open "$(IOS_PROJECT)"

else

ios ios-build ios-device ios-sim ios-open:
	@echo "iOS targets require macOS / Xcode." >&2; exit 1

endif

# --- Host / shared ---------------------------------------------------------

host-dev: ## Build luvia-host (debug)
	$(CARGO) build -p luvia-host

host: ## Build luvia-host (release)
	$(CARGO) build --release -p luvia-host

host-test: ## Test luvia-host
	$(CARGO) test -p luvia-host

shared-test: ## JVM tests for the shared KMP client
	$(GRADLE) :shared:jvmTest

test-rust: ## Rust workspace tests
	$(CARGO) test --workspace --all-targets

test-kotlin: shared-test ## Kotlin JVM tests
	$(GRADLE) :shared:testDebugUnitTest

test-android: ## Android debug assemble + unit tests
	$(GRADLE) :androidApp:assembleDebug :shared:jvmTest :shared:testDebugUnitTest

test: test-rust test-kotlin ## Rust workspace + Kotlin JVM tests

# ring compiles C via the cc crate, which probes for a bare <target>-clang.
# The NDK only ships API-suffixed drivers, so CC_/AR_ must be set or the
# build script dies before any Rust compiles.
check-transport-android: ## cargo check luvia-transport for Android ABIs
	@set -euo pipefail; \
	ndk="$(ANDROID_NDK_HOME)"; \
	if [ -z "$$ndk" ]; then \
	  echo "Set ANDROID_NDK_HOME to the NDK root (r29)." >&2; \
	  exit 1; \
	fi; \
	prebuilt=""; \
	for host in linux-x86_64 darwin-aarch64 darwin-x86_64; do \
	  cand="$$ndk/toolchains/llvm/prebuilt/$$host/bin"; \
	  if [ -d "$$cand" ]; then prebuilt="$$cand"; break; fi; \
	done; \
	if [ -z "$$prebuilt" ]; then echo "NDK clang not found under $$ndk" >&2; exit 1; fi; \
	api="$(ANDROID_API)"; \
	export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$$prebuilt/aarch64-linux-android$${api}-clang"; \
	export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$$prebuilt/x86_64-linux-android$${api}-clang"; \
	export CC_aarch64_linux_android="$$prebuilt/aarch64-linux-android$${api}-clang"; \
	export CC_x86_64_linux_android="$$prebuilt/x86_64-linux-android$${api}-clang"; \
	export AR_aarch64_linux_android="$$prebuilt/llvm-ar"; \
	export AR_x86_64_linux_android="$$prebuilt/llvm-ar"; \
	rustup target add aarch64-linux-android x86_64-linux-android >/dev/null; \
	$(CARGO) check -p luvia-transport --target aarch64-linux-android; \
	$(CARGO) check -p luvia-transport --target x86_64-linux-android

check-transport-ios: ## cargo check luvia-transport for iOS device + sim
	@set -euo pipefail; \
	if [ "$(UNAME_S)" != Darwin ]; then echo "iOS transport check requires macOS." >&2; exit 1; fi; \
	rustup target add aarch64-apple-ios aarch64-apple-ios-sim >/dev/null; \
	$(CARGO) check -p luvia-transport --target aarch64-apple-ios; \
	$(CARGO) check -p luvia-transport --target aarch64-apple-ios-sim

fmt: ## Check Rust formatting
	$(CARGO) fmt --all --check

check: fmt ## fmt + clippy + cargo check
	$(CARGO) check --workspace --all-targets
	$(CARGO) clippy --workspace --all-targets -- -D warnings

clean: ## Remove Gradle, Cargo, and iOS derived data
	$(GRADLE) clean
	$(CARGO) clean
	rm -rf "$(IOS_DERIVED)" build/make
