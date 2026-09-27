# Sourced by the test scripts: keeps throwaway test servers from starving the machine they run on.
#
#   SHULKER_TEST_CPUS   CPUs the test servers may use (taskset list), default 0-7
#   SHULKER_TEST_HEAP   heap per test server, default 2G
#
# Servers run with nice 10 and without heap pre-touching, so a desktop stays responsive.

SHULKER_TEST_CPUS="${SHULKER_TEST_CPUS:-0-7}"
SHULKER_TEST_HEAP="${SHULKER_TEST_HEAP:-2G}"

# limited <command...>: run a command (and everything it starts) on the test CPUs at low priority.
limited() {
    if command -v taskset >/dev/null; then
        nice -n 10 taskset -c "$SHULKER_TEST_CPUS" "$@"
    else
        nice -n 10 "$@"
    fi
}

# launcher_props <server dir> [extra JVM args...]: no pre-touch, plus any extra arguments.
launcher_props() {
    local dir=$1; shift
    echo "extra-jvm-args=-XX:-AlwaysPreTouch $*" > "$dir/shulker-launcher.properties"
}
