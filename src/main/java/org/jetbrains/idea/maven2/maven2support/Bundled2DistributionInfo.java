package org.jetbrains.idea.maven2.maven2support;

import com.intellij.openapi.application.PathManager;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.maven3.BundledDistributionInfo;
import org.jetbrains.idea.maven.server.LocalMavenDistribution;

import java.nio.file.Path;

public class Bundled2DistributionInfo extends BundledDistributionInfo {
    public Bundled2DistributionInfo(@Nullable String version) {
        super(version != null ? version : "2");
    }

    public static LocalMavenDistribution getMavenHome2() {
        Path pluginsRoot = PathManager.getPluginsDir();
        return new LocalMavenDistribution(pluginsRoot.resolve("maven2-support/bundled-maven2"), Maven2SupportExtension.BUNDLED_MAVEN_2);
    }
}
