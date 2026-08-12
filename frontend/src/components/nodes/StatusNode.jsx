import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import './NodeStyles.css';

/**
 * Status node (blue, rectangular) - represents intermediate flow statuses
 */
function StatusNode({ data, selected }) {
  return (
    <div className={`custom-node status-node ${selected ? 'selected' : ''}`}>
      <div className="node-header">
        <span className="node-badge">STATUS</span>
      </div>
      <div className="node-body">
        <div className="node-title">{data.statusId || 'New Status'}</div>
        <div className="node-description">{data.description || 'Flow status'}</div>
        {data.action && (
          <div className="node-action">
            <span className="action-badge">⚡ {data.action.actionId}</span>
          </div>
        )}
      </div>
      <Handle
        type="target"
        position={Position.Left}
        className="node-handle"
      />
      <Handle
        type="source"
        position={Position.Right}
        className="node-handle"
      />
    </div>
  );
}

export default memo(StatusNode);
