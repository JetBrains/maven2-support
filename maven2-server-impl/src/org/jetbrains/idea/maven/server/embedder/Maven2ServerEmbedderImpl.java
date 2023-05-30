// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.maven.server.embedder;

import com.intellij.openapi.util.text.StringUtilRt;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.manager.WagonManager;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.repository.ArtifactRepositoryFactory;
import org.apache.maven.artifact.repository.metadata.RepositoryMetadataManager;
import org.apache.maven.artifact.resolver.ArtifactNotFoundException;
import org.apache.maven.artifact.resolver.ArtifactResolutionException;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.model.Activation;
import org.apache.maven.model.Model;
import org.apache.maven.model.Profile;
import org.apache.maven.profiles.activation.*;
import org.apache.maven.project.*;
import org.apache.maven.project.artifact.ProjectArtifactFactory;
import org.apache.maven.project.inheritance.DefaultModelInheritanceAssembler;
import org.apache.maven.project.injection.DefaultProfileInjector;
import org.apache.maven.project.interpolation.AbstractStringBasedModelInterpolator;
import org.apache.maven.project.interpolation.ModelInterpolationException;
import org.apache.maven.project.interpolation.ModelInterpolator;
import org.apache.maven.project.path.DefaultPathTranslator;
import org.apache.maven.project.path.PathTranslator;
import org.apache.maven.project.validation.ModelValidationResult;
import org.apache.maven.shared.dependency.tree.DependencyNode;
import org.apache.maven.shared.dependency.tree.DependencyTreeResolutionListener;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.context.ContextException;
import org.codehaus.plexus.context.DefaultContext;
import org.codehaus.plexus.personality.plexus.lifecycle.phase.InitializationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.maven.model.*;
import org.jetbrains.idea.maven.server.*;
import org.jetbrains.idea.maven.server.security.MavenToken;
import org.jetbrains.maven.embedder.MavenEmbedder;
import org.jetbrains.maven.embedder.MavenEmbedderSettings;
import org.jetbrains.maven.embedder.MavenExecutionResult;
import org.jetbrains.maven.embedder.PlexusComponentConfigurator;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.rmi.RemoteException;
import java.rmi.server.UnicastRemoteObject;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class Maven2ServerEmbedderImpl extends MavenRemoteObject implements MavenServerEmbedder {
  private final MavenEmbedder myImpl;
  private final Maven2ServerConsoleWrapper myConsoleWrapper;
  private volatile MavenServerProgressIndicator myCurrentIndicator;

  private Maven2ServerEmbedderImpl(MavenEmbedder impl, Maven2ServerConsoleWrapper consoleWrapper) {
    myImpl = impl;
    myConsoleWrapper = consoleWrapper;
  }

  public static Maven2ServerEmbedderImpl create(MavenServerSettings facadeSettings) throws RemoteException {
    MavenEmbedderSettings settings = new MavenEmbedderSettings();

    List<String> commandLineOptions = new ArrayList<String>();
    String mavenEmbedderCliOptions = System.getProperty(MavenServerEmbedder.MAVEN_EMBEDDER_CLI_ADDITIONAL_ARGS);
    if (mavenEmbedderCliOptions != null) {
      commandLineOptions.addAll(StringUtilRt.splitHonorQuotes(mavenEmbedderCliOptions, ' '));
    }

    settings.setConfigurator(new PlexusComponentConfigurator() {
      @Override
      public void configureComponents(@NotNull PlexusContainer c) {
        setupContainer(c);
      }
    });
    Maven2ServerConsoleWrapper consoleWrapper = new Maven2ServerConsoleWrapper();
    consoleWrapper.setThreshold(facadeSettings.getLoggingLevel());
    settings.setLogger(consoleWrapper);
    settings.setRecursive(false);

    settings.setWorkOffline(facadeSettings.isOffline());
    settings.setUsePluginRegistry(false);

    String mavenHomePath = facadeSettings.getMavenHomePath();
    if (mavenHomePath != null) {
      settings.setMavenHomePath(facadeSettings.getMavenHomePath());
    }

    settings.setUserSettingsPath(facadeSettings.getUserSettingsPath());
    settings.setGlobalSettingsPath(facadeSettings.getGlobalSettingsPath());
    settings.setLocalRepositoryPath(facadeSettings.getLocalRepositoryPath());

    if (commandLineOptions.contains("-U") || commandLineOptions.contains("--update-snapshots")) {
      settings.setSnapshotUpdatePolicy(MavenEmbedderSettings.UpdatePolicy.ALWAYS_UPDATE);
      settings.setPluginUpdatePolicy(MavenEmbedderSettings.UpdatePolicy.ALWAYS_UPDATE);

    }
    else {
      settings.setSnapshotUpdatePolicy(MavenEmbedderSettings.UpdatePolicy.DO_NOT_UPDATE);
      settings.setPluginUpdatePolicy(MavenEmbedderSettings.UpdatePolicy.DO_NOT_UPDATE);
    }
    settings.setProperties(MavenServerUtil.collectSystemProperties());

    return new Maven2ServerEmbedderImpl(MavenEmbedder.create(settings), consoleWrapper);
  }


  private static Collection<String> collectProfilesIds(List<Profile> profiles) {
    Collection<String> result = new HashSet<String>();
    for (Profile each : profiles) {
      if (each.getId() != null) {
        result.add(each.getId());
      }
    }
    return result;
  }

  @NotNull
  public static MavenModel interpolateAndAlignModel(MavenModel model, File basedir) throws RemoteException {
    Model result = Maven2ModelConverter.toNativeModel(model);
    result = doInterpolate(result, basedir);

    PathTranslator pathTranslator = new DefaultPathTranslator();
    pathTranslator.alignToBaseDirectory(result, basedir);

    return Maven2ModelConverter.convertModel(result, null);
  }

  private static Model doInterpolate(Model result, File basedir) throws RemoteException {
    try {
      AbstractStringBasedModelInterpolator interpolator = new CustomModelInterpolator(new DefaultPathTranslator());
      interpolator.initialize();

      Properties props = MavenServerUtil.collectSystemProperties();
      ProjectBuilderConfiguration config = new DefaultProjectBuilderConfiguration().setExecutionProperties(props);
      result = interpolator.interpolate(result, basedir, config, false);
    }
    catch (ModelInterpolationException e) {
      Maven2ServerGlobals.getLogger().warn(e);
    }
    catch (InitializationException e) {
      Maven2ServerGlobals.getLogger().error(e);
    }
    return result;
  }

  public static MavenModel assembleInheritance(MavenModel model, MavenModel parentModel) throws RemoteException {
    Model result = Maven2ModelConverter.toNativeModel(model);
    new DefaultModelInheritanceAssembler().assembleModelInheritance(result, Maven2ModelConverter.toNativeModel(parentModel));
    return Maven2ModelConverter.convertModel(result, null);
  }

  public static ProfileApplicationResult applyProfiles(MavenModel model,
                                                       File basedir,
                                                       MavenExplicitProfiles explicitProfiles,
                                                       Collection<String> alwaysOnProfiles) throws RemoteException {
    Model nativeModel = Maven2ModelConverter.toNativeModel(model);

    Collection<String> enabledProfiles = explicitProfiles.getEnabledProfiles();
    Collection<String> disabledProfiles = explicitProfiles.getDisabledProfiles();
    List<Profile> activatedPom = new ArrayList<Profile>();
    List<Profile> activatedExternal = new ArrayList<Profile>();
    List<Profile> activeByDefault = new ArrayList<Profile>();

    List<Profile> rawProfiles = nativeModel.getProfiles();
    List<Profile> expandedProfilesCache = null;
    List<Profile> deactivatedProfiles = new ArrayList<Profile>();

    for (int i = 0; i < rawProfiles.size(); i++) {
      Profile eachRawProfile = rawProfiles.get(i);

      if (disabledProfiles.contains(eachRawProfile.getId())) {
        deactivatedProfiles.add(eachRawProfile);
        continue;
      }

      boolean shouldAdd = enabledProfiles.contains(eachRawProfile.getId()) || alwaysOnProfiles.contains(eachRawProfile.getId());

      Activation activation = eachRawProfile.getActivation();
      if (activation != null) {
        if (activation.isActiveByDefault()) {
          activeByDefault.add(eachRawProfile);
        }

        // expand only if necessary
        if (expandedProfilesCache == null) expandedProfilesCache = doInterpolate(nativeModel, basedir).getProfiles();
        Profile eachExpandedProfile = expandedProfilesCache.get(i);

        for (ProfileActivator eachActivator : getProfileActivators(basedir)) {
          try {
            if (eachActivator.canDetermineActivation(eachExpandedProfile) && eachActivator.isActive(eachExpandedProfile)) {
              shouldAdd = true;
              break;
            }
          }
          catch (ProfileActivationException e) {
            Maven2ServerGlobals.getLogger().warn(e);
          }
        }
      }

      if (shouldAdd) {
        if (MavenConstants.PROFILE_FROM_POM.equals(eachRawProfile.getSource())) {
          activatedPom.add(eachRawProfile);
        }
        else {
          activatedExternal.add(eachRawProfile);
        }
      }
    }

    List<Profile> activatedProfiles = new ArrayList<Profile>(activatedPom.isEmpty() ? activeByDefault : activatedPom);
    activatedProfiles.addAll(activatedExternal);

    for (Profile each : activatedProfiles) {
      new DefaultProfileInjector().inject(each, nativeModel);
    }

    return new ProfileApplicationResult(Maven2ModelConverter.convertModel(nativeModel, null),
                                        new MavenExplicitProfiles(collectProfilesIds(activatedProfiles),
                                                                  collectProfilesIds(deactivatedProfiles))
    );
  }

  private static ProfileActivator[] getProfileActivators(File basedir) throws RemoteException {
    SystemPropertyProfileActivator sysPropertyActivator = new SystemPropertyProfileActivator();
    DefaultContext context = new DefaultContext();
    context.put("SystemProperties", MavenServerUtil.collectSystemProperties());
    try {
      sysPropertyActivator.contextualize(context);
    }
    catch (ContextException e) {
      Maven2ServerGlobals.getLogger().error(e);
      return new ProfileActivator[0];
    }

    return new ProfileActivator[]{new MyFileProfileActivator(basedir),
      sysPropertyActivator,
      new JdkPrefixProfileActivator(),
      new OperatingSystemProfileActivator()};
  }

  private static void setupContainer(PlexusContainer c) {
    MavenEmbedder.setImplementation(c, ArtifactFactory.class, CustomArtifactFactory.class);
    MavenEmbedder.setImplementation(c, ProjectArtifactFactory.class, CustomArtifactFactory.class);
    MavenEmbedder.setImplementation(c, ArtifactResolver.class, CustomArtifactResolver.class);
    MavenEmbedder.setImplementation(c, RepositoryMetadataManager.class, CustomRepositoryMetadataManager.class);
    MavenEmbedder.setImplementation(c, WagonManager.class, CustomWagonManager.class);
    MavenEmbedder.setImplementation(c, ModelInterpolator.class, CustomModelInterpolator.class);
  }


  @NotNull
  @Override
  public Collection<MavenServerExecutionResult> resolveProjects(@NotNull String longRunningTaskId,
                                                                @NotNull ProjectResolutionRequest request, MavenToken token) {
    MavenServerUtil.checkToken(token);

    @NotNull final Collection<File> files = request.getPomFiles();
    @NotNull final Collection<String> activeProfiles = request.getActiveProfiles();
    @NotNull final Collection<String> inactiveProfiles = request.getInactiveProfiles();

    try {

      return files.stream().map(file -> {
        try {
          return doExecute(new Executor<MavenServerExecutionResult>() {
            @NotNull
            @Override
            public MavenServerExecutionResult execute() throws Exception {
              DependencyTreeResolutionListener listener = new DependencyTreeResolutionListener(myConsoleWrapper);
              MavenExecutionResult result = myImpl.resolveProject(file,
                      new ArrayList<String>(activeProfiles),
                      new ArrayList<String>(inactiveProfiles),
                      Collections.singletonList(listener));
              return createExecutionResult(file, result, listener.getRootNode());
            }
          });
        } catch (MavenServerProcessCanceledException | RemoteException e) {
          throw new RuntimeException(e);
        }
      }).collect(Collectors.toList());
    } finally {
      resetComponents();
    }
  }

  @NotNull
  private MavenServerExecutionResult createExecutionResult(File file, MavenExecutionResult result, DependencyNode rootNode)
    throws RemoteException {
    Collection<MavenProjectProblem> problems = MavenProjectProblem.createProblemsList();
    Set<MavenId> unresolvedArtifacts = new HashSet<MavenId>();

    validate(file, result.getExceptions(), problems, unresolvedArtifacts);

    MavenProject mavenProject = result.getMavenProject();
    if (mavenProject == null) return new MavenServerExecutionResult(null, problems, unresolvedArtifacts);

    MavenModel model = Maven2ModelConverter.convertModel(mavenProject.getModel(),
                                                         mavenProject.getCompileSourceRoots(),
                                                         mavenProject.getTestCompileSourceRoots(),
                                                         mavenProject.getArtifacts(),
                                                         (rootNode == null ? Collections.emptyList() : rootNode.getChildren()),
                                                         mavenProject.getExtensionArtifacts(),
                                                         getLocalRepositoryFile());

    RemoteNativeMavenProjectHolder holder = new RemoteNativeMavenProjectHolder(mavenProject);
    try {
      UnicastRemoteObject.exportObject(holder, 0);
    }
    catch (RemoteException e) {
      throw new RuntimeException(e);
    }

    Collection<String> activatedProfiles = collectActivatedProfiles(mavenProject);

    MavenServerExecutionResult.ProjectData data = new MavenServerExecutionResult.ProjectData(
      model, Maven2ModelConverter.convertToMap(mavenProject.getModel()), holder, activatedProfiles);
    return new MavenServerExecutionResult(data, problems, unresolvedArtifacts);
  }

  private static Collection<String> collectActivatedProfiles(MavenProject mavenProject) {
    // for some reason project's active profiles do not contain parent's profiles - only local and settings'.
    // parent's profiles do not contain settings' profiles.

    List<Profile> profiles = new ArrayList<Profile>();
    while (mavenProject != null) {
      profiles.addAll(mavenProject.getActiveProfiles());
      mavenProject = mavenProject.getParent();
    }
    return collectProfilesIds(profiles);
  }

  @Override
  @Nullable
  public String evaluateEffectivePom(@NotNull File file,
                                     @NotNull List<String> activeProfiles,
                                     @NotNull List<String> inactiveProfiles,
                                     MavenToken token) {
    MavenServerUtil.checkToken(token);
    throw new UnsupportedOperationException();
  }


  @Override
  @NotNull
  public List<MavenArtifact> resolveArtifacts(@NotNull String longRunningTaskId, @NotNull Collection<MavenArtifactResolutionRequest> requests, MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      Set<Artifact> toResolve = new LinkedHashSet<Artifact>();
      for (MavenArtifactInfo each : requests.stream().map(MavenArtifactResolutionRequest::getArtifactInfo).collect(Collectors.toList())) {
        toResolve.add(createArtifact(each));
      }

      return Maven2ModelConverter.convertArtifacts(myImpl.resolveTransitively(toResolve, convertRepositories(requests.stream().flatMap(it -> it.getRemoteRepositories().stream()).collect(Collectors.toList()))),
                                                   new HashMap<Artifact, MavenArtifact>(), getLocalRepositoryFile());
    }
    catch (ArtifactResolutionException | ArtifactNotFoundException e) {
      Maven2ServerGlobals.getLogger().info(e);
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
    return Collections.emptyList();
  }


  @NotNull
  @Override
  public MavenArtifactResolveResult resolveArtifactsTransitively(
          @NotNull List<MavenArtifactInfo> artifacts,
          @NotNull List<MavenRemoteRepository> remoteRepositories,
          MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    try {
      Set<Artifact> toResolve = new LinkedHashSet<Artifact>();
      for (MavenArtifactInfo each : artifacts) {
        toResolve.add(createArtifact(each));
      }

      return new MavenArtifactResolveResult(
        Maven2ModelConverter.convertArtifacts(myImpl.resolveTransitively(toResolve, convertRepositories(remoteRepositories)),
                                                   new HashMap<Artifact, MavenArtifact>(), getLocalRepositoryFile())
        , null);
    }
    catch (ArtifactResolutionException | ArtifactNotFoundException e) {
      Maven2ServerGlobals.getLogger().info(e);
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
    return new MavenArtifactResolveResult(Collections.emptyList(), null);
  }

  @NotNull
  private MavenArtifact doResolve(MavenArtifactInfo info, List<MavenRemoteRepository> remoteRepositories) throws RemoteException {
    Artifact resolved = doResolve(createArtifact(info), convertRepositories(remoteRepositories));
    return Maven2ModelConverter.convertArtifact(resolved, getLocalRepositoryFile());
  }

  private Artifact createArtifact(MavenArtifactInfo info) {
    return getComponent(ArtifactFactory.class).createArtifactWithClassifier(info.getGroupId(),
                                                                            info.getArtifactId(),
                                                                            info.getVersion(),
                                                                            info.getPackaging(),
                                                                            info.getClassifier());
  }

  private Artifact doResolve(Artifact artifact, List<ArtifactRepository> remoteRepositories) throws RemoteException {
    try {
      myImpl.resolve(artifact, remoteRepositories);
      return artifact;
    }
    catch (Exception e) {
      Maven2ServerGlobals.getLogger().info(e);
    }
    return artifact;
  }

  private List<ArtifactRepository> convertRepositories(List<MavenRemoteRepository> repositories) throws RemoteException {
    List<ArtifactRepository> result = new ArrayList<ArtifactRepository>();
    for (MavenRemoteRepository each : repositories) {
      try {
        ArtifactRepositoryFactory factory = getComponent(ArtifactRepositoryFactory.class);
        result.add(ProjectUtils.buildArtifactRepository(Maven2ModelConverter.toNativeRepository(each), factory, getContainer()));
      }
      catch (InvalidRepositoryException e) {
        Maven2ServerGlobals.getLogger().warn(e);
      }
    }
    return result;
  }

  @NotNull
  @Override
  public List<MavenGoalExecutionResult> executeGoal(
          @NotNull String longRunningTaskId,
          @NotNull Collection<MavenGoalExecutionRequest> requests,
          @NotNull String goal,
          MavenToken token) throws RemoteException {
    return requests.stream().map(r -> {
      try {
        return doExecute(new Executor<MavenGoalExecutionResult>() {

          @Override
          public @NotNull MavenGoalExecutionResult execute() throws Exception {
            MavenExecutionResult result = myImpl
                    .execute(r.file(), new ArrayList<String>(r.profiles().getEnabledProfiles()), new ArrayList<String>(r.profiles().getDisabledProfiles()), Collections.singletonList(goal), Collections.emptyList(), true,
                            true);

            return new MavenGoalExecutionResult(!result.hasExceptions(), r.file(), new MavenGoalExecutionResult.Folders(),
                    result.getExceptions().stream().map(e -> MavenProjectProblem.createStructureProblem(r.file().getPath(), e.getMessage())).collect(Collectors.toList()));
          }
        });
      } catch (Throwable e) {
        throw wrapToSerializableRuntimeException(e);
      }

    }).collect(Collectors.toList());
  }


  @NotNull
  private MavenServerExecutionResult execute(@NotNull final File file,
                                            @NotNull final Collection<String> activeProfiles,
                                            @NotNull final Collection<String> inactiveProfiles,
                                            @NotNull final List<String> goals,
                                            @NotNull final List<String> selectedProjects,
                                            final boolean alsoMake,
                                             final boolean alsoMakeDependents) throws RemoteException, MavenServerProcessCanceledException {
    return doExecute(new Executor<MavenServerExecutionResult>() {
      @NotNull
      @Override
      public MavenServerExecutionResult execute() throws Exception {
        MavenExecutionResult result = myImpl
          .execute(file, new ArrayList<String>(activeProfiles), new ArrayList<String>(inactiveProfiles), goals, selectedProjects, alsoMake,
                   alsoMakeDependents);
        return createExecutionResult(file, result, null);
      }
    });
  }

  private void validate(File file,
                        Collection<Exception> exceptions,
                        Collection<MavenProjectProblem> problems,
                        Collection<MavenId> unresolvedArtifacts) throws RemoteException {
    for (Exception each : exceptions) {
      Maven2ServerGlobals.getLogger().info(each);

      if (each instanceof InvalidProjectModelException) {
        ModelValidationResult modelValidationResult = ((InvalidProjectModelException)each).getValidationResult();
        if (modelValidationResult != null) {
          for (Object eachValidationProblem : modelValidationResult.getMessages()) {
            problems.add(MavenProjectProblem.createStructureProblem(file.getPath(), (String)eachValidationProblem));
          }
        }
        else {
          problems.add(MavenProjectProblem.createStructureProblem(file.getPath(), each.getCause().getMessage()));
        }
      }
      else if (each instanceof ProjectBuildingException) {
        String causeMessage = each.getCause() != null ? each.getCause().getMessage() : each.getMessage();
        problems.add(MavenProjectProblem.createStructureProblem(file.getPath(), causeMessage));
      }
      else {
        problems.add(MavenProjectProblem.createStructureProblem(file.getPath(), each.getMessage()));
      }
    }
    unresolvedArtifacts.addAll(retrieveUnresolvedArtifactIds());
  }

  private Set<MavenId> retrieveUnresolvedArtifactIds() {
    Set<MavenId> result = new HashSet<MavenId>();
    ((CustomWagonManager)getComponent(WagonManager.class)).getUnresolvedCollector().retrieveUnresolvedIds(result);
    ((CustomArtifactResolver)getComponent(ArtifactResolver.class)).getUnresolvedCollector().retrieveUnresolvedIds(result);
    return result;
  }

  @NotNull
  public File getLocalRepositoryFile() {
    return myImpl.getLocalRepositoryFile();
  }

  public <T> T getComponent(Class<T> clazz) {
    return myImpl.getComponent(clazz);
  }

  public <T> T getComponent(Class<T> clazz, String roleHint) {
    return myImpl.getComponent(clazz, roleHint);
  }

  public PlexusContainer getContainer() {
    return myImpl.getContainer();
  }

  @NotNull
  private <T> T doExecute(final Executor<T> executor) throws MavenServerProcessCanceledException, RemoteException {
    Future<T> future = ExecutorManager.execute(() -> executor.execute());

    MavenServerProgressIndicator indicator = myCurrentIndicator;
    while (true) {
      if (indicator.isCanceled()) throw new MavenServerProcessCanceledException();

      try {
        return future.get(50, TimeUnit.MILLISECONDS);
      }
      catch (TimeoutException ignore) {
      }
      catch (ExecutionException e) {
        Throwable cause = e.getCause();
        if (cause instanceof MavenProcessCanceledRuntimeException) {
          throw new MavenServerProcessCanceledException();
        }
        if (cause instanceof RuntimeRemoteException) {
          throw ((RuntimeRemoteException)cause).getCause();
        }
        throw getRethrowable(cause);
      }
      catch (InterruptedException e) {
        throw new MavenServerProcessCanceledException();
      }
    }
  }

  private RuntimeException getRethrowable(Throwable throwable) {
    if (throwable instanceof InvocationTargetException) throwable = throwable.getCause();
    return wrapToSerializableRuntimeException(throwable);
  }


  private void setConsoleAndIndicator(MavenServerConsoleIndicatorImpl console, MavenServerProgressIndicatorWrapper indicator) {
    myConsoleWrapper.setWrappee(console);
    myCurrentIndicator = indicator;

    WagonManager wagon = getComponent(WagonManager.class);
    wagon.setDownloadMonitor(indicator == null ? null : new TransferListenerAdapter(indicator));
  }


  private void resetComponents() {
    try {
      setConsoleAndIndicator(null, null);

      ((CustomArtifactFactory)getComponent(ProjectArtifactFactory.class)).reset();
      ((CustomArtifactFactory)getComponent(ArtifactFactory.class)).reset();
      ((CustomArtifactResolver)getComponent(ArtifactResolver.class)).reset();
      ((CustomRepositoryMetadataManager)getComponent(RepositoryMetadataManager.class)).reset();
      ((CustomWagonManager)getComponent(WagonManager.class)).reset();
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
  }

  @Override
  public void release(MavenToken token) {
    MavenServerUtil.checkToken(token);
    try {
      myImpl.release();
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
  }

  @NotNull
  @Override
  public LongRunningTaskStatus getLongRunningTaskStatus(@NotNull String longRunningTaskId, MavenToken token) throws RemoteException {
    return LongRunningTaskStatus.EMPTY;
  }

  @Override
  public boolean cancelLongRunningTask(@NotNull String longRunningTaskId, MavenToken token) throws RemoteException {
    return false;
  }

  @Override
  public boolean ping(MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    return true;
  }

  @Override
  public MavenModel readModel(File file, MavenToken token) throws RemoteException {
    MavenServerUtil.checkToken(token);
    return null;
  }

  private void withProjectCachesDo(Consumer<? super Map> func) throws RemoteException {
    MavenProjectBuilder builder = myImpl.getComponent(MavenProjectBuilder.class);
    Field field;
    try {
      field = builder.getClass().getDeclaredField("rawProjectCache");
      field.setAccessible(true);
      func.accept(((Map)field.get(builder)));

      field = builder.getClass().getDeclaredField("processedProjectCache");
      field.setAccessible(true);
      func.accept(((Map)field.get(builder)));
    }
    catch (NoSuchFieldException | IllegalAccessException e) {
      Maven2ServerGlobals.getLogger().info(e);
    }
    catch (Exception e) {
      throw wrapToSerializableRuntimeException(e);
    }
  }

  @Override
  public Set<MavenRemoteRepository> resolveRepositories(@NotNull Collection<MavenRemoteRepository> repositories,
                                                         MavenToken token) throws RemoteException {
    return Collections.emptySet();
  }

  @Override
  public Collection<MavenArchetype> getLocalArchetypes(MavenToken token, @NotNull String path) throws RemoteException {
    return Collections.emptyList();
  }

  @Override
  public Collection<MavenArchetype> getRemoteArchetypes(MavenToken token, @NotNull String url) throws RemoteException {
    return Collections.emptyList();
  }

  public List<PluginResolutionResponse> resolvePlugins(
          @NotNull String longRunningTaskId,
          @NotNull Collection<PluginResolutionRequest> pluginResolutionRequests,
          MavenToken token) throws RemoteException {
    return Collections.emptyList();
  }

  @NotNull
  @Override
  public Map<String, String> resolveAndGetArchetypeDescriptor(@NotNull String groupId,
                                                              @NotNull String artifactId,
                                                              @NotNull String version,
                                                              @NotNull List<MavenRemoteRepository> repositories,
                                                              @Nullable String url, MavenToken token) throws RemoteException {
    return Collections.emptyMap();
  }

  private interface Executor<T> {
    @NotNull
    T execute() throws Exception;
  }
}

