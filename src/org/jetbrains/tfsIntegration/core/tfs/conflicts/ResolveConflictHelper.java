/*
 * Copyright 2000-2008 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.tfsIntegration.core.tfs.conflicts;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.CurrentContentRevision;
import com.intellij.openapi.vcs.update.FileGroup;
import com.intellij.openapi.vcs.update.UpdatedFiles;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.vcsUtil.VcsRunnable;
import com.intellij.vcsUtil.VcsUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.TFSVcs;
import org.jetbrains.tfsIntegration.core.revision.TFSContentRevision;
import org.jetbrains.tfsIntegration.core.tfs.*;
import org.jetbrains.tfsIntegration.core.tfs.operations.ApplyGetOperations;
import org.jetbrains.tfsIntegration.exceptions.TfsException;
import org.jetbrains.tfsIntegration.stubs.versioncontrol.repository.*;
import org.jetbrains.tfsIntegration.ui.ContentTriplet;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;

public class ResolveConflictHelper {
  private final @NotNull Project myProject;
  private final @Nullable UpdatedFiles myUpdatedFiles;
  private final Map<Conflict, WorkspaceInfo> myConflict2Workspace = new HashMap<Conflict, WorkspaceInfo>();

  public ResolveConflictHelper(final Project project,
                               Map<WorkspaceInfo, Collection<Conflict>> workspace2Conflicts,
                               final UpdatedFiles updatedFiles) {
    myProject = project;

    for (Map.Entry<WorkspaceInfo, Collection<Conflict>> e : workspace2Conflicts.entrySet()) {
      for (Conflict conflict : e.getValue()) {
        myConflict2Workspace.put(conflict, e.getKey());
      }
    }
    myUpdatedFiles = updatedFiles;
  }

  public void acceptMerge(final @NotNull Conflict conflict) throws TfsException, VcsException {
    TFSVcs.assertTrue(canMerge(conflict));

    final WorkspaceInfo workspace = myConflict2Workspace.get(conflict);

    final String localPath = conflict.getSrclitem() != null ? conflict.getSrclitem() : conflict.getTgtlitem();

    final ContentTriplet contentTriplet = new ContentTriplet();
    VcsRunnable runnable = new VcsRunnable() {
      public void run() throws VcsException {
        // virtual file can be out of the current project so force its discovery
        TfsFileUtil.refreshAndFindFile(localPath);
        try {
          if (conflict.getYtype() == ItemType.File) {
            String original = TFSContentRevision.create(workspace, conflict.getBitemid(), conflict.getBver()).getContent();
            contentTriplet.baseContent = original != null ? original : "";
            String current = CurrentContentRevision.create(VcsUtil.getFilePath(localPath)).getContent();
            contentTriplet.localContent = current != null ? current : "";
            String last = TFSContentRevision.create(workspace, conflict.getTitemid(), conflict.getTver()).getContent();
            contentTriplet.serverContent = last != null ? last : "";
          }
        }
        catch (TfsException e) {
          throw new VcsException("Unable to get content for item " + localPath);
        }
      }
    };

    if (isContentConflict(conflict)) {
      // we will need content only if  it conflicts 
      VcsUtil.runVcsProcessWithProgress(runnable, "Prepare merge data...", false, myProject);
    }

    // merge names
    final String localName;
    if (isNameConflict(conflict)) {
      // TODO proper type?
      final String mergedServerPath = ConflictsEnvironment.getNameMerger().mergeName(workspace, conflict);
      if (mergedServerPath == null) {
        // user cancelled
        return;
      }
      FilePath mergedLocalPath = workspace.findLocalPathByServerPath(mergedServerPath, conflict.getYtype() == ItemType.Folder);
      TFSVcs.assertTrue(mergedLocalPath != null);
      localName = VersionControlPath.toTfsRepresentation(mergedLocalPath);
    }
    else {
      localName = conflict.getTgtlitem();
    }

    // merge content
    if (isContentConflict(conflict)) {
      TFSVcs.assertTrue(conflict.getYtype() == ItemType.File);
      final VirtualFile vFile = VcsUtil.getVirtualFile(localPath);
      if (vFile != null) {
        try {
          TfsFileUtil.setReadOnlyInEventDispathThread(vFile, false);
          ConflictsEnvironment.getContentMerger().mergeContent(conflict, contentTriplet, myProject, vFile, localName);
        }
        catch (IOException e) {
          throw new VcsException(e);
        }
      }
      else {
        String errorMessage = MessageFormat.format("File ''{0}'' is missing", localPath);
        throw new VcsException(errorMessage);
      }
    }
    conflictResolved(conflict, Resolution.AcceptMerge, localName);
  }

  public void acceptYours(final @NotNull Conflict conflict) throws TfsException, VcsException {
    conflictResolved(conflict, Resolution.AcceptYours, null);
    // no actions will be executed so fill UpdatedFiles explicitly
    if (myUpdatedFiles != null) {
      String localPath = conflict.getSrclitem() != null ? conflict.getSrclitem() : conflict.getTgtlitem();
      myUpdatedFiles.getGroupById(FileGroup.SKIPPED_ID).add(localPath);
    }
  }

  public void acceptTheirs(final @NotNull Conflict conflict) throws TfsException, IOException, VcsException {
    conflictResolved(conflict, Resolution.AcceptTheirs, null);
  }

  public void skip(final @NotNull Conflict conflict) {
    if (myUpdatedFiles != null) {
      String localPath = conflict.getSrclitem() != null ? conflict.getSrclitem() : conflict.getTgtlitem();
      myUpdatedFiles.getGroupById(FileGroup.SKIPPED_ID).add(localPath);
    }
  }

  public Collection<Conflict> getConflicts() {
    return Collections.unmodifiableCollection(myConflict2Workspace.keySet());
  }

  public static boolean canMerge(final @NotNull Conflict conflict) {
    if (conflict.getSrclitem() == null) {
      return false;
    }

    final EnumMask<ChangeType> yourChange = EnumMask.fromString(ChangeType.class, conflict.getYchg());
    final EnumMask<ChangeType> yourLocalChange = EnumMask.fromString(ChangeType.class, conflict.getYlchg());
    final EnumMask<ChangeType> baseChange = EnumMask.fromString(ChangeType.class, conflict.getBchg());

    boolean isNamespaceConflict =
      ((conflict.getCtype().equals(ConflictType.Get)) || (conflict.getCtype().equals(ConflictType.Checkin))) && conflict.getIsnamecflict();
    if (!isNamespaceConflict) {
      boolean yourRenamedOrModified = yourChange.containsAny(ChangeType.Rename, ChangeType.Edit);
      boolean baseRenamedOrModified = baseChange.containsAny(ChangeType.Rename, ChangeType.Edit);
      if (yourRenamedOrModified && baseRenamedOrModified) {
        return true;
      }
    }
    if ((conflict.getYtype() != ItemType.Folder) && !isNamespaceConflict) {
      if (conflict.getCtype().equals(ConflictType.Merge) && baseChange.contains(ChangeType.Edit)) {
        if (yourLocalChange.contains(ChangeType.Edit)) {
          return true;
        }
        if (conflict.getIsforced()) {
          return true;
        }
        if ((conflict.getTlmver() != conflict.getBver()) || (conflict.getYlmver() != conflict.getYver())) {
          return true;
        }
      }
    }
    return false;
  }

  private void conflictResolved(final Conflict conflict, final Resolution resolution, final String newLocalPath)
    throws TfsException, VcsException {
    WorkspaceInfo workspace = myConflict2Workspace.get(conflict);

    VersionControlServer.ResolveConflictParams resolveConflictParams =
      new VersionControlServer.ResolveConflictParams(conflict.getCid(), resolution, LockLevel.Unchanged, -2, newLocalPath);

    ResolveResponse response =
      workspace.getServer().getVCS().resolveConflict(workspace.getName(), workspace.getOwnerName(), resolveConflictParams);

    if (response.getResolveResult().getGetOperation() != null) {
      ApplyGetOperations.DownloadMode downloadMode =
        resolution == Resolution.AcceptTheirs ? ApplyGetOperations.DownloadMode.FORCE : ApplyGetOperations.DownloadMode.MERGE;

      final Collection<VcsException> applyErrors = ApplyGetOperations
        .execute(myProject, workspace, Arrays.asList(response.getResolveResult().getGetOperation()), null, myUpdatedFiles, downloadMode);
      if (!applyErrors.isEmpty()) {
        throw TfsUtil.collectExceptions(applyErrors);
      }
    }

    if (response.getUndoOperations().getGetOperation() != null) {
      final Collection<VcsException> applyErrors = ApplyGetOperations
        .execute(myProject, workspace, Arrays.asList(response.getUndoOperations().getGetOperation()), null, myUpdatedFiles,
                 ApplyGetOperations.DownloadMode.FORCE);
      if (!applyErrors.isEmpty()) {
        throw TfsUtil.collectExceptions(applyErrors);
      }
    }

    if (response.getResolveResult().getGetOperation() == null &&
        response.getUndoOperations().getGetOperation() == null &&
        newLocalPath != null) {
      // no actions will be executed so fill UpdatedFiles explicitly
      if (myUpdatedFiles != null) {
        myUpdatedFiles.getGroupById(FileGroup.MERGED_ID).add(newLocalPath);
      }
    }
    myConflict2Workspace.remove(conflict);
  }

  private static boolean isNameConflict(final @NotNull Conflict conflict) {
    final EnumMask<ChangeType> yourChange = EnumMask.fromString(ChangeType.class, conflict.getYchg());
    final EnumMask<ChangeType> baseChange = EnumMask.fromString(ChangeType.class, conflict.getBchg());
    return yourChange.contains(ChangeType.Rename) || baseChange.contains(ChangeType.Rename);
  }

  private static boolean isContentConflict(final @NotNull Conflict conflict) {
    final EnumMask<ChangeType> yourChange = EnumMask.fromString(ChangeType.class, conflict.getYchg());
    final EnumMask<ChangeType> baseChange = EnumMask.fromString(ChangeType.class, conflict.getBchg());
    return yourChange.contains(ChangeType.Edit) || baseChange.contains(ChangeType.Edit);
  }

  public static Collection<Conflict> getUnresolvedConflicts(Collection<Conflict> conflicts) {
    Collection<Conflict> result = new ArrayList<Conflict>();
    for (Conflict c : conflicts) {
      if (!c.getIsresolved()) {
        TFSVcs.assertTrue(c.getCid() != 0);
        result.add(c);
      }
    }
    return result;
  }

}
