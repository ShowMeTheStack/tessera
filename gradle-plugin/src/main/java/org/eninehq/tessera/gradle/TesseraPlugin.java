package org.eninehq.tessera.gradle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.tasks.Exec;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;

/**
 * Adds hardware tasks to a Java project whose code describes Tessera designs:
 * {@code tesseraEmit}, {@code tesseraLint}, {@code tesseraSynth}, {@code tesseraSim},
 * {@code tesseraBtor2}, {@code formal} (bounded model checking, optionally k-induction) and
 * {@code flash} (programs a board with openFPGALoader).
 */
public class TesseraPlugin implements Plugin<Project> {

    static final Map<String, String> BOARDS = Map.of(
            "zedboard", "zedboard",
            "arty-a7", "arty_a7_35t",
            "basys3", "basys3",
            "icebreaker", "ice40_generic",
            "ulx3s", "ulx3s");

    @Override
    public void apply(Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        var ext = project.getExtensions().create("tessera", TesseraExtension.class);
        ext.getSynthTarget().convention("generic");
        ext.getFormalDepth().convention(20);
        ext.getSimCycles().convention(1000);

        Configuration cli = project.getConfigurations().create("tesseraCli", c -> {
            c.setCanBeConsumed(false);
            c.setDescription("The Tessera command line used by the hardware tasks");
        });
        project.afterEvaluate(p -> {
            if (cli.getDependencies().isEmpty()) {
                var local = p.getRootProject().findProject(":tessera-cli");
                if (local != null) p.getDependencies().add(cli.getName(), p.getDependencies().project(Map.of("path", ":tessera-cli")));
                else p.getDependencies().add(cli.getName(), "org.eninehq.tessera:tessera-cli:" + pluginVersion());
            }
        });

        var sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
        FileCollection runtime = sourceSets.getByName("main").getRuntimeClasspath();

        cliTask(project, ext, cli, runtime, "tesseraEmit", "Writes SystemVerilog for every design", "emit");
        cliTask(project, ext, cli, runtime, "tesseraLint", "Lints the generated SystemVerilog with Verilator", "lint");
        cliTask(project, ext, cli, runtime, "tesseraSynth", "Synthesizes every design with Yosys", "synth");
        cliTask(project, ext, cli, runtime, "tesseraSim", "Simulates every design from reset", "sim");
        cliTask(project, ext, cli, runtime, "tesseraBtor2", "Writes BTOR2 models for external model checkers", "btor2");
        var formal = cliTask(project, ext, cli, runtime, "formal", "Checks the designs' assertions formally", "formal");
        formal.configure(t -> {
            t.setGroup("verification");
            // part of "check": a project without designs (only a board flow, say) has nothing to prove
            t.onlyIf("designs are configured", x -> !ext.getTops().get().isEmpty());
        });
        project.getTasks().named("check").configure(t -> t.dependsOn(formal));

        project.getTasks().register("flash", Exec.class, t -> {
            t.setGroup("tessera");
            t.setDescription("Programs the board with openFPGALoader (needs tessera.board and tessera.bitstream)");
            t.doFirst(x -> {
                String board = ext.getBoard().getOrNull(), bit = ext.getBitstream().getOrNull();
                if (board == null || bit == null) throw new IllegalStateException("set tessera.board and tessera.bitstream");
                String b = BOARDS.getOrDefault(board, board);
                String host = ext.getFlashHost().getOrNull();
                if (host == null) {
                    t.commandLine("openFPGALoader", "-b", b, bit);
                } else {
                    String remote = "/tmp/tessera-flash-" + new java.io.File(bit).getName();
                    project.getProviders().exec(e -> e.commandLine("scp", "-q", bit, host + ":" + remote)).getResult().get()
                            .assertNormalExitValue();
                    t.commandLine("ssh", host, "openFPGALoader -b " + b + " " + remote);
                }
            });
        });
    }

    private TaskProvider<TesseraCliTask> cliTask(Project project, TesseraExtension ext, Configuration cli, FileCollection runtime,
                                                 String name, String description, String command) {
        return project.getTasks().register(name, TesseraCliTask.class, t -> {
            t.setGroup("tessera");
            t.setDescription(description);
            t.getCommand().set(command);
            t.getTops().set(ext.getTops());
            t.getClasspath().from(runtime, cli);
            t.getOutputDir().set(project.getLayout().getBuildDirectory().dir("tessera"));
            var java = project.getExtensions().getByType(org.gradle.api.plugins.JavaPluginExtension.class);
            var toolchains = project.getExtensions().getByType(org.gradle.jvm.toolchain.JavaToolchainService.class);
            t.getLauncher().convention(toolchains.launcherFor(java.getToolchain()));
            t.getExtraArgs().set(project.provider(() -> {
                List<String> a = new ArrayList<>();
                switch (command) {
                    case "synth" -> a.addAll(List.of("--target", ext.getSynthTarget().get()));
                    case "formal" -> {
                        a.addAll(List.of("--depth", ext.getFormalDepth().get().toString()));
                        if (ext.getProveDepth().isPresent()) a.addAll(List.of("--prove", ext.getProveDepth().get().toString()));
                    }
                    case "sim" -> a.addAll(List.of("--cycles", ext.getSimCycles().get().toString()));
                    default -> {}
                }
                return a;
            }));
        });
    }

    private static String pluginVersion() {
        var v = TesseraPlugin.class.getPackage().getImplementationVersion();
        return v == null ? "0.1.0-SNAPSHOT" : v;
    }

}
