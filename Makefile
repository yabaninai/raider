# Raider development entrypoints (RAI-003 stage B).
# NOTE: quality-* targets appear only with RAI-004; do not invent gates.

.PHONY: bootstrap compile build-script spike-suite spike-oai \
        quality-changed quality-changed-execute quality-profile quality-verify \
        quality-inventory clean

bootstrap:
	sh scripts/bootstrap.sh

compile:
	sbt --batch compile

# Pinned dotc build path (kept working alongside sbt; used by CI-less lanes)
build-script:
	sh scripts/build/compile.sh

spike-suite:
	sh experiments/repl-spike/run_spike.sh

spike-oai:
	sh experiments/openai-chat-spike/run_spike.sh

# RAI-004 quality runner (stage-aware; full/milestone targets appear at their stage)
# NOTE: without QUALITY_BASE_MANIFEST the run is full-tree and selects the fast
# minimum (documented docs/quality-gates.md §2); diff classification requires a base.
# Mandatory workflow: `make quality-changed` classifies; QUALITY_EXECUTE=1 EXECUTES.
quality-changed:
	@if [ "$(QUALITY_EXECUTE)" = "1" ]; then \
		python3 scripts/quality/quality.py execute; \
	else \
		python3 scripts/quality/quality.py changed; \
	fi

quality-changed-execute:
	python3 scripts/quality/quality.py execute

space := $(empty) $(empty)
comma := ,

quality-profile:
	@if [ -z "$(PROFILES)" ]; then echo 'usage: make quality-profile PROFILES="static,unit" (comma- or space-separated)'; exit 2; fi
	python3 scripts/quality/quality.py execute --profiles "$(subst $(space),$(comma),$(strip $(PROFILES)))"

quality-verify:
	@if [ -z "$(MANIFEST)" ] || [ -z "$(PROFILES)" ]; then echo 'usage: make quality-verify MANIFEST=artifacts/quality/<run>/manifest.json PROFILES=fast'; exit 2; fi
	python3 scripts/quality/quality.py verify $(MANIFEST) --require $(PROFILES)

quality-inventory:
	python3 scripts/quality/quality.py inventory --out artifacts/quality/base-manifest.json

clean:
	rm -rf artifacts/build project/target target modules/*/target
