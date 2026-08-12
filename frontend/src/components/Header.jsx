import { useState, useEffect } from 'react';
import { useWorkspace } from '../contexts/WorkspaceContext';
import { useAuth } from '../contexts/AuthContext';
import { useToast } from '../contexts/ToastContext';
import * as api from '../services/api';
import MetadataEditor from './MetadataEditor';
import './Header.css';

/**
 * Application header with workspace selector and flow management
 */
export default function Header({
  currentFlowName,
  currentMetadata,
  onNewFlow,
  onLoadFlow,
  onSaveFlow,
  onValidateFlow,
  onDeleteFlow,
  onRenameFlow,
  onEditMetadata,
  hasUnsavedChanges
}) {
  const { userId, branch, defaultBranch, workspaceStatus, isMainBranch, initWorkspace } = useWorkspace();
  const { user, logout } = useAuth();
  const toast = useToast();
  const [showWorkspaceModal, setShowWorkspaceModal] = useState(false);
  const [showFlowListModal, setShowFlowListModal] = useState(false);
  const [showRenameModal, setShowRenameModal] = useState(false);
  const [showMetadataModal, setShowMetadataModal] = useState(false);
  const [showUserMenu, setShowUserMenu] = useState(false);
  const [newBranch, setNewBranch] = useState(branch);
  const [branches, setBranches] = useState([]);
  const [loadingBranches, setLoadingBranches] = useState(false);
  const [useCustomBranch, setUseCustomBranch] = useState(false);
  const [switchingBranch, setSwitchingBranch] = useState(false);

  // Close user menu on outside click
  useEffect(() => {
    if (!showUserMenu) return;
    const handleClick = () => setShowUserMenu(false);
    document.addEventListener('click', handleClick);
    return () => document.removeEventListener('click', handleClick);
  }, [showUserMenu]);

  useEffect(() => {
    if (showWorkspaceModal) {
      setLoadingBranches(true);
      setUseCustomBranch(false);
      api.listBranches()
        .then(list => setBranches(list))
        .catch(() => setBranches([]))
        .finally(() => setLoadingBranches(false));
    }
  }, [showWorkspaceModal]);

  const handleDelete = () => {
    if (window.confirm(`Are you sure you want to delete "${currentFlowName}"? This cannot be undone.`)) {
      onDeleteFlow();
    }
  };

  const handleBranchSwitch = async (e) => {
    e.preventDefault();
    if (switchingBranch) return;
    setSwitchingBranch(true);
    try {
      await initWorkspace(newBranch);
      setShowWorkspaceModal(false);
      toast.success(`Switched to branch: ${newBranch}`);
    } catch (err) {
      toast.error(`Failed to switch branch: ${err.message}`);
    } finally {
      setSwitchingBranch(false);
    }
  };

  return (
    <>
      <header className="app-header">
        <div className="header-left">
          <h1 className="app-title">Flow Designer</h1>
          {currentFlowName && (
            <div className="current-flow">
              <span className="flow-name">{currentFlowName}</span>
              {hasUnsavedChanges && <span className="unsaved-indicator">●</span>}
              <button
                className="metadata-btn"
                onClick={() => setShowMetadataModal(true)}
                title="Edit flow metadata"
              >
                ℹ️
              </button>
            </div>
          )}
        </div>

        <div className="header-center">
          <button
            className="btn btn-secondary"
            onClick={() => setShowFlowListModal(true)}
            title="Open flow"
          >
            📂 Open
          </button>
          <button
            className="btn btn-secondary"
            onClick={onNewFlow}
            title="New flow"
          >
            ➕ New
          </button>
          {!isMainBranch && (
            <>
              <button
                className="btn btn-secondary"
                onClick={onValidateFlow}
                disabled={!currentFlowName}
                title="Validate flow structure"
              >
                Validate
              </button>
              <button
                className="btn btn-primary"
                onClick={onSaveFlow}
                disabled={!currentFlowName}
                title="Save current flow"
              >
                💾 Save
              </button>
              <button
                className="btn btn-secondary"
                onClick={() => setShowRenameModal(true)}
                disabled={!currentFlowName}
                title="Rename flow"
              >
                ✏️ Rename
              </button>
              <button
                className="btn btn-danger"
                onClick={handleDelete}
                disabled={!currentFlowName}
                title="Delete flow"
              >
                🗑️ Delete
              </button>
            </>
          )}
        </div>

        <div className="header-right">
          <div className="branch-info" onClick={() => setShowWorkspaceModal(true)}>
            <div className="workspace-branch">
              {isMainBranch ? '🔒' : '🌿'} {branch}
            </div>
            {workspaceStatus && (
              <div className="workspace-commit">
                📍 {workspaceStatus.currentVersion?.substring(0, 7)}
              </div>
            )}
          </div>
          <div className="user-menu-container">
            <button
              className="user-menu-trigger"
              onClick={(e) => { e.stopPropagation(); setShowUserMenu(!showUserMenu); }}
            >
              👤 {user?.name || user?.username}
            </button>
            {showUserMenu && (
              <div className="user-menu-dropdown">
                <div className="user-menu-header">
                  Signed in as <strong>{user?.username}</strong>
                </div>
                <div className="user-menu-divider" />
                <button className="user-menu-item" onClick={logout}>
                  Sign Out
                </button>
              </div>
            )}
          </div>
        </div>
      </header>

      {/* Branch Switch Modal */}
      {showWorkspaceModal && (
        <div className="modal-overlay" onClick={() => !switchingBranch && setShowWorkspaceModal(false)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <div className="modal-header">
              <h3>Switch Branch</h3>
              <button className="close-btn" onClick={() => setShowWorkspaceModal(false)} disabled={switchingBranch}>✕</button>
            </div>
            <form onSubmit={handleBranchSwitch}>
              <div className="form-group">
                <label htmlFor="branch">Branch</label>
                {loadingBranches ? (
                  <div className="branch-loading">Loading branches...</div>
                ) : useCustomBranch ? (
                  <>
                    <input
                      id="branch"
                      type="text"
                      value={newBranch}
                      onChange={(e) => setNewBranch(e.target.value)}
                      placeholder="e.g., feature/TASK-123-new-flow"
                      required
                      autoFocus
                    />
                    <small>
                      Type a new branch name.{' '}
                      <button type="button" className="link-btn" onClick={() => setUseCustomBranch(false)}>
                        Back to list
                      </button>
                    </small>
                  </>
                ) : (
                  <>
                    <select
                      id="branch"
                      value={branches.includes(newBranch) ? newBranch : ''}
                      onChange={(e) => setNewBranch(e.target.value)}
                      required
                    >
                      <option value="" disabled>Select a branch...</option>
                      {branches.map(b => (
                        <option key={b} value={b}>{b}{b === defaultBranch ? ' (read-only)' : ''}</option>
                      ))}
                    </select>
                    <small>
                      Or{' '}
                      <button type="button" className="link-btn" onClick={() => { setUseCustomBranch(true); setNewBranch(''); }}>
                        type a new branch name
                      </button>
                    </small>
                  </>
                )}
              </div>
              <div className="form-actions">
                <button type="submit" className="btn btn-primary" disabled={switchingBranch}>
                  {switchingBranch ? 'Switching...' : 'Switch Branch'}
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => setShowWorkspaceModal(false)}
                  disabled={switchingBranch}
                >
                  Cancel
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Flow List Modal */}
      {showFlowListModal && (
        <FlowListModal
          onClose={() => setShowFlowListModal(false)}
          onLoadFlow={(flowName) => {
            onLoadFlow(flowName);
            setShowFlowListModal(false);
          }}
        />
      )}

      {/* Rename Flow Modal */}
      {showRenameModal && (
        <RenameFlowModal
          currentName={currentFlowName}
          onClose={() => setShowRenameModal(false)}
          onRename={(newName) => {
            onRenameFlow(newName);
            setShowRenameModal(false);
          }}
        />
      )}

      {/* Metadata Editor Modal */}
      {showMetadataModal && (
        <MetadataEditor
          metadata={currentMetadata}
          onSave={(updated) => {
            onEditMetadata(updated);
            setShowMetadataModal(false);
          }}
          onClose={() => setShowMetadataModal(false)}
        />
      )}
    </>
  );
}

/**
 * Modal for listing and loading flows
 */
function FlowListModal({ onClose, onLoadFlow }) {
  const { userId, branch, isMainBranch } = useWorkspace();
  const [flows, setFlows] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [viewSource, setViewSource] = useState(isMainBranch ? 'main' : 'workspace');

  const loadFlows = async () => {
    setLoading(true);
    setError(null);
    try {
      const api = await import('../services/api');
      const flowList = viewSource === 'main'
        ? await api.listFlowsFromMain()
        : await api.listWorkspaceFlows(branch);
      setFlows(flowList);
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadFlows();
  }, [viewSource, userId, branch]);

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content modal-large" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Open Flow</h3>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>

        <div className="modal-body">
          {!isMainBranch && (
            <div className="view-selector">
              <button
                className={`btn ${viewSource === 'workspace' ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setViewSource('workspace')}
              >
                Workspace
              </button>
              <button
                className={`btn ${viewSource === 'main' ? 'btn-primary' : 'btn-secondary'}`}
                onClick={() => setViewSource('main')}
              >
                Main Branch
              </button>
            </div>
          )}

          {loading && <div className="loading">Loading flows...</div>}
          {error && <div className="error">Error: {error}</div>}

          {!loading && !error && flows.length === 0 && (
            <div className="empty-state">
              No flows found. Create a new flow to get started.
            </div>
          )}

          {!loading && !error && flows.length > 0 && (
            <div className="flow-list">
              {flows.map((flow) => (
                <div
                  key={flow.name}
                  className="flow-item"
                  onClick={() => onLoadFlow(flow.name)}
                >
                  <div className="flow-item-name">{flow.name}</div>
                  {flow.description && (
                    <div className="flow-item-description">{flow.description}</div>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * Modal for renaming a flow
 */
function RenameFlowModal({ currentName, onClose, onRename }) {
  const [newName, setNewName] = useState(currentName);
  const toast = useToast();

  const handleSubmit = (e) => {
    e.preventDefault();
    const trimmedName = newName.trim();
    if (!trimmedName) {
      toast.warning('Please enter a flow name');
      return;
    }
    if (trimmedName === currentName) {
      toast.info('Name is unchanged');
      onClose();
      return;
    }
    onRename(trimmedName);
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Rename Flow</h3>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <div className="form-group">
              <label htmlFor="newFlowName">New Name</label>
              <input
                id="newFlowName"
                type="text"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                placeholder="e.g., debit-credit-flow"
                required
                autoFocus
              />
              <small>Use lowercase with hyphens (e.g., my-flow-name)</small>
            </div>
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn-primary">
              Rename
            </button>
            <button type="button" className="btn btn-secondary" onClick={onClose}>
              Cancel
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
