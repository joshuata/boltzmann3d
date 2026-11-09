package edu.byu.chem.boltzmann.tools;

import edu.byu.chem.boltzmann.fullapplication.view.simulationsettings.DefaultParticleInfo;
import edu.byu.chem.boltzmann.model.io.DummyMain;
import edu.byu.chem.boltzmann.model.physics.EventInfo;
import edu.byu.chem.boltzmann.model.physics.Physics;
import edu.byu.chem.boltzmann.utils.data.ParticleType;
import edu.byu.chem.boltzmann.utils.data.SimulationInfo;
import edu.byu.chem.boltzmann.utils.data.StatSettingsInfo;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Physics throughput of the Java app as the particle count grows, for comparison with
 * boltzmann4d (scripts/bench-scaling.ts uses the same scene and method).
 *
 * For each count: build a seeded 2D synthetic gas (same generator as boltzmann4d's
 * syntheticGas), time the Physics constructor (initial predictions), run whole frames of
 * the default frame duration for a warm-up period, then measure frames, events and
 * simulated time over the measurement period. Two variants: "java-stats" runs the
 * statistics on every event as the app does; "java-physics" skips per-event statistics
 * (frame-level bookkeeping still runs).
 *
 * Usage: PhysicsBenchmark <output.json> <warmupSeconds> <measureSeconds> <maxFrameSeconds> <count>...
 */
public final class PhysicsBenchmark {

    static final class CountingPhysics extends Physics {
        final boolean statistics;
        long events = 0;

        CountingPhysics(SimulationInfo info, boolean statistics) {
            super(info, new StatSettingsInfo());
            this.statistics = statistics;
        }

        @Override
        public void updateStatistics(EventInfo event) {
            events++;
            if (statistics) super.updateStatistics(event);
        }
    }

    /** mulberry32, identical to boltzmann4d's seededRandom. */
    static final class Mulberry32 {
        private int a;
        Mulberry32(int seed) { a = seed; }
        double next() {
            a += 0x6d2b79f5;
            int t = a;
            t = (t ^ (t >>> 15)) * (t | 1);
            t ^= t + (t ^ (t >>> 7)) * (t | 61);
            return ((t ^ (t >>> 14)) & 0xFFFFFFFFL) / 4294967296.0;
        }
    }

