package com.itemis.maven.plugins.unleash.scm.providers;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.jgit.api.AddCommand;
import org.eclipse.jgit.api.CheckoutCommand;
import org.eclipse.jgit.api.CloneCommand;
import org.eclipse.jgit.api.CommitCommand;
import org.eclipse.jgit.api.CreateBranchCommand;
import org.eclipse.jgit.api.CreateBranchCommand.SetupUpstreamMode;
import org.eclipse.jgit.api.DeleteTagCommand;
import org.eclipse.jgit.api.FetchCommand;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.LsRemoteCommand;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeCommand.FastForwardMode;
import org.eclipse.jgit.api.PushCommand;
import org.eclipse.jgit.api.ResetCommand.ResetType;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.TagCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.merge.MergeStrategy;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.RemoteRefUpdate.Status;
import org.eclipse.jgit.transport.TagOpt;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import com.google.common.base.Joiner;
import com.google.common.base.Objects;
import com.google.common.base.Optional;
import com.google.common.collect.Lists;
import com.itemis.maven.plugins.unleash.scm.ScmException;
import com.itemis.maven.plugins.unleash.scm.ScmOperation;
import com.itemis.maven.plugins.unleash.scm.ScmProvider;
import com.itemis.maven.plugins.unleash.scm.annotations.ScmProviderType;
import com.itemis.maven.plugins.unleash.scm.providers.merge.UnleashGitFullMergeStrategy;
import com.itemis.maven.plugins.unleash.scm.providers.util.GitUtil;
import com.itemis.maven.plugins.unleash.scm.requests.BranchRequest;
import com.itemis.maven.plugins.unleash.scm.requests.CheckoutRequest;
import com.itemis.maven.plugins.unleash.scm.requests.CommitRequest;
import com.itemis.maven.plugins.unleash.scm.requests.DeleteBranchRequest;
import com.itemis.maven.plugins.unleash.scm.requests.DeleteTagRequest;
import com.itemis.maven.plugins.unleash.scm.requests.PushRequest;
import com.itemis.maven.plugins.unleash.scm.requests.RevertCommitsRequest;
import com.itemis.maven.plugins.unleash.scm.requests.TagRequest;
import com.itemis.maven.plugins.unleash.scm.requests.UpdateRequest;

@ScmProviderType("git")
public class ScmProviderGit implements ScmProvider {
  private static final String LOG_PREFIX = "Git - ";

  private Logger log;
  private Git git;
  private PersonIdent personIdent;
  private CredentialsProvider credentialsProvider;
  private File workingDir;
  private List<String> additionalThingsToPush;
  private GitUtil util;

