package edu.byu.chem.boltzmann.tools;

import edu.byu.chem.boltzmann.model.io.Load;
import edu.byu.chem.boltzmann.model.physics.EventInfo;
import edu.byu.chem.boltzmann.model.physics.Particle;
import edu.byu.chem.boltzmann.model.physics.Physics;
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
 * and the final positions and velocities.
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
            SimulationInfo info = new Load(null).loadFile(preset);
            String json = dump(name, info, minEvents);
            Files.writeString(outputDir.resolve(name + ".json"), json, StandardCharsets.UTF_8);
            System.out.println("Wrote " + name + ".json");
        }
    }

    static String dump(String name, SimulationInfo info, int minEvents) {
        JsonWriter json = new JsonWriter().beginObject();
        json.name("preset").value(name);
        json.name("generator").value("boltzmann3d ParityDump, Java " + Runtime.version());
        json.name("dimension").value(info.dimension);
        json.name("arenaType").value(info.arenaType.name());
        json.name("arenaXSize").value(info.arenaXSize);
        json.name("arenaYSize").value(info.arenaYSize);
        json.name("arenaZSize").value(info.arenaZSize);
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
            physics.advanceToNextFrame();
            frames++;
        }
        json.newline().name("frameDuration").value(physics.getFrameDuration());
        json.name("frames").value(frames);
        json.name("time").value(physics.getTime());
        json.newline().name("events").beginArray();
        for (double[] e : physics.events) {
            json.newline().beginArray()
                .value((long) e[0]).value(e[1]).value((long) e[2]).value((long) e[3]).value((long) e[4])
                .endArray();
        }
        json.endArray();

        Particle[] particles = physics.getParticles();
        double[] x = new double[particles.length], y = new double[particles.length];
        double[] vx = new double[particles.length], vy = new double[particles.length];
        for (int k = 0; k < particles.length; k++) {
            x[k] = particles[k].getX();
            y[k] = particles[k].getY();
            vx[k] = particles[k].getXVel();
            vy[k] = particles[k].getYVel();
        }
        json.newline().name("final").beginObject();
        json.newline().name("x").numbers(x);
        json.newline().name("y").numbers(y);
        json.newline().name("vx").numbers(vx);
        json.newline().name("vy").numbers(vy);
        json.endObject();
        return json.endObject().newline().toString();
    }
}
