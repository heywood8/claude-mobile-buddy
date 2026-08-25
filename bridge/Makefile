SWIFT ?= swift
APP := dist/cmbridge.app
BINARY := .build/release/cmbridge

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

# Deliberately no launchctl here. Loading an agent writes to ~/Library/LaunchAgents and
# arranges for something to run at every login; that is the user's decision to make, the same
# way the hook block is. `cmbridge print-agent` prints what to install.
install: app
	@echo "Built. To run it at login:"
	@echo "  $(APP)/Contents/MacOS/cmbridge print-agent > ~/Library/LaunchAgents/dev.heywood8.cmbridge.plist"
	@echo "  launchctl load ~/Library/LaunchAgents/dev.heywood8.cmbridge.plist"

clean:
	rm -rf dist .build
