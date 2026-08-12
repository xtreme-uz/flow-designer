import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import './NodeStyles.css';

/**
 * Initial node (green, rounded) - represents the starting point of a flow
 */
function InitialNode({ data, selected }) {
  return (
    <div className={`custom-node initial-node ${selected ? 'selected' : ''}`}>
      <div className="node-header">
        <span className="node-badge">INITIAL</span>
      </div>
      <div className="node-body">
        <div className="node-title">{data.statusId || 'New Initial'}</div>
        <div className="node-description">{data.description || 'Initial status'}</div>
        {data.action && (
          <div className="node-action">
            <span className="action-badge">⚡ {data.action.actionId}</span>
          </div>
        )}
      </div>
      <Handle
        type="source"
        position={Position.Right}
        className="node-handle"
      />
    </div>
  );
}

export default memo(InitialNode);
