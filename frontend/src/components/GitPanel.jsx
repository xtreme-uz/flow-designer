import { useState } from 'react';
import { useWorkspace } from '../contexts/WorkspaceContext';
import { useToast } from '../contexts/ToastContext';
import * as api from '../services/api';
import './GitPanel.css';

/**
 * Git operations panel for commit/push workflow
 */
export default function GitPanel({ hasUnsavedChanges, onRefresh }) {
  const { userId, branch, workspaceStatus, isMainBranch, refreshStatus } = useWorkspace();
  const toast = useToast();
  const [commitMessage, setCommitMessage] = useState('');
  const [isCommitting, setIsCommitting] = useState(false);
  const [isPushing, setIsPushing] = useState(false);
  const [isPulling, setIsPulling] = useState(false);

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

  const handleCommit = async (e) => {
    e.preventDefault();
    if (!commitMessage.trim()) {
      toast.warning('Please enter a commit message');
      return;
    }

    setIsCommitting(true);
    try {
      await api.commitChanges(commitMessage, branch);
      await refreshStatus();
      setCommitMessage('');
      toast.success('Changes committed successfully');
      if (onRefresh) onRefresh();
    } catch (err) {
      toast.error(`Commit failed: ${err.message}`);
    } finally {
      setIsCommitting(false);
    }
  };

  const handlePush = async () => {
    setIsPushing(true);
    try {
      await api.pushToRemote(branch);
      await refreshStatus();
      toast.success('Changes pushed to remote');
      if (onRefresh) onRefresh();
    } catch (err) {
      toast.error(`Push failed: ${err.message}`);
    } finally {
      setIsPushing(false);
    }
  };

  const handlePull = async () => {
    setIsPulling(true);
    try {
      await api.pullFromRemote(branch);
      await refreshStatus();
      toast.success('Changes pulled from remote');
      if (onRefresh) onRefresh();
    } catch (err) {
      toast.error(`Pull failed: ${err.message}`);
    } finally {
      setIsPulling(false);
    }
  };

  return (
    <aside className="git-panel">
      <div className="panel-header">
        <h3>🌿 Git Operations</h3>
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
                <span className="status-label">Status:</span>
                <span className={`status-value status-badge ${hasUnsavedChanges ? 'modified' : 'clean'}`}>
                  {hasUnsavedChanges ? '● Modified' : '✓ Clean'}
                </span>
              </div>
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
              disabled={isCommitting || !commitMessage.trim()}
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
              disabled={isPushing}
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
