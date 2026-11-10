package edu.byu.chem.boltzmann.tools;

import edu.byu.chem.boltzmann.model.io.Load;
import edu.byu.chem.boltzmann.model.physics.EventInfo;
import edu.byu.chem.boltzmann.model.physics.Particle;
import edu.byu.chem.boltzmann.model.physics.Physics;
import edu.byu.chem.boltzmann.model.physics.Piston;
import edu.byu.chem.boltzmann.model.physics.Piston.PistonMode;
import edu.byu.chem.boltzmann.utils.data.ParticleType;
import edu.byu.chem.boltzmann.utils.data.SimulationInfo;
import edu.byu.chem.boltzmann.utils.data.StatSettingsInfo;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes parity fixtures for boltzmann4d (the TypeScript port): for each preset, the exact
 * initial state Java loaded (random placement differs between the two apps), then every
 * event Java performs over whole frames until at least the requested number of events,
 * and the final positions and velocities (with z components for 3D presets).
 *
 * A settings file may have a sibling <name>.actions script of run-time actions, one per
 * line, applied between frames (as the UI would): "<frame> movePiston <level> <ADIABATIC|
 * ISOTHERMAL|INSTANTANEOUS> <speed m/s>", "<frame> stopPiston", or "events <n>" to run at
 * least n events instead of minEvents. Frame k means just before the k-th frame after the
 * first. The actions are recorded in the fixture so the port can replay them.
 *
 * Usage: ParityDump <outputDir> <minEvents> <preset.set>...
 */
public final class ParityDump {

    /** Records each event just before Physics performs it. */
    static final class RecordingPhysics extends Physics {
        final List<double[]> events = new ArrayList<>();

        RecordingPhysics(SimulationInfo info) {
            super(info, new StatSettingsInfo());
        }

        @Override
        public void updateStatistics(EventInfo event) {
            events.add(new double[] { event.colType, event.colTime, event.part1, event.part2, event.side });
            super.updateStatistics(event);
        }
    }

