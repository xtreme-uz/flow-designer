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
import { thubToReactFlow, reactFlowToThub } from './utils/thubConverter';
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
  const { userId, branch, isMainBranch } = useWorkspace();
  const toast = useToast();

  // Flow state
  const [currentFlowName, setCurrentFlowName] = useState(null);
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

  // Track changes
  useEffect(() => {
    setHasUnsavedChanges(true);
  }, [nodes, edges]);

  // Reset unsaved changes when flow is loaded or saved
  const markAsSaved = useCallback(() => {
    setHasUnsavedChanges(false);
  }, []);

  // Node and edge handlers
  const onNodesChange = useCallback(
    (changes) => setNodes((nds) => applyNodeChanges(changes, nds)),
    []
  );

  const onEdgesChange = useCallback(
    (changes) => setEdges((eds) => applyEdgeChanges(changes, eds)),
    []
  );

  const onConnect = useCallback(
    (params) => setEdges((eds) => addEdge({
      ...params,
      type: 'smoothstep',
      animated: true,
      label: 'success',
      data: {
        actionResultTypeIds: 'success',
        storeAsRequestResult: true
      }
    }, eds)),
    []
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
    },
    [reactFlowInstance]
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
  }, []);

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
  }, []);

  const onEdgeDelete = useCallback((edgeId) => {
    setEdges((eds) => eds.filter((edge) => edge.id !== edgeId));
    setSelectedEdge(null);
  }, []);

  const onEdgeEditorClose = useCallback(() => {
    setSelectedEdge(null);
  }, []);

  // Arrange (re-layout) handler
  const handleArrange = useCallback((direction) => {
    setNodes((currentNodes) => {
      const positioned = applyDagreLayout(currentNodes, edges, { direction });
      return positioned;
    });
    if (reactFlowInstance) {
      setTimeout(() => reactFlowInstance.fitView({ padding: 0.2 }), 50);
    }
  }, [edges, reactFlowInstance]);

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

  // Flow management functions
  const loadFlow = async (flowName) => {
    setLoading(true);
    try {
      let deploymentData;
      if (isMainBranch) {
        deploymentData = await api.getFlowFromMain(flowName);
      } else {
        deploymentData = await api.getWorkspaceFlow(flowName, branch);
      }

      // Store the raw deployment data for metadata editing
      setCurrentDeploymentData(deploymentData);

      // Convert THUB data to React Flow nodes/edges with dagre layout
      const { nodes: flowNodes, edges: flowEdges } = thubToReactFlow(deploymentData);
      setNodes(flowNodes);
      setEdges(flowEdges);
      setCurrentFlowName(flowName);
      markAsSaved();
      fetchStatuses();
    } catch (err) {
      toast.error(`Failed to load flow: ${err.message}`);
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

    setLoading(true);
    try {
      // Convert React Flow nodes/edges back to THUB deployment data
      const existingFlowType = currentDeploymentData?.flowType || null;
      const deploymentData = reactFlowToThub(nodes, edges, currentFlowName, existingFlowType);

      await api.updateFlow(currentFlowName, deploymentData, branch);
      setCurrentDeploymentData(deploymentData);
      markAsSaved();
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
        flowStatuses: [
          { id: 'ACCEPTED', description: 'Flow started' },
          { id: 'FINISHED', description: 'Flow completed' },
        ],
        flowStatusActions: [],
        flowStatusTransitions: [],
        flowAssignments: [],
      };

      await api.createFlow(flowName, deploymentData, branch);

      // Convert to React Flow for canvas display
      const { nodes: flowNodes, edges: flowEdges } = thubToReactFlow(deploymentData);
      setCurrentFlowName(flowName);
      setCurrentDeploymentData(deploymentData);
      setNodes(flowNodes);
      setEdges(flowEdges);
      markAsSaved();
      setShowNewFlowModal(false);
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
    setHasUnsavedChanges(true);
  }, []);

  const renameFlow = async (newName) => {
    if (!currentFlowName) {
      toast.warning('No flow selected to rename.');
      return;
    }

    setLoading(true);
    try {
      await api.renameFlow(currentFlowName, newName, branch);
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
      const deploymentData = reactFlowToThub(nodes, edges, currentFlowName, existingFlowType);
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
            node={selectedNode}
            availableStatuses={availableStatuses}
            onUpdate={onNodeUpdate}
            onClose={onEditorClose}
          />
        )}
        {selectedEdge && (
          <EdgeEditor
            edge={selectedEdge}
            onUpdate={onEdgeUpdate}
            onDelete={onEdgeDelete}
            onClose={onEdgeEditorClose}
          />
        )}
      </div>

      <GitPanel hasUnsavedChanges={hasUnsavedChanges} onRefresh={() => {}} />

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
