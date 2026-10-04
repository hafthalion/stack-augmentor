#!/bin/sh
# Builds the project and runs one of the examples, the same way an application would:
#   ./run.sh [example] [JVM options]     example: java-agent, live-agent or build-time, the modules in examples
# Without an example name, it asks which one to run.
#
#   java-agent  the Java agent instruments classes, ids from annotations and from
#               stack-augmentor.toml, and a simple cause chain printed both ways:
#                 java -javaagent:<agent jar>=config=<stack-augmentor.toml> -jar <example jar>
#   live-agent  twice: with the agent alone, and in the agent's live-stack mode with the
#               native library as well, which shows ids on every frame:
#                 java -agentpath:<native library> -javaagent:<agent jar>=config=<stack-augmentor.toml> -jar <example jar>
#               Without a C compiler (cc or gcc) there is no native library, and only the first runs.
#   build-time  classes instrumented when they were built, no agent:
#                 java -jar <example jar>
#
# Builds everything first (including the tests), plus the example's lib folder.
# Options after the example name are passed to the JVM, e.g.  ./run.sh live-agent -Xshare:off
# The same as run.bat on Windows.

set -e
cd "$(dirname "$0")"

AGENT_JAR=stack-augmentor-agent/build/libs/stack-augmentor-agent-0.1.0-SNAPSHOT.jar
case "$(uname -s)" in
    Darwin) NATIVE_LIB="$PWD/stack-augmentor-native/build/native/libstackaugmentor.dylib" ;;
    *) NATIVE_LIB="$PWD/stack-augmentor-native/build/native/libstackaugmentor.so" ;;
esac

case "$1" in
    java-agent|live-agent|build-time)
        EXAMPLE=$1
        # Everything after the example name goes to the JVM.
        shift
        ;;
    ""|-*)
        # Only JVM options, e.g.  ./run.sh -Xshare:off : ask for the example, pass them all on.
        echo "Which example?"
        echo "  1. java-agent  Java agent, with a cause chain"
        echo "  2. live-agent  Java agent alone, then in the live-stack mode"
        echo "  3. build-time  build-time instrumentation, no agent"
        printf "Example [1-3]: "
        read -r choice
        case "$choice" in
            1) EXAMPLE=java-agent ;;
            2) EXAMPLE=live-agent ;;
            3) EXAMPLE=build-time ;;
            *) echo "No such example: $choice"; exit 1 ;;
        esac
        ;;
    *)
        echo "Unknown example: $1. Use java-agent, live-agent or build-time."
        exit 1
        ;;
esac

# The example is the Gradle module examples/$EXAMPLE. Its jar's manifest names the main class and the other jars
# in its lib folder.
CONFIG=examples/$EXAMPLE/stack-augmentor.toml
JAR=examples/$EXAMPLE/build/install/$EXAMPLE/lib/$EXAMPLE-0.1.0-SNAPSHOT.jar

if ! ./gradlew build ":examples:$EXAMPLE:installDist"; then
    echo "Build FAILED. Test reports: stack-augmentor-*/build/reports/tests"
    exit 1
fi

case "$EXAMPLE" in
    java-agent)
        echo
        echo "=== Java agent: $AGENT_JAR"
        echo
        java "$@" "-javaagent:$AGENT_JAR=config=$CONFIG" -jar "$JAR"
        ;;
    build-time)
        echo
        echo "=== Build-time instrumentation, no agent"
        echo
        java "$@" -jar "$JAR"
        ;;
    live-agent)
        echo
        echo "=== 1. Agent alone, instrumenting classes: $AGENT_JAR"
        echo
        java "$@" "-javaagent:$AGENT_JAR=config=$CONFIG" -jar "$JAR"
        echo
        if [ ! -f "$NATIVE_LIB" ]; then
            echo "=== 2. Live-stack mode skipped: no $NATIVE_LIB, which needs a C compiler"
            exit 0
        fi
        echo "=== 2. Live-stack mode: $AGENT_JAR with $NATIVE_LIB"
        echo
        java "$@" "-agentpath:$NATIVE_LIB" "-javaagent:$AGENT_JAR=config=$CONFIG" -jar "$JAR"
        ;;
esac
