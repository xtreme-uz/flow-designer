import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import NodeEditor from '../NodeEditor';

function node(overrides = {}) {
  return {
    id: 'n1',
    type: 'statusNode',
    data: { statusId: 'ACCEPTED', description: 'Payment accepted', action: null, ...overrides },
  };
}

describe('NodeEditor', () => {
  it('seeds the form from the node it is given', () => {
    render(<NodeEditor node={node()} onUpdate={vi.fn()} onClose={vi.fn()} />);

    expect(screen.getByLabelText(/status id/i)).toHaveValue('ACCEPTED');
    expect(screen.getByLabelText(/description/i)).toHaveValue('Payment accepted');
  });

  it('submits what the user typed', async () => {
    const onUpdate = vi.fn();
    const user = userEvent.setup();
    render(<NodeEditor node={node()} onUpdate={onUpdate} onClose={vi.fn()} />);

    await user.clear(screen.getByLabelText(/description/i));
    await user.type(screen.getByLabelText(/description/i), 'Reviewed by hand');
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(onUpdate).toHaveBeenCalledWith('n1', expect.objectContaining({
      statusId: 'ACCEPTED',
      description: 'Reviewed by hand',
    }));
  });

  it('carries an existing action through untouched fields', async () => {
    const onUpdate = vi.fn();
    const user = userEvent.setup();
    const withAction = node({
      action: { moduleId: 'source-payment-actor', actionId: 'realize-debit', maxTriesCount: 5 },
    });
    render(<NodeEditor node={withAction} onUpdate={onUpdate} onClose={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /save/i }));

    // maxTriesCount has no field in the form; it must survive anyway
    expect(onUpdate.mock.calls[0][1].action).toMatchObject({
      moduleId: 'source-payment-actor',
      maxTriesCount: 5,
    });
  });

  it('offers the known statuses and fills the description on pick', async () => {
    const onUpdate = vi.fn();
    const user = userEvent.setup();
    render(
      <NodeEditor
        node={node({ statusId: '', description: '' })}
        availableStatuses={[{ id: 'SRC_DEBITED', description: 'Source debited' }]}
        onUpdate={onUpdate}
        onClose={vi.fn()}
      />
    );

    await user.click(screen.getByLabelText(/status id/i));
    await user.click(await screen.findByText('SRC_DEBITED'));

    expect(screen.getByLabelText(/status id/i)).toHaveValue('SRC_DEBITED');
    expect(screen.getByLabelText(/description/i)).toHaveValue('Source debited');
  });
});