  @Override
  public void initialize(File workingDirectory, Optional<Logger> logger, Optional<String> username,
      Optional<String> password) {
    this.log = logger.or(Logger.getLogger(ScmProvider.class.getName()));
    this.workingDir = workingDirectory;
    this.additionalThingsToPush = Lists.newArrayList();

    if (workingDirectory.exists() && workingDirectory.isDirectory() && workingDirectory.list().length > 0) {
      try {
        FileRepositoryBuilder builder = new FileRepositoryBuilder();
        Repository repo = builder.findGitDir(this.workingDir).build();
        this.git = Git.wrap(repo);
        this.personIdent = new PersonIdent(repo);
        this.util = new GitUtil(this.git);
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    if (username.isPresent()) {
      this.credentialsProvider = new UsernamePasswordCredentialsProvider(username.get(), password.or(""));
    }
  }

  @Override
  public void close() {
    if (this.git != null) {
      this.git.close();
    }
  }

  @Override
  public void checkout(CheckoutRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Checking out from remote repository.");
    }

    // check if local working dir is empty
    if (this.workingDir.exists() && this.workingDir.list().length > 0) {
      // IDEA maybe also support checkout into non-empty WC. -> create new remote in case of differences and checkout
      // branch, ...
      throw new ScmException(ScmOperation.CHECKOUT,
          "Unable to checkout remote repository '" + request.getRemoteRepositoryUrl() + "'. Local working directory '"
              + this.workingDir.getAbsolutePath() + "' is not empty!");
    }

    try {
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Cloning remote repository.");
        StringBuilder message = new StringBuilder(LOG_PREFIX).append("Clone info:\n");
        message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
        message.append("\t- REMOTE_URL: ").append(request.getRemoteRepositoryUrl());
        this.log.fine(message.toString());
      }

      CloneCommand clone = Git.cloneRepository().setDirectory(this.workingDir).setURI(request.getRemoteRepositoryUrl())
          .setCredentialsProvider(this.credentialsProvider);
      if (!request.checkoutWholeRepository()) {
        clone.setNoCheckout(true);
      }
      this.git = clone.call();
      this.util = new GitUtil(this.git);

      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Cloning remote repository finished successfully.\n");
      }
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.CHECKOUT,
          "Unable to clone remote git repository '" + request.getRemoteRepositoryUrl()
              + "' into local working directory '" + this.workingDir.getAbsolutePath() + "'.",
          e);
    }

    if (!request.checkoutWholeRepository()) {
      // 1. checkout single filepaths from the repository
      String revision = request.getRevision().or(Constants.HEAD);
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Checking out single files only.");
        StringBuilder message = new StringBuilder(LOG_PREFIX).append("Checkout info:\n");
        message.append("\t- FILES: ").append(Joiner.on(',').join(request.getPathsToCheckout())).append('\n');
        message.append("\t- REVISION: ").append(revision);
        this.log.fine(message.toString());
      }

      try {
        CheckoutCommand checkout = this.git.checkout().setStartPoint(revision).setAllPaths(false);
        for (String path : request.getPathsToCheckout()) {
          checkout.addPath(path);
        }
        checkout.call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.CHECKOUT,
            "Unable to checkout commit with id '" + request.getRevision().get() + "' into local working directory '"
                + this.workingDir.getAbsolutePath() + "'.",
            e);
      }
    } else if (request.checkoutBranch()) {
      // 2. checkout a specific branch (and even a commit from this branch)
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Checking out branch" + request.getBranch().get() + ".");
      }

      if (hasBranch(request.getBranch().get())) {
        RevCommit startPoint = this.util.resolveCommit(request.getRevision(), request.getBranch());
        if (this.log.isLoggable(Level.FINE)) {
          StringBuilder message = new StringBuilder(LOG_PREFIX).append("Checkout info:\n");
          message.append("\t- BRANCH: ").append(request.getBranch().get()).append('\n');
          message.append("\t- REVISION: ").append(startPoint.getName());
          this.log.fine(message.toString());
        }

        try {
          CheckoutCommand checkout = this.git.checkout().setName(request.getBranch().get()).setCreateBranch(true)
              .setUpstreamMode(SetupUpstreamMode.SET_UPSTREAM).setStartPoint(startPoint);
          checkout.call();
        } catch (GitAPIException e) {
          throw new ScmException(ScmOperation.CHECKOUT, "Unable to checkout '" + request.getBranch().get()
              + "' into local working directory '" + this.workingDir.getAbsolutePath() + "'.", e);
        }
      } else {
        if (this.log.isLoggable(Level.WARNING)) {
          StringBuilder message = new StringBuilder(LOG_PREFIX)
              .append("The remote repository contains no branch with name '").append(request.getBranch().get())
              .append("'. Staying on current branch '").append(this.util.getCurrentBranchName()).append("'.");
          this.log.warning(message.toString());
        }
      }
    } else if (request.checkoutTag()) {
      // 3. checkout a specific tag
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Checking out tag" + request.getTag().get() + ".");
      }
      if (hasTag(request.getTag().get())) {
        try {
          CheckoutCommand checkout = this.git.checkout().setName(request.getTag().get());
          checkout.call();
        } catch (GitAPIException e) {
          throw new ScmException(ScmOperation.CHECKOUT, "Unable to checkout tag '" + request.getTag().get()
              + "' into local working directory '" + this.workingDir.getAbsolutePath() + "'.", e);
        }
      } else {
        if (this.log.isLoggable(Level.WARNING)) {
          StringBuilder message = new StringBuilder(LOG_PREFIX)
              .append("The remote repository contains no tag with name '").append(request.getTag().get())
              .append("'. Staying on current branch '").append(this.util.getCurrentBranchName()).append("'.");
          this.log.warning(message.toString());
        }
      }
    } else if (request.getRevision().isPresent()) {
      // 4. checkout a specific commit if no branch or tag is specified
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Checking out a specific revision from current branch.");
        StringBuilder message = new StringBuilder(LOG_PREFIX).append("Checkout info:\n");
        message.append("\t- BRANCH: ").append(this.util.getCurrentBranchName()).append('\n');
        message.append("\t- REVISION: ").append(request.getRevision().get());
        this.log.fine(message.toString());
      }
      try {
        CheckoutCommand checkout = this.git.checkout().setName(request.getRevision().get());
        checkout.call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.CHECKOUT,
            "Unable to checkout commit with id '" + request.getRevision().get() + "' into local working directory '"
                + this.workingDir.getAbsolutePath() + "'.",
            e);
      }
    }

    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Checkout finished successfully!");
    }
  }

  @Override
  public String commit(CommitRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Committing local changes.");
    }

    // FIXME check state prior to committing to avoid empty ones!
    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX + "Commit info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy()).append('\n');
      message.append("\t- PUSH: ").append(request.push()).append('\n');
      message.append("\t- COMMIT_ALL_CHANGES: ").append(request.commitAllChanges());
      if (!request.commitAllChanges()) {
        message.append("\n\t- FILES: ").append(Joiner.on(',').join(request.getPathsToCommit()));
      }
      this.log.fine(message.toString());
    }

    // add all changes to be committed (either everything or the specified paths)
    AddCommand add = this.git.add();
    if (request.commitAllChanges()) {
      add.addFilepattern(".");
    } else {
      for (String path : request.getPathsToCommit()) {
        add.addFilepattern(path);
      }
    }
    try {
      add.call();
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.COMMIT, "Unable to add local changes to the index.", e);
    }

    // commit all added changes
    CommitCommand commit = this.git.commit().setMessage(request.getMessage()).setCommitter(this.personIdent);
    if (request.commitAllChanges()) {
      commit.setAll(true);
    } else {
      for (String path : request.getPathsToCommit()) {
        commit.setOnly(path);
      }
    }

    String newRevision = null;
    try {
      RevCommit result = commit.call();
      newRevision = result.getName();
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.DELETE_TAG, "Could not commit chanhes of local repository.", e);
    }

    if (request.push()) {
      PushRequest pr = PushRequest.builder().mergeStrategy(request.getMergeStrategy())
          .mergeClient(request.getMergeClient().orNull()).build();
      push(pr);
      newRevision = getLatestRemoteRevision();
    }

    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Commit finished successfully. New revision is: " + newRevision);
    }

    return newRevision;
  }

  @Override
  public String push(PushRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Pushing local changes to remote repository.");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteBranchName = this.util.getRemoteBranchName(localBranchName);
    String remoteName = this.util.getRemoteName(localBranchName);
    String remoteUrl = this.util.getConnectionUrlOfRemote(remoteName);

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX + "Push info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- LOCAL_BRANCH: ").append(localBranchName).append('\n');
      message.append("\t- REMOTE_BRANCH: ").append(remoteBranchName).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_URL: ").append(remoteUrl).append('\n');
      message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy()).append('\n');
      this.log.fine(message.toString());
    }

    // 1. update the local repository before pushing to remote
    UpdateRequest ur = UpdateRequest.builder().mergeStrategy(request.getMergeStrategy())
        .mergeClient(request.getMergeClient().orNull()).build();
    update(ur);

    try {
      // 2. push local changes to remote repository
      PushCommand push = this.git.push().setRemote(remoteName).setCredentialsProvider(this.credentialsProvider)
          .setAtomic(true).setPushAll().setPushTags();
      for (String additional : this.additionalThingsToPush) {
        push.add(additional);
      }
      Iterable<PushResult> results = push.call();

      Status failureStatus = null;
      String reason = null;
      resultLoop: for (PushResult result : results) {
        Collection<RemoteRefUpdate> updates = result.getRemoteUpdates();
        for (RemoteRefUpdate update : updates) {
          if (update.getStatus() != Status.OK && update.getStatus() != Status.UP_TO_DATE) {
            failureStatus = update.getStatus();
            reason = update.getMessage();
            break resultLoop;
          }
        }
      }

      if (failureStatus != null) {
        StringBuilder message = new StringBuilder(
            "Could not push local changes to the remote repository due to the following error: [").append(failureStatus)
                .append("] ");
        if (reason != null) {
          message.append(reason);
        }
        throw new ScmException(ScmOperation.PUSH, message.toString());
      }
      this.additionalThingsToPush.clear();
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.PUSH, "Could not push local commits to remote repository", e);
    }

    String newRemoteRevision = getLatestRemoteRevision();
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Push finished successfully. New remote revision is: " + newRemoteRevision);
    }
    return newRemoteRevision;
  }

  @Override
  public String update(UpdateRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Updating local repository with remote changes.");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteBranchName = this.util.getRemoteBranchName(localBranchName);
    String remoteName = this.util.getRemoteName(localBranchName);
    String connectionUrl = this.util.getConnectionUrlOfRemote(remoteName);
    // TODO update paths only?

    if (this.log.isLoggable(Level.FINE)) {
      this.log.fine(LOG_PREFIX + "Fetching remote updates.");
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Fetch info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_URL: ").append(connectionUrl);
      this.log.fine(message.toString());
    }

    try {
      FetchCommand fetch = this.git.fetch().setRemote(remoteName).setCredentialsProvider(this.credentialsProvider)
          .setTagOpt(TagOpt.AUTO_FOLLOW).setRemoveDeletedRefs(true);
      fetch.call();
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.UPDATE,
          "Could not fetch changes from Git remote '" + remoteName + " [" + connectionUrl + "]'.", e);
    }

    MergeCommand merge = this.git.merge().setFastForward(FastForwardMode.FF).setCommit(true).setMessage("Merge");
    switch (request.getMergeStrategy()) {
      case USE_LOCAL:
        merge.setStrategy(MergeStrategy.OURS);
        break;
      case USE_REMOTE:
        merge.setStrategy(MergeStrategy.THEIRS);
        break;
      case FULL_MERGE:
        merge.setStrategy(new UnleashGitFullMergeStrategy(request.getMergeClient().get()));
        break;
      case DO_NOT_MERGE:
        // nothing to do here!
        break;
      default:
        throw new UnsupportedOperationException(
            "Unknown merge strategy! API and implementation versions are incompatible!");
    }

    String requestedRevision = request.getTargetRevision().or(getLatestRemoteRevision());
    try {
      ObjectId revision = this.git.getRepository().resolve(requestedRevision);
      merge.include(revision);
    } catch (Exception e) {
      throw new ScmException(ScmOperation.MERGE, "No Git commit id found for String '" + requestedRevision + "'.", e);
    }

    if (this.log.isLoggable(Level.FINE)) {
      this.log.fine(LOG_PREFIX + "Merging remote updates into local working copy.");
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Merge info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_BRANCH: ").append(remoteBranchName).append('\n');
      message.append("\t- REMOTE_REVISION: ").append(requestedRevision).append('\n');
      message.append("\t- LOCAL_BRANCH: ").append(localBranchName).append('\n');
      message.append("\t- LOCAL_REVISION: ").append(getLocalRevision()).append('\n');
      message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy());
      this.log.fine(message.toString());
    }

    try {
      merge.call();
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.MERGE, "Could not merge changes fetched from Git remote '" + remoteName + " ["
          + connectionUrl + "]' into local working copy '" + this.workingDir.getAbsolutePath() + "'.", e);
    }

    String newRevision = getLocalRevision();
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Update finished successfully. New revision is: " + newRevision);
    }
    return newRevision;
  }

  @Override
  public String tag(TagRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Tagging local repository.");
    }

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Tag info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- TAG_NAME: ").append(request.getTagName()).append('\n');
      message.append("\t- USE_WORKING_COPY: ").append(request.tagFromWorkingCopy()).append('\n');
      if (request.tagFromWorkingCopy()) {
        message.append("\t- COMMIT_BEFORE_TAGGING: ").append(request.commitBeforeTagging()).append('\n');
        message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy());
      } else {
        message.append("\t- REMOTE_URL: ").append(request.getRemoteRepositoryUrl()).append('\n');
        message.append("\t- REVISION: ").append(request.getRevision());
      }
      this.log.fine(message.toString());
    }

    if (request.tagFromWorkingCopy()) {
      // 1. commit the changes (no merging because we stay local!)
      String preTagCommitMessage = request.getPreTagCommitMessage()
          .or("Preparation for tag creation (Tag name: '" + request.getTagName() + "').");
      CommitRequest cr = CommitRequest.builder().message(preTagCommitMessage).build();
      commit(cr);

      try {
        // 2. tag local revision
        TagCommand tag = this.git.tag().setName(request.getTagName()).setMessage(request.getMessage())
            .setAnnotated(true).setTagger(this.personIdent);
        tag.call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.TAG, "An error occurred during local Git tag creation.", e);
      }

      if (!request.commitBeforeTagging()) {
        try {
          // 3. deletes the local commit that had been done for tag creation.
          this.git.reset().setMode(ResetType.MIXED).setRef(Constants.HEAD + "~1").call();
        } catch (GitAPIException e) {
          throw new ScmException(ScmOperation.TAG,
              "An error occurred during local commit resetting (no pre-tag commit was requested).", e);
        }
      }

      String newRevision;
      String tagPushName = GitUtil.TAG_NAME_PREFIX + request.getTagName();
      if (request.push()) {
        if (request.commitBeforeTagging()) {
          // if the commit shall be kept, push everything with update of the local WC!
          PushRequest pr = PushRequest.builder().mergeStrategy(request.getMergeStrategy())
              .mergeClient(request.getMergeClient().orNull()).build();
          newRevision = push(pr);
        } else {
          // if the commit was deleted, just push the tag
          String localBranchName = this.util.getCurrentBranchName();
          String remoteName = this.util.getRemoteName(localBranchName);
          String connectionUrl = this.util.getConnectionUrlOfRemote(remoteName);
          try {
            PushCommand push = this.git.push().setRemote(remoteName).setCredentialsProvider(this.credentialsProvider)
                .add(tagPushName);
            push.call();
            newRevision = getLatestRemoteRevision();
          } catch (GitAPIException e) {
            throw new ScmException(ScmOperation.PUSH, "Unable to push locally created tag '" + request.getTagName()
                + "' to remote '" + remoteName + "[" + connectionUrl + "]+'.", e);
          }
        }
      } else {
        newRevision = getLocalRevision();
      }

      if (this.log.isLoggable(Level.INFO)) {
        this.log.info(LOG_PREFIX + "Tag creation finished successfully. New revision is: " + newRevision);
      }
      return newRevision;
    } else {
      // TODO implement tagging from url!
      // git doesn't support remote tagging but we could clone in bare mode, create the tag, push it and delete the
      // cloned repo afterwards
      throw new UnsupportedOperationException(
          "This SCM provider doesn't support tagging from remote URLs only. This feature needs some workarounds and is scheduled for a later version.");
    }
  }

  @Override
  public boolean hasTag(String tagName) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Searching for Git tag");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteName = this.util.getRemoteName(localBranchName);
    String remoteUrl = this.util.getConnectionUrlOfRemote(remoteName);

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Query info:\n");
      message.append("\t- TAG_NAME: ").append(tagName).append('\n');
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_URL: ").append(remoteUrl);
      this.log.fine(message.toString());
    }

    try {
      LsRemoteCommand lsRemote = this.git.lsRemote().setRemote(remoteName)
          .setCredentialsProvider(this.credentialsProvider).setTags(true);
      Collection<Ref> tags = lsRemote.call();
      for (Ref tag : tags) {
        if (Objects.equal(tag.getName(), GitUtil.TAG_NAME_PREFIX + tagName)) {
          return true;
        }
      }
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.INFO,
          "An error occurred while querying the remote git repository for tag '" + tagName + "'.", e);
    }
    return false;
  }

  @Override
  public String deleteTag(DeleteTagRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Deleting Git tag");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteName = this.util.getRemoteName(localBranchName);
    String remoteUrl = this.util.getConnectionUrlOfRemote(remoteName);

    boolean hasRemoteTag = hasTag(request.getTagName());
    // 1. fetch the tag from remote if it is not known locally and the remote one exists
    if (!this.util.hasLocalTag(request.getTagName()) && hasRemoteTag) {
      if (this.log.isLoggable(Level.FINE)) {
        this.log.fine(LOG_PREFIX + "Fetching remote tag");
      }
      try {
        this.git.fetch().setRemote(remoteName).setTagOpt(TagOpt.FETCH_TAGS)
            .setCredentialsProvider(this.credentialsProvider).call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.DELETE_TAG, "Unable to fetch tags for deletion of tag '"
            + request.getTagName() + "' from remote '" + remoteName + "[" + remoteUrl + "]'.", e);
      }
    }

    // proceed only if tag exists locally (if this is not the case at this point, it doesn't exist remotely either)
    if (this.util.hasLocalTag(request.getTagName())) {
      if (this.log.isLoggable(Level.FINE)) {
        StringBuilder message = new StringBuilder(LOG_PREFIX).append("Tag info:\n");
        message.append("\t- TAG_NAME: ").append(request.getTagName()).append('\n');
        message.append("\t- REMOTE: ").append(remoteName).append('\n');
        message.append("\t- REMOTE_URL: ").append(remoteUrl);
        this.log.fine(message.toString());
      }

      try {
        // 2. delete the tag locally
        DeleteTagCommand deleteTag = this.git.tagDelete().setTags(GitUtil.TAG_NAME_PREFIX + request.getTagName());
        deleteTag.call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.DELETE_TAG,
            "An error occurred during the local deletion of tag '" + request.getTagName() + "'.", e);
      }

      try {
        // 3. if the tag exists in the remote repository the remote tag gets either deletet or will be scheduled for
        // deletion on next push
        if (hasRemoteTag) {
          String tagPushName = ":" + GitUtil.TAG_NAME_PREFIX + request.getTagName();
          if (request.push()) {
            PushCommand push = this.git.push().setRemote(remoteName).setCredentialsProvider(this.credentialsProvider)
                .add(tagPushName);
            push.call();
          } else {
            this.additionalThingsToPush.add(tagPushName);
          }
        }
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.DELETE_TAG, "An error occurred during the deletion of tag '"
            + request.getTagName() + "' from remote '" + remoteName + "[" + remoteUrl + "]'.", e);
      }
    }

    return getLatestRemoteRevision();
  }

  @Override
  public String branch(BranchRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Branching local repository.");
    }

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Branch info:\n");
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- BRANCH_NAME: ").append(request.getBranchName()).append('\n');
      message.append("\t- USE_WORKING_COPY: ").append(request.branchFromWorkingCopy()).append('\n');
      if (request.branchFromWorkingCopy()) {
        message.append("\t- COMMIT_BEFORE_BRANCHING: ").append(request.commitBeforeBranching()).append('\n');
        message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy());
      } else {
        message.append("\t- REMOTE_URL: ").append(request.getRemoteRepositoryUrl()).append('\n');
        message.append("\t- REVISION: ").append(request.getRevision());
      }
      this.log.fine(message.toString());
    }

    if (request.branchFromWorkingCopy()) {
      if (this.util.hasLocalBranch(request.getBranchName())) {
        // QUESTION eventually checkout local branch instead?
        throw new ScmException(ScmOperation.BRANCH, "A local branch with this name already exists!");
      }

      if (hasBranch(request.getBranchName())) {
        // QUESTION eventually fetch branch and create a local one tracking this one instead?
        throw new ScmException(ScmOperation.BRANCH, "A remote branch with this name already exists!");
      }

      // 1. commit the changes if branching from WC is requested(no merging!)
      if (!request.getRevision().isPresent()) {
        String preBranchCommitMessage = request.getPreBranchCommitMessage() != null
            ? request.getPreBranchCommitMessage() : request.getMessage();
        CommitRequest cr = CommitRequest.builder().message(preBranchCommitMessage).noMerge().build();
        commit(cr);
      }

      try {
        // 2. branch from WC
        CreateBranchCommand branch = this.git.branchCreate().setName(request.getBranchName())
            .setUpstreamMode(SetupUpstreamMode.TRACK).setStartPoint(request.getRevision().or(Constants.HEAD));
        branch.call();
      } catch (GitAPIException e) {
        throw new ScmException(ScmOperation.BRANCH, "Could not create local branch '" + request.getBranchName()
            + "' in working copy '" + this.workingDir.getAbsolutePath() + "'", e);
      }

      // 3. deletes the local commit that had been done for branch creation.
      if (!request.commitBeforeBranching()) {
        try {
          this.git.reset().setMode(ResetType.MIXED).setRef(Constants.HEAD + "~1").call();
        } catch (GitAPIException e) {
          throw new ScmException(ScmOperation.BRANCH,
              "An error occurred during local commit resetting (no pre-branch commit was requested).", e);
        }
      }

      String branchPushName = GitUtil.HEADS_NAME_PREFIX + request.getBranchName();
      String newRevision;
      if (request.push()) {
        if (request.commitBeforeBranching()) {
          // if the commit shall be kept, push everything with update of the local WC!
          PushRequest pr = PushRequest.builder().mergeStrategy(request.getMergeStrategy())
              .mergeClient(request.getMergeClient().orNull()).build();
          newRevision = push(pr);
        } else {
          String localBranchName = this.util.getCurrentBranchName();
          String remoteName = this.util.getRemoteName(localBranchName);
          String connectionUrl = this.util.getConnectionUrlOfRemote(remoteName);
          try {
            PushCommand push = this.git.push().setRemote(remoteName).setCredentialsProvider(this.credentialsProvider)
                .add(branchPushName);
            push.call();
            newRevision = getLatestRemoteRevision();
          } catch (GitAPIException e) {
            throw new ScmException(ScmOperation.PUSH, "Unable to push locally created branch '"
                + request.getBranchName() + "' to remote '" + remoteName + "[" + connectionUrl + "]+'.", e);
          }
        }
      } else {
        newRevision = getLocalRevision();
      }

      if (this.log.isLoggable(Level.INFO)) {
        this.log.info(LOG_PREFIX + "Branch creation finished successfully. New revision is: " + newRevision);
      }
      return newRevision;
    } else {
      // TODO implement remote branching -> similar to remote tagging!
      throw new UnsupportedOperationException(
          "This SCM provider doesn't support tagging from remote URLs only. This feature needs some workarounds and is scheduled for a later version.");
    }
  }

  @Override
  public boolean hasBranch(String branchName) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Searching for Git branch");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteName = this.util.getRemoteName(localBranchName);
    String remoteUrl = this.util.getConnectionUrlOfRemote(remoteName);

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Query info:\n");
      message.append("\t- BRANCH_NAME: ").append(branchName).append('\n');
      message.append("\t- WORKING_DIR: ").append(this.workingDir.getAbsolutePath()).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_URL: ").append(remoteUrl);
      this.log.fine(message.toString());
    }

    try {
      LsRemoteCommand lsRemote = this.git.lsRemote().setRemote(remoteName)
          .setCredentialsProvider(this.credentialsProvider).setHeads(true);
      Collection<Ref> branches = lsRemote.call();
      for (Ref branch : branches) {
        if (Objects.equal(branch.getName(), GitUtil.HEADS_NAME_PREFIX + branchName)) {
          return true;
        }
      }
    } catch (GitAPIException e) {
      throw new ScmException(ScmOperation.INFO,
          "An error occurred while querying the remote git repository for branch '" + branchName + "'.", e);
    }
    return false;
  }

  @Override
  public String deleteBranch(DeleteBranchRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Deleting Git branch");
    }

    String localBranchName = this.util.getCurrentBranchName();
    String remoteName = this.util.getRemoteName(localBranchName);
    String remoteUrl = this.util.getConnectionUrlOfRemote(remoteName);

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Branch info:\n");
      message.append("\t- TAG_NAME: ").append(request.getBranchName()).append('\n');
      message.append("\t- REMOTE: ").append(remoteName).append('\n');
      message.append("\t- REMOTE_URL: ").append(remoteUrl);
      this.log.fine(message.toString());
    }

    if (this.util.hasLocalBranch(request.getBranchName())) {
      try {
        this.git.branchDelete().setBranchNames(GitUtil.HEADS_NAME_PREFIX + request.getBranchName()).setForce(true)
            .call();
      } catch (GitAPIException e) {
        e.printStackTrace();
      }
    }

    if (hasBranch(request.getBranchName())) {
      if (request.push()) {
        try {
          this.git.push().setCredentialsProvider(this.credentialsProvider).setRemote(remoteName)
              .add(":" + GitUtil.HEADS_NAME_PREFIX + request.getBranchName()).call();
        } catch (GitAPIException e) {
          e.printStackTrace();
        }
      } else {
        this.additionalThingsToPush.add(":" + GitUtil.HEADS_NAME_PREFIX + request.getBranchName());
      }
    }

    return getLatestRemoteRevision();
  }

  @Override
  public String revertCommits(RevertCommitsRequest request) throws ScmException {
    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Reverting Git commits");
    }

    // String remoteRevision = getLatestRemoteRevision();
    // TODO check direction (from/to)

    if (this.log.isLoggable(Level.FINE)) {
      StringBuilder message = new StringBuilder(LOG_PREFIX).append("Commit info:\n");
      message.append("\t- FROM: ").append(request.getFromRevision()).append('\n');
      message.append("\t- TO: ").append(request.getToRevision()).append('\n');
      message.append("\t- MERGE_STRATEGY: ").append(request.getMergeStrategy()).append('\n');
      this.log.fine(message.toString());
    }

    // update to HEAD revision first then revert commits!
    UpdateRequest updateRequest = UpdateRequest.builder().mergeStrategy(request.getMergeStrategy())
        .mergeClient(request.getMergeClient().orNull()).build();
    update(updateRequest);

    try {
      RevertCommand revert = this.git.revert();

      List<RevCommit> commitsToRevert = this.util.resolveCommitRange(request.getToRevision(),
          request.getFromRevision());
      for (RevCommit commit : commitsToRevert) {
        revert.include(commit);
      }

      switch (request.getMergeStrategy()) {
        case USE_LOCAL:
          revert.setStrategy(MergeStrategy.OURS);
          break;
        case USE_REMOTE:
          revert.setStrategy(MergeStrategy.THEIRS);
          break;
        case FULL_MERGE:
          revert.setStrategy(new UnleashGitFullMergeStrategy(request.getMergeClient().get()));
          break;
        case DO_NOT_MERGE:
          // nothing to do here!
          break;
        default:
          throw new UnsupportedOperationException(
              "Unknown merge strategy! API and implementation versions are incompatible!");
      }
      revert.call();
    } catch (Exception e) {
      throw new ScmException(ScmOperation.REVERT_COMMITS, "An error occurred during the reversion of commits.", e);
    }

    String newRevision;
    if (request.push()) {
      PushRequest pr = PushRequest.builder().mergeStrategy(request.getMergeStrategy())
          .mergeClient(request.getMergeClient().orNull()).build();
      newRevision = push(pr);
    } else {
      newRevision = getLocalRevision();
    }

    if (this.log.isLoggable(Level.INFO)) {
      this.log.info(LOG_PREFIX + "Revert finished successfully. New revision is: " + newRevision);
    }

    return newRevision;
  }

  @Override
  public String getLocalRevision() {
    try {
      RevCommit revCommit = this.git.log().call().iterator().next();
      return revCommit.getName();
    } catch (GitAPIException e) {
      throw new IllegalStateException("Could not determine the last revision commit of the local repository.", e);
    }
  }

  @Override
  public String getLatestRemoteRevision() {
    try {
      String localBranchName = this.util.getCurrentBranchName();
      String remoteName = this.util.getRemoteBranchName(localBranchName);

      Collection<Ref> branches = this.git.lsRemote().setCredentialsProvider(this.credentialsProvider).setHeads(true)
          .call();
      for (Ref branch : branches) {
        if (Objects.equal(branch.getTarget().getName(), remoteName)) {
          return branch.getObjectId().getName();
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
    }
    return "";
  }

  @Override
  public String calculateTagConnectionString(String currentConnectionString, String tagName) {
    // connection string only points to the git dir and branches/tags have to be specified and checked out separately
    return currentConnectionString;
  }

  @Override
  public String calculateBranchConnectionString(String currentConnectionString, String branchName) {
    // connection string only points to the git dir and branches/tags have to be specified and checked out separately
    return currentConnectionString;
  }

  @Override
  public boolean isTagInfoIncludedInConnection() {
    // connection string only points to the git dir and branches/tags have to be specified and checked out separately
    return false;
  }
}