    public static void main(String[] args) throws Exception {
        Path outputDir = Path.of(args[0]);
        int minEvents = Integer.parseInt(args[1]);
        Files.createDirectories(outputDir);
        for (int i = 2; i < args.length; i++) {
            File preset = new File(args[i]);
            String name = preset.getName().replaceFirst("\\.set$", "");
            seedMathRandom(1);
            SimulationInfo info = new Load(null).loadFile(preset);
            List<String[]> actions = new ArrayList<>();
            int events = minEvents;
            File script = new File(preset.getParentFile(), name + ".actions");
            if (script.exists()) {
                for (String line : Files.readAllLines(script.toPath(), StandardCharsets.UTF_8)) {
                    line = line.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] words = line.split("\\s+");
                    if (words[0].equals("events")) events = Integer.parseInt(words[1]);
                    else actions.add(words);
                }
            }
            String json = dump(name, info, events, actions);
            Files.writeString(outputDir.resolve(name + ".json"), json, StandardCharsets.UTF_8);
            System.out.println("Wrote " + name + ".json");
        }
    }

    /**
     * Load places RandomlyPlaced particles with Math.random(), which can't be seeded through
     * its API. Seeding the generator behind it makes the fixtures reproducible, so they
     * only change when the Java program does. Needs --add-opens java.base/java.lang.
     */
    static void seedMathRandom(long seed) throws ReflectiveOperationException {
        Class<?> holder = Class.forName("java.lang.Math$RandomNumberGeneratorHolder");
        java.lang.reflect.Field field = holder.getDeclaredField("randomNumberGenerator");
        field.setAccessible(true);
        ((java.util.Random) field.get(null)).setSeed(seed);
    }

    static void apply(Physics physics, String[] action) {
        switch (action[1]) {
            case "movePiston" -> physics.AdjustPistonLevel(
                    Double.parseDouble(action[2]), PistonMode.valueOf(action[3]),
                    Double.parseDouble(action[4]));
            case "stopPiston" -> physics.AdjustPistonLevel(physics.getPiston().getCurLevel());
            default -> throw new IllegalArgumentException("Unknown action " + action[1]);
        }
    }

    static String dump(String name, SimulationInfo info, int minEvents, List<String[]> actions) {
        JsonWriter json = new JsonWriter().beginObject();
        json.name("preset").value(name);
        json.name("generator").value("boltzmann3d ParityDump, Java " + Runtime.version());
        json.name("dimension").value(info.dimension);
        json.name("arenaType").value(info.arenaType.name());
        json.name("arenaXSize").value(info.arenaXSize);
        json.name("arenaYSize").value(info.arenaYSize);
        json.name("arenaZSize").value(info.arenaZSize);
        json.name("holeDiameter").value(info.holeDiameter);
        json.name("maxwellDemonMode").value(info.maxwellDemonMode);
        json.name("dividerThermalMode").value(info.dividerThermalMode.name());
        if (info.dimension == 3 && info.arenaType == SimulationInfo.ArenaType.DIVIDED_ARENA_WITH_HOLE) {
            // The ring of edge spheres comes from Math.sin/cos, whose last bit varies between
            // JVMs and platforms (and differs from JavaScript's), so record it as Java built it.
            json.newline().name("edgeY").numbers(info.arenaInfo.getEdgeY());
            json.newline().name("edgeZ").numbers(info.arenaInfo.getEdgeZ());
        }
        json.newline().name("species").beginArray();
        for (ParticleType type : info.getParticleTypes()) {
            int n = info.getNumberOfParticles(type);
            List<double[]> positions = new ArrayList<>(n);
            List<double[]> velocities = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                positions.add(info.getParticlePosition(type, k));
                velocities.add(info.getParticleVelocity(type, k));
            }
            json.newline().beginObject();
            json.name("name").value(type.displayName);
            json.name("mass").value(type.particleMass);
            json.name("radius").value(type.particleRadius);
            json.newline().name("positions").numbers(positions);
            json.newline().name("velocities").numbers(velocities);
            json.endObject();
        }
        json.endArray();

        RecordingPhysics physics = new RecordingPhysics(info);
        physics.advanceToNextFrame(); // first call only creates the first frame
        int frames = 0;
        while (physics.events.size() < minEvents) {
            for (String[] action : actions) {
                if (Integer.parseInt(action[0]) == frames) apply(physics, action);
            }
            physics.advanceToNextFrame();
            frames++;
        }
        json.newline().name("frameDuration").value(physics.getFrameDuration());
        json.name("frames").value(frames);
        json.name("time").value(physics.getTime());
        json.newline().name("actions").beginArray();
        for (String[] action : actions) {
            json.beginArray().value(Long.parseLong(action[0])).value(action[1]);
            if (action[1].equals("movePiston")) {
                json.value(Double.parseDouble(action[2])).value(action[3]).value(Double.parseDouble(action[4]));
            }
            json.endArray();
        }
        json.endArray();
        Piston piston = physics.getPiston();
        json.newline().name("piston").beginObject();
        json.name("curLevel").value(piston.getCurLevel());
        json.name("targetLevel").value(piston.getTargetLevel());
        json.name("deltaKE").value(physics.getThermostat().getDeltaKE());
        json.name("thermostatEnabled").value(physics.getThermostat().isEnabled());
        json.endObject();
        json.newline().name("events").beginArray();
        for (double[] e : physics.events) {
            json.newline().beginArray()
                .value((long) e[0]).value(e[1]).value((long) e[2]).value((long) e[3]).value((long) e[4])
                .endArray();
        }
        json.endArray();

        Particle[] particles = physics.getParticles();
        double[] x = new double[particles.length], y = new double[particles.length];
        double[] z = new double[particles.length];
        double[] vx = new double[particles.length], vy = new double[particles.length];
        double[] vz = new double[particles.length];
        for (int k = 0; k < particles.length; k++) {
            x[k] = particles[k].getX();
            y[k] = particles[k].getY();
            vx[k] = particles[k].getXVel();
            vy[k] = particles[k].getYVel();
            z[k] = particles[k].getZ();
            vz[k] = particles[k].getZVel();
        }
        json.newline().name("final").beginObject();
        json.newline().name("x").numbers(x);
        json.newline().name("y").numbers(y);
        json.newline().name("vx").numbers(vx);
        json.newline().name("vy").numbers(vy);
        if (info.dimension == 3) {
            json.newline().name("z").numbers(z);
            json.newline().name("vz").numbers(vz);
        }
        json.endObject();
        return json.endObject().newline().toString();
    }
}
