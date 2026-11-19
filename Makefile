APP_ID := io.github.serabin1.tydl
GRADLE := ./gradlew
ADB := $(shell command -v adb 2>/dev/null || echo $(HOME)/Android/Sdk/platform-tools/adb)

.PHONY: help test build install run release logs clean

help:
	@echo "make test     run the JVM unit tests (no phone needed)"
	@echo "make build    build the debug APK"
	@echo "make install  build and install the debug APK on the connected phone"
	@echo "make run      install and launch Tydl"
	@echo "make logs     follow only Tydl's log output"
	@echo "make release  build the release APK (unsigned until a keystore is set up)"
	@echo "make clean    delete build outputs"

test:
	$(GRADLE) :app:testDebugUnitTest

build:
	$(GRADLE) :app:assembleDebug
	@echo "APK: app/build/outputs/apk/debug/app-debug.apk"

install:
	$(GRADLE) :app:installDebug

run: install
	$(ADB) shell am start -n $(APP_ID)/.ui.MainActivity

logs:
	$(ADB) logcat --pid=$$($(ADB) shell pidof -s $(APP_ID))

release:
	$(GRADLE) :app:assembleRelease
	@echo "APK: app/build/outputs/apk/release/app-release-unsigned.apk (not installable until signed)"

clean:
	$(GRADLE) clean
