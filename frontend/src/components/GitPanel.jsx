import { useState } from 'react';
import { useWorkspace } from '../contexts/WorkspaceContext';
import { useToast } from '../contexts/ToastContext';
import * as api from '../services/api';
import './GitPanel.css';

/**
 * Git operations panel for commit/push workflow
 */
export default function GitPanel({ hasUnsavedChanges }) {
  const { branch, workspaceStatus, isMainBranch, refreshStatus } = useWorkspace();
  const toast = useToast();
  const [commitMessage, setCommitMessage] = useState('');
  const [isCommitting, setIsCommitting] = useState(false);
  const [isPushing, setIsPushing] = useState(false);
  const [isPulling, setIsPulling] = useState(false);
  const [isRefreshing, setIsRefreshing] = useState(false);

  if (isMainBranch) {
    return (
      <aside className="git-panel">
        <div className="panel-header">
          <h3>🔒 Read-Only Mode</h3>
        </div>
        <div className="panel-body">
          <div className="info-box">
            <p>You are viewing the main branch.</p>
            <p>Switch to a feature branch to make changes.</p>
          </div>
        </div>
      </aside>
    );
  }

  // The Commit and Push buttons read the cached status; if a refresh ever fails
  // the user needs a way to ask for it again rather than being stuck
  const handleRefresh = async () => {
    setIsRefreshing(true);
    try {
      await refreshStatus();
    } catch (err) {
      toast.error(`Could not read workspace status: ${err.message}`);
    } finally {
      setIsRefreshing(false);
    }
  };

  // The operation already succeeded by the time the status is re-read; a failed
  // refresh is worth reporting, but not as a failure of the operation itself
  const refreshQuietly = async () => {
    try {
      await refreshStatus();
    } catch {
      toast.warning('Workspace status could not be refreshed — use ⟳ to retry.');
    }
  };

  const handleCommit = async (e) => {
    e.preventDefault();
    if (!commitMessage.trim()) {
      toast.warning('Please enter a commit message');
      return;
    }

    setIsCommitting(true);
    try {
      // Send the HEAD we last saw so the server refuses to commit over
      // someone else's work instead of silently stacking on top of it
      await api.commitChanges(commitMessage, branch, workspaceStatus?.currentVersion ?? null);
      setCommitMessage('');
      toast.success('Changes committed successfully');
      await refreshQuietly();
    } catch (err) {
      // A 409 means the workspace moved on: pull the current HEAD in, otherwise
      // every retry re-sends the same stale expectedVersion and fails again
      await refreshQuietly();
      toast.error(`Commit failed: ${err.message}`);
    } finally {
      setIsCommitting(false);
    }
  };

  const handlePush = async () => {
    setIsPushing(true);
    try {
      await api.pushToRemote(branch);
      toast.success('Changes pushed to remote');
      await refreshQuietly();
    } catch (err) {
      await refreshQuietly();
      toast.error(`Push failed: ${err.message}`);
    } finally {
      setIsPushing(false);
    }
  };

  const handlePull = async () => {
    setIsPulling(true);
    try {
      await api.pullFromRemote(branch);
      toast.success('Changes pulled from remote');
      await refreshQuietly();
    } catch (err) {
      await refreshQuietly();
      toast.error(`Pull failed: ${err.message}`);
    } finally {
      setIsPulling(false);
    }
  };

  return (
    <aside className="git-panel">
      <div className="panel-header">
        <h3>🌿 Git Operations</h3>
        <button
          className="refresh-btn"
          onClick={handleRefresh}
          disabled={isRefreshing}
          title="Refresh workspace status"
        >
          {isRefreshing ? '⏳' : '⟳'}
        </button>
      </div>

      <div className="panel-body">
        {/* Status Info */}
        <div className="status-section">
          <div className="status-item">
            <span className="status-label">Branch:</span>
            <span className="status-value">{branch}</span>
          </div>
          {workspaceStatus && (
            <>
              <div className="status-item">
                <span className="status-label">Commit:</span>
                <span className="status-value status-commit">
                  {workspaceStatus.currentVersion?.substring(0, 7) || 'N/A'}
                </span>
              </div>
              <div className="status-item">
                <span className="status-label">Workspace:</span>
                <span className={`status-value status-badge ${workspaceStatus.clean ? 'clean' : 'modified'}`}>
                  {workspaceStatus.clean
                    ? '✓ Committed'
                    : `● ${workspaceStatus.changedFiles.length} uncommitted file${workspaceStatus.changedFiles.length === 1 ? '' : 's'}`}
                </span>
              </div>
              {workspaceStatus.changedFiles.length > 0 && (
                <ul className="changed-files">
                  {workspaceStatus.changedFiles.map((file) => (
                    <li key={file}>{file}</li>
                  ))}
                </ul>
              )}
              {workspaceStatus.unmanagedFiles?.length > 0 && (
                <div className="unmanaged-files">
                  <span>These files are not part of any flow. Commit ignores them, but Pull
                    will not run until they are removed:</span>
                  <ul className="changed-files">
                    {workspaceStatus.unmanagedFiles.map((file) => (
                      <li key={file}>{file}</li>
                    ))}
                  </ul>
                </div>
              )}
              <div className="status-item">
                <span className="status-label">Remote:</span>
                <span className="status-value">
                  {!workspaceStatus.hasUpstream
                    ? 'branch not pushed yet'
                    : workspaceStatus.aheadCount === 0 && workspaceStatus.behindCount === 0
                      ? 'in sync'
                      : [
                          workspaceStatus.aheadCount > 0 ? `${workspaceStatus.aheadCount} to push` : null,
                          workspaceStatus.behindCount > 0 ? `${workspaceStatus.behindCount} to pull` : null
                        ].filter(Boolean).join(', ')}
                </span>
              </div>
              {hasUnsavedChanges && (
                <div className="status-item">
                  <span className="status-label">Canvas:</span>
                  <span className="status-value status-badge modified">● Unsaved edits</span>
                </div>
              )}
            </>
          )}
        </div>

        {/* Commit Section */}
        <div className="action-section">
          <h4>Commit Changes</h4>
          <form onSubmit={handleCommit}>
            <textarea
              className="commit-message"
              placeholder="Enter commit message..."
              value={commitMessage}
              onChange={(e) => setCommitMessage(e.target.value)}
              rows={3}
              disabled={isCommitting}
            />
            <button
              type="submit"
              className="btn btn-primary btn-full"
              disabled={isCommitting || !commitMessage.trim() || workspaceStatus?.clean}
              title={workspaceStatus?.clean ? 'Nothing to commit — save your flow first' : undefined}
            >
              {isCommitting ? '⏳ Committing...' : '📝 Commit'}
            </button>
          </form>
        </div>

        {/* Push/Pull Section */}
        <div className="action-section">
          <h4>Sync with Remote</h4>
          <div className="button-group">
            <button
              className="btn btn-secondary btn-full"
              onClick={handlePull}
              disabled={isPulling}
            >
              {isPulling ? '⏳ Pulling...' : '⬇️ Pull'}
            </button>
            <button
              className="btn btn-primary btn-full"
              onClick={handlePush}
              disabled={isPushing || (workspaceStatus?.hasUpstream && workspaceStatus?.aheadCount === 0)}
            >
              {isPushing ? '⏳ Pushing...' : '⬆️ Push'}
            </button>
          </div>
        </div>

        {/* Instructions */}
        <div className="instructions-section">
          <h4>Git Workflow</h4>
          <ol className="workflow-steps">
            <li>Make changes to flows</li>
            <li>Save flows to workspace</li>
            <li>Commit with a message</li>
            <li>Push to remote branch</li>
            <li>Create PR/MR in Git platform</li>
          </ol>
        </div>
      </div>
    </aside>
  );
}
