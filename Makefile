SHELL := /bin/bash
DOCKER ?= docker
DOCKER_COMPOSE ?= $(DOCKER) compose
HOST_ARCH := $(shell uname -m)
ifeq ($(HOST_ARCH),arm64)
export DOCKER_DEFAULT_PLATFORM ?= linux/arm64
else ifeq ($(HOST_ARCH),aarch64)
export DOCKER_DEFAULT_PLATFORM ?= linux/arm64
endif
APP_PORT ?= 8080
ADMIN_API_TOKEN ?=
MUSIC_DIR ?= $(CURDIR)/keygenmusic

.DEFAULT_GOAL := help

.PHONY: help
help:
	@echo "OrzMusic developer commands"
	@echo ""
	@echo "Setup:"
	@echo "  make setup          Install native and Web OrzAudioCore SDK assets"
	@echo "  make sdk-server     Install native OrzAudioCore SDK"
	@echo "  make sdk-web        Install Web/WASM OrzAudioCore SDK"
	@echo ""
	@echo "Local:"
	@echo "  make status         Show local ports and Docker service status"
	@echo "  make build          Build the Swift service"
	@echo "  make run            Run the Vapor service locally"
	@echo "  make native-install Install SDK, build release, and migrate Native service"
	@echo "  make native-up      Start Native release service and wait for health"
	@echo "  make native-down    Stop Native release service"
	@echo "  make native-status  Show Native PID and health status"
	@echo "  make generate-admin-token Generate a secure ADMIN_API_TOKEN"
	@echo "  make stop           Stop local OrzMusic services and Docker services"
	@echo "  make restart        Stop then run the local Vapor service"
	@echo "  make scan-local     Trigger configured local scan root (ADMIN_API_TOKEN=...)"
	@echo "  make audit-fingerprints Validate production fingerprint policy (ALL=1 for eligible full scan)"
	@echo "  make backfill-durations Repair missing song durations (DRY_RUN=1 for preview)"
	@echo "  make warm-decode-cache Pre-generate selected server-decode WAV caches"
	@echo "  make maintain-decode-cache Report or safely prune decoded WAV caches"
	@echo "  make test           Run Swift and browser tests"
	@echo "  make swift-test     Run Swift tests"
	@echo "  make browser-test   Run browser/WASM tests"
	@echo "  make script-test    Run deployment script tests"
	@echo ""
	@echo "Docker:"
	@echo "  make docker-up      Build and start the full stack in Docker"
	@echo "  make docker-install Validate, migrate, start, and smoke-check Docker stack"
	@echo "  make docker-restart Stop then start the full Docker stack"
	@echo "  make docker-down    Stop Docker services"
	@echo "  make docker-logs    Follow app logs"
	@echo "  make docker-config  Validate docker-compose.yml"
	@echo "  make migrate        Run database migrations in Docker"
	@echo "  make db-backup      Database backup (VERSION=X.Y.Z)"
	@echo "  make release-smoke   Run smoke check after upgrade (SERVICE_URL=http://...)"
	@echo "  make release-preflight  Preflight checks for production release"
	@echo "  make release-upgrade    Production upgrade (IMAGE_REF=..., ADMIN_API_TOKEN=...)"
	@echo "  make release-rollback   Rollback to previous version (IMAGE_REF=...)"
	@echo "  make package-deploy     Build lightweight deployment package"
	@echo "  make release-scan       Scan production music dir (ADMIN_API_TOKEN=...)"
	@echo "  make scan-docker-run Trigger configured Docker scan root (ADMIN_API_TOKEN=...)"
	@echo ""
	@echo "Cleanup:"
	@echo "  make clean          Remove local Swift build artifacts"

.PHONY: setup
setup: sdk-server sdk-web

.PHONY: sdk-server
sdk-server:
	./script/update-audio-core-server.sh

.PHONY: sdk-web
sdk-web:
	./script/update-audio-core-web.sh

