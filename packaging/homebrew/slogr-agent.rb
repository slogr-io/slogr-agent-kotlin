class SlogrAgent < Formula
  desc "Network path quality monitoring agent (TWAMP-based RTT/jitter/loss)"
  homepage "https://slogr.io"
  version "1.0.0"

  on_macos do
    on_arm do
      url "https://github.com/slogr/agent-kotlin/releases/download/v#{version}/slogr-agent-#{version}-macos-arm64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_MACOS_ARM64"
    end
    on_intel do
      url "https://github.com/slogr/agent-kotlin/releases/download/v#{version}/slogr-agent-#{version}-macos-amd64.tar.gz"
      sha256 "PLACEHOLDER_SHA256_MACOS_AMD64"
    end
  end

  depends_on "openjdk@21"

  def install
    # Install the fat JAR and the native dylib
    libexec.install "slogr-agent-all.jar"
    (lib/"slogr").install "libslogr_native.dylib"

    # Create a launcher wrapper script
    (bin/"slogr-agent").write <<~EOS
      #!/bin/bash
      export SLOGR_NATIVE_DIR="#{lib}/slogr"
      exec "#{Formula["openjdk@21"].opt_bin}/java" \
        -Djava.library.path="#{lib}/slogr" \
        -Xmx384m \
        -jar "#{libexec}/slogr-agent-all.jar" "$@"
    EOS
    chmod 0755, bin/"slogr-agent"

    # Default config
    (etc/"slogr").mkpath
    (etc/"slogr/agent.yaml").write "" unless (etc/"slogr/agent.yaml").exist?

    # Data directory
    (var/"lib/slogr").mkpath
  end

  service do
    run [opt_bin/"slogr-agent", "daemon"]
    keep_alive true
    working_dir var/"lib/slogr"
    log_path var/"log/slogr-agent.log"
    error_log_path var/"log/slogr-agent-error.log"
    environment_variables SLOGR_CONFIG_FILE: etc/"slogr/agent.yaml"
  end

  def post_install
    # If SLOGR_API_KEY is set in environment, connect automatically
    api_key = ENV["SLOGR_API_KEY"]
    if api_key && !api_key.empty?
      system bin/"slogr-agent", "connect", "--api-key", api_key
    else
      ohai "Run `slogr-agent connect --api-key <key>` to register this agent."
    end
  end

  test do
    output = shell_output("#{bin}/slogr-agent version")
    assert_match version.to_s, output
  end
end
