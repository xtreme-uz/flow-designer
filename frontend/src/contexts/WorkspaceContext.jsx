import { createContext, useContext, useState, useEffect } from 'react';
import { useAuth } from './AuthContext';
import * as api from '../services/api';

const WorkspaceContext = createContext();

/**
 * Workspace Provider - manages branch and workspace state.
 * userId comes from OAuth2 session (user.username).
 */
export function WorkspaceProvider({ children }) {
  const { user } = useAuth();
  const userId = user?.username;
  const [defaultBranch, setDefaultBranch] = useState('main');
  const [branch, setBranch] = useState(null);
  const [workspace, setWorkspace] = useState(null);
  const [workspaceStatus, setWorkspaceStatus] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Fetch default branch from backend on mount
  useEffect(() => {
    api.getConfig()
      .then((config) => {
        const db = config.defaultBranch || 'main';
        setDefaultBranch(db);
        setBranch((prev) => prev === null ? db : prev);
      })
      .catch(() => {
        setBranch((prev) => prev === null ? 'main' : prev);
      });
  }, []);

  /**
   * Initialize or switch workspace
   */
  const initWorkspace = async (newBranch) => {
    setLoading(true);
    setError(null);
    try {
      const ws = await api.createWorkspace(newBranch);
      const status = await api.getWorkspaceStatus(newBranch);

      setBranch(newBranch);
      setWorkspace(ws);
      setWorkspaceStatus(status);

      return ws;
    } catch (err) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  };

  /**
   * Refresh workspace status
   */
  const refreshStatus = async () => {
    if (!branch) return;

    try {
      const status = await api.getWorkspaceStatus(branch);
      setWorkspaceStatus(status);
      return status;
    } catch (err) {
      setError(err.message);
      throw err;
    }
  };

  /**
   * Delete current workspace
   */
  const deleteCurrentWorkspace = async () => {
    setLoading(true);
    setError(null);
    try {
      await api.deleteWorkspace(branch);
      setWorkspace(null);
      setWorkspaceStatus(null);
    } catch (err) {
      setError(err.message);
      throw err;
    } finally {
      setLoading(false);
    }
  };

  const isMainBranch = branch === defaultBranch;

  const value = {
    userId,
    branch,
    defaultBranch,
    workspace,
    workspaceStatus,
    loading,
    error,
    isMainBranch,
    initWorkspace,
    refreshStatus,
    deleteCurrentWorkspace,
    setBranch
  };

  return (
    <WorkspaceContext.Provider value={value}>
      {children}
    </WorkspaceContext.Provider>
  );
}

export function useWorkspace() {
  const context = useContext(WorkspaceContext);
  if (!context) {
    throw new Error('useWorkspace must be used within WorkspaceProvider');
  }
  return context;
}
