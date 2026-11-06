Raider REPL — installed distribution (RAI-262)
==============================================

Contents
  raider-repl       launcher script (POSIX sh)
  raider-repl.jar   self-contained fat jar, main raider.repl.Main
                    (in-process scala3-repl 3.9.0 compiler inside)
  version           distribution version
  README.txt        this file
  SHA256SUMS        sha256 checksums of the payload files

Requirements
  Java 21 or newer (java on PATH, or JAVA_HOME set).

Install and run
  1. Unpack:          unzip raider-repl-<version>.zip
  2. Make executable: chmod +x raider-repl-<version>/raider-repl
  3. Run:             raider-repl-<version>/raider-repl

  ./raider-repl --version prints the distribution version (exit 0).
  A distribution jar that is missing, unreadable, or lacks the in-process
  compiler is refused by the launcher with exit 21 and a one-line
  diagnostic; it never hangs.

Modes
  raider-repl              line REPL: Scala eval loop plus :commands
                           (:help :jobs :cancel <id> :reset :quit)
  raider-repl --tabs       product tab shell
  raider-repl --chat       chat layout over the scripted/live backend

  RAIDER_PROVIDER=mock selects the offline scripted fixture (no network);
  live providers follow the same environment contract as the raider CLI.
  SIGINT/SIGTERM terminate the REPL with exit codes 130/143.

Scope
  This distribution is independent of a source checkout. The headless
  raider-cli package does not include the REPL compiler or terminal stack;
  this distribution is the packaged REPL counterpart.
