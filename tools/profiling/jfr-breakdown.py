#!/usr/bin/env python3
"""Breaks a JFR CPU profile of a Shulker server down by subsystem.

    tools/profiling/jfr-breakdown.py <recording.jfr> [thread name substring]

Uses `jfr print --json`, keeps samples of the given thread (default "Server thread") and
assigns each sample to the first matching category, searching the stack from the root down.
Also prints the top self-time methods and the busiest threads overall.
"""
import collections
import json
import subprocess
import sys

# (category, substrings of "Class.method") in priority order: a sample goes to the first
# category with a matching frame anywhere in its stack, so specific phases come before the
# broad ones that contain them (a mob's brain runs inside ServerLevel.tick).
CATEGORIES = [
    ("chunk system / IO", ["ChunkTaskScheduler", "ChunkHolderManager", "RegionFile", "ShulkRegion", "moonrise$midTickTasks"]),
    ("network: connections", ["tickConnection", "ServerGamePacketListenerImpl", "Connection.tick"]),
    ("entity tracker", ["ChunkMap.tick", "AsyncTracker", "ServerEntity", "TrackedEntity"]),
    ("mob spawning", ["NaturalSpawner", "tickCustomSpawners", "SpawnState"]),
    ("random ticks / chunk ticks", ["tickChunk", "RandomTickSystem", "iterateTickingChunks"]),
    ("block entities", ["tickBlockEntities", "BlockEntity"]),
    ("entity: brain / AI", ["Brain.tick", "Behavior", "Sensor", "GoalSelector", "Sensing.tick", "customServerAiStep", "PathNavigation"]),
    ("entity: movement / collisions", ["Entity.move", "pushEntities", "checkInsideBlocks", "applyEffectsFromBlocks", "travel", "Collision", "getEntities"]),
    ("entity: attributes / effects / sync", ["AttributeMap", "tickEffects", "SynchedEntityData", "EntityEquipment", "detectEquipmentUpdates"]),
    ("entity: other tick", ["tickNonPassenger", "LivingEntity.tick", "Mob.tick", "Entity.tick", "checkDespawn", "DespawnMap"]),
    ("world misc", ["ServerLevel.tick", "tickTime", "tickPrecipitation", "WorldBorder"]),
    ("players", ["PlayerList.tick", "ServerPlayer.tick"]),
    ("waiting / sleeping", ["waitUntilNextTick", "managedBlock", "LockSupport.park", "Thread.sleep"]),
]


def frames(event):
    for frame in event["values"]["stackTrace"]["frames"]:
        method = frame["method"]
        yield method["type"]["name"].rsplit(".", 1)[-1] + "." + method["name"]


def main():
    path = sys.argv[1]
    thread_filter = sys.argv[2] if len(sys.argv) > 2 else "Server thread"
    data = json.loads(subprocess.run(["jfr", "print", "--json", "--stack-depth", "256", "--events", "jdk.ExecutionSample", path],
                                     capture_output=True, text=True, check=True).stdout)
    events = data["recording"]["events"]
    threads = collections.Counter(e["values"]["sampledThread"]["javaName"] for e in events)
    picked = [e for e in events if thread_filter in (e["values"]["sampledThread"]["javaName"] or "")]
    by_category, self_time = collections.Counter(), collections.Counter()
    self_by_category = collections.defaultdict(collections.Counter)
    for e in picked:
        stack = list(frames(e))
        if not stack:
            continue
        self_time[stack[0]] += 1
        category = next((name for name, keys in CATEGORIES if any(k in f for k in keys for f in stack)), "other")
        by_category[category] += 1
        self_by_category[category][stack[0]] += 1
    total = max(1, len(picked))
    print(f"{len(picked)} samples on threads matching {thread_filter!r} ({len(events)} total)\n")
    for name, n in by_category.most_common():
        print(f"  {100 * n / total:5.1f}%  {name}")
        for method, m in self_by_category[name].most_common(4):
            print(f"           {100 * m / total:4.1f}%  {method.split('/')[-1]}")
    print("\nTop self time:")
    for name, n in self_time.most_common(15):
        print(f"  {100 * n / total:5.1f}%  {name}")
    print("\nBusiest threads:")
    for name, n in threads.most_common(8):
        print(f"  {n:6d}  {name}")


if __name__ == "__main__":
    main()
