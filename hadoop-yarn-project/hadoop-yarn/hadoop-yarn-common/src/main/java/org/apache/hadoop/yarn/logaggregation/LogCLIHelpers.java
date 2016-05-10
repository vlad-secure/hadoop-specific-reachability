/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.logaggregation;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.AccessDeniedException;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileContext;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.HarFs;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.security.AccessControlException;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogKey;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat.LogReader;
import org.apache.hadoop.yarn.util.ConverterUtils;

import com.google.common.annotations.VisibleForTesting;

public class LogCLIHelpers implements Configurable {

  private Configuration conf;

  @Private
  @VisibleForTesting
  public int dumpAContainersLogs(String appId, String containerId,
      String nodeId, String jobOwner) throws IOException {
    return dumpAContainersLogsForALogType(appId, containerId, nodeId, jobOwner,
      null);
  }

  @Private
  @VisibleForTesting
  /**
   * Return the owner for a given AppId
   * @param remoteRootLogDir
   * @param appId
   * @param bestGuess
   * @param conf
   * @return the owner or null
   * @throws IOException
   */
  public static String getOwnerForAppIdOrNull(
      ApplicationId appId, String bestGuess,
      Configuration conf) throws IOException {
    Path remoteRootLogDir = new Path(conf.get(
        YarnConfiguration.NM_REMOTE_APP_LOG_DIR,
        YarnConfiguration.DEFAULT_NM_REMOTE_APP_LOG_DIR));
    String suffix = LogAggregationUtils.getRemoteNodeLogDirSuffix(conf);
    Path fullPath = LogAggregationUtils.getRemoteAppLogDir(remoteRootLogDir,
        appId, bestGuess, suffix);
    FileContext fc =
        FileContext.getFileContext(remoteRootLogDir.toUri(), conf);
    String pathAccess = fullPath.toString();
    try {
      if (fc.util().exists(fullPath)) {
        return bestGuess;
      }
      Path toMatch = LogAggregationUtils.
          getRemoteAppLogDir(remoteRootLogDir, appId, "*", suffix);
      pathAccess = toMatch.toString();
      FileStatus[] matching  = fc.util().globStatus(toMatch);
      if (matching == null || matching.length != 1) {
        return null;
      }
      //fetch the user from the full path /app-logs/user[/suffix]/app_id
      Path parent = matching[0].getPath().getParent();
      //skip the suffix too
      if (suffix != null && !StringUtils.isEmpty(suffix)) {
        parent = parent.getParent();
      }
      return parent.getName();
    } catch (AccessControlException | AccessDeniedException ex) {
      logDirNoAccessPermission(pathAccess, bestGuess, ex.getMessage());
      return null;
    }
  }

  @Private
  @VisibleForTesting
  public int dumpAContainersLogsForALogType(String appId, String containerId,
      String nodeId, String jobOwner, List<String> logType)
      throws IOException {
    return dumpAContainersLogsForALogType(appId, containerId, nodeId, jobOwner,
        logType, true);
  }

