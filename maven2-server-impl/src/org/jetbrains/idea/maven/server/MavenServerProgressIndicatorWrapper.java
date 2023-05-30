// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.idea.maven.server;

import org.jetbrains.annotations.Nullable;

import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * @author Sergey Evdokimov
 */
public class MavenServerProgressIndicatorWrapper extends MavenRemoteObject
        implements MavenServerProgressIndicator, MavenServerConsoleIndicator {

  private final ConcurrentLinkedQueue<MavenArtifactEvent> myPullingQueue
          = new ConcurrentLinkedQueue<MavenArtifactEvent>();

  private final ConcurrentLinkedQueue<MavenServerConsoleEvent> myConsoleEventsQueue
          = new ConcurrentLinkedQueue<MavenServerConsoleEvent>();

  private boolean myCancelled = false;


  @Override
  public void setText(String text) throws RemoteException {
  }

  @Override
  public void setText2(String text) throws RemoteException {
  }

  @Override
  public void startedDownload(ResolveType type, String dependencyId) {
    myPullingQueue.add(new MavenArtifactEvent(type,
            MavenArtifactEvent.ArtifactEventType.DOWNLOAD_STARTED,
            dependencyId,
            null,
            null));
  }

  @Override
  public void completedDownload(ResolveType type, String dependencyId) {
    myPullingQueue.add(new MavenArtifactEvent(type,
            MavenArtifactEvent.ArtifactEventType.DOWNLOAD_COMPLETED,
            dependencyId,
            null, null));
  }

  @Override
  public void failedDownload(ResolveType type, String dependencyId, String errorMessage, String stackTrace) {
    myPullingQueue.add(new MavenArtifactEvent(type,
            MavenArtifactEvent.ArtifactEventType.DOWNLOAD_FAILED,
            dependencyId,
            errorMessage, stackTrace));
  }

  @Override
  public boolean isCanceled() {
    return myCancelled;
  }

  @Override
  public void setIndeterminate(boolean value) throws RemoteException {
  }

  @Override
  public void setFraction(double fraction) throws RemoteException {
  }

  @Nullable
  @Override
  public List<MavenArtifactEvent> pullDownloadEvents() {
    return MavenRemotePullUtil.pull(myPullingQueue);
  }

  @Nullable
  @Override
  public List<MavenServerConsoleEvent> pullConsoleEvents() {
    return MavenRemotePullUtil.pull(myConsoleEventsQueue);
  }

  @Override
  public void cancel() {
    myCancelled = true;
  }
}
