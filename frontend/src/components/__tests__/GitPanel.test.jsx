import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const workspace = {
  branch: 'feature/TASK-1',
  workspaceStatus: null,
  isMainBranch: false,
  refreshStatus: vi.fn(),
};
const toast = { success: vi.fn(), error: vi.fn(), warning: vi.fn() };

vi.mock('../../contexts/WorkspaceContext', () => ({ useWorkspace: () => workspace }));
vi.mock('../../contexts/ToastContext', () => ({ useToast: () => toast }));
vi.mock('../../services/api', () => ({
  commitChanges: vi.fn(),
  pushToRemote: vi.fn(),
  pullFromRemote: vi.fn(),
}));

const api = await import('../../services/api');
const GitPanel = (await import('../GitPanel')).default;

function status(overrides = {}) {
  return {
    workspaceId: 'alisher-feature_TASK-1',
    branch: 'feature/TASK-1',
    currentVersion: 'abc1234',
    changedFiles: ['THUB/FlowType-data.json'],
    unmanagedFiles: [],
    clean: false,
    aheadCount: 0,
    behindCount: 0,
    hasUpstream: true,
    ...overrides,
  };
}

describe('GitPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    workspace.isMainBranch = false;
    workspace.workspaceStatus = status();
    workspace.refreshStatus = vi.fn().mockResolvedValue(status());
  });

  it('is read-only on the main branch', () => {
    workspace.isMainBranch = true;
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByText(/read-only mode/i)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /commit/i })).not.toBeInTheDocument();
  });

  it('lists the files waiting to be committed', () => {
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByText('THUB/FlowType-data.json')).toBeInTheDocument();
    expect(screen.getByText(/1 uncommitted file/i)).toBeInTheDocument();
  });

  it('lists files it does not manage, and says commit leaves them alone', () => {
    workspace.workspaceStatus = status({ unmanagedFiles: ['notes.txt'] });
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByText('notes.txt')).toBeInTheDocument();
    expect(screen.getByText(/Commit leaves them alone/i)).toBeInTheDocument();
  });

  it('refuses to commit a clean workspace', () => {
    workspace.workspaceStatus = status({ changedFiles: [], clean: true });
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByRole('button', { name: /commit/i })).toBeDisabled();
  });

  it('sends the HEAD it last saw with the commit', async () => {
    const user = userEvent.setup();
    api.commitChanges.mockResolvedValue({ commitHash: 'def5678' });
    render(<GitPanel hasUnsavedChanges={false} />);

    await user.type(screen.getByPlaceholderText(/commit message/i), 'Add debit step');
    await user.click(screen.getByRole('button', { name: /commit/i }));

    expect(api.commitChanges).toHaveBeenCalledWith('Add debit step', 'feature/TASK-1', 'abc1234');
    expect(toast.success).toHaveBeenCalled();
  });

  it('refuses to commit when it does not know the workspace HEAD', async () => {
    const user = userEvent.setup();
    workspace.workspaceStatus = status({ currentVersion: null });
    render(<GitPanel hasUnsavedChanges={false} />);

    await user.type(screen.getByPlaceholderText(/commit message/i), 'Add debit step');
    await user.click(screen.getByRole('button', { name: /commit/i }));

    expect(api.commitChanges).not.toHaveBeenCalled();
    expect(toast.warning).toHaveBeenCalledWith(expect.stringContaining('refresh'));
  });

  it('reports a failed commit without claiming success', async () => {
    const user = userEvent.setup();
    api.commitChanges.mockRejectedValue(new Error('Version conflict'));
    render(<GitPanel hasUnsavedChanges={false} />);

    await user.type(screen.getByPlaceholderText(/commit message/i), 'Add debit step');
    await user.click(screen.getByRole('button', { name: /commit/i }));

    expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('Version conflict'));
    expect(toast.success).not.toHaveBeenCalled();
  });

  it('says nothing is waiting to be pushed when the branch is in sync', () => {
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByText(/in sync/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /push/i })).toBeDisabled();
  });

  it('counts what the remote is ahead by', () => {
    workspace.workspaceStatus = status({ aheadCount: 2, behindCount: 3 });
    render(<GitPanel hasUnsavedChanges={false} />);

    expect(screen.getByText(/2 to push, 3 to pull/i)).toBeInTheDocument();
  });
});
