SWIFT ?= swift
APP := dist/cmbridge.app
BINARY := .build/release/cmbridge
PREFIX ?= $(HOME)/Applications
INSTALLED := $(PREFIX)/cmbridge.app

.PHONY: all test release app install clean

all: app

test:
	$(SWIFT) test

release:
	$(SWIFT) build -c release

# The bundle is what gives the bridge a TCC identity of its own. A bare binary inherits
# Bluetooth permission from whatever launched it, which works from a terminal and fails
# under launchd, where there is nothing to inherit from.
#
# Signed ad-hoc, because a TCC grant needs *some* signature to attach to. The identity is the
# code hash, so rebuilding the binary invalidates the grant and macOS asks again — annoying
# during development, irrelevant once installed. A real Developer ID would make it stable;
# see backlog.md.
app: release
	rm -rf $(APP)
	mkdir -p $(APP)/Contents/MacOS
	cp Resources/Info.plist $(APP)/Contents/Info.plist
	cp $(BINARY) $(APP)/Contents/MacOS/cmbridge
	codesign --force --sign - --options runtime $(APP)
	@codesign --verify --verbose=1 $(APP) 2>&1 | sed 's/^/  /'
	@echo "built $(APP)"

# Copies the bundle out of the build directory, and stops there. Loading an agent writes to
# ~/Library/LaunchAgents and arranges for something to run at every login; that is the user's
# decision to make, the same way the hook block is. `cmbridge print-agent` prints what to load.
#
# The copy is the point. launchd stores the path it is given and never revisits it, so an agent
# pointed into dist/ dies the first time you `make clean` or move the checkout — and dies
# quietly, respawning a file that is no longer there.
install: app
	mkdir -p $(PREFIX)
	rm -rf $(INSTALLED)
	cp -R $(APP) $(INSTALLED)
	@codesign --verify --verbose=1 $(INSTALLED) 2>&1 | sed 's/^/  /'
	@echo "installed $(INSTALLED)"
	@echo
	@echo "To run it at login:"
	@echo "  $(INSTALLED)/Contents/MacOS/cmbridge print-agent > ~/Library/LaunchAgents/dev.heywood8.cmbridge.plist"
	@echo "  launchctl load ~/Library/LaunchAgents/dev.heywood8.cmbridge.plist"
	@echo
	@echo "Already loaded? Replacing the bundle leaves the old copy running:"
	@echo "  launchctl kickstart -k gui/$$(id -u)/dev.heywood8.cmbridge"
	@echo
	@echo "macOS may ask for Bluetooth again at the new location."

clean:
	rm -rf dist .build