    /** Port of boltzmann4d's syntheticGas: two species on a jittered grid, 10% packing. */
    static SimulationInfo syntheticGas(int count, int seed, double packing) {
        Mulberry32 random = new Mulberry32(seed);
        double[] mass = { 2, 32 }, radius = { 0.5e-9, 0.8e-9 }, speed = { 1579, 395 };
        double meanArea = Math.PI * Math.pow((0.5e-9 + 0.8e-9) / 2, 2);
        double side = Math.sqrt((count * meanArea) / packing);
        int cols = (int) Math.ceil(Math.sqrt(count));
        double cell = side / cols;
        List<List<double[]>> positions = List.of(new ArrayList<>(), new ArrayList<>());
        List<List<double[]>> velocities = List.of(new ArrayList<>(), new ArrayList<>());
        for (int k = 0; k < count; k++) {
            int t = k % 2;
            double jitter = (cell - 2 * radius[t]) * 0.4;
            double x = ((k % cols) + 0.5) * cell + (random.next() - 0.5) * jitter;
            double y = ((double) (k / cols) + 0.5) * cell + (random.next() - 0.5) * jitter;
            positions.get(t).add(new double[] { x, y, 0 });
            double theta = random.next() * 2 * Math.PI;
            velocities.get(t).add(new double[] { Math.cos(theta) * speed[t], Math.sin(theta) * speed[t], 0 });
        }
        DummyMain d = new DummyMain();
        d.dimension = 2;
        d.arenaType = SimulationInfo.ArenaType.REFLECTING_BOUNDARIES;
        d.xSize = side / 1e-9; // DummyMain stores nanometres
        d.ySize = side / 1e-9;
        d.zSize = 0;
        d.redParticle = new ParticleType(mass[0], radius[0], DefaultParticleInfo.RED, "Red");
        d.blueParticle = new ParticleType(mass[1], radius[1], DefaultParticleInfo.BLUE, "Blue");
        d.redPositions = positions.get(0);
        d.bluePositions = positions.get(1);
        d.redVelocities = velocities.get(0);
        d.blueVelocities = velocities.get(1);
        return new SimulationInfo(d);
    }

    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]);
        double warmup = Double.parseDouble(args[1]);
        double measure = Double.parseDouble(args[2]);
        double maxFrame = Double.parseDouble(args[3]);
        int seed = 1;
        double packing = 0.1;

        JsonWriter json = new JsonWriter().beginObject();
        json.name("runner").value("boltzmann3d PhysicsBenchmark");
        json.name("runtime").value("Java " + Runtime.version() + " (" + System.getProperty("java.vm.name") + ")");
        json.name("os").value(System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        json.name("cpus").value(Runtime.getRuntime().availableProcessors());
        json.name("maxHeapMB").value(Runtime.getRuntime().maxMemory() / (1024 * 1024));
        json.name("warmupSeconds").value(warmup);
        json.name("measureSeconds").value(measure);
        json.name("frameDuration").value(Physics.DEFAULT_FRAME_DURATION);
        json.name("seed").value(seed);
        json.name("packing").value(packing);
        json.newline().name("results").beginArray();

        for (String variant : new String[] { "java-stats", "java-physics" }) {
            boolean stopped = false;
            for (int i = 4; i < args.length; i++) {
                int count = Integer.parseInt(args[i]);
                json.newline().beginObject().name("variant").value(variant).name("particles").value(count);
                if (stopped) {
                    json.name("status").value("skipped (previous count hit a limit)").endObject();
                    continue;
                }
                try {
                    stopped = run(json, count, seed, packing, variant.equals("java-stats"), warmup, measure, maxFrame);
                } catch (OutOfMemoryError e) {
                    json.name("status").value("out of memory");
                    stopped = true;
                }
                json.endObject();
                System.gc();
            }
        }
        json.endArray().endObject().newline();
        Files.writeString(output, json.toString(), StandardCharsets.UTF_8);
        System.out.println("Wrote " + output);
    }

    /** Returns true if this count hit a limit, so larger counts are skipped. */
    static boolean run(JsonWriter json, int count, int seed, double packing, boolean statistics,
                       double warmup, double measure, double maxFrame) {
        SimulationInfo info = syntheticGas(count, seed, packing);
        System.gc();
        long setupStart = System.nanoTime();
        CountingPhysics physics = new CountingPhysics(info, statistics);
        double setupMs = (System.nanoTime() - setupStart) / 1e6;
        Runtime rt = Runtime.getRuntime();
        System.gc();
        long heapMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        json.name("setupMs").value(setupMs).name("memoryMB").value(heapMB);
        System.out.printf("%s %d particles: setup %.0f ms, %d MB%n", statistics ? "stats" : "physics", count, setupMs, heapMB);

        physics.advanceToNextFrame(); // first call only creates the first frame
        long warmEnd = System.nanoTime() + (long) (warmup * 1e9);
        while (System.nanoTime() < warmEnd) {
            long frameStart = System.nanoTime();
            physics.advanceToNextFrame();
            if ((System.nanoTime() - frameStart) / 1e9 > maxFrame) {
                json.name("status").value("frame slower than " + maxFrame + " s");
                return true;
            }
        }

        long events0 = physics.events;
        double time0 = physics.getTime();
        long frames = 0;
        long start = System.nanoTime();
        long end = start + (long) (measure * 1e9);
        do {
            physics.advanceToNextFrame();
            frames++;
        } while (System.nanoTime() < end);
        double seconds = (System.nanoTime() - start) / 1e9;
        long events = physics.events - events0;
        json.name("status").value("ok")
            .name("frames").value(frames)
            .name("events").value(events)
            .name("seconds").value(seconds)
            .name("eventsPerSecond").value(events / seconds)
            .name("framesPerSecond").value(frames / seconds)
            .name("simSecondsPerSecond").value((physics.getTime() - time0) / seconds);
        System.out.printf("  %.0f events/s, %.1f frames/s%n", events / seconds, frames / seconds);
        return false;
    }
}