.PHONY: build
build:
	swift build

.PHONY: status
status:
	@echo "Ports:"
	@for port in $(APP_PORT); do \
		pids="$$(lsof -tiTCP:$$port -sTCP:LISTEN 2>/dev/null || true)"; \
		if [ -n "$$pids" ]; then \
			echo "  $$port: in use by PID(s) $$pids"; \
			lsof -nP -iTCP:$$port -sTCP:LISTEN 2>/dev/null | sed 's/^/    /'; \
		else \
			echo "  $$port: free"; \
		fi; \
	done
	@echo ""
	@echo "Docker:"
	@if command -v "$(firstword $(DOCKER))" >/dev/null 2>&1; then \
		$(DOCKER_COMPOSE) ps; \
	else \
		echo "  docker CLI not found in PATH"; \
	fi

.PHONY: stop-local
stop-local:
	@for port in $(APP_PORT); do \
		pids="$$(lsof -tiTCP:$$port -sTCP:LISTEN 2>/dev/null || true)"; \
		if [ -z "$$pids" ]; then \
			echo "No local listener on port $$port"; \
			continue; \
		fi; \
		for pid in $$pids; do \
			cmd="$$(ps -p $$pid -o command= 2>/dev/null || true)"; \
			case "$$cmd" in \
				*OrzMusicService*|*".build"*"/Run"*|*"swift run OrzMusicService"*) \
					echo "Stopping local OrzMusic PID $$pid on port $$port"; \
					kill $$pid 2>/dev/null || true; \
					;; \
				*) \
					echo "Port $$port is used by PID $$pid, but it does not look like OrzMusic: $$cmd"; \
					echo "Leaving it running."; \
					;; \
			esac; \
		done; \
	done

.PHONY: stop
stop: stop-local docker-down

.PHONY: run
run:
	swift run OrzMusicService

.PHONY: native-install
native-install:
	./script/native-install.sh

.PHONY: native-up
native-up:
	./script/native-up.sh

.PHONY: native-down
native-down:
	./script/native-down.sh

.PHONY: native-status
native-status:
	./script/native-status.sh

.PHONY: generate-admin-token
generate-admin-token:
	@./script/generate-admin-token.sh

.PHONY: restart
restart: stop-local run

.PHONY: scan-local
scan-local:
	@test -n "$(ADMIN_API_TOKEN)" || (echo "ERROR: ADMIN_API_TOKEN is required"; exit 1)
	@echo "Scanning configured root through http://127.0.0.1:$(APP_PORT)/api/scan"
	curl -fsS -X POST "http://127.0.0.1:$(APP_PORT)/api/scan" \
		-H "Authorization: Bearer $(ADMIN_API_TOKEN)"

.PHONY: audit-fingerprints
audit-fingerprints:
	@args='--source "$(MUSIC_DIR)"'; \
	if [ "$(ALL)" = "1" ]; then args="$$args --all"; else args="$$args --limit-per-format \"$${LIMIT_PER_FORMAT:-1}\""; fi; \
	if [ "$(FORCE_ALL_FORMATS)" = "1" ]; then args="$$args --force-all-formats"; fi; \
	if [ -n "$(FORMATS)" ]; then args="$$args --formats \"$(FORMATS)\""; fi; \
	echo "swift run OrzFingerprintAudit $$args"; \
	eval swift run OrzFingerprintAudit "$$args"

.PHONY: backfill-durations
backfill-durations:
	@args='--batch-size "$${BATCH_SIZE:-50}" --concurrency "$${CONCURRENCY:-2}"'; \
	if [ -n "$${LIMIT:-}" ]; then args="$$args --limit \"$$LIMIT\""; fi; \
	if [ "$(DRY_RUN)" = "1" ]; then args="$$args --dry-run"; fi; \
	echo "swift run OrzDurationBackfill $$args"; \
	eval swift run OrzDurationBackfill "$$args"