  @Private
  @VisibleForTesting
  public int dumpAContainersLogsForALogType(String appId, String containerId,
      String nodeId, String jobOwner, List<String> logType,
      boolean outputFailure) throws IOException {
    ApplicationId applicationId = ConverterUtils.toApplicationId(appId);
    RemoteIterator<FileStatus> nodeFiles = getRemoteNodeFileDir(
        applicationId, jobOwner);
    if (nodeFiles == null) {
      return -1;
    }
    boolean foundContainerLogs = false;
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      String fileName = thisNodeFile.getPath().getName();
      if (fileName.equals(applicationId + ".har")) {
        Path p = new Path("har:///"
            + thisNodeFile.getPath().toUri().getRawPath());
        nodeFiles = HarFs.get(p.toUri(), conf).listStatusIterator(p);
        continue;
      }
      if (fileName.contains(LogAggregationUtils.getNodeString(nodeId))
          && !fileName.endsWith(LogAggregationUtils.TMP_FILE_SUFFIX)) {
        AggregatedLogFormat.LogReader reader = null;
        try {
          reader =
              new AggregatedLogFormat.LogReader(getConf(),
                thisNodeFile.getPath());
          if (logType == null) {
            if (dumpAContainerLogs(containerId, reader, System.out,
                thisNodeFile.getModificationTime()) > -1) {
              foundContainerLogs = true;
            }
          } else {
            if (dumpAContainerLogsForALogType(containerId, reader, System.out,
                thisNodeFile.getModificationTime(), logType) > -1) {
              foundContainerLogs = true;
            }
          }
        } finally {
          if (reader != null) {
            reader.close();
          }
        }
      }
    }
    if (!foundContainerLogs && outputFailure) {
      containerLogNotFound(containerId);
      return -1;
    }
    return 0;
  }

  @Private
  public int dumpAContainersLogsForALogTypeWithoutNodeId(String appId,
      String containerId, String jobOwner, List<String> logType)
    throws IOException {
    ApplicationId applicationId = ConverterUtils.toApplicationId(appId);
    RemoteIterator<FileStatus> nodeFiles = getRemoteNodeFileDir(
        applicationId, jobOwner);
    if (nodeFiles == null) {
      return -1;
    }
    boolean foundContainerLogs = false;
    while(nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      if (!thisNodeFile.getPath().getName().endsWith(
          LogAggregationUtils.TMP_FILE_SUFFIX)) {
        AggregatedLogFormat.LogReader reader = null;
        try {
          reader =
              new AggregatedLogFormat.LogReader(getConf(),
              thisNodeFile.getPath());
          if (logType == null) {
            if (dumpAContainerLogs(containerId, reader, System.out,
                thisNodeFile.getModificationTime()) > -1) {
              foundContainerLogs = true;
            }
          } else {
            if (dumpAContainerLogsForALogType(containerId, reader, System.out,
                thisNodeFile.getModificationTime(), logType) > -1) {
              foundContainerLogs = true;
            }
          }
        } finally {
          if (reader != null) {
            reader.close();
          }
        }
      }
    }
    if (!foundContainerLogs) {
      containerLogNotFound(containerId);
      return -1;
    }
    return 0;
  }
  @Private
  public int dumpAContainerLogs(String containerIdStr,
      AggregatedLogFormat.LogReader reader, PrintStream out,
      long logUploadedTime) throws IOException {
    DataInputStream valueStream;
    LogKey key = new LogKey();
    valueStream = reader.next(key);

    while (valueStream != null && !key.toString().equals(containerIdStr)) {
      // Next container
      key = new LogKey();
      valueStream = reader.next(key);
    }

    if (valueStream == null) {
      return -1;
    }

    boolean foundContainerLogs = false;
    while (true) {
      try {
        LogReader.readAContainerLogsForALogType(valueStream, out,
            logUploadedTime);
        foundContainerLogs = true;
      } catch (EOFException eof) {
        break;
      }
    }
    if (foundContainerLogs) {
      return 0;
    }
    return -1;
  }

  @Private
  public int dumpAContainerLogsForALogType(String containerIdStr,
      AggregatedLogFormat.LogReader reader, PrintStream out,
      long logUploadedTime, List<String> logType) throws IOException {
    DataInputStream valueStream;
    LogKey key = new LogKey();
    valueStream = reader.next(key);

    while (valueStream != null && !key.toString().equals(containerIdStr)) {
      // Next container
      key = new LogKey();
      valueStream = reader.next(key);
    }

    if (valueStream == null) {
      return -1;
    }

    boolean foundContainerLogs = false;
    while (true) {
      try {
        int result = LogReader.readContainerLogsForALogType(
            valueStream, out, logUploadedTime, logType);
        if (result == 0) {
          foundContainerLogs = true;
        }
      } catch (EOFException eof) {
        break;
      }
    }

    if (foundContainerLogs) {
      return 0;
    }
    return -1;
  }

  @Private
  public int dumpAllContainersLogs(ApplicationId appId, String appOwner,
      PrintStream out) throws IOException {
    RemoteIterator<FileStatus> nodeFiles = getRemoteNodeFileDir(
        appId, appOwner);
    if (nodeFiles == null) {
      return -1;
    }
    boolean foundAnyLogs = false;
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      if (thisNodeFile.getPath().getName().equals(appId + ".har")) {
        Path p = new Path("har:///"
            + thisNodeFile.getPath().toUri().getRawPath());
        nodeFiles = HarFs.get(p.toUri(), conf).listStatusIterator(p);
        continue;
      }
      if (!thisNodeFile.getPath().getName()
          .endsWith(LogAggregationUtils.TMP_FILE_SUFFIX)) {
        AggregatedLogFormat.LogReader reader =
            new AggregatedLogFormat.LogReader(getConf(),
                thisNodeFile.getPath());
        try {

          DataInputStream valueStream;
          LogKey key = new LogKey();
          valueStream = reader.next(key);

          while (valueStream != null) {

            String containerString =
                "\n\nContainer: " + key + " on "
                + thisNodeFile.getPath().getName();
            out.println(containerString);
            out.println(StringUtils.repeat("=", containerString.length()));
            while (true) {
              try {
                LogReader.readAContainerLogsForALogType(valueStream, out,
                    thisNodeFile.getModificationTime());
                foundAnyLogs = true;
              } catch (EOFException eof) {
                break;
              }
            }

            // Next container
            key = new LogKey();
            valueStream = reader.next(key);
          }
        } finally {
          reader.close();
        }
      }
    }
    if (!foundAnyLogs) {
      emptyLogDir(getRemoteAppLogDir(appId, appOwner).toString());
      return -1;
    }
    return 0;
  }

  @Private
  public void printLogMetadata(ApplicationId appId,
      String containerIdStr, String nodeId, String appOwner,
      PrintStream out, PrintStream err)
      throws IOException {
    boolean getAllContainers = (containerIdStr == null);
    String nodeIdStr = (nodeId == null) ? null
        : LogAggregationUtils.getNodeString(nodeId);
    RemoteIterator<FileStatus> nodeFiles = getRemoteNodeFileDir(
        appId, appOwner);
    if (nodeFiles == null) {
      return;
    }
    boolean foundAnyLogs = false;
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      if (nodeIdStr != null) {
        if (!thisNodeFile.getPath().getName().contains(nodeIdStr)) {
          continue;
        }
      }
      if (!thisNodeFile.getPath().getName()
          .endsWith(LogAggregationUtils.TMP_FILE_SUFFIX)) {
        AggregatedLogFormat.LogReader reader =
            new AggregatedLogFormat.LogReader(getConf(),
            thisNodeFile.getPath());
        try {
          DataInputStream valueStream;
          LogKey key = new LogKey();
          valueStream = reader.next(key);
          while (valueStream != null) {
            if (getAllContainers || (key.toString().equals(containerIdStr))) {
              String containerString =
                  "\n\nContainer: " + key + " on "
                  + thisNodeFile.getPath().getName();
              out.println(containerString);
              out.println("Log Upload Time:"
                  + thisNodeFile.getModificationTime());
              out.println(StringUtils.repeat("=", containerString.length()));
              while (true) {
                try {
                  LogReader.readContainerMetaDataAndSkipData(valueStream, out);
                } catch (EOFException eof) {
                  break;
                }
              }
              foundAnyLogs = true;
              if (!getAllContainers) {
                break;
              }
            }
            // Next container
            key = new LogKey();
            valueStream = reader.next(key);
          }
        } finally {
          reader.close();
        }
      }
    }
    if (!foundAnyLogs) {
      if (containerIdStr != null && nodeId != null) {
        err.println("The container " + containerIdStr + " couldn't be found "
            + "on the node specified: " + nodeId);
      } else if (nodeId != null) {
        err.println("Can not find log metadata for any containers on "
            + nodeId);
      } else if (containerIdStr != null) {
        err.println("Can not find log metadata for container: "
            + containerIdStr);
      }
    }
  }

  @Private
  public void printNodesList(ApplicationId appId, String appOwner,
      PrintStream out, PrintStream err) throws IOException {
    RemoteIterator<FileStatus> nodeFiles = getRemoteNodeFileDir(
        appId, appOwner);
    if (nodeFiles == null) {
      return;
    }
    boolean foundNode = false;
    StringBuilder sb = new StringBuilder();
    while (nodeFiles.hasNext()) {
      FileStatus thisNodeFile = nodeFiles.next();
      sb.append(thisNodeFile.getPath().getName() + "\n");
      foundNode = true;
    }
    if (!foundNode) {
      err.println("No nodes found that aggregated logs for "
          + "the application: " + appId);
    } else {
      out.println(sb.toString());
    }
  }

  private RemoteIterator<FileStatus> getRemoteNodeFileDir(ApplicationId appId,
      String appOwner) throws IOException {
    Path remoteAppLogDir = getRemoteAppLogDir(appId, appOwner);
    RemoteIterator<FileStatus> nodeFiles = null;
    try {
      Path qualifiedLogDir =
          FileContext.getFileContext(getConf()).makeQualified(remoteAppLogDir);
      nodeFiles = FileContext.getFileContext(qualifiedLogDir.toUri(),
          getConf()).listStatus(remoteAppLogDir);
    } catch (FileNotFoundException fnf) {
      logDirNotExist(remoteAppLogDir.toString());
    } catch (AccessControlException | AccessDeniedException ace) {
      logDirNoAccessPermission(remoteAppLogDir.toString(), appOwner,
        ace.getMessage());
    }
    return nodeFiles;
  }

  private Path getRemoteAppLogDir(ApplicationId appId, String appOwner) {
    Path remoteRootLogDir = new Path(getConf().get(
        YarnConfiguration.NM_REMOTE_APP_LOG_DIR,
        YarnConfiguration.DEFAULT_NM_REMOTE_APP_LOG_DIR));
    String user = appOwner;
    String logDirSuffix = LogAggregationUtils
        .getRemoteNodeLogDirSuffix(getConf());
    // TODO Change this to get a list of files from the LAS.
    return LogAggregationUtils.getRemoteAppLogDir(
        remoteRootLogDir, appId, user, logDirSuffix);
  }

  @Override
  public void setConf(Configuration conf) {
    this.conf = conf;
  }

  @Override
  public Configuration getConf() {
    return this.conf;
  }

  private static void containerLogNotFound(String containerId) {
    System.err.println("Logs for container " + containerId
        + " are not present in this log-file.");
  }

  private static void logDirNotExist(String remoteAppLogDir) {
    System.err.println(remoteAppLogDir + " does not exist.");
    System.err.println("Log aggregation has not completed or is not enabled.");
  }

  private static void emptyLogDir(String remoteAppLogDir) {
    System.err.println(remoteAppLogDir + " does not have any log files.");
  }

  private static void logDirNoAccessPermission(String remoteAppLogDir,
      String appOwner, String errorMessage) throws IOException {
    System.err.println("Guessed logs' owner is " + appOwner
        + " and current user "
        + UserGroupInformation.getCurrentUser().getUserName() + " does not "
        + "have permission to access " + remoteAppLogDir
        + ". Error message found: " + errorMessage);
  }
}
