import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import EdgeEditor from '../EdgeEditor';

function edge(overrides = {}) {
  return {
    id: 'e1',
    source: 'ACCEPTED',
    target: 'FINISHED',
    data: { actionResultTypeIds: 'success', storeAsRequestResult: true },
    ...overrides,
  };
}

describe('EdgeEditor', () => {
  it('shows which statuses the transition connects', () => {
    render(<EdgeEditor edge={edge()} onUpdate={vi.fn()} onDelete={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByText('ACCEPTED')).toBeInTheDocument();
    expect(screen.getByText('FINISHED')).toBeInTheDocument();
  });

  it('checks the result types the edge already carries', () => {
    render(
      <EdgeEditor
        edge={edge({ data: { actionResultTypeIds: 'success,business-error', storeAsRequestResult: true } })}
        onUpdate={vi.fn()}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByRole('checkbox', { name: /success/i })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /business error/i })).toBeChecked();
    expect(screen.getByRole('checkbox', { name: /technical error/i })).not.toBeChecked();
  });

  it('matches result types written with spaces after the comma', async () => {
    const onUpdate = vi.fn();
    const user = userEvent.setup();
    render(
      <EdgeEditor
        edge={edge({ data: { actionResultTypeIds: 'success, business-error', storeAsRequestResult: true } })}
        onUpdate={onUpdate}
        onDelete={vi.fn()}
        onClose={vi.fn()}
      />
    );

    expect(screen.getByRole('checkbox', { name: /business error/i })).toBeChecked();

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(onUpdate.mock.calls[0][1].actionResultTypeIds).toBe('success, business-error');
  });

  it('collects several result types onto the one transition', async () => {
    const onUpdate = vi.fn();
    const user = userEvent.setup();
    render(<EdgeEditor edge={edge()} onUpdate={onUpdate} onDelete={vi.fn()} onClose={vi.fn()} />);

    await user.click(screen.getByRole('checkbox', { name: /technical error/i }));
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(onUpdate).toHaveBeenCalledWith('e1', expect.objectContaining({
      actionResultTypeIds: 'success,technical-error',
    }));
  });

  it('refuses to leave a transition with no result type', async () => {
    const user = userEvent.setup();
    render(<EdgeEditor edge={edge()} onUpdate={vi.fn()} onDelete={vi.fn()} onClose={vi.fn()} />);

    await user.click(screen.getByRole('checkbox', { name: /success/i }));

    expect(screen.getByRole('checkbox', { name: /success/i })).toBeChecked();
  });

  it('asks before deleting', async () => {
    const onDelete = vi.fn();
    const user = userEvent.setup();
    vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<EdgeEditor edge={edge()} onUpdate={vi.fn()} onDelete={onDelete} onClose={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /delete/i }));

    expect(onDelete).not.toHaveBeenCalled();
    window.confirm.mockRestore();
  });
});
