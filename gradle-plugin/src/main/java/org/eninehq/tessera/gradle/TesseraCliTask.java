package org.eninehq.tessera.gradle;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.process.ExecOperations;

/** Runs one {@code tessera} command for every configured design. */
public abstract class TesseraCliTask extends DefaultTask {

    @Input public abstract Property<String> getCommand();

    @Input public abstract ListProperty<String> getTops();

    /** Extra arguments after {@code --top} (target, depth, cycles, ...). */
    @Input public abstract ListProperty<String> getExtraArgs();

    @Classpath public abstract ConfigurableFileCollection getClasspath();

    @Internal public abstract DirectoryProperty getOutputDir();

    /** The JVM to run designs on (the project's Java toolchain). */
    @org.gradle.api.tasks.Nested public abstract Property<org.gradle.jvm.toolchain.JavaLauncher> getLauncher();

    @Inject protected abstract ExecOperations getExecOperations();

    @TaskAction
    public void run() {
        var tops = getTops().get();
        if (tops.isEmpty()) throw new IllegalStateException("no designs: add tessera { tops.add(\"com.example.Top\") }");
        for (String top : tops) {
            List<String> args = new ArrayList<>(List.of(getCommand().get(), "--top", top,
                    "--out", getOutputDir().get().getAsFile().getPath()));
            args.addAll(getExtraArgs().get());
            getExecOperations().javaexec(spec -> {
                spec.setClasspath(getClasspath());
                if (getLauncher().isPresent()) spec.setExecutable(getLauncher().get().getExecutablePath().getAsFile());
                spec.getMainClass().set("org.eninehq.tessera.cli.TesseraCli");
                spec.jvmArgs("--enable-native-access=ALL-UNNAMED");
                spec.setArgs(args);
            });
        }
    }
}
