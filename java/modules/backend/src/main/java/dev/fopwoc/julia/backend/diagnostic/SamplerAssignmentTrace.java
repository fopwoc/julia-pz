package dev.fopwoc.julia.backend.diagnostic;

import com.gtnewhorizons.angelica.sdlgpu.shader.ShaderManager.ProgramObject;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import java.util.WeakHashMap;

/** Bounded, failure-only evidence of completed sampler writes; values never retain their program keys. */
public final class SamplerAssignmentTrace {
    private static final Map<ProgramObject, History> HISTORIES = new WeakHashMap<>();
    private static final StackWalker WALKER = StackWalker.getInstance();

    private SamplerAssignmentTrace() {}

    public static synchronized void linked(ProgramObject program) {
        var history = new History();
        history.initial.putAll(snapshot(program));
        history.omittedInitial = Math.max(
                0,
                program.nameToLocation.keySet().stream()
                                .filter(program.allSamplerNames::contains)
                                .count()
                        - history.initial.size());
        HISTORIES.put(program, history);
    }

    private static Map<String, String> snapshot(ProgramObject program) {
        Map<String, String> result = new TreeMap<>();
        program.allSamplerNames.stream()
                .filter(program.nameToLocation::containsKey)
                .sorted()
                .limit(History.LIMIT)
                .forEach(name -> {
                    int location = program.nameToLocation.getInt(name);
                    result.put(
                            name,
                            "location=" + location + ", map="
                                    + program.samplerTextureUnits.getInt(name) + ", slot="
                                    + program.locationSamplerUnit[location]);
                });
        return result;
    }

    public static synchronized void lookup(ProgramObject program, String name, int location) {
        var history = HISTORIES.get(program);
        if (history != null) history.lookup(name, location);
    }

    public static synchronized void assigned(ProgramObject program, int location, int requested) {
        if (program == null
                || location < 0
                || location >= program.locationKind.length
                || program.locationKind[location] != ProgramObject.LOCATION_KIND_SAMPLER) return;
        var history = HISTORIES.get(program);
        if (history == null) return;
        String name = program.locationName[location];
        String caller = history.needsCaller(name) ? caller() : null;
        history.assigned(
                name,
                location,
                requested,
                program.samplerTextureUnits.getInt(name),
                program.locationSamplerUnit[location],
                caller);
    }

    public static synchronized String describe(ProgramObject program) {
        var history = HISTORIES.get(program);
        return "; sampler trace=" + (history == null ? "not captured" : history.describe()) + "; draw sampler state="
                + snapshot(program);
    }

    private static String caller() {
        return WALKER.walk(
                frames -> frames.filter(frame -> !frame.getClassName().startsWith("dev.fopwoc.julia.backend.")
                                && !frame.getClassName().startsWith("com.gtnewhorizons.angelica.")
                                && !frame.getClassName().startsWith("org.lwjgl."))
                        .findFirst()
                        .map(Object::toString)
                        .orElse("unknown"));
    }

    static final class History {
        static final int LIMIT = 128;
        static final int EVENTS = 32;
        final Map<String, String> initial = new TreeMap<>();
        private final Map<String, Assignment> assignments = new TreeMap<>();
        private final Map<String, Integer> lookups = new TreeMap<>();
        private long omittedLookups;
        private final ArrayDeque<String> recent = new ArrayDeque<>();
        long omittedInitial;
        private long writes;
        private long omittedWrites;
        private long changes;

        void lookup(String name, int location) {
            if (lookups.size() == LIMIT && !lookups.containsKey(name)) {
                omittedLookups++;
                return;
            }
            lookups.put(name, location);
        }

        boolean needsCaller(String name) {
            return assignments.size() < LIMIT && !assignments.containsKey(name);
        }

        void assigned(String name, int location, int requested, int stored, int slot, String caller) {
            writes++;
            var assignment = assignments.get(name);
            if (assignment == null) {
                if (assignments.size() == LIMIT) {
                    omittedWrites++;
                    return;
                }
                assignment = new Assignment(caller);
                assignments.put(name, assignment);
            }
            boolean changed = assignment.count == 0
                    || assignment.location != location
                    || assignment.requested != requested
                    || assignment.stored != stored
                    || assignment.slot != slot;
            assignment.count++;
            assignment.location = location;
            assignment.requested = requested;
            assignment.stored = stored;
            assignment.slot = slot;
            if (changed) {
                changes++;
                if (recent.size() == EVENTS) recent.removeFirst();
                recent.addLast("#" + writes + " " + name + "{" + assignment.values() + "}");
            }
        }

        String describe() {
            return "{completed writes=" + writes + ", omitted writes=" + omittedWrites
                    + ", initial (-1 map means default)=" + initial + ", omitted initial=" + omittedInitial
                    + ", uniform lookups=" + lookups + ", omitted lookups=" + omittedLookups
                    + ", per sampler=" + assignments + ", omitted changes=" + Math.max(0, changes - recent.size())
                    + ", recent changes=" + recent + "}";
        }

        private static final class Assignment {
            private final String firstCaller;
            private long count;
            private int location, requested, stored, slot;

            Assignment(String firstCaller) {
                this.firstCaller = firstCaller;
            }

            String values() {
                return "location=" + location + ", requested=" + requested + ", stored=" + stored + ", slot=" + slot;
            }

            @Override
            public String toString() {
                return "{writes=" + count + ", " + values() + ", first caller=" + firstCaller + "}";
            }
        }
    }
}
