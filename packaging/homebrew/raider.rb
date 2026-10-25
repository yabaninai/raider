class Raider < Formula
  desc "Scala-based AI-native SDLC CLI"
  homepage "https://github.com/raider-dev/raider"
  url "https://github.com/raider-dev/raider/releases/download/vVERSION/raider-cli.jar"
  sha256 "REPLACE_WITH_RELEASE_SHA256"
  license "MIT"

  depends_on "openjdk@21"

  def install
    libexec.install "raider-cli.jar"
    (libexec/"version").write "VERSION\n"
    (libexec/"raider").write <<~SH
      #!/bin/sh
      set -eu
      HERE="#{libexec}"
      if [ "${1:-}" = "--version" ] || [ "${1:-}" = "-V" ]; then
        printf 'raider %s\\n' "$(<"$HERE/version")"
        exit 0
      fi
      if command -v cs >/dev/null 2>&1; then
        exec cs launch --jar "$HERE/raider-cli.jar" -- "$@"
      fi
      exec java -jar "$HERE/raider-cli.jar" "$@"
    SH
    (libexec/"raider").chmod 0555
    (bin/"raider").write <<~SH
      #!/bin/sh
      exec "#{libexec}/raider" "$@"
    SH
    (bin/"raider").chmod 0555
  end

  test do
    assert_match "raider VERSION", shell_output("#{bin}/raider --version")
  end
end
