import './Toolbar.css';

/**
 * Toolbar with draggable node types and layout controls
 */
export default function Toolbar({ onArrange }) {
  const onDragStart = (event, nodeType, nodeData) => {
    event.dataTransfer.setData('application/reactflow-nodetype', nodeType);
    event.dataTransfer.setData('application/reactflow-nodedata', JSON.stringify(nodeData));
    event.dataTransfer.effectAllowed = 'move';
  };

  return (
    <aside className="toolbar">
      <h3>Flow Elements</h3>

      <div className="toolbar-section">
        <h4>Nodes</h4>
        <div
          className="toolbar-item initial-item"
          draggable
          onDragStart={(e) => onDragStart(e, 'initialNode', {
            statusId: 'NEW_STATUS',
            description: 'Initial status',
            isInitial: true,
            isFinal: false
          })}
        >
          <span className="item-icon">🟢</span>
          <span>Initial Node</span>
        </div>

        <div
          className="toolbar-item status-item"
          draggable
          onDragStart={(e) => onDragStart(e, 'statusNode', {
            statusId: 'NEW_STATUS',
            description: 'Flow status',
            isInitial: false,
            isFinal: false
          })}
        >
          <span className="item-icon">🔵</span>
          <span>Status Node</span>
        </div>

        <div
          className="toolbar-item final-item"
          draggable
          onDragStart={(e) => onDragStart(e, 'finalNode', {
            statusId: 'FINISHED',
            description: 'Final status',
            isInitial: false,
            isFinal: true
          })}
        >
          <span className="item-icon">🔴</span>
          <span>Final Node</span>
        </div>
      </div>

      {onArrange && (
        <div className="toolbar-section">
          <h4>Layout</h4>
          <div className="layout-buttons">
            <button
              className="layout-btn"
              onClick={() => onArrange('TB')}
              title="Arrange nodes top to bottom"
            >
              Top-Bottom
            </button>
            <button
              className="layout-btn"
              onClick={() => onArrange('LR')}
              title="Arrange nodes left to right"
            >
              Left-Right
            </button>
          </div>
        </div>
      )}

      <div className="toolbar-section">
        <h4>Instructions</h4>
        <ul className="instructions">
          <li>Drag nodes onto canvas</li>
          <li>Click node to edit</li>
          <li>Connect nodes by dragging</li>
          <li>Delete: Select + Del/Backspace</li>
        </ul>
      </div>
    </aside>
  );
}
