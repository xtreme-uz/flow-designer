import { memo } from 'react';
import { Handle, Position } from '@xyflow/react';
import './NodeStyles.css';

/**
 * Final node (red, rounded) - represents the end point of a flow
 */
function FinalNode({ data, selected }) {
  return (
    <div className={`custom-node final-node ${selected ? 'selected' : ''}`}>
      <div className="node-header">
        <span className="node-badge">FINAL</span>
      </div>
      <div className="node-body">
        <div className="node-title">{data.statusId || 'New Final'}</div>
        <div className="node-description">{data.description || 'Final status'}</div>
      </div>
      <Handle
        type="target"
        position={Position.Left}
        className="node-handle"
      />
    </div>
  );
}

export default memo(FinalNode);
