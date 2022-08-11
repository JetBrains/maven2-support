package org.jetbrains.idea.maven2.maven2support;

import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.roots.ui.distribution.DistributionInfo;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.util.PathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.MavenVersionAwareSupportExtension;
import org.jetbrains.idea.maven.model.MavenId;
import org.jetbrains.idea.maven.server.MavenDistribution;
import org.jetbrains.idea.maven.server.MavenServer;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

public class Maven2SupportExtension implements MavenVersionAwareSupportExtension {
    public static final String BUNDLED_MAVEN_2 = "Bundled (Maven 2)";

    @Override
    public @NotNull List<String> supportedBundles() {
        return List.of(BUNDLED_MAVEN_2);
    }

    @Override
    public boolean isSupportedByExtension(@Nullable File mavenHome) {
        String version = MavenUtil.getMavenVersion(mavenHome);
        return StringUtil.compareVersionNumbers(version, "3") < 0;
    }

    @Override
    public @Nullable File getMavenHomeFile(@Nullable String mavenHomeName) {
        if (mavenHomeName == null) return null;
        if (StringUtil.equals(BUNDLED_MAVEN_2, mavenHomeName)) {
            return Bundled2DistributionInfo.getMavenHome2().getMavenHome().toFile();
        }
        return null;
    }

    @Override
    public @Nullable String asMavenHome(DistributionInfo distribution) {
        if (distribution instanceof Bundled2DistributionInfo) return BUNDLED_MAVEN_2;
        return null;
    }

    @Override
    public @Nullable DistributionInfo asDistributionInfo(String mavenHome) {
        if (StringUtil.equals(BUNDLED_MAVEN_2, mavenHome)) {
            return new Bundled2DistributionInfo(Bundled2DistributionInfo.getMavenHome2().getVersion());
        }
        return null;
    }

    @Override
    public @NotNull List<File> collectClassPathAndLibsFolder(@NotNull MavenDistribution distribution) {
        String pluginsRoot = PathManager.getPluginsPath();
        final List<File> classpath = new ArrayList<>();

        classpath.add(new File(PathUtil.getJarPathForClass(MavenId.class)));
        classpath.add(new File(PathUtil.getJarPathForClass(MavenServer.class)));

        addDir(classpath, new File(pluginsRoot, "maven2-support/server"), file -> file.getName().endsWith(".jar"));
        addMavenLibs(classpath, distribution.getMavenHome().toFile());
        return classpath;
    }

    private static void addDir(List<File> classpath, File dir, Predicate<File> filter) {
        File[] files = dir.listFiles();
        if (files == null) return;

        for (File jar : files) {
            if (jar.isFile() && jar.getName().endsWith(".jar") && filter.test(jar)) {
                classpath.add(jar);
            }
        }
    }

    private static void addMavenLibs(List<File> classpath, File mavenHome) {
        addDir(classpath, new File(mavenHome, "lib"), f -> !f.getName().contains("maven-slf4j-provider"));
        File bootFolder = new File(mavenHome, "boot");
        File[] classworldsJars = bootFolder.listFiles((dir, name) -> StringUtil.contains(name, "classworlds"));
        if (classworldsJars != null) {
            Collections.addAll(classpath, classworldsJars);
        }
    }
}
