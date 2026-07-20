#!/usr/bin/env bash
#
# Runs the Gradle wrapper with a Java 8 JDK selected automatically.
#
# Why this exists: this machine setup deliberately keeps no `java` on PATH and no
# global JAVA_HOME (see dev-configurations/jdk-gradle-setup), and `org.gradle.java.home`
# cannot help — the wrapper needs a JVM to start *before* it reads any properties.
# The result was that `./gradlew` failed outright from a clean shell, so all builds
# happened in the IDE, which is what kept rewriting the Gradle wrapper.
#
# Usage: ./build.sh [gradle args...]      e.g. ./build.sh clean build
set -euo pipefail

find_java8() {
    # 1. An explicit override always wins.
    if [[ -n "${JAVA8_HOME:-}" && -x "${JAVA8_HOME}/bin/java" ]]; then
        echo "${JAVA8_HOME}"
        return 0
    fi
    # 2. An already-correct JAVA_HOME.
    if [[ -n "${JAVA_HOME:-}" && -x "${JAVA_HOME}/bin/java" ]]; then
        if "${JAVA_HOME}/bin/java" -version 2>&1 | grep -q '"1\.8'; then
            echo "${JAVA_HOME}"
            return 0
        fi
    fi
    # 3. The ~/.jdks convention used across these repos. Newest match wins.
    local candidate
    for candidate in "${HOME}/.jdks"/*1.8*; do
        if [[ -x "${candidate}/bin/java" ]]; then
            echo "${candidate}"
            return 0
        fi
    done
    return 1
}

if ! JAVA_HOME="$(find_java8)"; then
    cat >&2 <<'EOF'
No Java 8 JDK found.

ForgeGradle 2.3 (Minecraft 1.12.2) only builds on JDK 8. Install one via
IntelliJ (Project Structure > SDKs > Download JDK > Azul Zulu 8), which places
it in ~/.jdks, or point JAVA8_HOME at an existing installation:

    JAVA8_HOME=/path/to/jdk8 ./build.sh build
EOF
    exit 1
fi

export JAVA_HOME
# stderr, not stdout: `./build.sh -q printModVersion` must emit only the value.
echo "Using JDK: ${JAVA_HOME}" >&2
exec "$(dirname "$0")/gradlew" "$@"
