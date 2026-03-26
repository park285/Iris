GO ?= go
GOLANGCI_LINT ?= golangci-lint
BUNDLE_DIR := internal/agentbundle/bundles

.PHONY: fmt
fmt:
	GOWORK=off $(GOLANGCI_LINT) fmt
	GOWORK=off $(GOLANGCI_LINT) run --fix ./...

.PHONY: lint
lint:
	GOWORK=off $(GOLANGCI_LINT) run ./...

.PHONY: test
test:
	GOWORK=off $(GO) test ./...

.PHONY: test-race
test-race:
	GOWORK=off $(GO) test -race ./...

.PHONY: vet
vet:
	GOWORK=off $(GO) vet ./...

.PHONY: sync-bundles
sync-bundles:
	@test -d ../agent/generated || (echo "ERROR: agent generated/ not found" && exit 1)
	@ls ../agent/generated/*.js >/dev/null 2>&1 || (echo "ERROR: no .js bundles" && exit 1)
	rm -f $(BUNDLE_DIR)/*.js
	cp ../agent/generated/*.js $(BUNDLE_DIR)/

.PHONY: build
build: lint
	GOWORK=off $(GO) build ./...

.PHONY: build-fresh
build-fresh: lint sync-bundles
	GOWORK=off $(GO) build ./...