.PHONY: warm-decode-cache
warm-decode-cache:
	@args='--concurrency "$${CONCURRENCY:-1}"'; \
	if [ -n "$${SONG_ID:-}" ]; then args="$$args --id \"$$SONG_ID\""; fi; \
	if [ -n "$${SONG_IDS:-}" ]; then args="$$args --ids \"$$SONG_IDS\""; fi; \
	if [ -n "$${FORMAT:-}" ]; then args="$$args --format \"$$FORMAT\""; fi; \
	if [ -n "$${RECENT:-}" ]; then args="$$args --recent \"$$RECENT\""; fi; \
	if [ "$(DRY_RUN)" = "1" ]; then args="$$args --dry-run"; fi; \
	echo "swift run OrzDecodeCacheWarmup $$args"; \
	eval swift run OrzDecodeCacheWarmup "$$args"

.PHONY: maintain-decode-cache
maintain-decode-cache:
	@args=''; \
	if [ -n "$${MAX_BYTES:-}" ]; then args="$$args --max-bytes \"$$MAX_BYTES\""; fi; \
	if [ "$(REMOVE_OLD_FINGERPRINTS)" = "1" ]; then args="$$args --remove-old-fingerprints"; fi; \
	if [ -n "$${MINIMUM_AGE_SECONDS:-}" ]; then args="$$args --minimum-age-seconds \"$$MINIMUM_AGE_SECONDS\""; fi; \
	if [ "$(APPLY)" = "1" ]; then args="$$args --apply"; else args="$$args --dry-run"; fi; \
	echo "swift run OrzDecodeCacheMaintenance $$args"; \
	eval swift run OrzDecodeCacheMaintenance "$$args"

.PHONY: test
test: swift-test browser-test script-test docker-config

.PHONY: swift-test
swift-test:
	swift test

.PHONY: browser-test
browser-test:
	node --test Tests/Browser/*.test.mjs

.PHONY: script-test
script-test:
	bash Tests/AppTests/native-scripts-test.sh
	bash Tests/AppTests/release-scripts-test.sh
	bash Tests/AppTests/db-backup-test.sh
	bash Tests/AppTests/release-smoke-test.sh

.PHONY: docker-build
docker-build:
	$(DOCKER_COMPOSE) build

.PHONY: docker-up
docker-up:
	$(DOCKER_COMPOSE) up --build -d

.PHONY: docker-install
docker-install:
	./script/docker-install.sh

.PHONY: docker-restart
docker-restart: docker-down docker-up

.PHONY: docker-down
docker-down:
	$(DOCKER_COMPOSE) down

.PHONY: docker-logs
docker-logs:
	$(DOCKER_COMPOSE) logs -f app

.PHONY: docker-config
docker-config:
	$(DOCKER_COMPOSE) config --quiet

.PHONY: migrate
migrate:
	$(DOCKER_COMPOSE) run --rm migrate

.PHONY: db-backup
db-backup:
	./script/db-backup.sh

.PHONY: release-preflight
release-preflight:
	./script/release-preflight.sh

.PHONY: release-smoke
release-smoke:
	./script/release-smoke.sh

.PHONY: release-upgrade
release-upgrade:
	./script/release-upgrade.sh

.PHONY: release-rollback
release-rollback:
	./script/release-rollback.sh

.PHONY: package-deploy
package-deploy:
	./script/package-deploy.sh

.PHONY: release-scan
release-scan:
	./script/release-scan.sh

.PHONY: scan-docker-run
scan-docker-run:
	@test -n "$(ADMIN_API_TOKEN)" || (echo "ERROR: ADMIN_API_TOKEN is required"; exit 1)
	@echo "Scanning configured Docker root through http://127.0.0.1:$(APP_PORT)/api/scan"
	curl -fsS -X POST "http://127.0.0.1:$(APP_PORT)/api/scan" \
		-H "Authorization: Bearer $(ADMIN_API_TOKEN)"

.PHONY: clean
clean:
	swift package clean
