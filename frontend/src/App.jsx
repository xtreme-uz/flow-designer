import { useState, useCallback, useRef, useEffect } from 'react';
import {
  ReactFlow,
  applyNodeChanges,
  applyEdgeChanges,
  addEdge,
  Background,
  Controls,
  MiniMap,
} from '@xyflow/react';
import '@xyflow/react/dist/style.css';

import { useWorkspace } from './contexts/WorkspaceContext';
import { useToast } from './contexts/ToastContext';
import * as api from './services/api';
import { thubToReactFlow, reactFlowToThub, reactFlowToLayout } from './utils/thubConverter';
import { applyDagreLayout } from './utils/layoutUtils';

import InitialNode from './components/nodes/InitialNode';
import StatusNode from './components/nodes/StatusNode';
import FinalNode from './components/nodes/FinalNode';
import Toolbar from './components/Toolbar';
import NodeEditor from './components/NodeEditor';
import EdgeEditor from './components/EdgeEditor';
import Header from './components/Header';
import GitPanel from './components/GitPanel';

const nodeTypes = {
  initialNode: InitialNode,
  statusNode: StatusNode,
  finalNode: FinalNode,
};

let nodeId = 0;
const getId = () => `node_${nodeId++}`;

export default function App() {
  const { branch, isMainBranch, refreshStatus } = useWorkspace();
  const toast = useToast();

  // Flow state
  const [currentFlowName, setCurrentFlowName] = useState(null);
  // Opened from the main-branch listing while on a feature branch: saving then
  // replaces this branch's own copy, which the user has to agree to
  const [openedFromMain, setOpenedFromMain] = useState(false);
  const [currentDeploymentData, setCurrentDeploymentData] = useState(null);
  const [nodes, setNodes] = useState([]);
  const [edges, setEdges] = useState([]);
  const [selectedNode, setSelectedNode] = useState(null);
  const [selectedEdge, setSelectedEdge] = useState(null);
  const [reactFlowInstance, setReactFlowInstance] = useState(null);
  const reactFlowWrapper = useRef(null);

  // Available statuses for combobox
  const [availableStatuses, setAvailableStatuses] = useState([]);

  // UI state
  const [hasUnsavedChanges, setHasUnsavedChanges] = useState(false);
  const [showNewFlowModal, setShowNewFlowModal] = useState(false);
  const [loading, setLoading] = useState(false);

  // Reset unsaved changes when flow is loaded or saved
  const markAsSaved = useCallback(() => {
    setHasUnsavedChanges(false);
  }, []);

  const markAsChanged = useCallback(() => {
    setHasUnsavedChanges(true);
  }, []);

  // Warn before leaving with work that is not in the workspace yet
  useEffect(() => {
    if (!hasUnsavedChanges) return;
    const warn = (event) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [hasUnsavedChanges]);

  // Node and edge handlers.
  // Structural changes count as edits, and so does a finished drag now that
  // positions are saved with the flow. Selection and the in-flight steps of a
  // drag change nothing that is stored, so they are ignored.
  const onNodesChange = useCallback(
    (changes) => {
      const edited = changes.some((c) =>
        c.type === 'add' || c.type === 'remove' || c.type === 'replace' ||
        (c.type === 'position' && c.dragging === false)
      );
      if (edited) {
        markAsChanged();
      }
      setNodes((nds) => applyNodeChanges(changes, nds));
    },
    [markAsChanged]
  );

  const onEdgesChange = useCallback(
    (changes) => {
      if (changes.some((c) => c.type === 'add' || c.type === 'remove' || c.type === 'replace')) {
        markAsChanged();
      }
      setEdges((eds) => applyEdgeChanges(changes, eds));
    },
    [markAsChanged]
  );

  const onConnect = useCallback(
    (params) => {
      // THUB stores one transition per (status, next status) pair, so a second
      // edge between the same two nodes would silently replace the first on save.
      // Multiple result types belong on one edge, via the edge editor.
      const duplicate = edges.some(
        (e) => e.source === params.source && e.target === params.target
      );
      if (duplicate) {
        toast.warning(
          'These statuses are already connected. Open the existing transition to add more result types.'
        );
        return;
      }

      setEdges((eds) => addEdge({
        ...params,
        type: 'smoothstep',
        animated: true,
        label: 'success',
        data: {
          actionResultTypeIds: 'success',
          storeAsRequestResult: true
        }
      }, eds));
      markAsChanged();
    },
    [edges, toast, markAsChanged]
  );

  const onNodeClick = useCallback((event, node) => {
    setSelectedNode(node);
    setSelectedEdge(null);
  }, []);

  const onEdgeClick = useCallback((event, edge) => {
    setSelectedEdge(edge);
    setSelectedNode(null);
  }, []);

  const onPaneClick = useCallback(() => {
    setSelectedNode(null);
    setSelectedEdge(null);
  }, []);

  const onDragOver = useCallback((event) => {
    event.preventDefault();
    event.dataTransfer.dropEffect = 'move';
  }, []);

  const onDrop = useCallback(
    (event) => {
      event.preventDefault();

      const type = event.dataTransfer.getData('application/reactflow-nodetype');
      const nodeData = JSON.parse(event.dataTransfer.getData('application/reactflow-nodedata'));

      if (typeof type === 'undefined' || !type || !reactFlowInstance) {
        return;
      }

      const position = reactFlowInstance.screenToFlowPosition({
        x: event.clientX,
        y: event.clientY,
      });

      const newNode = {
        id: getId(),
        type,
        position,
        data: nodeData,
      };

      setNodes((nds) => nds.concat(newNode));
      markAsChanged();
    },
    [reactFlowInstance, markAsChanged]
  );

  const onNodeUpdate = useCallback((nodeId, formData) => {
    setNodes((nds) =>
      nds.map((node) => {
        if (node.id === nodeId) {
          return {
            ...node,
            data: {
              ...node.data,
              statusId: formData.statusId,
              description: formData.description,
              action: formData.action.moduleId || formData.action.actionId
                ? formData.action
                : null,
            },
          };
        }
        return node;
      })
    );
    setSelectedNode(null);
    markAsChanged();
  }, [markAsChanged]);

  const onEditorClose = useCallback(() => {
    setSelectedNode(null);
  }, []);

  const onEdgeUpdate = useCallback((edgeId, formData) => {
    setEdges((eds) =>
      eds.map((edge) => {
        if (edge.id === edgeId) {
          return {
            ...edge,
            label: formData.label,
            data: {
              ...edge.data,
              actionResultTypeIds: formData.actionResultTypeIds,
              storeAsRequestResult: formData.storeAsRequestResult
            }
          };
        }
        return edge;
      })
    );
    setSelectedEdge(null);
    markAsChanged();
  }, [markAsChanged]);

  const onEdgeDelete = useCallback((edgeId) => {
    setEdges((eds) => eds.filter((edge) => edge.id !== edgeId));
    setSelectedEdge(null);
    markAsChanged();
  }, [markAsChanged]);

  const onEdgeEditorClose = useCallback(() => {
    setSelectedEdge(null);
  }, []);

  // Arrange (re-layout) handler
  const handleArrange = useCallback((direction) => {
    setNodes((currentNodes) => {
      const positioned = applyDagreLayout(currentNodes, edges, { direction });
      return positioned;
    });
    markAsChanged();
    if (reactFlowInstance) {
      setTimeout(() => reactFlowInstance.fitView({ padding: 0.2 }), 50);
    }
  }, [edges, reactFlowInstance, markAsChanged]);

  // Fetch available statuses
  const fetchStatuses = async () => {
    try {
      let statuses;
      if (isMainBranch) {
        statuses = await api.listStatusesFromMain();
      } else {
        statuses = await api.listWorkspaceStatuses(branch);
      }
      setAvailableStatuses(statuses || []);
    } catch (err) {
      console.warn('Failed to fetch statuses:', err.message);
    }
  };

  // A loaded flow belongs to the branch it came from. Clear it when the branch
  // changes, otherwise the next save writes the previous branch's flow here.
  const previousBranch = useRef(null);
  useEffect(() => {
    if (!branch) return;
    if (previousBranch.current === null) {
      previousBranch.current = branch;
      return;
    }
    if (previousBranch.current === branch) return;

    previousBranch.current = branch;
    setCurrentFlowName(null);
    setCurrentDeploymentData(null);
    setNodes([]);
    setEdges([]);
    setSelectedNode(null);
    setSelectedEdge(null);
    markAsSaved();
  }, [branch, markAsSaved]);

  // Mirrors GitPanel: the workspace write already succeeded, but a stale status
  // would leave its buttons disabled, so say so instead of failing silently
  const refreshStatusQuietly = () => refreshStatus().catch(() => {
    toast.warning('Workspace status could not be refreshed — use ⟳ in the Git panel.');
  });

  const confirmDiscardChanges = (action) => {
    if (!hasUnsavedChanges) return true;
    return window.confirm(`You have unsaved changes. ${action} anyway?`);
  };

  // Flow management functions
  /**
   * @param source 'main' or 'workspace' — which listing the flow was picked from.
   *   The flow list offers both while on a feature branch, so guessing from the
   *   current branch fetches the wrong copy, or 404s on a main-only flow.
   */
  const loadFlow = async (flowName, source = (isMainBranch ? 'main' : 'workspace')) => {
    // Returns whether the flow was actually opened, so the caller's modal can
    // stay put when the user backs out of the unsaved-changes prompt
    if (!confirmDiscardChanges('Open another flow')) return false;
    setLoading(true);
    try {
      const [deploymentData, layout] = await Promise.all([
        source === 'main'
          ? api.getFlowFromMain(flowName)
          : api.getWorkspaceFlow(flowName, branch),
        // A missing or unreadable layout only costs the saved positions
        (source === 'main'
          ? api.getFlowLayoutFromMain(flowName)
          : api.getWorkspaceFlowLayout(flowName, branch)).catch(() => null),
      ]);

      // Store the raw deployment data for metadata editing
      setCurrentDeploymentData(deploymentData);

      // Convert THUB data to React Flow nodes/edges, keeping saved positions
      const { nodes: flowNodes, edges: flowEdges } = thubToReactFlow(deploymentData, layout);
      setNodes(flowNodes);
      setEdges(flowEdges);
      setCurrentFlowName(flowName);
      setOpenedFromMain(source === 'main' && !isMainBranch);
      markAsSaved();
      fetchStatuses();
      return true;
    } catch (err) {
      toast.error(`Failed to load flow: ${err.message}`);
      return false;
    } finally {
      setLoading(false);
    }
  };

  const saveFlow = async () => {
    if (!currentFlowName) {
      toast.warning('No flow loaded. Please create a new flow first.');
      return;
    }

    if (isMainBranch) {
      toast.warning('Cannot save to main branch. Please switch to a feature branch.');
      return;
    }

    // The canvas id would otherwise travel to the shared status file as the
    // status id, where every other flow would see it
    const unnamed = nodes.filter((n) => !n.data?.statusId);
    if (unnamed.length > 0) {
      toast.error(
        `${unnamed.length} node${unnamed.length === 1 ? ' has' : 's have'} no status ID. ` +
        'Open each one and set it before saving.'
      );
      return;
    }

    if (openedFromMain && !window.confirm(
      `This is the main-branch copy of "${currentFlowName}". Saving replaces this branch's version. Continue?`
    )) {
      return;
    }

    setLoading(true);
    try {
      // Convert React Flow nodes/edges back to THUB deployment data
      const existingFlowType = currentDeploymentData?.flowType || null;
      const existingAssignments = currentDeploymentData?.flowAssignments || [];
      const deploymentData = reactFlowToThub(
        nodes, edges, currentFlowName, existingFlowType, existingAssignments
      );

      // A flow opened from the main branch may or may not already exist in this
      // workspace — a feature branch cut from main carries all of them. Update
      // first and create only when it really is not there.
      try {
        await api.updateFlow(currentFlowName, deploymentData, branch);
      } catch (err) {
        if (err.status !== 404) throw err;
        await api.createFlow(currentFlowName, deploymentData, branch);
      }
      setOpenedFromMain(false);
      // Positions are the user's arrangement, not THUB data — stored separately.
      // The flow itself is already saved, so a failure here costs the layout only.
      try {
        await api.saveWorkspaceFlowLayout(currentFlowName, reactFlowToLayout(nodes), branch);
      } catch (layoutError) {
        toast.warning(`Flow saved, but the canvas layout was not: ${layoutError.message}`);
      }
      setCurrentDeploymentData(deploymentData);
      markAsSaved();
      refreshStatusQuietly();
      toast.success('Flow saved successfully!');
    } catch (err) {
      if (err.errors && err.errors.length > 0) {
        toast.error(`Validation failed:\n${err.errors.map(e => '- ' + e).join('\n')}`, 10000);
      } else {
        toast.error(`Failed to save flow: ${err.message}`);
      }
    } finally {
      setLoading(false);
    }
  };

  const createNewFlow = async (flowName, description) => {
    if (!confirmDiscardChanges('Create a new flow')) return;
    if (isMainBranch) {
      toast.warning('Cannot create flows in main branch. Please switch to a feature branch.');
      return;
    }

    setLoading(true);
    try {
      // Build minimal ThubDeploymentData with ACCEPTED + FINISHED
      const deploymentData = {
        flowType: {
          id: flowName,
          initialflowstatusid: 'ACCEPTED',
          finalflowstatusid: 'FINISHED',
          description: description || '',
          version: '1.0',
          component: 'THUB',
          categorization: {},
        },
        // Blank descriptions on purpose: ACCEPTED and FINISHED are shared, and a
        // canned description here would overwrite what other flows show
        flowStatuses: [
          { id: 'ACCEPTED', description: '' },
          { id: 'FINISHED', description: '' },
        ],
        flowStatusActions: [],
        flowStatusTransitions: [],
        flowAssignments: [],
      };

      await api.createFlow(flowName, deploymentData, branch);

      // Convert to React Flow for canvas display
      const { nodes: flowNodes, edges: flowEdges } = thubToReactFlow(deploymentData);
      setCurrentFlowName(flowName);
      setOpenedFromMain(false);
      setCurrentDeploymentData(deploymentData);
      setNodes(flowNodes);
      setEdges(flowEdges);
      markAsSaved();
      setShowNewFlowModal(false);
      refreshStatusQuietly();
      toast.success(`Flow '${flowName}' created successfully!`);
      fetchStatuses();
    } catch (err) {
      if (err.errors && err.errors.length > 0) {
        toast.error(`Validation failed:\n${err.errors.map(e => '- ' + e).join('\n')}`, 10000);
      } else {
        toast.error(`Failed to create flow: ${err.message}`);
      }
    } finally {
      setLoading(false);
    }
  };

  const deleteFlow = async () => {
    if (!currentFlowName) {
      toast.warning('No flow selected to delete.');
      return;
    }

    setLoading(true);
    try {
      await api.deleteFlow(currentFlowName, branch);
      refreshStatusQuietly();
      toast.success(`Flow '${currentFlowName}' deleted successfully`);
      setCurrentFlowName(null);
      setCurrentDeploymentData(null);
      setNodes([]);
      setEdges([]);
      markAsSaved();
    } catch (err) {
      toast.error(`Failed to delete flow: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  const editMetadata = useCallback((updatedFlowType) => {
    // updatedFlowType comes from MetadataEditor — merge into currentDeploymentData
    setCurrentDeploymentData((prev) => {
      if (!prev) return prev;
      return { ...prev, flowType: { ...prev.flowType, ...updatedFlowType } };
    });
    markAsChanged();
  }, [markAsChanged]);

  const renameFlow = async (newName) => {
    if (!currentFlowName) {
      toast.warning('No flow selected to rename.');
      return;
    }

    setLoading(true);
    try {
      await api.renameFlow(currentFlowName, newName, branch);
      setOpenedFromMain(false);
      refreshStatusQuietly();
      toast.success(`Flow renamed to '${newName}'`);
      setCurrentFlowName(newName);
      setCurrentDeploymentData((prev) => {
        if (!prev) return prev;
        return { ...prev, flowType: { ...prev.flowType, id: newName } };
      });
    } catch (err) {
      toast.error(`Failed to rename flow: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  const validateCurrentFlow = async () => {
    if (!currentFlowName) {
      toast.warning('No flow loaded to validate.');
      return;
    }

    setLoading(true);
    try {
      const existingFlowType = currentDeploymentData?.flowType || null;
      const existingAssignments = currentDeploymentData?.flowAssignments || [];
      const deploymentData = reactFlowToThub(
        nodes, edges, currentFlowName, existingFlowType, existingAssignments
      );
      const result = await api.validateFlow(deploymentData);

      if (result.valid) {
        toast.success('Flow is valid!');
      } else {
        toast.error(`Validation failed:\n${result.errors.map(e => '- ' + e).join('\n')}`, 10000);
      }
    } catch (err) {
      toast.error(`Validation check failed: ${err.message}`);
    } finally {
      setLoading(false);
    }
  };

  // Extract metadata-like object for Header/MetadataEditor compatibility
  const currentMetadata = currentDeploymentData?.flowType
    ? {
        name: currentDeploymentData.flowType.id,
        description: currentDeploymentData.flowType.description,
        version: currentDeploymentData.flowType.version,
        component: currentDeploymentData.flowType.component,
        createdBy: currentDeploymentData.flowType.createdby ?? currentDeploymentData.flowType.createdBy,
        createdAt: currentDeploymentData.flowType.createdat ?? currentDeploymentData.flowType.createdAt,
        lastModifiedBy: currentDeploymentData.flowType.lastmodifiedby ?? currentDeploymentData.flowType.lastModifiedBy,
        lastModifiedAt: currentDeploymentData.flowType.lastmodifiedat ?? currentDeploymentData.flowType.lastModifiedAt,
        categorization: currentDeploymentData.flowType.categorization,
      }
    : null;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', width: '100vw', height: '100vh' }}>
      <Header
        currentFlowName={currentFlowName}
        currentMetadata={currentMetadata}
        hasUnsavedChanges={hasUnsavedChanges}
        onNewFlow={() => setShowNewFlowModal(true)}
        onLoadFlow={loadFlow}
        onSaveFlow={saveFlow}
        onValidateFlow={validateCurrentFlow}
        onDeleteFlow={deleteFlow}
        onRenameFlow={renameFlow}
        onEditMetadata={editMetadata}
      />

      <div style={{ flex: 1, position: 'relative' }} ref={reactFlowWrapper}>
        <ReactFlow
          nodes={nodes}
          edges={edges}
          onNodesChange={onNodesChange}
          onEdgesChange={onEdgesChange}
          onConnect={onConnect}
          onNodeClick={onNodeClick}
          onEdgeClick={onEdgeClick}
          onPaneClick={onPaneClick}
          onInit={setReactFlowInstance}
          onDrop={onDrop}
          onDragOver={onDragOver}
          nodeTypes={nodeTypes}
          fitView
          deleteKeyCode={['Backspace', 'Delete']}
        >
          <Background />
          <Controls />
          <MiniMap
            nodeColor={(node) => {
              switch (node.type) {
                case 'initialNode':
                  return '#10b981';
                case 'statusNode':
                  return '#3b82f6';
                case 'finalNode':
                  return '#ef4444';
                default:
                  return '#6b7280';
              }
            }}
          />
        </ReactFlow>

        <Toolbar onArrange={handleArrange} />
        {selectedNode && (
          <NodeEditor
            key={selectedNode.id}
            node={selectedNode}
            availableStatuses={availableStatuses}
            onUpdate={onNodeUpdate}
            onClose={onEditorClose}
          />
        )}
        {selectedEdge && (
          <EdgeEditor
            key={selectedEdge.id}
            edge={selectedEdge}
            onUpdate={onEdgeUpdate}
            onDelete={onEdgeDelete}
            onClose={onEdgeEditorClose}
          />
        )}
      </div>

      <GitPanel hasUnsavedChanges={hasUnsavedChanges} />

      {/* New Flow Modal */}
      {showNewFlowModal && (
        <NewFlowModal
          onClose={() => setShowNewFlowModal(false)}
          onCreate={createNewFlow}
        />
      )}

      {/* Loading Overlay */}
      {loading && (
        <div style={{
          position: 'fixed',
          top: 0,
          left: 0,
          right: 0,
          bottom: 0,
          background: 'rgba(0,0,0,0.3)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          zIndex: 9999
        }}>
          <div style={{
            background: 'white',
            padding: '2rem',
            borderRadius: '8px',
            fontSize: '1.1rem'
          }}>
            Loading...
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Modal for creating new flow
 */
function NewFlowModal({ onClose, onCreate }) {
  const [flowName, setFlowName] = useState('');
  const [description, setDescription] = useState('');
  const toast = useToast();

  const handleSubmit = (e) => {
    e.preventDefault();
    if (!flowName.trim()) {
      toast.warning('Please enter a flow name');
      return;
    }
    onCreate(flowName.trim(), description.trim());
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal-content" onClick={(e) => e.stopPropagation()}>
        <div className="modal-header">
          <h3>Create New Flow</h3>
          <button className="close-btn" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <div className="form-group">
              <label htmlFor="flowName">Flow Name *</label>
              <input
                id="flowName"
                type="text"
                value={flowName}
                onChange={(e) => setFlowName(e.target.value)}
                placeholder="e.g., debit-credit-flow"
                required
                autoFocus
              />
              <small>Use lowercase with hyphens (e.g., my-flow-name)</small>
            </div>
            <div className="form-group">
              <label htmlFor="description">Description</label>
              <textarea
                id="description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                placeholder="Brief description of this flow"
                rows={3}
              />
            </div>
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn-primary">
              Create Flow
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
