import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import Toolbar from '../Toolbar';

/** Captures what a palette item puts on the drag event. */
function dragPayload(item) {
  const data = {};
  const dataTransfer = {
    setData: (type, value) => { data[type] = value; },
    effectAllowed: null,
  };
  fireEvent.dragStart(item, { dataTransfer });
  return data;
}

describe('Toolbar', () => {
  it('offers the three node kinds', () => {
    render(<Toolbar onArrange={vi.fn()} />);

    expect(screen.getByText('Initial Node')).toBeInTheDocument();
    expect(screen.getByText('Status Node')).toBeInTheDocument();
    expect(screen.getByText('Final Node')).toBeInTheDocument();
  });

  it('drops nodes without a status id, so none is invented for the shared status file', () => {
    const { container } = render(<Toolbar onArrange={vi.fn()} />);
    const items = container.querySelectorAll('.toolbar-item');

    for (const item of items) {
      const payload = dragPayload(item);
      const data = JSON.parse(payload['application/reactflow-nodedata']);
      expect(data.statusId).toBe('');
    }
  });

  it('marks which kind is being dragged', () => {
    const { container } = render(<Toolbar onArrange={vi.fn()} />);
    const initial = container.querySelector('.initial-item');

    expect(dragPayload(initial)['application/reactflow-nodetype']).toBe('initialNode');
  });
});
