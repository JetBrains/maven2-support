/* ==========================================================================
 * Copyright 2006 Mevenide Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * =========================================================================
 */

package org.jetbrains.maven.embedder;

import org.codehaus.plexus.logging.Logger;

public abstract class AbstractMavenLogger implements Logger {
  private static final String LINE_SEPARATOR = System.getProperty("line.separator");

  private int myThreshold;

  private void doPrint(int level, String message, Throwable throwable) {
    if (level < myThreshold) return;

    if (!message.endsWith(LINE_SEPARATOR)) {
      message += LINE_SEPARATOR;
    }

    printMessage(level, message, throwable);
  }

  protected abstract void printMessage(int level, String message, Throwable throwable);

  @Override
  public void debug(String string, Throwable throwable) {
    doPrint(LEVEL_DEBUG, string, throwable);
  }

  @Override
  public void info(String string, Throwable throwable) {
    doPrint(LEVEL_INFO, string, throwable);
  }

  @Override
  public void warn(String string, Throwable throwable) {
    doPrint(LEVEL_WARN, string, throwable);
  }

  @Override
  public void error(String string, Throwable throwable) {
    doPrint(LEVEL_ERROR, string, throwable);
  }

  @Override
  public void fatalError(String string, Throwable throwable) {
    doPrint(LEVEL_FATAL, string, throwable);
  }

  @Override
  public void debug(String message) {
    debug(message, null);
  }

  @Override
  public boolean isDebugEnabled() {
    return getThreshold() <= LEVEL_DEBUG;
  }

  @Override
  public void info(String message) {
    info(message, null);
  }

  @Override
  public boolean isInfoEnabled() {
    return getThreshold() <= LEVEL_INFO;
  }

  @Override
  public void warn(String message) {
    warn(message, null);
  }

  @Override
  public boolean isWarnEnabled() {
    return getThreshold() <= LEVEL_WARN;
  }

  @Override
  public void error(String message) {
    error(message, null);
  }

  @Override
  public boolean isErrorEnabled() {
    return getThreshold() <= LEVEL_ERROR;
  }

  @Override
  public void fatalError(String message) {
    fatalError(message, null);
  }

  @Override
  public boolean isFatalErrorEnabled() {
    return getThreshold() <= LEVEL_FATAL;
  }

  public void setThreshold(int threshold) {
    this.myThreshold = threshold;
  }

  @Override
  public int getThreshold() {
    return myThreshold;
  }

  @Override
  public Logger getChildLogger(String s) {
    return null;
  }

  @Override
  public String getName() {
    return toString();
  }
}
